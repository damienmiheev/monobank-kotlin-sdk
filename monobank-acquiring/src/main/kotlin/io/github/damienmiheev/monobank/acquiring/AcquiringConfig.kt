package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.core.MonobankClientOptions

/**
 * Settings for [MonobankAcquiring].
 *
 * The token is never printed by [toString].
 *
 * @property token Merchant token from https://web.monobank.ua/ (or a test token from https://api.monobank.ua/),
 *   sent as `X-Token`.
 * @property cmsName Optional `X-Cms` header: the name of your CMS or platform.
 * @property cmsVersion Optional `X-Cms-Version` header.
 * @property options Transport settings: host override, timeouts, retries, custom HTTP client.
 */
public class AcquiringConfig private constructor(
    public val token: String,
    public val cmsName: String?,
    public val cmsVersion: String?,
    public val options: MonobankClientOptions,
) {
    override fun toString(): String =
        "AcquiringConfig(token=***, cmsName=$cmsName, cmsVersion=$cmsVersion, options=$options)"

    /** Builder for [AcquiringConfig]. */
    public class Builder internal constructor(
        private val token: String,
    ) {
        private var cmsName: String? = null
        private var cmsVersion: String? = null
        private var options: MonobankClientOptions = MonobankClientOptions.DEFAULT

        /** Sets the `X-Cms` header. */
        public fun cmsName(cmsName: String?): Builder = apply { this.cmsName = cmsName }

        /** Sets the `X-Cms-Version` header. */
        public fun cmsVersion(cmsVersion: String?): Builder = apply { this.cmsVersion = cmsVersion }

        /** Sets transport options. */
        public fun options(options: MonobankClientOptions): Builder = apply { this.options = options }

        /** Builds the config. */
        public fun build(): AcquiringConfig = AcquiringConfig(token, cmsName, cmsVersion, options)
    }

    public companion object {
        /** Starts a config for [token]. */
        @JvmStatic
        public fun builder(token: String): Builder {
            require(token.isNotBlank()) { "token must not be blank" }
            require(token.none { it == '\r' || it == '\n' }) { "token must be one line" }
            return Builder(token)
        }
    }
}
