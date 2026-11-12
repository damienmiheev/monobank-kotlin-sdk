package io.github.damienmiheev.monobank.personal

import com.fasterxml.jackson.databind.cfg.PackageVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JacksonVersionTest {
    @Test
    fun `testJackson213 really runs this module's tests on the old Jackson`() {
        val expected = System.getProperty("monobank.expectedJacksonDatabind") ?: return
        val actual = PackageVersion.VERSION
        assertThat(
            "${actual.majorVersion}.${actual.minorVersion}.${actual.patchLevel}",
        ).isEqualTo(expected.split(".").take(3).joinToString("."))
    }
}
