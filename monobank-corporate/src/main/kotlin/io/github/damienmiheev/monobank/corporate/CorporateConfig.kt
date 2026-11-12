package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import java.time.Clock

/**
 * Settings for [MonobankCorporate].
 *
 * Start with [builder] and a PEM private key (or your own [CorporateSigner]). The private key
 * is never printed by [toString].
 *
 * The signing switches ([signatureEncoding], [signQueryString]) exist because monobank's docs
 * leave these details open and the SDK's choice has not been confirmed live yet. Keep the
 * defaults unless monobank answers HTTP 403 to correctly registered keys.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 *
 * @property signer Signs requests; a [CorporateKey] when built from PEM.
 * @property keyId `X-Key-Id` sent with every request except company registration. Derived from
 *   the key unless overridden with [Builder.keyId].
 * @property signatureEncoding How `X-Sign` encodes the signature; default [SignatureEncoding.P1363].
 * @property signQueryString Whether the signed URL includes the query string (only
 *   `signature/status` and `signature/cancel` have one); default `false`, see [Builder.signQueryString].
 * @property clock Source of `X-Time`. Replace it to correct a skewed host clock.
 * @property options Transport settings: host override, timeouts, retries, custom HTTP client.
 */
@ExperimentalMonobankApi
public class CorporateConfig private constructor(
    public val signer: CorporateSigner,
    public val keyId: String,
    public val signatureEncoding: SignatureEncoding,
    public val signQueryString: Boolean,
    public val clock: Clock,
    public val options: MonobankClientOptions,
) {
    override fun toString(): String =
        "CorporateConfig(keyId=$keyId, signer=${signer.javaClass.simpleName}, " +
            "signatureEncoding=$signatureEncoding, signQueryString=$signQueryString, clock=$clock, options=$options)"

    /** Builder for [CorporateConfig]. */
    public class Builder internal constructor(
        private val signer: CorporateSigner,
    ) {
        private var keyId: String? = null
        private var signatureEncoding: SignatureEncoding = SignatureEncoding.P1363
        private var signQueryString: Boolean = false
        private var clock: Clock = Clock.systemUTC()
        private var options: MonobankClientOptions = MonobankClientOptions.DEFAULT

        /**
         * Overrides `X-Key-Id`. Normally not needed: it is derived from the key and equals the
         * `keyId` that the registration status call returns. Set it to that returned value if
         * the two ever differ.
         */
        public fun keyId(keyId: String?): Builder =
            apply {
                if (keyId != null) requireHeaderValue(keyId, "keyId")
                this.keyId = keyId
            }

        /** Sets how `X-Sign` encodes the signature (default [SignatureEncoding.P1363]). */
        public fun signatureEncoding(encoding: SignatureEncoding): Builder = apply { signatureEncoding = encoding }

        /**
         * Whether the URL that is signed includes the query string, e.g.
         * `/personal/signature/status?requestId=...` instead of `/personal/signature/status`.
         * The docs only say "URL", and community clients sign the path alone, which is the
         * default (`false`). Affects only `signature/status` and `signature/cancel`.
         */
        public fun signQueryString(sign: Boolean): Builder = apply { signQueryString = sign }

        /** Sets the clock that produces `X-Time` (default: system UTC clock). */
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Sets transport options. */
        public fun options(options: MonobankClientOptions): Builder = apply { this.options = options }

        /** Builds the config. */
        public fun build(): CorporateConfig {
            val effectiveKeyId = keyId ?: signer.keyId
            requireHeaderValue(effectiveKeyId, "keyId")
            return CorporateConfig(signer, effectiveKeyId, signatureEncoding, signQueryString, clock, options)
        }
    }

    public companion object {
        /**
         * Starts a config for the company's private key in PEM (SEC1 `EC PRIVATE KEY` or PKCS#8
         * `PRIVATE KEY`, or base64 of either), as [CorporateKey.fromPem] reads it.
         *
         * @throws IllegalArgumentException when the key cannot be read or is not secp256k1.
         */
        @JvmStatic
        public fun builder(privateKeyPem: String): Builder = Builder(CorporateKey.fromPem(privateKeyPem))

        /** Starts a config for a [CorporateKey] or your own [CorporateSigner] (for example a KMS key). */
        @JvmStatic
        public fun builder(signer: CorporateSigner): Builder = Builder(signer)

        internal fun requireHeaderValue(
            value: String,
            name: String,
        ) {
            require(value.isNotBlank()) { "$name must not be blank" }
            require(value.none { it == '\r' || it == '\n' }) { "$name must be one line" }
        }
    }
}
