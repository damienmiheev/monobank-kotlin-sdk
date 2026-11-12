package io.github.damienmiheev.monobank.installments

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.Redaction
import io.github.damienmiheev.monobank.installments.internal.HmacSigner

/**
 * Settings for [MonobankInstallments].
 *
 * The store secret is never printed by [toString] and is not exposed: it is kept only inside the request signer.
 *
 * @property storeId Store id issued by monobank, sent as the `store-id` header.
 * @property environment Which monobank host to call; `options.baseUrl` overrides it.
 * @property options Transport settings: host override, timeouts, retries, custom HTTP client.
 */
public class InstallmentsConfig private constructor(
    public val storeId: String,
    storeSecret: String,
    public val environment: InstallmentsEnvironment,
    public val options: MonobankClientOptions,
) {
    /** Signs with the store secret; the secret itself is not reachable from it. */
    internal val signer: HmacSigner = HmacSigner(storeSecret)

    /** The host requests go to: `options.baseUrl` when set, otherwise [environment]'s host. */
    public val baseUrl: String
        get() = options.baseUrl?.toString() ?: environment.baseUrl

    override fun toString(): String =
        "InstallmentsConfig(storeId=$storeId, storeSecret=***, environment=$environment, options=$options)"

    /** Builder for [InstallmentsConfig]. */
    public class Builder internal constructor(
        private val storeId: String,
        private val storeSecret: String,
    ) {
        private var environment: InstallmentsEnvironment = InstallmentsEnvironment.PRODUCTION
        private var options: MonobankClientOptions = MonobankClientOptions.DEFAULT

        /** Selects the environment (default [InstallmentsEnvironment.PRODUCTION]). */
        public fun environment(environment: InstallmentsEnvironment): Builder = apply { this.environment = environment }

        /** Sets transport options. */
        public fun options(options: MonobankClientOptions): Builder = apply { this.options = options }

        /** Builds the config. */
        public fun build(): InstallmentsConfig = InstallmentsConfig(storeId, storeSecret, environment, options)

        override fun toString(): String = "InstallmentsConfig.Builder(storeId=$storeId, storeSecret=***)"
    }

    public companion object {
        /**
         * Starts a config.
         *
         * @param storeId Store id issued by monobank (`store-id` header).
         * @param storeSecret Store secret used to sign requests and verify callbacks (HMAC-SHA256 key, UTF-8).
         */
        @JvmStatic
        public fun builder(
            storeId: String,
            storeSecret: String,
        ): Builder {
            require(storeId.isNotBlank()) { "storeId must not be blank" }
            // A stray space from an env var would otherwise give a 401 on every call.
            require(storeId == storeId.trim()) { "storeId must not have leading or trailing whitespace" }
            Redaction.requireHeaderSafe(storeId, "storeId")
            // The secret is used exactly as issued (it is the HMAC key), so it is not trimmed.
            require(storeSecret.isNotEmpty()) { "storeSecret must not be empty" }
            return Builder(storeId, storeSecret)
        }
    }
}
