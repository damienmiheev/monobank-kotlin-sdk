package io.github.damienmiheev.monobank.acquiring.monopay

import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.core.json.MonobankJson
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The values the monopay button widget (`MonoPay.init`) needs, signed on your server.
 * [toString] does not print the payload, which can contain customer data.
 *
 * @property requestId Unique request id (the widget accepts it for 10 minutes).
 * @property payloadBase64 Base64 of the order JSON.
 * @property signature Base64 DER ECDSA signature.
 */
@ConsistentCopyVisibility
public data class MonopaySignedRequest internal constructor(
    public val requestId: String,
    public val payloadBase64: String,
    public val signature: String,
) {
    override fun toString(): String =
        "MonopaySignedRequest(requestId=$requestId, payloadBase64=<${payloadBase64.length} chars>, signature=$signature)"
}

/**
 * Signs monopay button widget requests: `signature = base64(DER ECDSA-P256-SHA256(orderJson + requestId))`
 * and `payloadBase64 = base64(orderJson)`. The same JSON bytes go into the payload and the
 * signature, so pass the exact JSON you want the widget to use.
 *
 * **Experimental.** monobank's docs disagree on what is signed: the widget page, the text of the
 * signature-example page and its Node.js example sign `JSON + requestId` (no separator), while
 * the Go, Python, Java, Ruby and PHP examples on the same page sign the JSON only. This class
 * follows the normative text (`JSON + requestId`). Verify one widget session against monobank's
 * test environment before relying on it; the behaviour may change if monobank settles it the
 * other way.
 *
 * Generate an EC P-256 key pair, import the public key with `MonopayApi.importKey`
 * ([publicKeyValue] gives `keyValue`), and keep the private key on your server. Only P-256
 * (secp256r1, prime256v1) keys are accepted. Uses only JDK cryptography. Thread-safe.
 * [toString] never prints the key.
 */
@ExperimentalMonobankApi
public class MonopaySigner private constructor(
    private val privateKey: PrivateKey,
) {
    /**
     * Signs [orderJson] (the order data JSON) with [requestId].
     *
     * @throws IllegalArgumentException when the key cannot sign.
     */
    public fun sign(
        orderJson: ByteArray,
        requestId: String,
    ): MonopaySignedRequest {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        val signed = orderJson + requestId.toByteArray(StandardCharsets.UTF_8)
        val der =
            try {
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(privateKey)
                    update(signed)
                    sign()
                }
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("Could not sign with the monopay key: ${e.javaClass.simpleName}", e)
            }
        val encoder = Base64.getEncoder()
        return MonopaySignedRequest(requestId, encoder.encodeToString(orderJson), encoder.encodeToString(der))
    }

    /** Signs an order JSON string (UTF-8). */
    public fun sign(
        orderJson: String,
        requestId: String,
    ): MonopaySignedRequest = sign(orderJson.toByteArray(StandardCharsets.UTF_8), requestId)

    /**
     * Serializes [order] as the widget's order data and signs it. The widget documents only
     * `amount`, `ccy`, `merchantPaymInfo`, `redirectUrl`, `successUrl`, `failUrl`, `webHookUrl`,
     * `validity` and `agentFeePercent`; leave the other fields unset.
     */
    public fun sign(
        order: CreateInvoiceRequest,
        requestId: String,
    ): MonopaySignedRequest = sign(MonobankJson.write(order), requestId)

    override fun toString(): String = "MonopaySigner(privateKey=***)"

    public companion object {
        private const val DER_SEQUENCE = 0x30
        private const val DER_OCTET_STRING = 0x04
        private const val DER_CONTEXT_0 = 0xA0
        private const val SHORT_LENGTH_LIMIT = 0x80
        private const val LENGTH_BYTES_MASK = 0x7F
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
        private const val PEM_LINE = 64

        /** DER of OID 1.2.840.10045.3.1.7 (prime256v1). */
        private val P256_OID =
            byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

        /** `INTEGER 0` (PKCS#8 version) followed by AlgorithmIdentifier { ecPublicKey, prime256v1 }. */
        private val PKCS8_EC_P256_PREFIX =
            byteArrayOf(
                0x02,
                0x01,
                0x00,
                0x30,
                0x13,
                0x06,
                0x07,
                0x2A,
                0x86.toByte(),
                0x48,
                0xCE.toByte(),
                0x3D,
                0x02,
                0x01,
            ) + P256_OID

        private val P256: ECParameterSpec by lazy {
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }

        /** `true` when [key] is an EC key on P-256 (curve, generator, order and cofactor compared). */
        private fun isP256(key: Any): Boolean {
            val params = (key as? ECKey)?.params ?: return false
            val p256 = P256
            return params.curve == p256.curve &&
                params.generator == p256.generator &&
                params.order == p256.order &&
                params.cofactor == p256.cofactor
        }

        /**
         * A signer for an EC P-256 private key.
         *
         * @throws IllegalArgumentException when the key is not an EC P-256 key.
         */
        @JvmStatic
        public fun of(privateKey: PrivateKey): MonopaySigner {
            require(isP256(privateKey)) { "monopay keys must be EC P-256 (secp256r1) keys" }
            return MonopaySigner(privateKey)
        }

        /**
         * A signer for a PEM private key: PKCS#8 (`BEGIN PRIVATE KEY`) or SEC1 (`BEGIN EC PRIVATE KEY`,
         * as `openssl ecparam -genkey -name prime256v1` writes it). Base64 of such a PEM is accepted too.
         *
         * @throws IllegalArgumentException when the key cannot be read or is not EC P-256.
         */
        @JvmStatic
        public fun fromPem(pem: String): MonopaySigner {
            val text = pemText(pem)
            val sec1 = text.contains("-----BEGIN EC PRIVATE KEY-----")
            val marker = if (sec1) "EC PRIVATE KEY" else "PRIVATE KEY"
            val body =
                text
                    .substringAfter("-----BEGIN $marker-----", "")
                    .substringBefore("-----END $marker-----", "")
                    .filterNot { it.isWhitespace() }
            require(body.isNotEmpty()) { "no PRIVATE KEY or EC PRIVATE KEY block in the PEM" }
            val der =
                try {
                    Base64.getDecoder().decode(body)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("the PEM body is not base64", e)
                }
            val pkcs8 = if (sec1) wrapSec1(der) else der
            val key =
                try {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("Not an EC private key: ${e.javaClass.simpleName}", e)
                }
            return of(key)
        }

        /**
         * `keyValue` for `MonopayApi.importKey`: base64 of the PEM-encoded public key.
         *
         * @throws IllegalArgumentException when the key is not an EC P-256 key.
         */
        @JvmStatic
        public fun publicKeyValue(publicKey: PublicKey): String {
            require(isP256(publicKey)) { "monopay keys must be EC P-256 (secp256r1) keys" }
            val pem =
                "-----BEGIN PUBLIC KEY-----\n" +
                    Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(publicKey.encoded) +
                    "\n-----END PUBLIC KEY-----\n"
            return Base64.getEncoder().encodeToString(pem.toByteArray(StandardCharsets.US_ASCII))
        }

        private fun pemText(input: String): String {
            val trimmed = input.trim()
            if (trimmed.startsWith("-----BEGIN")) return trimmed
            val decoded =
                try {
                    String(Base64.getMimeDecoder().decode(trimmed), StandardCharsets.US_ASCII)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("not a PEM private key", e)
                }
            return decoded.trim()
        }

        /**
         * Wraps a SEC1 `ECPrivateKey` into a PKCS#8 `PrivateKeyInfo` for P-256. When the SEC1 key
         * names its curve (`[0] parameters`), it must be prime256v1.
         */
        private fun wrapSec1(sec1: ByteArray): ByteArray {
            val curve = sec1CurveParameters(sec1)
            require(curve == null || curve.contentEquals(P256_OID)) {
                "monopay keys must be EC P-256 (secp256r1); this SEC1 key is on another curve"
            }
            val octet = derTlv(DER_OCTET_STRING, sec1)
            return derTlv(DER_SEQUENCE, PKCS8_EC_P256_PREFIX + octet)
        }

        /** The content of the `[0]` (parameters) element of a SEC1 `ECPrivateKey`, or `null`. */
        private fun sec1CurveParameters(sec1: ByteArray): ByteArray? {
            val outer = Der(sec1, 0)
            require(outer.tag == DER_SEQUENCE) { "not a SEC1 EC private key" }
            var offset = outer.contentStart
            while (offset < outer.end) {
                val element = Der(sec1, offset)
                if (element.tag == DER_CONTEXT_0) return sec1.copyOfRange(element.contentStart, element.end)
                offset = element.end
            }
            return null
        }

        /** One DER element at [start]: its tag, where its content starts and where it ends. */
        private class Der(
            bytes: ByteArray,
            start: Int,
        ) {
            val tag: Int
            val contentStart: Int
            val end: Int

            init {
                require(start + 1 < bytes.size) { "truncated DER" }
                tag = bytes[start].toInt() and BYTE_MASK
                val first = bytes[start + 1].toInt() and BYTE_MASK
                var length: Int
                var pos = start + 2
                if (first < SHORT_LENGTH_LIMIT) {
                    length = first
                } else {
                    val count = first and LENGTH_BYTES_MASK
                    require(count in 1..2 && pos + count <= bytes.size) { "unsupported DER length" }
                    length = 0
                    repeat(count) { length = (length shl BITS_PER_BYTE) or (bytes[pos++].toInt() and BYTE_MASK) }
                }
                contentStart = pos
                end = pos + length
                require(end <= bytes.size) { "truncated DER" }
            }
        }

        private fun derTlv(
            tag: Int,
            content: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = content.size
            if (length < SHORT_LENGTH_LIMIT) {
                out.write(length)
            } else {
                val bytes = generateSequence(length) { it ushr BITS_PER_BYTE }.takeWhile { it > 0 }.toList().reversed()
                out.write(SHORT_LENGTH_LIMIT or bytes.size)
                bytes.forEach { out.write(it and BYTE_MASK) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}
