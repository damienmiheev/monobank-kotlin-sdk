package io.github.damienmiheev.monobank.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class OptionsTest {
    @Test
    fun `defaults and builder round trip`() {
        val defaults = MonobankClientOptions.DEFAULT
        assertThat(defaults.baseUrl).isNull()
        assertThat(defaults.retryPolicy).isEqualTo(RetryPolicy.DEFAULT)
        val custom =
            defaults
                .toBuilder()
                .baseUrl("http://localhost:1/")
                .requestTimeout(Duration.ofSeconds(3))
                .build()
        assertThat(custom.baseUrl.toString()).isEqualTo("http://localhost:1")
        assertThat(custom.toBuilder().build().requestTimeout).isEqualTo(Duration.ofSeconds(3))
    }

    @Test
    fun `validates input`() {
        assertThatThrownBy {
            MonobankClientOptions.builder().baseUrl("ftp://x")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonobankClientOptions.builder().requestTimeout(Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonobankClientOptions.builder().userAgentSuffix("a\r\nX-Evil: 1") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            RetryPolicy.of(-1, Duration.ZERO, Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sdk version is filled in`() {
        assertThat(SdkInfo.version).isNotEqualTo("unknown").doesNotContain("\${")
    }
}
