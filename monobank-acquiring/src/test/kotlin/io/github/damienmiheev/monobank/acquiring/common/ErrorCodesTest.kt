package io.github.damienmiheev.monobank.acquiring.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ErrorCodesTest {
    @Test
    fun `payment error codes describe documented codes`() {
        val info = PaymentErrorCodes.describe(PaymentErrorCodes.INSUFFICIENT_FUNDS)!!
        assertThat(info.code).isEqualTo("59")
        assertThat(info.party).isEqualTo(PaymentErrorParty.CARD_ISSUER)
        assertThat(info.description).containsIgnoringCase("insufficient funds")
        assertThat(PaymentErrorCodes.describe(" 8002 ")!!.party).isEqualTo(PaymentErrorParty.CUSTOMER)
        assertThat(PaymentErrorCodes.describe("1001")!!.party).isEqualTo(PaymentErrorParty.API)
        assertThat(PaymentErrorCodes.describe("99999")).isNull()
        assertThat(PaymentErrorCodes.describe(null)).isNull()
        assertThat(PaymentErrorCodes.all()).hasSize(78)
    }

    @Test
    fun `request error codes match the documented strings`() {
        assertThat(AcquiringErrorCodes.TOO_MANY_REQUESTS).isEqualTo("TMR")
        assertThat(AcquiringErrorCodes.INVALID_REQUEST).isEqualTo("1001")
        assertThat(AcquiringErrorCodes.NOT_FOUND_NUMERIC).isEqualTo("1004")
    }

    @Test
    fun `fromValue maps unknown and null to UNKNOWN`() {
        assertThat(InvoiceStatus.fromValue("hold")).isEqualTo(InvoiceStatus.HOLD)
        assertThat(InvoiceStatus.fromValue("new_one")).isEqualTo(InvoiceStatus.UNKNOWN)
        assertThat(InvoiceStatus.fromValue(null)).isEqualTo(InvoiceStatus.UNKNOWN)
        assertThat(OperationStatus.fromValue("failure")).isEqualTo(OperationStatus.FAILURE)
        assertThat(CardTokenStatus.fromValue("created")).isEqualTo(CardTokenStatus.CREATED)
        assertThat(PaymentMethod.fromValue("wallet")).isEqualTo(PaymentMethod.WALLET)
        assertThat(PaymentSystem.fromValue("mastercard")).isEqualTo(PaymentSystem.MASTERCARD)
    }
}
