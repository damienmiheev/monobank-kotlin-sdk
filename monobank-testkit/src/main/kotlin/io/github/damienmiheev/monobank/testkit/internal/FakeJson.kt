package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * An error answer of the fake: HTTP [status] with `{"errCode": code, "errText": text}`.
 * Thrown by request handlers and turned into a response by the router.
 */
internal class ApiError(
    val status: Int,
    val code: String,
    val text: String,
) : RuntimeException("$status $code: $text", null, false, false) {
    companion object {
        fun badRequest(text: String) = ApiError(400, "BAD_REQUEST", text)

        fun notFound(text: String) = ApiError(404, "NOT_FOUND", text)
    }
}

/** JSON helpers of the fake (plain Jackson trees, only APIs that exist in Jackson 2.13). */
internal object FakeJson {
    val mapper: ObjectMapper = ObjectMapper()

    fun obj(): ObjectNode = mapper.createObjectNode()

    fun bytes(node: JsonNode): ByteArray = mapper.writeValueAsBytes(node)

    fun errorBody(
        code: String,
        text: String,
    ): ByteArray = bytes(obj().put("errCode", code).put("errText", text))

    /** Parses a request body that must be a JSON object; anything else is a 400. */
    fun parseObject(body: ByteArray): ObjectNode {
        val node =
            try {
                if (body.isEmpty()) null else mapper.readTree(body)
            } catch (
                @Suppress("SwallowedException") e: JsonProcessingException,
            ) {
                throw ApiError.badRequest("invalid request body")
            }
        return node as? ObjectNode ?: throw ApiError.badRequest("invalid request body")
    }

    /** RFC 3339 in UTC with millisecond precision, e.g. `2024-05-01T09:30:00.123Z`. */
    fun date(instant: Instant): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC))

    fun utf8(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
}

/** Field readers that answer 400 like monobank when a field has the wrong type. */
internal object Fields {
    fun string(
        node: JsonNode,
        name: String,
    ): String? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        if (!value.isTextual) throw ApiError.badRequest("invalid '$name'")
        return value.asText()
    }

    fun requiredString(
        node: JsonNode,
        name: String,
    ): String = string(node, name)?.takeIf { it.isNotBlank() } ?: throw ApiError.badRequest("empty '$name'")

    /** A whole number of minor units; `5000.0` is accepted as monobank's own example uses it. */
    fun long(
        node: JsonNode,
        name: String,
    ): Long? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        val whole =
            when {
                value.isIntegralNumber && value.canConvertToLong() -> return value.asLong()
                value.isFloatingPointNumber -> value.decimalValue()
                else -> throw ApiError.badRequest("invalid '$name'")
            }
        val stripped = whole.stripTrailingZeros()
        if (stripped.scale() > 0 || stripped.abs() > BigDecimal.valueOf(Long.MAX_VALUE)) {
            throw ApiError.badRequest("invalid '$name'")
        }
        return stripped.toLong()
    }

    fun requiredLong(
        node: JsonNode,
        name: String,
    ): Long = long(node, name) ?: throw ApiError.badRequest("empty '$name'")

    fun boolean(
        node: JsonNode,
        name: String,
    ): Boolean? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        if (!value.isBoolean) throw ApiError.badRequest("invalid '$name'")
        return value.asBoolean()
    }

    fun obj(
        node: JsonNode,
        name: String,
    ): ObjectNode? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        return value as? ObjectNode ?: throw ApiError.badRequest("invalid '$name'")
    }
}
