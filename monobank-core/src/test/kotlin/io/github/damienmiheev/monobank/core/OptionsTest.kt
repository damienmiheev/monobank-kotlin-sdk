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

class RetryPolicyBuilderTest {
    @Test
    fun `builder defaults equal DEFAULT and validate bounds`() {
        assertThat(RetryPolicy.builder().build()).isEqualTo(RetryPolicy.DEFAULT)
        assertThat(
            RetryPolicy.DEFAULT
                .toBuilder()
                .maxRetries(0)
                .build()
                .maxRetries,
        ).isZero()
        assertThatThrownBy {
            RetryPolicy
                .builder()
                .maxRetries(
                    11,
                ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetryPolicy.builder().maxBackoff(Duration.ofDays(2)).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

class PlainHttpOptInTest {
    @Test
    fun `plain http to a non-loopback host needs an explicit opt-in, in any call order`() {
        assertThatThrownBy {
            MonobankClientOptions
                .builder()
                .baseUrl(
                    "http://host.testcontainers.internal:1234",
                ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("allowPlainHttp")
        val after =
            MonobankClientOptions
                .builder()
                .baseUrl(
                    "http://host.testcontainers.internal:1234",
                ).allowPlainHttp(true)
                .build()
        val before =
            MonobankClientOptions
                .builder()
                .allowPlainHttp(
                    true,
                ).baseUrl("http://host.docker.internal:1")
                .build()
        assertThat(after.baseUrl.toString()).isEqualTo("http://host.testcontainers.internal:1234")
        assertThat(before.allowPlainHttp).isTrue()
        assertThat(after.toBuilder().build().allowPlainHttp).isTrue()
        assertThat(
            MonobankClientOptions
                .builder()
                .baseUrl("http://localhost:1")
                .build()
                .allowPlainHttp,
        ).isFalse()
        assertThat(MonobankClientOptions.DEFAULT.allowPlainHttp).isFalse()
    }
}
