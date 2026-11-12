package io.github.damienmiheev.monobank.installments.internal

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamWriteFeature
import com.fasterxml.jackson.databind.ObjectWriter
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.http.ApiError
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.ApiResponse
import io.github.damienmiheev.monobank.core.http.ErrorDecoder
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.core.http.MonobankHttp
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsConfig
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Error body of the installments API: `{"message": "..."}`. It has no machine-readable code. Spring's default error
 * body (`{"status": 500, "error": "Internal Server Error"}`, e.g. from a gateway) falls back to `error`.
 */
internal object InstallmentsErrorDecoder : ErrorDecoder {
    override fun decode(body: ByteArray): ApiError {
        if (body.isEmpty()) return ApiError(null, null)
        val node = MonobankJson.readTree(body)
        val text = node.path("message").asText(null) ?: node.path("error").asText(null)
        return ApiError(null, text)
    }

    /** monobank's message from an accepted non-2xx answer, or `null` when the body is empty or not JSON. */
    fun messageOrNull(response: ApiResponse): String? = runCatching { decode(response.body).text }.getOrNull()
}

/** monobank's trace id of [this] answer (`trace-id` header; quote it to monobank support). */
internal fun ApiResponse.traceId(): String? =
    listOf("trace-id", "x-trace-id", "X-Request-Id", "request_trace_id").firstNotNullOfOrNull(::header)

/** `Base64(HMAC-SHA256(body bytes, store secret UTF-8))`, as the installments API signs requests and callbacks. */
internal class HmacSigner(
    secret: String,
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)

    /** Raw 32-byte HMAC of [body]. */
    fun mac(body: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(key)
            doFinal(body)
        }

    /** Base64 of [mac], the value of the `signature` header. */
    fun sign(body: ByteArray): String = Base64.getEncoder().encodeToString(mac(body))

    override fun toString(): String = "HmacSigner(***)"

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}

/** JSON writing for this API: money is `BigDecimal` hryvnias, always written in plain notation (never `1E+3`). */
internal object InstallmentsJson {
    private val writer: ObjectWriter = MonobankJson.mapper.writer().with(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)

    fun write(value: Any): ByteArray =
        try {
            writer.writeValueAsBytes(value)
        } catch (e: JsonProcessingException) {
            throw MonobankSerializationException(
                "Could not encode ${value.javaClass.simpleName} as JSON",
                e,
                RequestOutcome.NOT_SENT,
            )
        }
}

/** Signed JSON POSTs over [MonobankHttp] for the installments API. */
internal class InstallmentsHttp(
    config: InstallmentsConfig,
    sleeper: MonobankHttp.Sleeper = MonobankHttp.Sleeper.SYSTEM,
) {
    private val signer = config.signer
    private val http =
        MonobankHttp(
            options = config.options,
            defaultBaseUrl = config.environment.baseUrl,
            module = "installments",
            // Runs once per attempt and signs the exact bytes that are sent.
            authenticator = { request ->
                mapOf(
                    STORE_ID_HEADER to config.storeId,
                    SIGNATURE_HEADER to signer.sign(request.body ?: ByteArray(0)),
                )
            },
            errorDecoder = InstallmentsErrorDecoder,
            sleeper = sleeper,
        )

    /** POSTs [body] as JSON and returns the raw answer (2xx or one of [acceptedStatuses]). */
    fun execute(
        path: String,
        body: Any,
        idempotent: Boolean,
        acceptedStatuses: Set<Int> = emptySet(),
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse =
        http.execute(
            ApiRequest(
                HttpMethod.POST,
                path,
                headers = headers,
                body = InstallmentsJson.write(body),
                idempotent = idempotent,
                acceptedStatuses = acceptedStatuses,
            ),
        )

    /** POSTs [body] and decodes the JSON answer as [type]. */
    fun <T> post(
        path: String,
        body: Any,
        type: Class<T>,
        idempotent: Boolean,
    ): T = MonobankJson.read(execute(path, body, idempotent).body, type)

    companion object {
        const val STORE_ID_HEADER = "store-id"
        const val SIGNATURE_HEADER = "signature"
    }
}

/** Body of the endpoints that take only an order id. */
internal class OrderIdBody(
    @get:JsonProperty("order_id") val orderId: String,
)

internal fun requireOrderId(orderId: String): String {
    require(orderId.isNotBlank()) { "orderId must not be blank" }
    return orderId
}
