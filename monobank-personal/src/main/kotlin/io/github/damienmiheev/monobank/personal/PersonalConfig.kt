package io.github.damienmiheev.monobank.personal

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.Redaction

/**
 * Settings for [MonobankPersonal].
 *
 * The token is never printed by [toString].
 *
 * @property token Personal token from https://api.monobank.ua/, sent as `X-Token` to the
 *   `/personal/...` endpoints only. `null` for a client that only calls the public `/bank/...`
 *   endpoints (see [publicBuilder]).
 * @property options Transport settings: host override, timeouts, retries, custom HTTP client.
 * @property rateLimiter Client-side limiter for the endpoints monobank limits to one call per
 *   60 seconds (`client-info`, `statement`). `null` (the default) means no client-side limiting:
 *   a call made too early fails at once with `MonobankRateLimitException` (`retryAfter` = 60 s);
 *   monobank sends no `Retry-After`, so the SDK assumes 60 s, which is longer than any
 *   `RetryPolicy.maxBackoff` below 60 s (the default is 10 s), so the call is not retried.
 */
public class PersonalConfig private constructor(
    public val token: String?,
    public val options: MonobankClientOptions,
    public val rateLimiter: PersonalRateLimiter?,
) {
    override fun toString(): String =
        "PersonalConfig(token=${if (token == null) "null" else "***"}, options=$options, rateLimiter=$rateLimiter)"

    /** Builder for [PersonalConfig]. */
    public class Builder internal constructor(
        private val token: String?,
    ) {
        private var options: MonobankClientOptions = MonobankClientOptions.DEFAULT
        private var rateLimiter: PersonalRateLimiter? = null

        /** Sets transport options. */
        public fun options(options: MonobankClientOptions): Builder = apply { this.options = options }

        /**
         * Enables a client-side rate limiter (off by default). With it, calls to `client-info` and
         * `statement` block until the limiter's interval (61 s by default) has passed since the
         * previous call to the same endpoint. Share one limiter between clients of the same token.
         */
        public fun rateLimiter(rateLimiter: PersonalRateLimiter?): Builder = apply { this.rateLimiter = rateLimiter }

        /** Builds the config. */
        public fun build(): PersonalConfig = PersonalConfig(token, options, rateLimiter)
    }

    public companion object {
        /**
         * Starts a config for a personal [token] (from https://api.monobank.ua/).
         *
         * @throws IllegalArgumentException when the token is empty or has characters an HTTP header
         *   cannot carry (spaces, control characters, non-ASCII). The message never contains the token.
         */
        @JvmStatic
        public fun builder(token: String): Builder {
            Redaction.requireHeaderSafe(token, "token")
            return Builder(token)
        }

        /**
         * Starts a config without a token. Such a client can call only the public endpoints
         * ([MonobankPersonal.bank]); the others throw [IllegalStateException].
         */
        @JvmStatic
        public fun publicBuilder(): Builder = Builder(null)
    }
}
