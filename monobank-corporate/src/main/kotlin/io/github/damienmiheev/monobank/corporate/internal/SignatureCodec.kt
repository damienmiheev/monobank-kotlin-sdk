package io.github.damienmiheev.monobank.corporate.internal

import io.github.damienmiheev.monobank.corporate.SignatureEncoding
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger

/** Conversion between the two ECDSA signature encodings: 64-byte `r‖s` (P1363) and DER. */
internal object SignatureCodec {
    private const val DER_SEQUENCE: Byte = 0x30

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

    /**
     * Splits a 64-byte `r‖s` or a DER signature into `(r, s)` and range-checks both. Input that
     * starts like DER is read as DER only if it is canonical DER (re-encodes to the same bytes),
     * so a 64-byte DER signature is not mistaken for `r‖s` and vice versa.
     */
    fun parse(signature: ByteArray): Pair<BigInteger, BigInteger> {
        val (r, s) =
            parseDer(signature)
                ?: run {
                    require(signature.size == Secp256k1.RAW_SIGNATURE_BYTES) {
                        "signature is neither 64-byte r||s nor DER (${signature.size} bytes)"
                    }
                    BigInteger(1, signature.copyOfRange(0, Secp256k1.FIELD_BYTES)) to
                        BigInteger(1, signature.copyOfRange(Secp256k1.FIELD_BYTES, Secp256k1.RAW_SIGNATURE_BYTES))
                }
        require(r.signum() > 0 && r < Secp256k1.domain.n && s.signum() > 0 && s < Secp256k1.domain.n) {
            "signature values are out of range for secp256k1"
        }
        return r to s
    }

    private fun parseDer(signature: ByteArray): Pair<BigInteger, BigInteger>? {
        val looksLikeDer =
            signature.size > 2 && signature[0] == DER_SEQUENCE && signature[1].toInt() == signature.size - 2
        if (!looksLikeDer) return null
        val sequence = runCatching { ASN1Sequence.getInstance(signature) }.getOrNull() ?: return null
        if (sequence.size() != 2 || !sequence.getEncoded(ASN1Encoding.DER).contentEquals(signature)) return null
        return runCatching {
            ASN1Integer.getInstance(sequence.getObjectAt(0)).value to
                ASN1Integer.getInstance(sequence.getObjectAt(1)).value
        }.getOrNull()
    }

    /** `r‖s`, each left-padded to 32 bytes. */
    fun rawOf(
        r: BigInteger,
        s: BigInteger,
    ): ByteArray =
        BigIntegers.asUnsignedByteArray(Secp256k1.FIELD_BYTES, r) +
            BigIntegers.asUnsignedByteArray(Secp256k1.FIELD_BYTES, s)
}
