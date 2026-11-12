package io.github.damienmiheev.monobank.core

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * Transport settings shared by every monobank client. Credentials are not part of these
 * options; each client takes its own.
 *
 * Build with [builder]; [DEFAULT] uses monobank's production host for the client it is given to.
 *
 * @property baseUrl Overrides the API host, for tests or a proxy. `null` means the client's default host.
 * @property connectTimeout TCP/TLS connect timeout.
 * @property requestTimeout Timeout for one whole request (sending it and reading the answer).
 * @property retryPolicy When failed requests are retried.
 * @property httpClient Your own [HttpClient] (for a proxy, custom TLS or executor). When set,
 *   [connectTimeout] is ignored because it belongs to the client.
 * @property userAgentSuffix Appended to the SDK's `User-Agent`, for example `my-shop/2.1`.
 */
public class MonobankClientOptions private constructor(
    builder: Builder,
) {
    public val baseUrl: URI? = builder.baseUrl
    public val connectTimeout: Duration = builder.connectTimeout
    public val requestTimeout: Duration = builder.requestTimeout
    public val retryPolicy: RetryPolicy = builder.retryPolicy
    public val httpClient: HttpClient? = builder.httpClient
    public val userAgentSuffix: String? = builder.userAgentSuffix

    /** Returns a builder pre-filled with these options. */
    public fun toBuilder(): Builder =
        Builder()
            .also { it.baseUrl = baseUrl }
            .connectTimeout(connectTimeout)
            .requestTimeout(requestTimeout)
            .retryPolicy(retryPolicy)
            .httpClient(httpClient)
            .userAgentSuffix(userAgentSuffix)

    override fun toString(): String =
        "MonobankClientOptions(baseUrl=$baseUrl, connectTimeout=$connectTimeout, requestTimeout=$requestTimeout, " +
            "retryPolicy=$retryPolicy, customHttpClient=${httpClient != null}, userAgentSuffix=$userAgentSuffix)"

    /** Builder for [MonobankClientOptions]. */
    public class Builder internal constructor() {
        internal var baseUrl: URI? = null
        internal var connectTimeout: Duration = Duration.ofSeconds(10)
        internal var requestTimeout: Duration = Duration.ofSeconds(30)
        internal var retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
        internal var httpClient: HttpClient? = null
        internal var userAgentSuffix: String? = null

        /** Overrides the API host, e.g. `http://localhost:8080` in tests. */
        public fun baseUrl(baseUrl: String): Builder =
            apply {
                val uri = URI.create(baseUrl.trimEnd('/'))
                require(uri.scheme == "https" || uri.scheme == "http") { "baseUrl must be an http(s) URL" }
                this.baseUrl = uri
            }

        /** Sets the connect timeout (default 10 s). */
        public fun connectTimeout(timeout: Duration): Builder =
            apply {
                require(!timeout.isNegative && !timeout.isZero) { "connectTimeout must be positive" }
                connectTimeout = timeout
            }

        /** Sets the per-request timeout (default 30 s). */
        public fun requestTimeout(timeout: Duration): Builder =
            apply {
                require(!timeout.isNegative && !timeout.isZero) { "requestTimeout must be positive" }
                requestTimeout = timeout
            }

        /** Sets the retry policy (default [RetryPolicy.DEFAULT]). */
        public fun retryPolicy(policy: RetryPolicy): Builder = apply { retryPolicy = policy }

        /** Uses your own [HttpClient]. */
        public fun httpClient(client: HttpClient?): Builder = apply { httpClient = client }

        /** Appends a product token to the `User-Agent` header. */
        public fun userAgentSuffix(suffix: String?): Builder =
            apply {
                require(
                    suffix == null || suffix.none { it == '\r' || it == '\n' },
                ) { "userAgentSuffix must be one line" }
                userAgentSuffix = suffix
            }

        /** Builds the options. */
        public fun build(): MonobankClientOptions = MonobankClientOptions(this)
    }

    public companion object {
        /** Default options: production host, 10 s connect, 30 s request timeout, [RetryPolicy.DEFAULT]. */
        @JvmField
        public val DEFAULT: MonobankClientOptions = Builder().build()

        /** Starts building options. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
