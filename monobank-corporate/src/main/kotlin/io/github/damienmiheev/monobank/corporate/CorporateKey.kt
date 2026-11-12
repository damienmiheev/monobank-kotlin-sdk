package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.internal.Secp256k1
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.util.io.pem.PemHeader
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The company's secp256k1 private key, loaded from PEM; signs corporate API requests.
 *
 * Create the key as monobank's docs show:
 * ```
 * openssl ecparam -genkey -name secp256k1 -rand /dev/urandom -out priv.key
 * openssl ec -in priv.key -pubout -out pub.key
 * ```
 * and load `priv.key` with [fromPem]. Signing uses BouncyCastle's lightweight API (the JDK
 * dropped secp256k1 in Java 16) with a deterministic nonce (RFC 6979); no security provider is
 * registered. Immutable and thread-safe. [toString] never shows the private key.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 */
@ExperimentalMonobankApi
public class CorporateKey private constructor(
    private val privateScalar: BigInteger,
) : CorporateSigner {
    private val publicPoint = Secp256k1.publicPoint(privateScalar)
    private val uncompressedPoint: ByteArray = Secp256k1.uncompressed(publicPoint)

    /** `X-Key-Id`: lowercase hex SHA-1 of the uncompressed public key point (40 characters). */
    override val keyId: String = Secp256k1.keyIdOf(uncompressedPoint)

    /** The public key as a PEM `PUBLIC KEY` block, exactly as `openssl ec -pubout` writes it. */
    public val publicKeyPem: String = Secp256k1.publicKeyPem(publicPoint)

    /**
     * Base64 of [publicKeyPem]: the `pubkey` value for company registration and registration
     * status. It must be the same string in both calls.
     */
    public val publicKeyPemBase64: String =
        Base64.getEncoder().encodeToString(publicKeyPem.toByteArray(StandardCharsets.US_ASCII))

    /** The uncompressed public key point `0x04‖X‖Y` (65 bytes); a fresh copy on each call. */
    public fun publicKeyUncompressed(): ByteArray = uncompressedPoint.copyOf()

    /** Signs [data]: ECDSA over SHA-256, deterministic nonce, 64-byte `r‖s`. */
    override fun sign(data: ByteArray): ByteArray = Secp256k1.sign(privateScalar, data)

    override fun toString(): String = "CorporateKey(keyId=$keyId, privateKey=***)"

    public companion object {
        private const val SEC1_TYPE = "EC PRIVATE KEY"
        private const val PKCS8_TYPE = "PRIVATE KEY"
        private const val PEM_BEGIN = "-----BEGIN"

        /**
         * Loads a secp256k1 private key from PEM text: SEC1 (`BEGIN EC PRIVATE KEY`, what the
         * docs' `openssl ecparam -genkey` writes, with or without the `EC PARAMETERS` block) or
         * unencrypted PKCS#8 (`BEGIN PRIVATE KEY`). Base64 of such PEM text (handy in an
         * environment variable) is accepted too.
         *
         * @throws IllegalArgumentException when the text holds no such key, the key is
         *   encrypted, or it is not on secp256k1. The message never contains key material.
         */
        @JvmStatic
        public fun fromPem(pem: String): CorporateKey {
            val text = if (pem.contains(PEM_BEGIN)) pem else decodeBase64Pem(pem)
            val objects = readPemObjects(text)
            val keyObject =
                objects.firstOrNull { it.type == SEC1_TYPE || it.type == PKCS8_TYPE }
                    ?: throw IllegalArgumentException(
                        if (objects.any { it.type.contains("ENCRYPTED") }) {
                            "encrypted private keys are not supported; export it unencrypted (PKCS#8 or SEC1)"
                        } else {
                            "no 'EC PRIVATE KEY' or 'PRIVATE KEY' PEM block found"
                        },
                    )
            val encrypted = keyObject.headers.filterIsInstance<PemHeader>().any { "ENCRYPTED" in it.value.orEmpty() }
            require(!encrypted) {
                "encrypted private keys are not supported; export it unencrypted (PKCS#8 or SEC1)"
            }
            val parsed =
                try {
                    val sec1 =
                        if (keyObject.type == SEC1_TYPE) {
                            ECPrivateKey.getInstance(keyObject.content)
                        } else {
                            sec1FromPkcs8(keyObject.content)
                        }
                    ParsedKey(sec1.key, sec1.parametersObject?.toASN1Primitive(), sec1.publicKey?.octets)
                } catch (e: RuntimeException) {
                    // Malformed ASN.1 surfaces as IllegalArgumentException, ClassCastException, ...
                    // BouncyCastle's messages describe the structure, never the key bytes.
                    throw IllegalArgumentException("malformed ${keyObject.type}: ${e.message}", e)
                }
            return fromParsed(parsed)
        }

        private fun decodeBase64Pem(value: String): String {
            val decoded =
                try {
                    String(Base64.getMimeDecoder().decode(value.trim()), StandardCharsets.US_ASCII)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("not PEM text and not base64 of PEM text", e)
                }
            require(decoded.contains(PEM_BEGIN)) { "not PEM text and not base64 of PEM text" }
            return decoded
        }

        private fun readPemObjects(text: String): List<PemObject> {
            val reader = PemReader(StringReader(text))
            return try {
                generateSequence { reader.readPemObject() }.toList()
            } catch (e: IOException) {
                throw IllegalArgumentException("malformed PEM: ${e.javaClass.simpleName}", e)
            }
        }

        private fun sec1FromPkcs8(content: ByteArray): ECPrivateKey {
            val info = PrivateKeyInfo.getInstance(content)
            val algorithm = info.privateKeyAlgorithm
            require(algorithm.algorithm == X9ObjectIdentifiers.id_ecPublicKey) {
                "PRIVATE KEY is not an EC key (algorithm ${algorithm.algorithm})"
            }
            checkCurve(algorithm.parameters?.toASN1Primitive())
            return ECPrivateKey.getInstance(info.parsePrivateKey())
        }

        private class ParsedKey(
            val d: BigInteger,
            val parameters: ASN1Primitive?,
            val embeddedPublicKey: ByteArray?,
        )

        private fun fromParsed(parsed: ParsedKey): CorporateKey {
            checkCurve(parsed.parameters)
            require(Secp256k1.isValidPrivate(parsed.d)) { "private key is out of range for secp256k1" }
            val key = CorporateKey(parsed.d)
            // The optional embedded public key must match: catches keys of another curve whose
            // parameters were not stored by name.
            parsed.embeddedPublicKey?.let { embedded ->
                val matches =
                    runCatching { Secp256k1.decodePoint(embedded) == key.publicPoint }.getOrDefault(false)
                require(matches) { "the key's public part does not match a secp256k1 private key" }
            }
            return key
        }

        private fun checkCurve(parameters: ASN1Primitive?) {
            if (parameters == null) return
            require(parameters is ASN1ObjectIdentifier) {
                "explicit EC parameters are not supported; use the named curve secp256k1"
            }
            require(parameters == SECObjectIdentifiers.secp256k1) {
                "the key is on curve $parameters, but monobank requires secp256k1 (${SECObjectIdentifiers.secp256k1})"
            }
        }
    }
}
