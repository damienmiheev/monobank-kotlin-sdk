package io.github.damienmiheev.monobank.acquiring.terminal

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class TerminalApiTest : AcquiringTestSupport() {
    @Test
    fun `list is unwrapped`() {
        enqueueFixture("GET_api_merchant_t2p_terminal_list.response.json")
        val terminals = mono.terminals.list()
        takeRequest("GET", "/api/merchant/t2p/terminal/list")
        assertThat(terminals).hasSize(2)
        assertThat(terminals[0].code).isEqualTo("56f27a30b94c4e8db5c3912e7d6af8b1")
        assertThat(terminals[0].name).isEqualTo("Cats n Cash inc.")
        assertThat(terminals[0].terminal).isEqualTo("MT500001")
    }

    @Test
    fun `list may be missing, empty or null`() {
        enqueueJson("{}")
        assertThat(mono.terminals.list()).isEmpty()
        enqueueJson("""{"list":null}""")
        assertThat(mono.terminals.list()).isEmpty()
    }

    @Test
    fun `payment status reads every field`() {
        enqueueFixture("GET_api_merchant_t2p_terminal_payment_external_status.response.json")

        val status = mono.terminals.paymentStatus("18247112-4eac-4465-aa3c-c42c18f601eb")

        assertThat(takeRequest("GET", "/api/merchant/t2p/terminal/payment/external/status").query("externalPaymentId"))
            .isEqualTo("18247112-4eac-4465-aa3c-c42c18f601eb")
        assertThat(status.bank).isEqualTo("АТ Універсал Банк")
        assertThat(status.terminal).isEqualTo("MT507582")
        assertThat(status.paymentType).isEqualTo(T2pPaymentType.PURCHASE)
        assertThat(status.amount).isEqualTo(3500)
        assertThat(status.ccy).isEqualTo("UAH")
        assertThat(status.status).isEqualTo(T2pPaymentStatus.SUCCESS)
        assertThat(status.dataTime).isEqualTo("2026-04-21 23:01:54")
        assertThat(status.dataTimeUtc()).isEqualTo(Instant.parse("2026-04-21T23:01:54Z"))
        assertThat(status.internalPaymentId).isEqualTo("f2224275-ba0a-4f58-b6f7-bd00df63b01c")
        assertThat(status.externalPaymentId).isEqualTo("18247112-4eac-4465-aa3c-c42c18f601eb")
        assertThat(status.transactionId).isEqualTo("23417832268")
        assertThat(status.maskedPan).isEqualTo("44179274******76")
        assertThat(status.cardMask).isEqualTo("visa")
        assertThat(status.approvalCode).isEqualTo("125261")
        assertThat(status.rrn).isEqualTo("448571901425")
        assertThat(status.responseCode).isEqualTo("1")
        assertThat(status.errorMessage).isEqualTo("string")
        assertThat(status.countryCard).isEqualTo("804")
    }

    @Test
    fun `payment status tolerates unknown values and bad dates`() {
        enqueueJson("""{"status":"failed","paymentType":"cashback","dataTime":"21.04.2026"}""")
        val status = mono.terminals.paymentStatus("x")
        assertThat(status.status).isEqualTo(T2pPaymentStatus.FAILED)
        assertThat(status.paymentType).isEqualTo(T2pPaymentType.UNKNOWN)
        assertThat(status.paymentTypeRaw).isEqualTo("cashback")
        assertThat(status.dataTimeUtc()).isNull()
        assertThat(status.amount).isNull()
    }

    @Test
    fun `payment not found`() {
        enqueueJson("""{"errCode":"NOT_FOUND","errText":"payment not found"}""", status = 404)
        assertThatThrownBy { mono.terminals.paymentStatus("x") }
            .isInstanceOfSatisfying(MonobankNotFoundException::class.java) {
                assertThat(it.errorText).isEqualTo("payment not found")
            }
        assertThatThrownBy { mono.terminals.paymentStatus("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
