package io.github.damienmiheev.monobank.personal.internal

import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.http.ApiError
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.ErrorDecoder
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.core.http.MonobankHttp
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.personal.PersonalConfig
import io.github.damienmiheev.monobank.personal.PersonalRateLimiter
import java.time.Duration

/**
 * Error body of the personal API: `{"errorDescription": "..."}`. It has no machine-readable
 * code, so [ApiError.code] is always `null` and the text goes to [ApiError.text].
 */
internal object PersonalErrorDecoder : ErrorDecoder {
    override fun decode(body: ByteArray): ApiError {
        if (body.isEmpty()) return ApiError(null, null)
        val node = MonobankJson.readTree(body)
        val text = node.get("errorDescription")?.takeIf { it.isTextual }?.textValue()
        return ApiError(null, text)
    }
}

/** Thin JSON helpers over [MonobankHttp] for the personal API. */
internal class PersonalHttp(
    private val config: PersonalConfig,
    sleeper: MonobankHttp.Sleeper = MonobankHttp.Sleeper.SYSTEM,
    private val pacerFactory: () -> PersonalRateLimiter = { PersonalRateLimiter.create() },
) {
    private val http =
        MonobankHttp(
            options = config.options,
            defaultBaseUrl = DEFAULT_BASE_URL,
            module = "personal",
            authenticator = { request -> authHeaders(request) },
            errorDecoder = PersonalErrorDecoder,
            sleeper = sleeper,
            defaultRetryAfter = RATE_LIMIT_WAIT,
        )

    /**
     * A limiter for one multi-request operation (`listAll`) to pace itself with, or `null` when the
     * config already has one (which then spaces every request).
     */
    fun pacerIfUnlimited(): PersonalRateLimiter? = if (config.rateLimiter == null) pacerFactory() else null

    /** Throws when this client was built without a token. */
    fun requireToken(operation: String) {
        checkNotNull(config.token) {
            "$operation needs a personal token; this client was built with PersonalConfig.publicBuilder()"
        }
    }

    /**
     * Called once per HTTP attempt, so the optional rate limiter also spaces out retries.
     * The token is sent only to `/personal/...`; the public `/bank/...` endpoints do not need it.
     */
    private fun authHeaders(request: ApiRequest): Map<String, String> {
        val token = config.token
        if (token == null || !request.path.startsWith(PERSONAL_PREFIX)) return emptyMap()
        val limiter = config.rateLimiter
        val key = rateLimitKey(request.path)
        if (limiter != null && key != null) awaitSlot(limiter, key)
        return mapOf("X-Token" to token)
    }

    /** Waits for [limiter]'s next slot; an interrupt becomes a NOT_SENT network exception. */
    fun awaitSlot(
        limiter: PersonalRateLimiter,
        key: String,
    ) {
        try {
            limiter.acquire(key)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MonobankNetworkException(
                "Interrupted while waiting for the personal API rate limit",
                e,
                RequestOutcome.NOT_SENT,
            )
        }
    }

    fun <T> get(
        path: String,
        type: Class<T>,
    ): T {
        val response = http.execute(ApiRequest(HttpMethod.GET, path, idempotent = true))
        return MonobankJson.read(response.body, type)
    }

    /** POST whose answer body is irrelevant. */
    fun postNoContent(
        path: String,
        body: Any,
        idempotent: Boolean,
    ) {
        http.execute(ApiRequest(HttpMethod.POST, path, body = MonobankJson.write(body), idempotent = idempotent))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.monobank.ua"
        private const val PERSONAL_PREFIX = "/personal/"
        const val CLIENT_INFO_PATH = "/personal/client-info"
        const val STATEMENT_PATH = "/personal/statement/"
        const val CLIENT_INFO_KEY = "client-info"
        const val STATEMENT_KEY = "statement"

        fun rateLimitKey(path: String): String? =
            when {
                path == CLIENT_INFO_PATH -> CLIENT_INFO_KEY
                path.startsWith(STATEMENT_PATH) -> STATEMENT_KEY
                else -> null
            }

        /** monobank sends no `Retry-After`; its documented limits are one call per 60 seconds. */
        val RATE_LIMIT_WAIT: Duration = Duration.ofSeconds(60)
    }
}
