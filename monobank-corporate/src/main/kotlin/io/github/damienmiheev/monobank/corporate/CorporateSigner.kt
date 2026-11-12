package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi

/**
 * Signs corporate API requests with the company's secp256k1 key.
 *
 * [CorporateKey] is the built-in implementation (a key loaded from PEM). Implement this
 * interface yourself to keep the key in a KMS or HSM; AWS KMS, for example, supports the
 * `ECC_SECG_P256K1` key spec.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 */
@ExperimentalMonobankApi
public interface CorporateSigner {
    /**
     * Key id sent as `X-Key-Id`: lowercase hex SHA-1 of the uncompressed public key point
     * (`0x04‖X‖Y`). monobank also returns it as `keyId` from the registration status call.
     */
    public val keyId: String

    /**
     * Signs [data] with ECDSA on secp256k1 over its SHA-256 digest (the signer hashes [data]
     * itself). Returns the signature either as 64 bytes `r‖s` or as DER; the SDK converts it
     * to the configured [SignatureEncoding]. Called once per HTTP attempt, from any thread.
     */
    public fun sign(data: ByteArray): ByteArray
}

/**
 * How the ECDSA signature is encoded (before base64) in the `X-Sign` header.
 *
 * **Experimental:** see [CorporateSigner].
 */
@ExperimentalMonobankApi
public enum class SignatureEncoding {
    /**
     * 64 bytes: `r` and `s`, each a 32-byte big-endian integer (IEEE P1363). The default: the
     * `X-Sign` example in monobank's docs decodes to exactly 64 bytes, and the python-monobank
     * client (`ecdsa` library, default `sigencode_string`) sends this format.
     */
    P1363,

    /**
     * ASN.1 DER `SEQUENCE { r INTEGER, s INTEGER }` (usually 70–72 bytes): what `openssl dgst
     * -sign`, JCA `SHA256withECDSA`, Go `ecdsa.SignASN1` and AWS KMS produce. Several open-source
     * monobank clients send DER (their source shows it); whether monobank accepts it is not
     * confirmed. Try it if monobank rejects [P1363] signatures with HTTP 401 or 403.
     */
    DER,
}
