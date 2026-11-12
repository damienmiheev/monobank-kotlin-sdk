package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.Redaction
import java.time.Duration

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
 * @property webhookKeyRefreshInterval Minimum time between two webhook key fetches triggered by a
 *   signature that does not verify (default 1 minute). Tests that rotate keys can lower it.
 */
public class AcquiringConfig private constructor(
    public val token: String,
    public val cmsName: String?,
    public val cmsVersion: String?,
    public val options: MonobankClientOptions,
    public val webhookKeyRefreshInterval: Duration,
) {
    override fun toString(): String =
        "AcquiringConfig(token=***, cmsName=$cmsName, cmsVersion=$cmsVersion, options=$options, " +
            "webhookKeyRefreshInterval=$webhookKeyRefreshInterval)"

    /** Builder for [AcquiringConfig]. */
    public class Builder internal constructor(
        private val token: String,
    ) {
        private var cmsName: String? = null
        private var cmsVersion: String? = null
        private var options: MonobankClientOptions = MonobankClientOptions.DEFAULT
        private var webhookKeyRefreshInterval: Duration = Duration.ofMinutes(1)

        /** Sets the `X-Cms` header. */
        public fun cmsName(cmsName: String?): Builder =
            apply {
                this.cmsName =
                    cmsName?.let { headerText(it, "cmsName") }
            }

        /** Sets the `X-Cms-Version` header. */
        public fun cmsVersion(cmsVersion: String?): Builder =
            apply { this.cmsVersion = cmsVersion?.let { headerText(it, "cmsVersion") } }

        /** Sets transport options. */
        public fun options(options: MonobankClientOptions): Builder = apply { this.options = options }

        /** Minimum time between webhook key re-fetches (default 1 minute; zero allowed in tests). */
        public fun webhookKeyRefreshInterval(interval: Duration): Builder =
            apply {
                require(!interval.isNegative) { "webhookKeyRefreshInterval must not be negative" }
                webhookKeyRefreshInterval = interval
            }

        /** Builds the config. */
        public fun build(): AcquiringConfig =
            AcquiringConfig(token, cmsName, cmsVersion, options, webhookKeyRefreshInterval)
    }

    public companion object {
        /** Starts a config for [token]. */
        @JvmStatic
        public fun builder(token: String): Builder {
            require(token.isNotBlank()) { "token must not be blank" }
            // Printable ASCII only: otherwise the JDK would reject it on the first request and
            // echo the whole token in its exception message.
            Redaction.requireHeaderSafe(token, "token")
            return Builder(token)
        }

        private val HEADER_TEXT = 0x20..0x7E

        /** Printable ASCII; inner spaces are allowed (e.g. `Kill Bill`), leading/trailing ones are not. */
        private fun headerText(
            value: String,
            what: String,
        ): String {
            require(value.isNotEmpty() && value.trim() == value) { "$what must not be empty or start/end with a space" }
            require(value.all { it.code in HEADER_TEXT }) { "$what may contain only printable ASCII characters" }
            return value
        }
    }
}
