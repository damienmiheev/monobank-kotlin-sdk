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
import java.util.Base64

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
 * The key is loaded on first use and cached. When a signature does not match, the key is
 * re-fetched (at most once per [minRefreshInterval], one fetch at a time), because monobank may
 * have rotated it. The previous key stays accepted, so webhooks signed just before a rotation
 * still verify. Thread-safe.
 */
public class WebhookVerifier
    @JvmOverloads
    constructor(
        private val keyProvider: WebhookKeyProvider,
        private val minRefreshInterval: Duration = Duration.ofMinutes(1),
        private val clock: Clock = Clock.systemUTC(),
    ) {
        private val logger = LoggerFactory.getLogger(WebhookVerifier::class.java)
        private val lock = Any()

        @Volatile
        private var keys: KeySet? = null

        private class Refresh(
            val keys: KeySet?,
            val fetchFailed: Boolean,
        )

        private class KeySet(
            val current: PublicKey,
            val previous: PublicKey?,
            val fetchedAt: Instant,
        )

        /** Checks [xSign] against [body] and says why it failed, if it did. */
        public fun check(
            xSign: String?,
            body: ByteArray,
        ): VerificationResult {
            val signature = decodeSignature(xSign) ?: return VerificationResult.INVALID_SIGNATURE
            val loaded = keys ?: (refresh(force = true).keys ?: return VerificationResult.KEY_UNAVAILABLE)
            if (matches(loaded, signature, body)) return VerificationResult.VALID
            val refreshed = refresh(force = false, seen = loaded)
            val newKeys = refreshed.keys
            return when {
                newKeys != null && newKeys !== loaded && matches(newKeys, signature, body) -> VerificationResult.VALID

                // The key may have rotated but could not be re-fetched: let monobank retry later.
                refreshed.fetchFailed -> VerificationResult.KEY_UNAVAILABLE

                else -> VerificationResult.INVALID_SIGNATURE
            }
        }

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
        ): Boolean =
            verifyWith(set.current, signature, body) || (set.previous?.let { verifyWith(it, signature, body) } ?: false)

        /**
         * Loads a fresh key. With [force] = false it does nothing when the cached key set is
         * newer than [seen] (another thread refreshed) or was fetched less than
         * [minRefreshInterval] ago. [Refresh.keys] is `null` when no key is available at all.
         */
        private fun refresh(
            force: Boolean,
            seen: KeySet? = null,
        ): Refresh =
            synchronized(lock) {
                val cached = keys
                if (cached != null) {
                    if (cached !== seen) return Refresh(cached, fetchFailed = false)
                    if (!force && Duration.between(cached.fetchedAt, clock.instant()) < minRefreshInterval) {
                        return Refresh(cached, fetchFailed = false)
                    }
                }
                val fetched =
                    try {
                        parsePublicKey(keyProvider.fetchPublicKey())
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
                        logger.warn("Could not load monobank webhook public key", e)
                        null
                    }
                if (fetched == null) return Refresh(cached, fetchFailed = true)
                val updated =
                    if (cached != null && cached.current.encoded.contentEquals(fetched.encoded)) {
                        KeySet(cached.current, cached.previous, clock.instant())
                    } else {
                        KeySet(fetched, cached?.current, clock.instant())
                    }
                keys = updated
                Refresh(updated, fetchFailed = false)
            }

        public companion object {
            /**
             * A verifier with a fixed key (no fetching, no rotation), e.g. a key stored per tenant.
             *
             * @param publicKey Key as returned by `GET /api/merchant/pubkey` (base64 of PEM), a PEM, or base64 DER.
             * @throws IllegalArgumentException when the key cannot be parsed.
             */
            @JvmStatic
            public fun ofPublicKey(publicKey: String): WebhookVerifier {
                val parsed =
                    try {
                        parsePublicKey(publicKey)
                    } catch (e: GeneralSecurityException) {
                        throw IllegalArgumentException("Not an EC public key: ${e.message}", e)
                    }
                val encoded = Base64.getEncoder().encodeToString(parsed.encoded)
                return WebhookVerifier(WebhookKeyProvider { encoded }, Duration.ofDays(DAYS_FOREVER))
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

    /** Parses a webhook body given as a string. */
    @JvmStatic
    public fun parse(body: String): InvoiceStatusResponse = parse(body.toByteArray(StandardCharsets.UTF_8))
}
