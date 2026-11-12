package io.github.damienmiheev.monobank.acquiring.internal

import io.github.damienmiheev.monobank.acquiring.AcquiringConfig
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.http.ApiError
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.ApiResponse
import io.github.damienmiheev.monobank.core.http.ErrorDecoder
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.core.http.MonobankHttp
import io.github.damienmiheev.monobank.core.json.MonobankJson
import java.time.Duration

/** Error body of the acquiring API: `{"errCode": "...", "errText": "..."}`. */
internal object AcquiringErrorDecoder : ErrorDecoder {
    /**
     * 5xx codes that monobank documents as definite failures: nothing was done. Only
     * `CANCEL_NOT_AVAILABLE` ("the payment cannot be cancelled", returned by `invoice/cancel`) qualifies.
     * `EXTERNAL_ERROR` is deliberately not here: an external system failing does not prove that
     * a charge was not made, so it stays UNKNOWN.
     */
    private val definiteServerErrors = setOf("CANCEL_NOT_AVAILABLE")

    override fun decode(body: ByteArray): ApiError {
        if (body.isEmpty()) return ApiError(null, null)
        val node = MonobankJson.readTree(body)
        return ApiError(node.path("errCode").asText(null), node.path("errText").asText(null))
    }

    override fun serverErrorOutcome(errorCode: String?): RequestOutcome =
        if (errorCode in definiteServerErrors) RequestOutcome.REJECTED else RequestOutcome.UNKNOWN
}

/** Thin JSON helpers over [MonobankHttp] for the acquiring API. */
internal class AcquiringHttp(
    config: AcquiringConfig,
    sleeper: MonobankHttp.Sleeper = MonobankHttp.Sleeper.SYSTEM,
    options: MonobankClientOptions = config.options,
) {
    private val http =
        MonobankHttp(
            options = options,
            defaultBaseUrl = DEFAULT_BASE_URL,
            module = "acquiring",
            authenticator = {
                buildMap {
                    put("X-Token", config.token)
                    config.cmsName?.let { put("X-Cms", it) }
                    config.cmsVersion?.let { put("X-Cms-Version", it) }
                }
            },
            errorDecoder = AcquiringErrorDecoder,
            sleeper = sleeper,
        )

    fun execute(request: ApiRequest): ApiResponse = http.execute(request)

    fun <T> get(
        path: String,
        type: Class<T>,
        query: List<Pair<String, String?>> = emptyList(),
        idempotent: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val response = http.execute(ApiRequest(HttpMethod.GET, path, query, headers, idempotent = idempotent))
        return MonobankJson.read(response.body, type)
    }

    fun <T> post(
        path: String,
        body: Any,
        type: Class<T>,
        idempotent: Boolean,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val response =
            http.execute(
                ApiRequest(
                    HttpMethod.POST,
                    path,
                    body = MonobankJson.write(body),
                    idempotent = idempotent,
                    headers = headers,
                ),
            )
        return MonobankJson.read(response.body, type)
    }

    /** POST whose answer body is empty or irrelevant (`{}`). */
    fun postNoContent(
        path: String,
        body: Any,
        idempotent: Boolean,
        headers: Map<String, String> = emptyMap(),
    ) {
        http.execute(
            ApiRequest(
                HttpMethod.POST,
                path,
                body = MonobankJson.write(body),
                idempotent = idempotent,
                headers = headers,
            ),
        )
    }

    fun delete(
        path: String,
        query: List<Pair<String, String?>>,
        idempotent: Boolean = true,
    ) {
        http.execute(ApiRequest(HttpMethod.DELETE, path, query, idempotent = idempotent))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.monobank.ua"

        /** Upper bound for the webhook key fetch: a webhook request is waiting for it. */
        private val KEY_FETCH_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * A transport for fetching the webhook public key: no retries and short timeouts, because
         * the caller is a webhook request thread.
         */
        fun forKeyFetch(config: AcquiringConfig): AcquiringHttp {
            val options = config.options
            val keyOptions =
                options
                    .toBuilder()
                    .retryPolicy(RetryPolicy.NONE)
                    .requestTimeout(minOf(options.requestTimeout, KEY_FETCH_TIMEOUT))
                    .connectTimeout(minOf(options.connectTimeout, KEY_FETCH_TIMEOUT))
                    .build()
            return AcquiringHttp(config, options = keyOptions)
        }
    }
}
