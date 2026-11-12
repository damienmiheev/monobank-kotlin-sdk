package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CardData
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentDirectRequest
import io.github.damienmiheev.monobank.core.MonobankApiException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Live: which partial refunds of a converted (EUR → UAH) payment monobank accepts on a merchant with
 * fiscal receipts and split payments. Two 1.00 EUR payments (each needs a 3-D Secure confirmation),
 * then refund variants; prints every answer and the statement afterwards. Learns, does not assert.
 *
 * Runs only with `MONOBANK_ACQUIRING_TEST_TOKEN` (a TEST token) AND `MONOBANK_LIVE_PAYMENTS=true`.
 */
@Tag("live")
class AcquiringConvertedRefundLiveTest {
    private fun log(message: String) {
        println("[conv-live] $message")
        System.out.flush()
    }

    private val name = "SDK converted refund"
    private val code = "sdk-conv"

    @Test
    fun `partial refunds of a converted payment`() {
        val token = System.getenv("MONOBANK_ACQUIRING_TEST_TOKEN")
        assumeTrue(!token.isNullOrBlank(), "MONOBANK_ACQUIRING_TEST_TOKEN is not set")
        assumeTrue(System.getenv("MONOBANK_LIVE_PAYMENTS") == "true", "MONOBANK_LIVE_PAYMENTS=true is not set")
        val mono = MonobankAcquiring.create(token!!)
        val receiver =
            mono.split
                .receivers()
                .firstOrNull()
                ?.id
        val started = Instant.now().minusSeconds(300)

        fun item(
            qty: String,
            sum: Long,
        ) = FiscalizationItem.builder(name, BigDecimal(qty), sum, code).splitReceiverId(receiver).build()

        val p1 = pay(mono, receiver, withTotal = false, label = "P1 (basket without total)")
        if (p1 != null) {
            refund(mono, p1, "P1 2019 (40%) no items") { it.amount(2019) }
            refund(mono, p1, "P1 1262 (exact 25%) no items") { it.amount(1262) }
            refund(mono, p1, "P1 2019 items qty 1 x sum 2019") { it.amount(2019).items(listOf(item("1", 2019))) }
            refund(mono, p1, "P1 2019 items qty 0.4 x sum 5048") { it.amount(2019).items(listOf(item("0.4", 5048))) }
            refund(mono, p1, "P1 100 items qty 1 x sum 100") { it.amount(100).items(listOf(item("1", 100))) }
            refund(mono, p1, "P1 full (no amount, no items)") { it }
            statement(mono, p1, started)
        }
        val p2 = pay(mono, receiver, withTotal = true, label = "P2 (basket with total)")
        if (p2 != null) {
            refund(mono, p2, "P2 2524 (exact 50%) no items") { it.amount(2524) }
            refund(mono, p2, "P2 1009 (about 20%) no items") { it.amount(1009) }
            refund(mono, p2, "P2 full (no amount, no items)") { it }
            statement(mono, p2, started)
        }
    }

    private fun pay(
        mono: MonobankAcquiring,
        receiver: String?,
        withTotal: Boolean,
        label: String,
    ): String? {
        val line = BasketItem.builder(name, BigDecimal.ONE, 100, code).splitReceiverId(receiver)
        if (withTotal) line.total(100)
        val answer =
            try {
                mono.invoices.paymentDirect(
                    PaymentDirectRequest
                        .builder(
                            100,
                            CardData.of("4242424242424242", "1230", "123"),
                            InitiationKind.CLIENT,
                            "https://shop.example/r",
                        ).ccy(978)
                        .merchantPaymInfo(
                            MerchantPaymInfo
                                .builder()
                                .reference("sdk-conv-" + UUID.randomUUID().toString().take(8))
                                .destination("SDK converted refund test")
                                .basketOrder(listOf(line.build()))
                                .build(),
                        ).build(),
                )
            } catch (e: MonobankApiException) {
                log("$label: payment refused: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
                return null
            }
        answer.tdsUrl?.let { log("$label: 3-D Secure: open and CONFIRM: $it") }
        var status = mono.invoices.status(answer.invoiceId)
        repeat(120) {
            if (status.status.isFinal) return@repeat
            Thread.sleep(1_500)
            status = mono.invoices.status(answer.invoiceId)
        }
        log("$label: ${answer.invoiceId} status=${status.statusRaw} amount=${status.amount} ccy=${status.ccy}")
        return answer.invoiceId.takeIf { status.status == InvoiceStatus.SUCCESS }
    }

    private fun refund(
        mono: MonobankAcquiring,
        invoiceId: String,
        what: String,
        configure: (CancelInvoiceRequest.Builder) -> CancelInvoiceRequest.Builder,
    ) {
        val request =
            configure(
                CancelInvoiceRequest.builder(invoiceId).extRef("sdk-conv-" + UUID.randomUUID().toString().take(8)),
            )
        try {
            log("$what: OK status=${mono.invoices.cancel(request.build()).statusRaw}")
        } catch (e: MonobankApiException) {
            log("$what: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
        }
        Thread.sleep(2_000)
    }

    private fun statement(
        mono: MonobankAcquiring,
        invoiceId: String,
        from: Instant,
    ) {
        mono.merchant.statement(from).filter { it.invoiceId == invoiceId }.forEach {
            log(
                "statement $invoiceId: amount=${it.amount} ccy=${it.ccy} refunds=${it.cancelList?.map { c ->
                    c.amount
                }}",
            )
        }
    }
}
