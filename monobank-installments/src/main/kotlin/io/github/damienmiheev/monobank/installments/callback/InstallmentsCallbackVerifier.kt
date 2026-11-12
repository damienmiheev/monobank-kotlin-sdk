package io.github.damienmiheev.monobank.installments.callback

import io.github.damienmiheev.monobank.core.MonobankSignatureException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.internal.HmacSigner
import io.github.damienmiheev.monobank.installments.order.OrderStateInfo
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Verifies installment callbacks: monobank POSTs `{"order_id", "state", "order_sub_state"}` to your
 * `result_callback` with a `signature` header = `Base64(HMAC-SHA256(raw body, store secret))`, the same signature
 * scheme as requests.
 *
 * Always verify the **raw bytes** you received, before parsing: re-serialized JSON will not match. The comparison
 * is constant-time. Answer a failed verification with HTTP 4xx and ignore the body. Thread-safe.
 *
 * Callbacks are sent only on `IN_PROCESS` / `WAITING_FOR_STORE_CONFIRM` and on any `FAIL` (QR flow: also
 * `SUCCESS` / `ACTIVE`); poll `orders.state` for the rest.
 *
 * @param storeSecret The store secret (the same one used for requests).
 */
public class InstallmentsCallbackVerifier(
    storeSecret: String,
) {
    private val signer: HmacSigner

    init {
        require(storeSecret.isNotEmpty()) { "storeSecret must not be empty" }
        signer = HmacSigner(storeSecret)
    }

    /**
     * `true` when [signature] (the `signature` header) matches [body].
     * A missing, blank or non-Base64 header is simply invalid.
     */
    public fun verify(
        signature: String?,
        body: ByteArray,
    ): Boolean {
        val provided = decode(signature) ?: return false
        return MessageDigest.isEqual(signer.mac(body), provided)
    }

    /**
     * Throws unless [signature] matches [body].
     *
     * @throws MonobankSignatureException with reason `INVALID_SIGNATURE`.
     */
    public fun verifyOrThrow(
        signature: String?,
        body: ByteArray,
    ) {
        if (!verify(signature, body)) {
            throw MonobankSignatureException(
                MonobankSignatureException.Reason.INVALID_SIGNATURE,
                "Invalid monobank installments callback signature",
            )
        }
    }

    /**
     * Verifies the signature, then parses the body.
     *
     * @throws MonobankSignatureException when the signature is not valid.
     * @throws io.github.damienmiheev.monobank.core.MonobankSerializationException when the body is not a callback.
     */
    public fun verifyAndParse(
        signature: String?,
        body: ByteArray,
    ): OrderStateInfo {
        verifyOrThrow(signature, body)
        return InstallmentsCallbackParser.parse(body)
    }

    /**
     * The `signature` value monobank would send for [body]. Useful to test your callback endpoint.
     */
    public fun sign(body: ByteArray): String = signer.sign(body)

    override fun toString(): String = "InstallmentsCallbackVerifier(storeSecret=***)"

    public companion object {
        /** Name of the header that carries the callback signature. */
        public const val SIGNATURE_HEADER: String = "signature"

        private fun decode(signature: String?): ByteArray? {
            if (signature.isNullOrBlank()) return null
            return try {
                Base64.getDecoder().decode(signature.trim()).takeIf { it.isNotEmpty() }
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                null // not Base64: an invalid signature, not an error
            }
        }
    }
}

/**
 * Parses installment callback bodies. Prefer [InstallmentsCallbackVerifier.verifyAndParse], which verifies first.
 */
public object InstallmentsCallbackParser {
    /**
     * Parses a callback body. Unknown states and sub-states are read as `UNKNOWN`.
     *
     * @throws io.github.damienmiheev.monobank.core.MonobankSerializationException when it is not valid.
     */
    @JvmStatic
    public fun parse(body: ByteArray): OrderStateInfo =
        MonobankJson.read(body, OrderStateInfo::class.java, outcome = null)

    /** Parses a callback body given as a string. */
    @JvmStatic
    public fun parse(body: String): OrderStateInfo = parse(body.toByteArray(StandardCharsets.UTF_8))
}
