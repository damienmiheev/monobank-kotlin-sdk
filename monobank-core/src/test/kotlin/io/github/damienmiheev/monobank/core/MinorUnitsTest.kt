package io.github.damienmiheev.monobank.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MinorUnitsTest {
    @Test
    fun `converts kopiyky to hryvnias and back exactly`() {
        assertThat(MinorUnits.toMajor(49_900, CurrencyCodes.UAH)).isEqualTo(BigDecimal("499.00"))
        assertThat(MinorUnits.toMajor(1_000_000, CurrencyCodes.UAH)).isEqualTo(BigDecimal("10000.00"))
        assertThat(MinorUnits.toMinor(BigDecimal("499.00"), CurrencyCodes.UAH)).isEqualTo(49_900)
        assertThat(MinorUnits.toMinor(BigDecimal("499"), CurrencyCodes.UAH)).isEqualTo(49_900)
        assertThat(MinorUnits.toMajor(-150, CurrencyCodes.USD)).isEqualTo(BigDecimal("-1.50"))
    }

    @Test
    fun `uses the ISO 4217 exponent of the currency`() {
        assertThat(MinorUnits.fractionDigits(392)).isEqualTo(0) // JPY
        assertThat(MinorUnits.fractionDigits(414)).isEqualTo(3) // KWD
        assertThat(MinorUnits.toMajor(1234, 414)).isEqualTo(BigDecimal("1.234"))
    }

    @Test
    fun `never rounds`() {
        assertThatThrownBy { MinorUnits.toMinor(BigDecimal("42.001"), CurrencyCodes.UAH) }
            .isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `rejects unknown currency codes`() {
        assertThatThrownBy { MinorUnits.toMajor(1, 1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
