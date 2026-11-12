package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.core.MonobankApiException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Refunds one existing test invoice and prints monobank's answers (status and statement).
 *
 * Needs `MONOBANK_ACQUIRING_TEST_TOKEN`, `MONOBANK_LIVE_PAYMENTS=true` and
 * `MONOBANK_LIVE_REFUND_INVOICE_ID`; optional `MONOBANK_LIVE_REFUND_AMOUNT` (minor units; default:
 * a full refund without amount and items).
 */
@Tag("live")
class AcquiringRefundLiveTest {
    private fun log(message: String) {
        println("[refund-live] $message")
        System.out.flush()
    }

    @Test
    fun `refund an existing invoice`() {
        val token = System.getenv("MONOBANK_ACQUIRING_TEST_TOKEN")
        val invoiceId = System.getenv("MONOBANK_LIVE_REFUND_INVOICE_ID")
        assumeTrue(!token.isNullOrBlank() && !invoiceId.isNullOrBlank(), "token or invoice id not set")
        assumeTrue(System.getenv("MONOBANK_LIVE_PAYMENTS") == "true", "MONOBANK_LIVE_PAYMENTS=true is not set")
        val mono = MonobankAcquiring.create(token!!)
        val amount = System.getenv("MONOBANK_LIVE_REFUND_AMOUNT")?.toLongOrNull()

        val request =
            CancelInvoiceRequest.builder(invoiceId!!).extRef("sdk-live-refund-" + UUID.randomUUID().toString().take(8))
        if (amount != null) request.amount(amount)
        try {
            log(
                "refund ${amount ?: "full (no amount, no items)"}: OK status=${mono.invoices.cancel(
                    request.build(),
                ).statusRaw}",
            )
        } catch (e: MonobankApiException) {
            log("refund ${amount ?: "full"}: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
        }
        Thread.sleep(5_000)
        try {
            val s = mono.invoices.status(invoiceId)
            log("status=${s.statusRaw} amount=${s.amount} ccy=${s.ccy} finalAmount=${s.finalAmount}")
            log("cancelList=${s.cancelList?.map { "${it.statusRaw}/${it.amount}/${it.ccy}" }}")
        } catch (e: MonobankApiException) {
            log("status: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
        }
        mono.merchant
            .statement(Instant.now().minusSeconds(7_200))
            .filter { it.invoiceId == invoiceId }
            .forEach {
                log("statement: status=${it.statusRaw} amount=${it.amount} ccy=${it.ccy}")
                log("statement cancelList=${it.cancelList?.map { c -> "${c.amount}/${c.ccy}" }}")
            }
    }
}
