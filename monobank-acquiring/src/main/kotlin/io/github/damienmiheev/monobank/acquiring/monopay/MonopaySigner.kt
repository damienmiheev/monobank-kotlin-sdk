package io.github.damienmiheev.monobank.acquiring.monopay

import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.core.json.MonobankJson
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The values the monopay button widget (`MonoPay.init`) needs, signed on your server.
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
)

/**
 * Signs monopay button widget requests: `signature = base64(ECDSA-P256-SHA256(orderJson + requestId))`
 * (DER signature) and `payloadBase64 = base64(orderJson)`, as monobank's monopay button docs
 * describe. The same JSON bytes go into the payload and the signature.
 *
 * Generate an EC P-256 key pair, import the public key with `MonopayApi.importKey`
 * ([publicKeyValue] gives `keyValue`), and keep the private key on your server. Uses only JDK
 * cryptography. Thread-safe. [toString] never prints the key.
 */
public class MonopaySigner private constructor(
    private val privateKey: PrivateKey,
) {
    /**
     * Signs [orderJson] (the order data JSON, structured like `invoice/create`) with [requestId].
     */
    public fun sign(
        orderJson: ByteArray,
        requestId: String,
    ): MonopaySignedRequest {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        val signed = orderJson + requestId.toByteArray(StandardCharsets.UTF_8)
        val der =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(signed)
                sign()
            }
        val encoder = Base64.getEncoder()
        return MonopaySignedRequest(requestId, encoder.encodeToString(orderJson), encoder.encodeToString(der))
    }

    /** Signs an order JSON string (UTF-8). */
    public fun sign(
        orderJson: String,
        requestId: String,
    ): MonopaySignedRequest = sign(orderJson.toByteArray(StandardCharsets.UTF_8), requestId)

    /** Serializes [order] as the widget's order data and signs it. */
    public fun sign(
        order: CreateInvoiceRequest,
        requestId: String,
    ): MonopaySignedRequest = sign(MonobankJson.write(order), requestId)

    override fun toString(): String = "MonopaySigner(privateKey=***)"

    public companion object {
        private const val P256_FIELD_BITS = 256
        private const val DER_SEQUENCE = 0x30
        private const val DER_OCTET_STRING = 0x04
        private const val SHORT_LENGTH_LIMIT = 0x80
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8

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
                0x06,
                0x08,
                0x2A,
                0x86.toByte(),
                0x48,
                0xCE.toByte(),
                0x3D,
                0x03,
                0x01,
                0x07,
            )

        /**
         * A signer for an EC P-256 private key.
         *
         * @throws IllegalArgumentException when the key is not an EC P-256 key.
         */
        @JvmStatic
        public fun of(privateKey: PrivateKey): MonopaySigner {
            require(privateKey is ECPrivateKey && privateKey.params.curve.field.fieldSize == P256_FIELD_BITS) {
                "monopay keys must be EC P-256 keys"
            }
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
            val der = Base64.getDecoder().decode(body)
            val pkcs8 = if (sec1) wrapSec1(der) else der
            val key =
                try {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("Not an EC private key: ${e.message}", e)
                }
            return of(key)
        }

        /**
         * `keyValue` for `MonopayApi.importKey`: base64 of the PEM-encoded public key.
         */
        @JvmStatic
        public fun publicKeyValue(publicKey: PublicKey): String {
            val pem =
                "-----BEGIN PUBLIC KEY-----\n" +
                    Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(publicKey.encoded) +
                    "\n-----END PUBLIC KEY-----\n"
            return Base64.getEncoder().encodeToString(pem.toByteArray(StandardCharsets.US_ASCII))
        }

        private const val PEM_LINE = 64

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

        /** Wraps a SEC1 `ECPrivateKey` into a PKCS#8 `PrivateKeyInfo` for P-256. */
        private fun wrapSec1(sec1: ByteArray): ByteArray {
            val octet = derTlv(DER_OCTET_STRING, sec1)
            return derTlv(DER_SEQUENCE, PKCS8_EC_P256_PREFIX + octet)
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
