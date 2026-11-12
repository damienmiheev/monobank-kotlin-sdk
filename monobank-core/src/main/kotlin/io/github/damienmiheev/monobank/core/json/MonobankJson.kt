package io.github.damienmiheev.monobank.core.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import io.github.damienmiheev.monobank.core.InternalMonobankApi
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The SDK's JSON codec.
 *
 * Deliberately uses only jackson-databind APIs available since 2.13 and no Jackson modules
 * (Kotlin, JSR-310), so it works when an application or Kill Bill pins an older Jackson 2.x.
 * Models declare their JSON names explicitly with `@JsonProperty`.
 */
@InternalMonobankApi
public object MonobankJson {
    /** Shared, thread-safe mapper. */
    public val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .defaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL),
            ).addModule(timeModule())
            .build()

    /** Encodes [value] to UTF-8 JSON bytes. */
    public fun write(value: Any): ByteArray =
        try {
            mapper.writeValueAsBytes(value)
        } catch (e: JsonProcessingException) {
            throw MonobankSerializationException(
                "Could not encode ${value.javaClass.simpleName} as JSON",
                e,
                RequestOutcome.NOT_SENT,
            )
        }

    /** Decodes a response body. [outcome] is what the failure means for the caller's request. */
    public fun <T> read(
        body: ByteArray,
        type: Class<T>,
        outcome: RequestOutcome? = RequestOutcome.UNKNOWN,
    ): T = decode(type.simpleName, outcome) { mapper.readValue(body, type) }

    /** Decodes a response body into a generic type. */
    public fun <T> read(
        body: ByteArray,
        type: TypeReference<T>,
        outcome: RequestOutcome? = RequestOutcome.UNKNOWN,
    ): T = decode(type.type.typeName, outcome) { mapper.readValue(body, type) }

    /** Parses a body as a JSON tree. */
    public fun readTree(
        body: ByteArray,
        outcome: RequestOutcome? = RequestOutcome.UNKNOWN,
    ): JsonNode = decode("JSON", outcome) { mapper.readTree(body) ?: mapper.nullNode() }

    private inline fun <T> decode(
        what: String,
        outcome: RequestOutcome?,
        block: () -> T,
    ): T =
        try {
            block() ?: throw MonobankSerializationException("monobank returned an empty body for $what", null, outcome)
        } catch (e: MonobankSerializationException) {
            throw e
        } catch (e: IOException) {
            throw MonobankSerializationException("Could not decode monobank response as $what", e, outcome)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // Kotlin null checks on non-null constructor parameters, date parsing, unexpected shapes.
            throw MonobankSerializationException(
                "Could not decode monobank response as $what: ${e.message}",
                e,
                outcome,
            )
        }

    /** "Europe/Kyiv" exists only in tzdata 2022b+ (JDK 11.0.17+); older JDKs know "Europe/Kiev". */
    private val KYIV: java.time.ZoneId =
        try {
            java.time.ZoneId.of("Europe/Kyiv")
        } catch (
            @Suppress("SwallowedException") e: java.time.DateTimeException,
        ) {
            java.time.ZoneId.of("Europe/Kiev")
        }

    private fun timeModule(): SimpleModule =
        SimpleModule("monobank-time")
            .addDeserializer(OffsetDateTime::class.java, OffsetDateTimeDeserializer)
            .addSerializer(OffsetDateTime::class.java, OffsetDateTimeSerializer)
            .addDeserializer(Instant::class.java, EpochSecondsDeserializer)
            .addSerializer(Instant::class.java, EpochSecondsSerializer)
            .addDeserializer(LocalDate::class.java, LocalDateDeserializer)
            .addSerializer(LocalDate::class.java, LocalDateSerializer)

    /** ISO-8601 date-time with offset, e.g. `2024-01-31T10:15:30+02:00` or `...Z`. */
    private object OffsetDateTimeDeserializer : JsonDeserializer<OffsetDateTime>() {
        override fun deserialize(
            p: JsonParser,
            ctxt: DeserializationContext,
        ): OffsetDateTime? {
            val text = p.valueAsString?.trim().orEmpty()
            if (text.isEmpty()) return null
            return try {
                OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (e: DateTimeParseException) {
                // Some monobank fields omit the offset; they are in Kyiv time.
                try {
                    java.time.LocalDateTime
                        .parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .atZone(KYIV)
                        .toOffsetDateTime()
                } catch (
                    @Suppress("SwallowedException") ignored: DateTimeParseException,
                ) {
                    throw ctxt.weirdStringException(text, OffsetDateTime::class.java, e.message)
                }
            }
        }
    }

    private object OffsetDateTimeSerializer : JsonSerializer<OffsetDateTime>() {
        override fun serialize(
            value: OffsetDateTime,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            gen.writeString(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
    }

    /** Unix time in seconds, as monobank's statements use. */
    private object EpochSecondsDeserializer : JsonDeserializer<Instant>() {
        override fun deserialize(
            p: JsonParser,
            ctxt: DeserializationContext,
        ): Instant? =
            when (p.currentToken) {
                JsonToken.VALUE_NUMBER_INT -> {
                    Instant.ofEpochSecond(p.longValue)
                }

                JsonToken.VALUE_STRING -> {
                    val text = p.text.trim()
                    when {
                        text.isEmpty() -> {
                            null
                        }

                        text.trimStart('-').let { d -> d.isNotEmpty() && d.all { it in '0'..'9' } } -> {
                            Instant.ofEpochSecond(text.toLong())
                        }

                        else -> {
                            Instant.parse(text)
                        }
                    }
                }

                else -> {
                    throw ctxt.wrongTokenException(p, Instant::class.java, JsonToken.VALUE_NUMBER_INT, null)
                }
            }
    }

    private object EpochSecondsSerializer : JsonSerializer<Instant>() {
        override fun serialize(
            value: Instant,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            gen.writeNumber(value.epochSecond)
        }
    }

    private object LocalDateDeserializer : JsonDeserializer<LocalDate>() {
        override fun deserialize(
            p: JsonParser,
            ctxt: DeserializationContext,
        ): LocalDate? {
            val text = p.valueAsString?.trim().orEmpty()
            return if (text.isEmpty()) null else LocalDate.parse(text.take(10))
        }
    }

    private object LocalDateSerializer : JsonSerializer<LocalDate>() {
        override fun serialize(
            value: LocalDate,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            gen.writeString(value.toString())
        }
    }
}
