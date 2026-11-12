package io.github.damienmiheev.monobank.corporate.internal

import io.github.damienmiheev.monobank.corporate.SignatureEncoding
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * secp256k1 operations on BouncyCastle's lightweight API. No JCA provider is registered, so
 * nothing global changes in the host application.
 */
internal object Secp256k1 {
    private val x9 = CustomNamedCurves.getByName("secp256k1")

    /** Curve domain parameters. */
    val domain: ECDomainParameters = ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)

    /** Size of one coordinate and of `r` / `s`, in bytes. */
    const val FIELD_BYTES: Int = 32

    /** Size of a P1363 (`r‖s`) signature. */
    const val RAW_SIGNATURE_BYTES: Int = 2 * FIELD_BYTES

    /** `true` when [d] is a valid private scalar: 1 ≤ d < n. */
    fun isValidPrivate(d: BigInteger): Boolean = d.signum() > 0 && d < domain.n

    /** Public point `d·G`, normalized. */
    fun publicPoint(d: BigInteger): ECPoint = FixedPointCombMultiplier().multiply(domain.g, d).normalize()

    /** Decodes an encoded point (compressed or uncompressed) and checks that it is on the curve. */
    fun decodePoint(encoded: ByteArray): ECPoint = domain.curve.decodePoint(encoded).normalize()

    /** Uncompressed encoding `0x04‖X‖Y` (65 bytes). */
    fun uncompressed(point: ECPoint): ByteArray = point.getEncoded(false)

    /**
     * monobank's key id: lowercase hex SHA-1 of the uncompressed public point. The same rule
     * produces `serverKeyId` from `serverPubKey` in `GET /bank/sync`.
     */
    fun keyIdOf(uncompressedPoint: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(uncompressedPoint)
        return digest.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
    }

    /** X.509 `SubjectPublicKeyInfo` PEM, byte-for-byte what `openssl ec -pubout` writes. */
    fun publicKeyPem(point: ECPoint): String {
        val info =
            SubjectPublicKeyInfo(
                AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256k1),
                uncompressed(point),
            )
        val base64 = Base64.getEncoder().encodeToString(info.getEncoded(ASN1Encoding.DER))
        return buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            base64.chunked(PEM_LINE).forEach { append(it).append('\n') }
            append("-----END PUBLIC KEY-----\n")
        }
    }

    /**
     * ECDSA over SHA-256 of [data], with a deterministic nonce (RFC 6979). Returns `r‖s`,
     * each left-padded to 32 bytes. `s` is not normalized to the lower half: neither
     * monobank's docs nor the community clients do that, and ECDSA verifiers accept both.
     */
    fun sign(
        d: BigInteger,
        data: ByteArray,
    ): ByteArray {
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val (r, s) = signer.generateSignature(sha256(data))
        return SignatureCodec.rawOf(r, s)
    }

    /** `true` when [signature] (`r‖s` or DER) is a valid ECDSA-SHA256 signature of [data] by [point]. */
    fun verify(
        point: ECPoint,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val (r, s) = runCatching { SignatureCodec.parse(signature) }.getOrNull() ?: return false
        val verifier = ECDSASigner()
        verifier.init(false, ECPublicKeyParameters(point, domain))
        return verifier.verifySignature(sha256(data), r, s)
    }

    /**
     * Requires [parameters], when present, to name secp256k1.
     *
     * @throws IllegalArgumentException for another curve or explicit parameters.
     */
    fun checkCurve(parameters: ASN1Primitive?) {
        if (parameters == null) return
        require(parameters is ASN1ObjectIdentifier) {
            "explicit EC parameters are not supported; use the named curve secp256k1"
        }
        require(parameters == SECObjectIdentifiers.secp256k1) {
            "the key is on curve $parameters, but monobank requires secp256k1 (${SECObjectIdentifiers.secp256k1})"
        }
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash
    }

    private const val PEM_LINE = 64
    private const val BYTE_MASK = 0xff
}
