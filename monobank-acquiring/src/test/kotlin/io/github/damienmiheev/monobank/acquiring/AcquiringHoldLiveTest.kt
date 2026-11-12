package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CardData
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentDirectRequest
import io.github.damienmiheev.monobank.core.CurrencyCodes
import io.github.damienmiheev.monobank.core.MonobankApiException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Live check of holds on monobank's test merchant: pays a 2.00 UAH hold with monobank's test card
 * through `payment-direct`, captures 1.50 as `qty` 0.75 of the original line, then refunds 0.50 as
 * `qty` 0.25. Prints monobank's answer to every step instead of asserting, to learn its rules.
 *
 * Runs only with `MONOBANK_ACQUIRING_TEST_TOKEN` (a TEST token) AND `MONOBANK_LIVE_PAYMENTS=true`.
 */
@Tag("live")
class AcquiringHoldLiveTest {
    private fun log(message: String) = println("[hold-live] $message")

    private fun <T> step(
        name: String,
        block: () -> T,
    ): T? =
        try {
            block().also { log("$name: OK $it") }
        } catch (e: MonobankApiException) {
            log("$name: HTTP ${e.statusCode} errCode=${e.errorCode} errText=${e.errorText}")
            null
        }

    @Test
    fun `hold, partial capture with fractional qty, partial refund`() {
        val token = System.getenv("MONOBANK_ACQUIRING_TEST_TOKEN")
        assumeTrue(!token.isNullOrBlank(), "MONOBANK_ACQUIRING_TEST_TOKEN is not set")
        assumeTrue(System.getenv("MONOBANK_LIVE_PAYMENTS") == "true", "MONOBANK_LIVE_PAYMENTS=true is not set")
        val mono = MonobankAcquiring.create(token!!)

        val receiver =
            mono.split
                .receivers()
                .firstOrNull()
                ?.id
        log("split receiver present: ${receiver != null}")
        val name = "SDK live hold"
        val code = "sdk-live-hold"

        fun item(qty: String) =
            FiscalizationItem.builder(name, BigDecimal(qty), 200, code).splitReceiverId(receiver).build()

        // MONOBANK_LIVE_INVOICE_ID: continue with an already finalized invoice (skips paying again).
        val existing = System.getenv("MONOBANK_LIVE_INVOICE_ID")?.takeIf { it.isNotBlank() }
        val invoiceId =
            existing ?: run {
                val paid =
                    step("payment-direct hold 2.00 UAH") {
                        mono.invoices.paymentDirect(
                            PaymentDirectRequest
                                .builder(
                                    200,
                                    CardData.of("4242424242424242", "1230", "123"),
                                    InitiationKind.CLIENT,
                                    "https://shop.example/return",
                                ).ccy(CurrencyCodes.UAH)
                                .paymentType(PaymentType.HOLD)
                                .merchantPaymInfo(
                                    MerchantPaymInfo
                                        .builder()
                                        .reference("sdk-live-hold-" + UUID.randomUUID())
                                        .destination("SDK live hold test")
                                        .basketOrder(
                                            listOf(
                                                BasketItem
                                                    .builder(
                                                        name,
                                                        BigDecimal.ONE,
                                                        200,
                                                        code,
                                                    ).splitReceiverId(receiver)
                                                    .build(),
                                            ),
                                        ).build(),
                                ).build(),
                        )
                    } ?: return
                if (paid.tdsUrl != null) {
                    log("3-D Secure: open this link in your browser and confirm, then wait here: ${paid.tdsUrl}")
                    System.out.flush()
                }

                val held = waitFor(mono, paid.invoiceId, attempts = 120) { it == InvoiceStatus.HOLD || it.isFinal }
                log("status after payment: $held")
                if (held != InvoiceStatus.HOLD) return

                step("finalize 1.50 as qty 0.75 x 2.00") {
                    mono.invoices.finalize(
                        FinalizeInvoiceRequest.builder(paid.invoiceId, 150).items(listOf(item("0.75"))).build(),
                    )
                }
                log(
                    "status after finalize: ${waitFor(
                        mono,
                        paid.invoiceId,
                    ) { it.isFinal }}, finalAmount=${mono.invoices.status(paid.invoiceId).finalAmount}",
                )
                paid.invoiceId
            }
        // A finalized invoice stays `processing` for a while; a refund before `success` gets 404 1004.
        val settled = waitFor(mono, invoiceId, attempts = 120) { it.isFinal }
        log("status before refund: $settled")

        step("refund 0.50 as qty 0.25 x 2.00") {
            mono.invoices.cancel(
                CancelInvoiceRequest
                    .builder(
                        invoiceId,
                    ).amount(50)
                    .extRef("sdk-live-refund-1")
                    .items(listOf(item("0.25")))
                    .build(),
            )
        }
        Thread.sleep(3_000)
        val last = mono.invoices.status(invoiceId)
        log(
            "final: status=${last.status} finalAmount=${last.finalAmount} cancelList=${last.cancelList?.map {
                "${it.status}/${it.amount}"
            }}",
        )
    }

    private fun waitFor(
        mono: MonobankAcquiring,
        invoiceId: String,
        attempts: Int = 20,
        done: (InvoiceStatus) -> Boolean,
    ): InvoiceStatus {
        var status = mono.invoices.status(invoiceId).status
        repeat(attempts) {
            if (done(status)) return status
            Thread.sleep(1_500)
            status = mono.invoices.status(invoiceId).status
        }
        return status
    }
}
