package io.github.damienmiheev.monobank.corporate.internal

import io.github.damienmiheev.monobank.corporate.SignatureEncoding
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
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
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val (r, s) = signer.generateSignature(hash)
        return rawOf(r, s)
    }

    /**
     * Converts a signature from a signer (64-byte `r‖s` or DER) to [encoding].
     *
     * @throws IllegalArgumentException when [signature] is neither.
     */
    fun encode(
        signature: ByteArray,
        encoding: SignatureEncoding,
    ): ByteArray {
        val (r, s) = parse(signature)
        return when (encoding) {
            SignatureEncoding.P1363 -> rawOf(r, s)
            SignatureEncoding.DER -> DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).getEncoded(ASN1Encoding.DER)
        }
    }

    /** Splits a 64-byte `r‖s` or a DER signature into `(r, s)` and range-checks both. */
    fun parse(signature: ByteArray): Pair<BigInteger, BigInteger> {
        val (r, s) =
            if (signature.size == RAW_SIGNATURE_BYTES) {
                BigInteger(1, signature.copyOfRange(0, FIELD_BYTES)) to
                    BigInteger(1, signature.copyOfRange(FIELD_BYTES, RAW_SIGNATURE_BYTES))
            } else {
                val sequence =
                    try {
                        ASN1Sequence.getInstance(signature)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("signature is neither 64-byte r||s nor DER", e)
                    }
                require(sequence.size() == 2) { "DER signature must hold exactly r and s" }
                ASN1Integer.getInstance(sequence.getObjectAt(0)).value to
                    ASN1Integer.getInstance(sequence.getObjectAt(1)).value
            }
        require(r.signum() > 0 && r < domain.n && s.signum() > 0 && s < domain.n) {
            "signature values are out of range for secp256k1"
        }
        return r to s
    }

    private fun rawOf(
        r: BigInteger,
        s: BigInteger,
    ): ByteArray = BigIntegers.asUnsignedByteArray(FIELD_BYTES, r) + BigIntegers.asUnsignedByteArray(FIELD_BYTES, s)

    private const val PEM_LINE = 64
    private const val BYTE_MASK = 0xff
}
