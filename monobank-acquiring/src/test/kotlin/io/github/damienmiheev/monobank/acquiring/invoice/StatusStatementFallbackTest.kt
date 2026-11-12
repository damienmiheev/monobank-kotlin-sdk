package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.core.MonobankServerException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class StatusStatementFallbackTest : AcquiringTestSupport() {
    private val from = Instant.parse("2026-11-12T19:00:00Z")
    private val brokenStatus = """{"errCode":"INTERNAL_ERROR","errText":"unexpected end of JSON input"}"""

    // What monobank's statement showed live for a 1.00 EUR payment (converted to 50.48 UAH)
    // after a 25.24 UAH partial refund, while invoice/status answered 500.
    private val statement =
        """{"list":[
          {"invoiceId":"other","status":"success","amount":100,"ccy":980,"date":"2026-11-12T19:40:00Z"},
          {"invoiceId":"2611126aUVPy8iumDuNG","status":"success","maskedPan":"42424242******42",
           "date":"2026-11-12T19:41:25Z","amount":5048,"ccy":980,"reference":"ref-1",
           "destination":"SDK live currency test","approvalCode":"111111","rrn":"222222",
           "cancelList":[{"amount":2524,"ccy":980,"date":"2026-11-12T19:43:25Z","approvalCode":"948376",
             "rrn":"075413923434","maskedPan":"42424242******42"}]}
        ]}"""

    @Test
    fun `a broken invoice status is rebuilt from the statement`() {
        repeat(3) { enqueueJson(brokenStatus, status = 500) } // status is retried twice first
        enqueueJson(statement)

        val state = mono.invoices.status("2611126aUVPy8iumDuNG", from)

        assertThat(state.isReconstructedFromStatement).isTrue()
        assertThat(state.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(state.amount).isEqualTo(5048)
        assertThat(state.ccy).isEqualTo(980)
        assertThat(state.finalAmount).isEqualTo(2524)
        assertThat(state.reference).isEqualTo("ref-1")
        assertThat(state.cancelList).singleElement().satisfies({
            assertThat(it.amount).isEqualTo(2524)
            assertThat(it.extRef).isNull()
        })
        assertThat(state.modifiedDate.toString()).isEqualTo("2026-11-12T19:43:25Z")
        assertThat(state.paymentInfo!!.maskedPan).isEqualTo("42424242******42")
        repeat(3) { assertThat(takeRequest().path).startsWith("/api/merchant/invoice/status") }
        assertThat(takeRequest().path).isEqualTo("/api/merchant/statement?from=${from.epochSecond}")
    }

    @Test
    fun `a working status is returned as is`() {
        enqueueFixture("GET_api_merchant_invoice_status.response.json")
        val state = mono.invoices.status("p2_9ZgpZVsl3", from)
        assertThat(state.isReconstructedFromStatement).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `other errors and invoices missing from the statement keep the original error`() {
        repeat(3) { enqueueJson("""{"errCode":"SOMETHING_ELSE"}""", status = 500) }
        assertThatThrownBy { mono.invoices.status("x", from) }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(3)

        repeat(3) { enqueueJson(brokenStatus, status = 500) }
        enqueueJson("""{"list":[]}""")
        assertThatThrownBy { mono.invoices.status("missing", from) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.errorCode).isEqualTo("INTERNAL_ERROR")
            }
    }

    @Test
    fun `a status read from monobank is never marked as reconstructed`() {
        enqueueJson(
            """{"invoiceId":"i","status":"success","amount":1,"ccy":980,"sdkReconstructedFromStatement":true}""",
        )
        val state = mono.invoices.status("i")
        assertThat(state.isReconstructedFromStatement).isFalse()
    }
}
