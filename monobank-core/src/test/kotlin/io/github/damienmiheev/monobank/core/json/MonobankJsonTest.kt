package io.github.damienmiheev.monobank.core.json

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MonobankJsonTest {
    enum class Color(
        @get:JsonValue val wire: String,
    ) {
        RED("red"),

        @JsonEnumDefaultValue
        UNKNOWN("unknown"),
    }

    class Model
        @JsonCreator
        constructor(
            @param:JsonProperty("id") val id: String,
            @param:JsonProperty("color") val color: Color?,
            @param:JsonProperty("created") val created: OffsetDateTime?,
            @param:JsonProperty("time") val time: Instant?,
        )

    @Test
    fun `reads unknown enum values as the default and ignores unknown fields`() {
        val model =
            MonobankJson.read(
                """{"id":"1","color":"ultraviolet","extra":42,"created":"2024-01-31T10:15:30Z","time":1700000000}"""
                    .toByteArray(),
                Model::class.java,
            )
        assertThat(model.color).isEqualTo(Color.UNKNOWN)
        assertThat(model.created).isEqualTo(OffsetDateTime.of(2024, 1, 31, 10, 15, 30, 0, ZoneOffset.UTC))
        assertThat(model.time).isEqualTo(Instant.ofEpochSecond(1_700_000_000))
    }

    @Test
    fun `dates without offset are read as Kyiv time`() {
        val model = MonobankJson.read("""{"id":"1","created":"2024-07-01T12:00:00"}""".toByteArray(), Model::class.java)
        assertThat(model.created).isEqualTo(OffsetDateTime.of(2024, 7, 1, 12, 0, 0, 0, ZoneOffset.ofHours(3)))
    }

    @Test
    fun `missing required field becomes a serialization exception with the given outcome`() {
        assertThatThrownBy { MonobankJson.read("""{"color":"red"}""".toByteArray(), Model::class.java) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThatThrownBy { MonobankJson.read("not json".toByteArray(), Model::class.java, outcome = null) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isNull()
            }
        assertThatThrownBy { MonobankJson.read(ByteArray(0), Model::class.java) }
            .isInstanceOf(MonobankSerializationException::class.java)
    }

    @Test
    fun `writes without nulls and with enum wire values`() {
        class Req(
            val amount: Long,
            val note: String?,
            val color: Color,
        )
        val json = String(MonobankJson.write(Req(100, null, Color.RED)))
        assertThat(json).isEqualTo("""{"amount":100,"color":"red"}""")
    }
}
