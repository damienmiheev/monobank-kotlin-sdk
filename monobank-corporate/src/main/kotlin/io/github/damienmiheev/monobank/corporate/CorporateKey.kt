package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.internal.Pem
import io.github.damienmiheev.monobank.corporate.internal.Secp256k1
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.util.io.pem.PemHeader
import java.math.BigInteger
import java.util.Arrays

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
    /** The matching public key: registration `pubkey`, PEM and verification. */
    public val publicKey: CorporatePublicKey = CorporatePublicKey(Secp256k1.publicPoint(privateScalar))

    /** `X-Key-Id`: lowercase hex SHA-1 of the uncompressed public key point (40 characters). */
    override val keyId: String = publicKey.keyId

    /** Signs [data]: ECDSA over SHA-256, deterministic nonce, 64-byte `r‖s`. */
    override fun sign(data: ByteArray): ByteArray = Secp256k1.sign(privateScalar, data)

    override fun toString(): String = "CorporateKey(keyId=$keyId, privateKey=***)"

    public companion object {
        private const val SEC1_TYPE = "EC PRIVATE KEY"
        private const val PKCS8_TYPE = "PRIVATE KEY"
        private const val PARAMETERS_TYPE = "EC PARAMETERS"
        private const val ENCRYPTED_MESSAGE =
            "encrypted private keys are not supported; export it unencrypted (PKCS#8 or SEC1)"

        /**
         * Loads a secp256k1 private key from PEM text: SEC1 (`BEGIN EC PRIVATE KEY`, what the
         * docs' `openssl ecparam -genkey` writes, with or without the `EC PARAMETERS` block) or
         * unencrypted PKCS#8 (`BEGIN PRIVATE KEY`). Base64 of such PEM text (handy in an
         * environment variable) is accepted too. A key that names no curve is taken as secp256k1.
         *
         * @throws IllegalArgumentException when the text holds no such key, the key is
         *   encrypted, or it is not on secp256k1. The message never contains key material.
         */
        @JvmStatic
        public fun fromPem(pem: String): CorporateKey {
            val objects = Pem.readObjects(Pem.textOf(pem))
            try {
                val keyObject =
                    objects.firstOrNull { it.type == SEC1_TYPE || it.type == PKCS8_TYPE }
                        ?: throw IllegalArgumentException(
                            if (objects.any { it.type.contains("ENCRYPTED") }) {
                                ENCRYPTED_MESSAGE
                            } else {
                                "no 'EC PRIVATE KEY' or 'PRIVATE KEY' PEM block found"
                            },
                        )
                val encrypted =
                    keyObject.headers.filterIsInstance<PemHeader>().any { "ENCRYPTED" in it.value.orEmpty() }
                require(!encrypted) { ENCRYPTED_MESSAGE }
                // openssl writes the curve into an EC PARAMETERS block too; it must agree.
                objects.firstOrNull { it.type == PARAMETERS_TYPE }?.let {
                    Secp256k1.checkCurve(parse(PARAMETERS_TYPE) { ASN1Primitive.fromByteArray(it.content) })
                }
                val parsed =
                    parse(keyObject.type) {
                        val sec1 =
                            if (keyObject.type == SEC1_TYPE) {
                                ECPrivateKey.getInstance(keyObject.content)
                            } else {
                                sec1FromPkcs8(keyObject.content)
                            }
                        ParsedKey(sec1.key, sec1.parametersObject?.toASN1Primitive(), sec1.publicKey?.octets)
                    }
                return fromParsed(parsed)
            } finally {
                // Defence in depth: the decoded key bytes do not outlive this call.
                objects.forEach { Arrays.fill(it.content, 0) }
            }
        }

        /** Runs [block]; malformed ASN.1 (IllegalArgumentException, ClassCastException, ...) becomes one message. */
        private inline fun <T> parse(
            type: String,
            block: () -> T,
        ): T =
            try {
                block()
            } catch (e: RuntimeException) {
                // BouncyCastle's messages describe the structure, never the key bytes.
                throw IllegalArgumentException("malformed $type: ${e.message}", e)
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("malformed $type: ${e.message}", e)
            }

        private fun sec1FromPkcs8(content: ByteArray): ECPrivateKey {
            val info = PrivateKeyInfo.getInstance(content)
            val algorithm = info.privateKeyAlgorithm
            require(algorithm.algorithm == X9ObjectIdentifiers.id_ecPublicKey) {
                "PRIVATE KEY is not an EC key (algorithm ${algorithm.algorithm})"
            }
            Secp256k1.checkCurve(algorithm.parameters?.toASN1Primitive())
            return ECPrivateKey.getInstance(info.parsePrivateKey())
        }

        private class ParsedKey(
            val d: BigInteger,
            val parameters: ASN1Primitive?,
            val embeddedPublicKey: ByteArray?,
        )

        private fun fromParsed(parsed: ParsedKey): CorporateKey {
            Secp256k1.checkCurve(parsed.parameters)
            require(Secp256k1.isValidPrivate(parsed.d)) { "private key is out of range for secp256k1" }
            val key = CorporateKey(parsed.d)
            // The optional embedded public key must match: catches keys of another curve whose
            // parameters were not stored by name.
            parsed.embeddedPublicKey?.let { embedded ->
                val matches =
                    runCatching { Secp256k1.decodePoint(embedded) == key.publicKey.point }.getOrDefault(false)
                require(matches) { "the key's public part does not match a secp256k1 private key" }
            }
            return key
        }
    }
}
