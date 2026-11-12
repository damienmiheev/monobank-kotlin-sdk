package io.github.damienmiheev.monobank.core.http

import io.github.damienmiheev.monobank.core.InternalMonobankApi
import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.SdkInfo
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SSLHandshakeException

/**
 * Blocking HTTP transport shared by all monobank clients. Thread-safe.
 *
 * Never logs bodies, query strings or headers: only method, path, status and timing.
 */
@InternalMonobankApi
public class MonobankHttp(
    private val options: MonobankClientOptions,
    defaultBaseUrl: String,
    private val module: String,
    private val authenticator: RequestAuthenticator,
    private val errorDecoder: ErrorDecoder,
    private val sleeper: Sleeper = Sleeper.SYSTEM,
    /**
     * Wait assumed for a 429 that has no `Retry-After` header. APIs with fixed limits (the
     * personal API allows one call per 60 s) set it, so the SDK does not retry into another 429.
     */
    private val defaultRetryAfter: Duration? = null,
) {
    private val logger = LoggerFactory.getLogger(MonobankHttp::class.java)
    private val baseUrl: String = (options.baseUrl?.toString() ?: defaultBaseUrl).trimEnd('/')
    private val client: HttpClient =
        options.httpClient ?: HttpClient
            .newBuilder()
            // A connect timeout longer than the request timeout would let the request timer fire
            // during connect, and a request that was never sent would be reported as UNKNOWN.
            .connectTimeout(minOf(options.connectTimeout, options.requestTimeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            // HTTP/1.1: JDK 11's HTTP/2 client had stalls fixed only in later updates.
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    private val userAgent: String =
        listOfNotNull(SdkInfo.userAgent(module), options.userAgentSuffix).joinToString(" ")

    /** Pauses between retries; replaceable in tests. */
    @InternalMonobankApi
    public fun interface Sleeper {
        /** Sleeps for [duration]. */
        public fun sleep(duration: Duration)

        public companion object {
            /** Uses [Thread.sleep]. */
            public val SYSTEM: Sleeper = Sleeper { Thread.sleep(it.toMillis()) }
        }
    }

    /**
     * Executes [request] with retries, returning a 2xx answer or one of
     * [ApiRequest.acceptedStatuses]; throws a [MonobankException] otherwise.
     */
    public fun execute(request: ApiRequest): ApiResponse {
        val policy = options.retryPolicy
        var attempt = 0
        while (true) {
            val failure =
                try {
                    return executeOnce(request)
                } catch (e: MonobankException) {
                    e
                }
            val retryAfter = (failure as? MonobankRateLimitException)?.retryAfter
            val canRetry =
                attempt < policy.maxRetries &&
                    isRetryable(request, failure) &&
                    // Waiting less than monobank asked would only earn another 429.
                    (retryAfter == null || retryAfter <= policy.maxBackoff) &&
                    !Thread.currentThread().isInterrupted
            if (!canRetry) {
                if (attempt > 0) {
                    logger.warn(
                        "monobank {} {} failed after {} attempts: {}",
                        request.method,
                        request.path,
                        attempt + 1,
                        failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
            attempt++
            val wait = backoff(attempt, retryAfter)
            logger.debug("monobank {} {} retry {} in {} ms", request.method, request.path, attempt, wait.toMillis())
            try {
                sleeper.sleep(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
        }
    }

    private fun isRetryable(
        request: ApiRequest,
        failure: MonobankException,
    ): Boolean =
        when {
            failure.outcome == RequestOutcome.NOT_SENT -> {
                true
            }

            failure is MonobankRateLimitException -> {
                true
            }

            failure.outcome == RequestOutcome.UNKNOWN && request.idempotent -> {
                failure is MonobankNetworkException || failure is MonobankServerException
            }

            else -> {
                false
            }
        }

    private fun backoff(
        attempt: Int,
        retryAfter: Duration?,
    ): Duration {
        val policy = options.retryPolicy
        if (retryAfter != null) return minOf(retryAfter, policy.maxBackoff)
        val maxMillis = policy.maxBackoff.toMillis()
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val initial = policy.initialBackoff.toMillis()
        // Saturating exponential growth (backoff is capped at one hour, so no overflow).
        val base = if (initial > (maxMillis shr shift)) maxMillis else minOf(initial shl shift, maxMillis)
        val jittered = base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1)
        return Duration.ofMillis(jittered)
    }

    private fun executeOnce(request: ApiRequest): ApiResponse {
        val httpRequest = buildRequest(request)
        val started = System.nanoTime()
        val response =
            try {
                client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MonobankNetworkException("monobank request interrupted", e, RequestOutcome.UNKNOWN)
            } catch (e: IOException) {
                throw networkException(e)
            }
        val status = response.statusCode()
        logger.debug(
            "monobank {} {} -> {} in {} ms",
            request.method,
            request.path,
            status,
            Duration.ofNanos(System.nanoTime() - started).toMillis(),
        )
        val apiResponse = ApiResponse(status, response.headers().map(), response.body() ?: ByteArray(0))
        if (status in SUCCESS || status in request.acceptedStatuses) return apiResponse
        throw apiException(apiResponse)
    }

    private fun buildRequest(request: ApiRequest): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder(URI.create(baseUrl + request.pathAndQuery))
                .timeout(options.requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
        request.contentType?.let { builder.setHeader("Content-Type", it) }
        request.headers.forEach { (name, value) -> builder.setHeader(name, value) }
        authenticator.headers(request).forEach { (name, value) -> builder.setHeader(name, value) }
        val publisher =
            request.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody()
        return builder.method(request.method.name, publisher).build()
    }

    private fun networkException(e: IOException): MonobankNetworkException {
        val notSent =
            generateSequence(e as Throwable) { it.cause }.take(MAX_CAUSES).any {
                it is ConnectException ||
                    it is HttpConnectTimeoutException ||
                    it is UnknownHostException ||
                    it is NoRouteToHostException ||
                    it is SSLHandshakeException
            }
        val outcome = if (notSent) RequestOutcome.NOT_SENT else RequestOutcome.UNKNOWN
        return MonobankNetworkException("monobank request failed: ${e.javaClass.simpleName}: ${e.message}", e, outcome)
    }

    private fun apiException(response: ApiResponse): MonobankApiException {
        val bodyText = response.body.toString(StandardCharsets.UTF_8)
        val error = runCatching { errorDecoder.decode(response.body) }.getOrElse { ApiError(null, null) }
        logger.debug("monobank error: status={}, errorCode={}", response.statusCode, error.code)
        val status = response.statusCode
        val exception =
            when {
                status == 401 || status == 403 -> {
                    MonobankAuthException(status, error.code, error.text, bodyText)
                }

                status == 404 -> {
                    MonobankNotFoundException(error.code, error.text, bodyText)
                }

                status == 429 -> {
                    MonobankRateLimitException(error.code, error.text, bodyText, retryAfter(response))
                }

                status >= 500 -> {
                    MonobankServerException(
                        status,
                        error.code,
                        error.text,
                        bodyText,
                        errorDecoder.serverErrorOutcome(error.code),
                    )
                }

                else -> {
                    MonobankBadRequestException(status, error.code, error.text, bodyText)
                }
            }
        exception.traceId =
            listOf("trace-id", "x-trace-id", "request_trace_id").firstNotNullOfOrNull(response::header)
        return exception
    }

    private fun retryAfter(response: ApiResponse): Duration? {
        val raw =
            response.header("Retry-After") ?: response.header("x-rate-limit-retry-after-seconds")
                ?: return defaultRetryAfter
        return raw
            .trim()
            .toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.let { Duration.ofSeconds(it) } ?: defaultRetryAfter
    }

    private companion object {
        val SUCCESS = 200..299
        const val MAX_SHIFT = 20
        const val MAX_CAUSES = 10
    }
}
