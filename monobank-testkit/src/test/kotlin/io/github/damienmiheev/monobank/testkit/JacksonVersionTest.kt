package io.github.damienmiheev.monobank.testkit

import com.fasterxml.jackson.databind.cfg.PackageVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JacksonVersionTest {
    @Test
    fun `testJackson213 really runs on the old Jackson`() {
        val expected = System.getProperty("monobank.expectedJacksonDatabind") ?: return
        val actual = PackageVersion.VERSION
        assertThat(
            "${actual.majorVersion}.${actual.minorVersion}.${actual.patchLevel}",
        ).isEqualTo(expected.split(".").take(3).joinToString("."))
    }
}
