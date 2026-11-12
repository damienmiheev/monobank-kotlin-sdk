package io.github.damienmiheev.monobank.acquiring.webhook

import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse
import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.MonobankSignatureException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Supplies the merchant's webhook public key, in the format `GET /api/merchant/pubkey` returns
 * it (base64 of a PEM `PUBLIC KEY`). A raw PEM or base64 DER is accepted too.
 *
 * Multi-tenant integrations implement it per tenant, e.g. `{ tenantClient.merchant.publicKey().key }`
 * or a value from their own storage.
 */
public fun interface WebhookKeyProvider {
    /** Loads the current key. May throw; the verifier reports that as [VerificationResult.KEY_UNAVAILABLE]. */
    public fun fetchPublicKey(): String
}

/** Result of checking a webhook signature. */
public enum class VerificationResult {
    /** The signature matches the body. */
    VALID,

    /** The signature is missing, malformed, or does not match. Answer with HTTP 400 and ignore the body. */
    INVALID_SIGNATURE,

    /**
     * The key could not be loaded, so nothing could be verified. Answer with HTTP 5xx so monobank
     * delivers the webhook again (it retries a few times), and alert.
     */
    KEY_UNAVAILABLE,
}

/**
 * Verifies the `X-Sign` header of acquiring webhooks: an ECDSA (P-256, SHA-256) signature over
 * the raw request body, base64-encoded DER.
 *
 * Always verify the **raw bytes** you received, before parsing: re-serialized JSON will not match.
 *
 * Key handling, built so a slow or failing key endpoint never stalls your request threads:
 * - The key is loaded on first use and cached. When a signature does not match, the key is
 *   re-fetched, because monobank may have rotated it, at most once per `minRefreshInterval`.
 * - Only one thread fetches at a time. Other threads use the cached key, or wait at most
 *   `maxWaitForKey` for the fetch and otherwise get [VerificationResult.KEY_UNAVAILABLE].
 * - After a failed fetch, no new fetch is tried for `failureBackoff`; in that window callers get
 *   [VerificationResult.KEY_UNAVAILABLE] (or `null` from [currentPublicKey]) at once.
 * - After a rotation, the previous key is still accepted for `previousKeyGrace` (default
 *   10 minutes), so webhooks signed just before the rotation verify; after that it is rejected.
 *
 * The client's default key provider (`MonobankAcquiring.webhooks`) fetches with a short timeout
 * and no retries. Thread-safe. Build with [builder] for all options.
 */
public class WebhookVerifier private constructor(
    private val keyProvider: WebhookKeyProvider,
    private val minRefreshInterval: Duration,
    private val failureBackoff: Duration,
    private val previousKeyGrace: Duration,
    private val maxWaitForKey: Duration,
    private val clock: Clock,
) {
    /**
     * Creates a verifier with default timing (see [builder] for every option).
     *
     * @param minRefreshInterval Minimum time between key fetches after a successful one (default 1 minute).
     */
    @JvmOverloads
    public constructor(
        keyProvider: WebhookKeyProvider,
        minRefreshInterval: Duration = DEFAULT_MIN_REFRESH,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        keyProvider,
        minRefreshInterval,
        DEFAULT_FAILURE_BACKOFF,
        DEFAULT_PREVIOUS_KEY_GRACE,
        DEFAULT_MAX_WAIT,
        clock,
    )

    private val logger = LoggerFactory.getLogger(WebhookVerifier::class.java)

    @Volatile
    private var keys: KeySet? = null

    /** When the last fetch finished (successfully or not), and whether it failed. */
    @Volatile
    private var lastAttempt: Attempt? = null

    private val inFlight = AtomicReference<CompletableFuture<KeySet?>?>()

    private class Attempt(
        val at: Instant,
        val failed: Boolean,
    )

    private class Refresh(
        val keys: KeySet?,
        val fetchFailed: Boolean,
    )

    private class KeySet(
        val current: PublicKey,
        val currentRaw: String,
        val previous: PublicKey?,
        val rotatedAt: Instant?,
    )

    /** Checks [xSign] against [body] and says why it failed, if it did. */
    public fun check(
        xSign: String?,
        body: ByteArray,
    ): VerificationResult {
        val signature = decodeSignature(xSign) ?: return VerificationResult.INVALID_SIGNATURE
        val loaded = keys ?: (refresh(initial = true, seen = null).keys ?: return VerificationResult.KEY_UNAVAILABLE)
        if (matches(loaded, signature, body)) return VerificationResult.VALID
        val refreshed = refresh(initial = false, seen = loaded)
        val newKeys = refreshed.keys
        return when {
            newKeys != null && newKeys !== loaded && matches(newKeys, signature, body) -> VerificationResult.VALID

            // The key may have rotated but could not be re-fetched: let monobank retry later.
            refreshed.fetchFailed -> VerificationResult.KEY_UNAVAILABLE

            else -> VerificationResult.INVALID_SIGNATURE
        }
    }

    /**
     * The current webhook public key exactly as the [WebhookKeyProvider] returned it (for the
     * default provider: base64 of a PEM, as `GET /api/merchant/pubkey` returns it), loading it
     * on first use. Returns `null` when the key cannot be loaded right now; it follows the same
     * single-fetch and failure back-off rules as [check], so calling it often is cheap.
     */
    public fun currentPublicKey(): String? = (keys ?: refresh(initial = true, seen = null).keys)?.currentRaw

    /** `true` only when [check] is [VerificationResult.VALID]. */
    public fun verify(
        xSign: String?,
        body: ByteArray,
    ): Boolean = check(xSign, body) == VerificationResult.VALID

    /**
     * Throws [MonobankSignatureException] unless the signature is valid. The exception's
     * `reason` tells whether to answer 4xx (invalid) or 5xx (key unavailable).
     */
    public fun verifyOrThrow(
        xSign: String?,
        body: ByteArray,
    ) {
        when (check(xSign, body)) {
            VerificationResult.VALID -> {
                return
            }

            VerificationResult.INVALID_SIGNATURE -> {
                throw MonobankSignatureException(
                    MonobankSignatureException.Reason.INVALID_SIGNATURE,
                    "Invalid monobank webhook signature",
                )
            }

            VerificationResult.KEY_UNAVAILABLE -> {
                throw MonobankSignatureException(
                    MonobankSignatureException.Reason.KEY_UNAVAILABLE,
                    "monobank webhook public key is unavailable",
                )
            }
        }
    }

    /**
     * Verifies the signature, then parses the body.
     *
     * @throws MonobankSignatureException when the signature is not valid.
     * @throws io.github.damienmiheev.monobank.core.MonobankSerializationException when the body is not an invoice.
     */
    public fun verifyAndParse(
        xSign: String?,
        body: ByteArray,
    ): InvoiceStatusResponse {
        verifyOrThrow(xSign, body)
        return WebhookParser.parse(body)
    }

    private fun matches(
        set: KeySet,
        signature: ByteArray,
        body: ByteArray,
    ): Boolean {
        if (verifyWith(set.current, signature, body)) return true
        val previous = set.previous ?: return false
        val rotatedAt = set.rotatedAt ?: return false
        return elapsedSince(rotatedAt) <= previousKeyGrace && verifyWith(previous, signature, body)
    }

    /** Time since [instant]; a clock that went backwards counts as "long ago" so it never blocks a refresh. */
    private fun elapsedSince(instant: Instant): Duration {
        val elapsed = Duration.between(instant, clock.instant())
        return if (elapsed.isNegative) ChronoUnit.FOREVER.duration else elapsed
    }

    /**
     * Loads a fresh key, unless that is not allowed or needed right now:
     * - another thread already replaced [seen]: use its keys;
     * - the last fetch failed less than [failureBackoff] ago: fail fast;
     * - (after a mismatch, [initial] = false) the last successful fetch is younger than [minRefreshInterval].
     */
    private fun refresh(
        initial: Boolean,
        seen: KeySet?,
    ): Refresh {
        gate(initial, seen)?.let { return it }
        val mine = CompletableFuture<KeySet?>()
        val running = inFlight.compareAndExchange(null, mine)
        if (running != null) return awaitOther(running, keys)
        try {
            // Re-check: another thread may have finished a fetch between the gate and the claim.
            gate(initial, seen)?.let { early ->
                // Waiters get the same keys, not "unavailable".
                mine.complete(early.keys)
                return early
            }
            val cached = keys
            val fetched = fetch(cached)
            mine.complete(fetched)
            return Refresh(fetched ?: cached, fetchFailed = fetched == null)
        } finally {
            mine.complete(null)
            inFlight.set(null)
        }
    }

    /** The answer without fetching, or `null` when a fetch is allowed and needed. */
    private fun gate(
        initial: Boolean,
        seen: KeySet?,
    ): Refresh? {
        val cached = keys
        if (cached != null && cached !== seen) return Refresh(cached, fetchFailed = false)
        val last = lastAttempt ?: return null
        val since = elapsedSince(last.at)
        return when {
            last.failed && since < failureBackoff -> Refresh(cached, fetchFailed = true)
            !last.failed && !initial && since < minRefreshInterval -> Refresh(cached, fetchFailed = false)
            else -> null
        }
    }

    private fun awaitOther(
        running: CompletableFuture<KeySet?>,
        cached: KeySet?,
    ): Refresh {
        val result =
            try {
                running.get(maxWaitForKey.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (
                @Suppress("SwallowedException") e: TimeoutException,
            ) {
                null
            } catch (
                @Suppress("SwallowedException") e: ExecutionException,
            ) {
                null
            }
        return Refresh(result ?: cached, fetchFailed = result == null)
    }

    /** Fetches and parses the key; updates [keys] and [lastAttempt]. Returns `null` on failure. */
    private fun fetch(cached: KeySet?): KeySet? {
        var raw = ""
        val parsed =
            try {
                raw = keyProvider.fetchPublicKey()
                parsePublicKey(raw)
            } catch (e: MonobankException) {
                logger.warn("Could not load monobank webhook public key: {}", e.message)
                null
            } catch (e: GeneralSecurityException) {
                logger.warn("Could not parse monobank webhook public key: {}", e.message)
                null
            } catch (e: IllegalArgumentException) {
                logger.warn("Could not parse monobank webhook public key: {}", e.message)
                null
            } catch (e: RuntimeException) {
                logger.warn("Could not load monobank webhook public key: {}", e.javaClass.name)
                null
            }
        val now = clock.instant()
        if (parsed == null) {
            lastAttempt = Attempt(now, failed = true)
            return null
        }
        val updated =
            if (cached != null && cached.current.encoded.contentEquals(parsed.encoded)) {
                KeySet(cached.current, raw.trim(), cached.previous, cached.rotatedAt)
            } else {
                KeySet(parsed, raw.trim(), cached?.current, if (cached != null) now else null)
            }
        keys = updated
        lastAttempt = Attempt(now, failed = false)
        return updated
    }

    /**
     * Builder for [WebhookVerifier].
     */
    public class Builder internal constructor(
        private val keyProvider: WebhookKeyProvider,
    ) {
        private var minRefreshInterval: Duration = DEFAULT_MIN_REFRESH
        private var failureBackoff: Duration = DEFAULT_FAILURE_BACKOFF
        private var previousKeyGrace: Duration = DEFAULT_PREVIOUS_KEY_GRACE
        private var maxWaitForKey: Duration = DEFAULT_MAX_WAIT
        private var clock: Clock = Clock.systemUTC()

        /** Minimum time between key fetches after a successful one (default 1 minute). */
        public fun minRefreshInterval(interval: Duration): Builder =
            apply { minRefreshInterval = requireNotNegative(interval, "minRefreshInterval") }

        /** How long to wait after a failed fetch before trying again (default 10 seconds). */
        public fun failureBackoff(backoff: Duration): Builder =
            apply { failureBackoff = requireNotNegative(backoff, "failureBackoff") }

        /** How long the previous key stays valid after a rotation (default 10 minutes; zero disables it). */
        public fun previousKeyGrace(grace: Duration): Builder =
            apply { previousKeyGrace = requireNotNegative(grace, "previousKeyGrace") }

        /** How long a thread waits for another thread's key fetch before giving up (default 2 seconds). */
        public fun maxWaitForKey(wait: Duration): Builder =
            apply { maxWaitForKey = requireNotNegative(wait, "maxWaitForKey") }

        /** Clock, for tests. */
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Builds the verifier. */
        public fun build(): WebhookVerifier =
            WebhookVerifier(keyProvider, minRefreshInterval, failureBackoff, previousKeyGrace, maxWaitForKey, clock)

        private fun requireNotNegative(
            value: Duration,
            name: String,
        ): Duration {
            require(!value.isNegative) { "$name must not be negative" }
            return value
        }
    }

    public companion object {
        private val DEFAULT_MIN_REFRESH: Duration = Duration.ofMinutes(1)
        private val DEFAULT_FAILURE_BACKOFF: Duration = Duration.ofSeconds(10)
        private val DEFAULT_PREVIOUS_KEY_GRACE: Duration = Duration.ofMinutes(10)
        private val DEFAULT_MAX_WAIT: Duration = Duration.ofSeconds(2)

        /** Starts a verifier for [keyProvider] with every timing option. */
        @JvmStatic
        public fun builder(keyProvider: WebhookKeyProvider): Builder = Builder(keyProvider)

        /**
         * A verifier with a fixed key (no fetching, no rotation), e.g. a key stored per tenant.
         *
         * @param publicKey Key as returned by `GET /api/merchant/pubkey` (base64 of PEM), a PEM, or base64 DER.
         * @throws IllegalArgumentException when the key cannot be parsed.
         */
        @JvmStatic
        public fun ofPublicKey(publicKey: String): WebhookVerifier {
            try {
                parsePublicKey(publicKey)
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("Not an EC public key: ${e.message}", e)
            }
            val fixed = publicKey.trim()
            return WebhookVerifier(WebhookKeyProvider { fixed }, Duration.ofDays(DAYS_FOREVER))
        }

        private const val DAYS_FOREVER = 36_500L

        internal fun parsePublicKey(raw: String): PublicKey {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "public key is empty" }
            val pem =
                if (trimmed.startsWith("-----BEGIN")) {
                    trimmed
                } else {
                    val decoded = Base64.getMimeDecoder().decode(trimmed)
                    val text = String(decoded, StandardCharsets.US_ASCII)
                    if (text.trimStart().startsWith("-----BEGIN")) text else return ecKey(decoded)
                }
            val body =
                pem
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("-----") }
                    .joinToString("")
            return ecKey(Base64.getDecoder().decode(body))
        }

        private fun ecKey(der: ByteArray): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

        private fun decodeSignature(xSign: String?): ByteArray? {
            if (xSign.isNullOrBlank()) return null
            return try {
                Base64.getDecoder().decode(xSign.trim()).takeIf { it.isNotEmpty() }
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                null // not base64: an invalid signature, not an error
            }
        }

        private fun verifyWith(
            key: PublicKey,
            signature: ByteArray,
            body: ByteArray,
        ): Boolean =
            try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(body)
                    verify(signature)
                }
            } catch (
                @Suppress("SwallowedException") e: GeneralSecurityException,
            ) {
                false // malformed DER signature: simply does not verify
            }
    }
}

/**
 * Parses webhook bodies. Prefer [WebhookVerifier.verifyAndParse], which verifies first.
 */
public object WebhookParser {
    /**
     * Parses an acquiring webhook body (same shape as invoice status).
     *
     * @throws io.github.damienmiheev.monobank.core.MonobankSerializationException when it is not valid.
     */
    @JvmStatic
    public fun parse(body: ByteArray): InvoiceStatusResponse =
        MonobankJson.read(body, InvoiceStatusResponse::class.java, outcome = null)

    /**
     * The `invoiceId` of a webhook body, read without parsing the rest, or `null` when there is
     * none. Use it when a **verified** body fails to parse (e.g. a field changed type): you can
     * still poll `invoices.status(invoiceId)` instead of losing the event.
     */
    @JvmStatic
    public fun invoiceIdOf(body: ByteArray): String? =
        try {
            MonobankJson
                .readTree(body, outcome = null)
                .path("invoiceId")
                .takeIf { it.isTextual }
                ?.asText()
                ?.takeIf { it.isNotEmpty() }
        } catch (
            @Suppress("SwallowedException") e: MonobankException,
        ) {
            null
        }

    /** Parses a webhook body given as a string. */
    @JvmStatic
    public fun parse(body: String): InvoiceStatusResponse = parse(body.toByteArray(StandardCharsets.UTF_8))
}
