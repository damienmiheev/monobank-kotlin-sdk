package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.internal.Pem
import io.github.damienmiheev.monobank.corporate.internal.Secp256k1
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.math.ec.ECPoint
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The company's secp256k1 public key, and the values monobank derives from it.
 *
 * Get it from [CorporateKey.publicKey], or, when the private key lives in a KMS or HSM, load it
 * from the public key that service exports ([fromPem], [fromSpkiDer] or [fromPoint]). It gives
 * the exact registration `pubkey` value ([publicKeyPemBase64]) and `X-Key-Id` ([keyId]), and
 * can check a [CorporateSigner]'s output with [verify]. Immutable and thread-safe.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 */
@ExperimentalMonobankApi
public class CorporatePublicKey internal constructor(
    internal val point: ECPoint,
) {
    private val uncompressedPoint: ByteArray = Secp256k1.uncompressed(point)

    /** `X-Key-Id`: lowercase hex SHA-1 of the uncompressed point `0x04‖X‖Y` (40 characters). */
    public val keyId: String = Secp256k1.keyIdOf(uncompressedPoint)

    /**
     * PEM `PUBLIC KEY` block (X.509 SubjectPublicKeyInfo, named curve, uncompressed point,
     * 64-character lines, `\n` line ends), byte-for-byte what `openssl ec -pubout` writes.
     */
    public val publicKeyPem: String = Secp256k1.publicKeyPem(point)

    /**
     * Base64 of [publicKeyPem]: the `pubkey` value for company registration and registration
     * status. Both calls must send the same string.
     */
    public val publicKeyPemBase64: String =
        Base64.getEncoder().encodeToString(publicKeyPem.toByteArray(StandardCharsets.US_ASCII))

    /** The uncompressed point `0x04‖X‖Y` (65 bytes); a fresh copy on each call. */
    public fun uncompressed(): ByteArray = uncompressedPoint.copyOf()

    /**
     * `true` when [signature] (64-byte `r‖s` or DER) is a valid ECDSA-SHA256 signature of [data]
     * by this key. Use it to check a custom [CorporateSigner] before going live.
     */
    public fun verify(
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = Secp256k1.verify(point, data, signature)

    override fun equals(other: Any?): Boolean = other is CorporatePublicKey && other.keyId == keyId

    override fun hashCode(): Int = keyId.hashCode()

    override fun toString(): String = "CorporatePublicKey(keyId=$keyId)"

    public companion object {
        /**
         * Loads a public key from PEM (`BEGIN PUBLIC KEY`, as `openssl ec -pubout` writes) or from
         * base64 of that PEM (the registration `pubkey` format).
         *
         * @throws IllegalArgumentException when there is no secp256k1 public key.
         */
        @JvmStatic
        public fun fromPem(pem: String): CorporatePublicKey {
            val text = Pem.textOf(pem)
            val block =
                Pem.readObjects(text).firstOrNull { it.type == "PUBLIC KEY" }
                    ?: throw IllegalArgumentException("no 'PUBLIC KEY' PEM block found")
            return fromSpkiDer(block.content)
        }

        /**
         * Loads a public key from DER X.509 SubjectPublicKeyInfo, e.g. what AWS KMS `GetPublicKey`
         * returns for an `ECC_SECG_P256K1` key.
         *
         * @throws IllegalArgumentException when it is not a secp256k1 public key.
         */
        @JvmStatic
        public fun fromSpkiDer(der: ByteArray): CorporatePublicKey {
            val info =
                try {
                    SubjectPublicKeyInfo.getInstance(der)
                } catch (e: RuntimeException) {
                    throw IllegalArgumentException("malformed SubjectPublicKeyInfo: ${e.message}", e)
                }
            val algorithm = info.algorithm
            require(algorithm.algorithm == X9ObjectIdentifiers.id_ecPublicKey) {
                "not an EC public key (algorithm ${algorithm.algorithm})"
            }
            val parameters = algorithm.parameters?.toASN1Primitive()
            require(parameters is ASN1ObjectIdentifier) { "the key must name its curve (secp256k1)" }
            Secp256k1.checkCurve(parameters)
            return fromPoint(info.publicKeyData.octets)
        }

        /**
         * Loads a public key from an encoded secp256k1 point: uncompressed (65 bytes, `0x04‖X‖Y`,
         * like `serverPubKey` of `GET /bank/sync`) or compressed (33 bytes).
         *
         * @throws IllegalArgumentException when the bytes are not a point on secp256k1.
         */
        @JvmStatic
        public fun fromPoint(encoded: ByteArray): CorporatePublicKey {
            val point =
                try {
                    Secp256k1.decodePoint(encoded)
                } catch (e: RuntimeException) {
                    throw IllegalArgumentException("not a secp256k1 point: ${e.message}", e)
                }
            require(!point.isInfinity) { "not a secp256k1 point: point at infinity" }
            return CorporatePublicKey(point)
        }
    }
}
