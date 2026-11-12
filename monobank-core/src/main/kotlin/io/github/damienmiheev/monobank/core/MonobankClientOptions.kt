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
 * @property httpClient Your own [HttpClient] (for a proxy, custom TLS or executor). It must not
 *   follow redirects. When set, [connectTimeout] is ignored because it belongs to the client.
 * @property userAgentSuffix Appended to the SDK's `User-Agent`, for example `my-shop/2.1`.
 * @property maxResponseBytes Largest response body the SDK reads (default 20 MB). A bigger answer
 *   (for example from a misbehaving proxy) fails with `MonobankSerializationException` instead of
 *   exhausting memory.
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
    public val maxResponseBytes: Long = builder.maxResponseBytes

    /** Returns a builder pre-filled with these options. */
    public fun toBuilder(): Builder =
        Builder()
            .also { it.baseUrl = baseUrl }
            .connectTimeout(connectTimeout)
            .requestTimeout(requestTimeout)
            .retryPolicy(retryPolicy)
            .httpClient(httpClient)
            .userAgentSuffix(userAgentSuffix)
            .maxResponseBytes(maxResponseBytes)

    override fun toString(): String =
        "MonobankClientOptions(baseUrl=$baseUrl, connectTimeout=$connectTimeout, requestTimeout=$requestTimeout, " +
            "retryPolicy=$retryPolicy, customHttpClient=${httpClient != null}, userAgentSuffix=$userAgentSuffix, maxResponseBytes=$maxResponseBytes)"

    /** Builder for [MonobankClientOptions]. */
    public class Builder internal constructor() {
        internal var baseUrl: URI? = null
        internal var connectTimeout: Duration = Duration.ofSeconds(10)
        internal var requestTimeout: Duration = Duration.ofSeconds(30)
        internal var retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
        internal var httpClient: HttpClient? = null
        internal var userAgentSuffix: String? = null
        internal var maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES

        /**
         * Overrides the API host, e.g. a proxy or `http://localhost:8080` in tests. Plain `http` is
         * allowed only for loopback hosts, so credentials never travel unencrypted. `null` restores the default.
         */
        public fun baseUrl(baseUrl: String?): Builder =
            apply {
                if (baseUrl == null) {
                    this.baseUrl = null
                } else {
                    val uri = URI.create(baseUrl.trimEnd('/'))
                    require(uri.scheme == "https" || uri.scheme == "http") { "baseUrl must be an http(s) URL" }
                    require(uri.host != null) { "baseUrl must have a host" }
                    require(uri.rawQuery == null && uri.rawFragment == null) {
                        "baseUrl must not have a query or fragment"
                    }
                    require(uri.scheme == "https" || uri.host in LOOPBACK_HOSTS) {
                        "Plain http is allowed only for localhost; use https for ${uri.host}"
                    }
                    this.baseUrl = uri
                }
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
        public fun httpClient(client: HttpClient?): Builder =
            apply {
                require(client == null || client.followRedirects() == HttpClient.Redirect.NEVER) {
                    "The HttpClient must use HttpClient.Redirect.NEVER, " +
                        "so credentials are never re-sent to another host"
                }
                httpClient = client
            }

        /** Appends a product token to the `User-Agent` header. */
        public fun userAgentSuffix(suffix: String?): Builder =
            apply {
                require(
                    suffix == null || suffix.none { it == '\r' || it == '\n' },
                ) { "userAgentSuffix must be one line" }
                userAgentSuffix = suffix
            }

        /** Largest response body to read, in bytes (default 20 MB). */
        public fun maxResponseBytes(bytes: Long): Builder =
            apply {
                require(bytes > 0) { "maxResponseBytes must be positive" }
                maxResponseBytes = bytes
            }

        /** Builds the options. */
        public fun build(): MonobankClientOptions = MonobankClientOptions(this)
    }

    public companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        private const val DEFAULT_MAX_RESPONSE_BYTES: Long = 20L * 1024 * 1024

        /** Default options: production host, 10 s connect, 30 s request timeout, [RetryPolicy.DEFAULT]. */
        @JvmField
        public val DEFAULT: MonobankClientOptions = Builder().build()

        /** Starts building options. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
