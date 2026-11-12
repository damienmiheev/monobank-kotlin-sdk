package io.github.damienmiheev.monobank.corporate.internal

import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.http.ApiError
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.ApiResponse
import io.github.damienmiheev.monobank.core.http.ErrorDecoder
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.core.http.MonobankHttp
import io.github.damienmiheev.monobank.core.http.RequestAuthenticator
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.corporate.CorporateConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Error body of the corporate API: `{"errorDescription": "..."}`. There is no machine-readable
 * code, so [ApiError.code] is always `null`.
 */
internal object CorporateErrorDecoder : ErrorDecoder {
    override fun decode(body: ByteArray): ApiError {
        if (body.isEmpty()) return ApiError(null, null)
        val node = MonobankJson.readTree(body)
        return ApiError(null, node.path("errorDescription").asText(null))
    }
}

/**
 * Which headers an endpoint needs and what its `X-Sign` covers.
 *
 * @property sendKeyId Whether `X-Key-Id` is sent. Company registration has no key id yet.
 * @property signRequestId Whether `X-Request-Id` is part of the signed string
 *   (`X-Time | X-Request-Id | URL` instead of `X-Time | URL`).
 */
internal class SigningRule(
    val sendKeyId: Boolean,
    val signRequestId: Boolean,
)

/**
 * Per-endpoint signing table, from the `X-Sign` description of each operation in the
 * v260831 docs. A finding from a live test is a one-line change here.
 */
internal object SigningRules {
    const val REGISTRATION = "/personal/auth/registration"
    const val REGISTRATION_STATUS = "/personal/auth/registration/status"
    const val CORP_WEBHOOK = "/personal/corp/webhook"
    const val CORP_SETTINGS = "/personal/corp/settings"
    const val AUTH_REQUEST = "/personal/auth/request"
    const val CLIENT_INFO = "/personal/client-info"
    const val STATEMENT_PREFIX = "/personal/statement/"
    const val SIGNATURE_CREATE = "/personal/signature/create"
    const val SIGNATURE_STATUS = "/personal/signature/status"
    const val SIGNATURE_CANCEL = "/personal/signature/cancel"

    /** Registration: "Sign (X-Time | URL)", no `X-Key-Id` header in the docs. */
    private val REGISTRATION_RULE = SigningRule(sendKeyId = false, signRequestId = false)

    /** "Sign (X-Time | URL)". */
    private val TIME_URL = SigningRule(sendKeyId = true, signRequestId = false)

    /** "Sign (X-Time | X-Request-Id | URL)". */
    private val TIME_REQUEST_ID_URL = SigningRule(sendKeyId = true, signRequestId = true)

    /**
     * `corp/webhook` and `corp/settings`: the docs list `X-Request-Id` as a header but describe
     * `X-Sign` as "Sign (X-Time | URL)". The SDK follows the `X-Sign` text: an `X-Request-Id`, if
     * the caller passes one, is sent but not signed. Community clients send none at all.
     */
    private val COMPANY_RULE = TIME_URL

    fun ruleFor(
        method: HttpMethod,
        path: String,
    ): SigningRule =
        when {
            path == REGISTRATION || path == REGISTRATION_STATUS -> REGISTRATION_RULE
            path == CORP_WEBHOOK || path == CORP_SETTINGS -> COMPANY_RULE
            path == AUTH_REQUEST -> if (method == HttpMethod.POST) TIME_URL else TIME_REQUEST_ID_URL
            path == CLIENT_INFO || path.startsWith(STATEMENT_PREFIX) -> TIME_REQUEST_ID_URL
            path == SIGNATURE_CREATE || path == SIGNATURE_STATUS || path == SIGNATURE_CANCEL -> TIME_URL
            else -> throw IllegalStateException("no signing rule for $method $path")
        }
}

/**
 * Adds `X-Time`, `X-Sign` and (except for registration) `X-Key-Id`. Called once per HTTP
 * attempt, so every retry has a fresh time and signature.
 */
internal class CorporateAuthenticator(
    private val config: CorporateConfig,
) : RequestAuthenticator {
    override fun headers(request: ApiRequest): Map<String, String> {
        val rule = SigningRules.ruleFor(request.method, request.path)
        val time =
            config.clock
                .instant()
                .epochSecond
                .toString()
        val requestId = request.headers[REQUEST_ID_HEADER]
        if (rule.signRequestId) checkNotNull(requestId) { "${request.path} needs $REQUEST_ID_HEADER" }
        val signedUrl = if (config.signQueryString) request.pathAndQuery else request.path
        val data = stringToSign(time, if (rule.signRequestId) requestId else null, signedUrl)
        val signature = sign(data.toByteArray(StandardCharsets.UTF_8))
        return buildMap {
            if (rule.sendKeyId) put(KEY_ID_HEADER, config.keyId)
            put(TIME_HEADER, time)
            put(SIGN_HEADER, Base64.getEncoder().encodeToString(signature))
        }
    }

    private fun sign(data: ByteArray): ByteArray =
        try {
            Secp256k1.encode(config.signer.sign(data), config.signatureEncoding)
        } catch (e: MonobankException) {
            throw e
        } catch (e: RuntimeException) {
            // A custom signer (KMS, HSM) failed or returned garbage: nothing was sent.
            throw MonobankSerializationException(
                "Could not sign the corporate API request: ${e.javaClass.simpleName}: ${e.message}",
                e,
                RequestOutcome.NOT_SENT,
            )
        }

    companion object {
        const val KEY_ID_HEADER = "X-Key-Id"
        const val TIME_HEADER = "X-Time"
        const val SIGN_HEADER = "X-Sign"
        const val REQUEST_ID_HEADER = "X-Request-Id"

        /** Plain concatenation, no separators: `X-Time [+ X-Request-Id] + URL`. */
        fun stringToSign(
            time: String,
            requestId: String?,
            url: String,
        ): String = time + requestId.orEmpty() + url
    }
}

/** Thin JSON helpers over [MonobankHttp] for the corporate API. */
internal class CorporateHttp(
    config: CorporateConfig,
    sleeper: MonobankHttp.Sleeper = MonobankHttp.Sleeper.SYSTEM,
) {
    private val http =
        MonobankHttp(
            options = config.options,
            defaultBaseUrl = DEFAULT_BASE_URL,
            module = "corporate",
            authenticator = CorporateAuthenticator(config),
            errorDecoder = CorporateErrorDecoder,
            sleeper = sleeper,
        )

    fun execute(request: ApiRequest): ApiResponse = http.execute(request)

    fun <T> get(
        path: String,
        type: Class<T>,
        query: List<Pair<String, String?>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val response = http.execute(ApiRequest(HttpMethod.GET, path, query, headers, idempotent = true))
        return MonobankJson.read(response.body, type)
    }

    fun <T> post(
        path: String,
        body: Any?,
        type: Class<T>,
        idempotent: Boolean,
        headers: Map<String, String> = emptyMap(),
    ): T = MonobankJson.read(postRaw(path, body, idempotent, headers).body, type)

    /** POST whose answer body is empty or irrelevant (`{}`). */
    fun postNoContent(
        path: String,
        body: Any?,
        idempotent: Boolean,
        headers: Map<String, String> = emptyMap(),
    ) {
        postRaw(path, body, idempotent, headers)
    }

    private fun postRaw(
        path: String,
        body: Any?,
        idempotent: Boolean,
        headers: Map<String, String>,
    ): ApiResponse =
        http.execute(
            ApiRequest(
                HttpMethod.POST,
                path,
                headers = headers,
                body = body?.let { MonobankJson.write(it) },
                idempotent = idempotent,
            ),
        )

    fun delete(
        path: String,
        query: List<Pair<String, String?>>,
    ) {
        http.execute(ApiRequest(HttpMethod.DELETE, path, query, idempotent = true))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.monobank.ua"

        /** Encodes one path segment (`/` and everything else unsafe become `%XX`). */
        fun encodeSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        /** Validates a value that goes into an HTTP header. */
        fun requireHeaderValue(
            value: String,
            name: String,
        ) {
            require(value.isNotBlank()) { "$name must not be blank" }
            require(value.none { it == '\r' || it == '\n' }) { "$name must be one line" }
        }
    }
}
