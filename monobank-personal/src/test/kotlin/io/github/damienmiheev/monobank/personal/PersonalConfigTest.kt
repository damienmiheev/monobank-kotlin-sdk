package io.github.damienmiheev.monobank.personal

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersonalConfigTest {
    @Test
    fun `toString never prints the token`() {
        val config =
            PersonalConfig
                .builder(PersonalTestSupport.TOKEN)
                .rateLimiter(PersonalRateLimiter.create())
                .build()

        assertThat(config.toString()).doesNotContain(PersonalTestSupport.TOKEN).contains("token=***")
        assertThat(MonobankPersonal(config).toString()).doesNotContain(PersonalTestSupport.TOKEN)
        assertThat(MonobankPersonal.create(PersonalTestSupport.TOKEN).toString())
            .doesNotContain(PersonalTestSupport.TOKEN)
        assertThat(config.token).isEqualTo(PersonalTestSupport.TOKEN)
    }

    @Test
    fun `defaults are no rate limiter and default options`() {
        val config = PersonalConfig.builder("t").build()

        assertThat(config.rateLimiter).isNull()
        assertThat(config.options).isSameAs(MonobankClientOptions.DEFAULT)
    }

    @Test
    fun `public config has no token`() {
        val config = PersonalConfig.publicBuilder().build()

        assertThat(config.token).isNull()
        assertThat(config.toString()).contains("token=null")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "  ", "a\nb", "a\rb", "abc\u0001SECRETdef", "abc\u007FSECRET", "abc SECRET", "abc\tSECRET", "токен",
        ],
    )
    fun `tokens that cannot be an HTTP header are rejected without echoing them`(token: String) {
        assertThatThrownBy { PersonalConfig.builder(token) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("token")
            .satisfies({
                if (token.isNotBlank()) assertThat(it.message).doesNotContain("SECRET").doesNotContain(token)
            })
    }
}
