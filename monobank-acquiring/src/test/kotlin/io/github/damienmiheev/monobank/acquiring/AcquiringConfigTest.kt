package io.github.damienmiheev.monobank.acquiring

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AcquiringConfigTest {
    @Test
    fun `tokens that cannot be sent as a header are rejected without echoing them`() {
        listOf("tok en", "token\u00A0", "\uFEFFtoken", "токен", "tok\nen").forEach { token ->
            assertThatThrownBy { AcquiringConfig.builder(token) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .satisfies({ assertThat(it.message).doesNotContain(token) })
        }
        assertThatThrownBy { AcquiringConfig.builder(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(AcquiringConfig.builder("uX1-abc_DEF.123").build().token).isEqualTo("uX1-abc_DEF.123")
    }

    @Test
    fun `cms headers must be printable ASCII, inner spaces allowed`() {
        val builder = AcquiringConfig.builder("token")
        assertThat(
            builder
                .cmsName("Kill Bill")
                .cmsVersion("0.24.22")
                .build()
                .cmsName,
        ).isEqualTo("Kill Bill")
        assertThatThrownBy { builder.cmsName("Кілл Білл") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { builder.cmsName(" lead") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { builder.cmsVersion("1.0\r\nX: y") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { builder.cmsVersion("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(builder.cmsName(null).build().cmsName).isNull()
    }
}
