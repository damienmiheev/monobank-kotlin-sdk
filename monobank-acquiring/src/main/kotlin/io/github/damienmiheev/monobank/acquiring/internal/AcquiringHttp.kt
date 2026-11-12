package io.github.damienmiheev.monobank.acquiring.internal

import io.github.damienmiheev.monobank.acquiring.AcquiringConfig
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.http.ApiError
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.ApiResponse
import io.github.damienmiheev.monobank.core.http.ErrorDecoder
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.core.http.MonobankHttp
import io.github.damienmiheev.monobank.core.json.MonobankJson

/** Error body of the acquiring API: `{"errCode": "...", "errText": "..."}`. */
internal object AcquiringErrorDecoder : ErrorDecoder {
    /** 5xx codes that monobank documents as definite failures: nothing was done. */
    private val definiteServerErrors = setOf("CANCEL_NOT_AVAILABLE", "EXTERNAL_ERROR")

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
) {
    private val http =
        MonobankHttp(
            options = config.options,
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
    }
}
