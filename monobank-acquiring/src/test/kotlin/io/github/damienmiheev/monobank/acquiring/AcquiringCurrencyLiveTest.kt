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
import java.util.UUID

/**
 * Live check of non-UAH payments on monobank's test merchant: pays 1.00 EUR and 1.00 USD with test
 * cards through `payment-direct` (each needs a 3-D Secure confirmation in the browser), prints how
 * monobank reports them, then tries refund variants and prints every answer. Learns, does not assert.
 *
 * Runs only with `MONOBANK_ACQUIRING_TEST_TOKEN` (a TEST token) AND `MONOBANK_LIVE_PAYMENTS=true`.
 */
@Tag("live")
class AcquiringCurrencyLiveTest {
    private fun log(message: String) {
        println("[ccy-live] $message")
        System.out.flush()
    }

    private val name = "SDK live currency"
    private val code = "sdk-live-ccy"

    @Test
    fun `non-UAH payments - how they settle and how to refund them`() {
        val token = System.getenv("MONOBANK_ACQUIRING_TEST_TOKEN")
        assumeTrue(!token.isNullOrBlank(), "MONOBANK_ACQUIRING_TEST_TOKEN is not set")
        assumeTrue(System.getenv("MONOBANK_LIVE_PAYMENTS") == "true", "MONOBANK_LIVE_PAYMENTS=true is not set")
        val mono = MonobankAcquiring.create(token!!)
        val receiver =
            mono.split
                .receivers()
                .firstOrNull()
                ?.id

        val eur = pay(mono, receiver, 978, "4242424242424242", "EUR, test Visa 4242")
        val usd = pay(mono, receiver, 840, "4242424242424242", "USD, test Visa 4242")
        pay(mono, receiver, 978, "5555555555554444", "EUR, test Mastercard 5555")

        if (eur != null) refundVariants(mono, receiver, eur, "EUR payment")
        if (usd != null) {
            attempt(
                mono,
                usd,
                "USD full refund, no amount, no items",
            ) { CancelInvoiceRequest.builder(usd).extRef(ref()).build() }
        }
    }

    private fun ref() = "sdk-live-ccy-" + UUID.randomUUID().toString().take(8)

    private fun pay(
        mono: MonobankAcquiring,
        receiver: String?,
        ccy: Int,
        pan: String,
        label: String,
    ): String? {
        log("=== $label: paying 100 minor units, ccy $ccy")
        val answer =
            try {
                mono.invoices.paymentDirect(
                    PaymentDirectRequest
                        .builder(
                            100,
                            CardData.of(pan, "1230", "123"),
                            InitiationKind.CLIENT,
                            "https://shop.example/return",
                        ).ccy(ccy)
                        .merchantPaymInfo(
                            MerchantPaymInfo
                                .builder()
                                .reference(ref())
                                .destination("SDK live currency test")
                                .basketOrder(
                                    listOf(
                                        BasketItem
                                            .builder(
                                                name,
                                                BigDecimal.ONE,
                                                100,
                                                code,
                                            ).splitReceiverId(receiver)
                                            .build(),
                                    ),
                                ).build(),
                        ).build(),
                )
            } catch (e: MonobankApiException) {
                log("$label: payment refused: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
                return null
            }
        log("$label: answer amount=${answer.amount} ccy=${answer.ccy} status=${answer.statusRaw}")
        answer.tdsUrl?.let { log("$label: 3-D Secure: open and CONFIRM: $it") }
        var status = mono.invoices.status(answer.invoiceId)
        repeat(120) {
            if (status.status.isFinal || status.status == InvoiceStatus.HOLD) return@repeat
            Thread.sleep(1_500)
            status = mono.invoices.status(answer.invoiceId)
        }
        log(
            "$label: status=${status.statusRaw} amount=${status.amount} ccy=${status.ccy} " +
                "finalAmount=${status.finalAmount} card country=${status.paymentInfo?.country} " +
                "bank=${status.paymentInfo?.bank} system=${status.paymentInfo?.paymentSystemRaw} fee=${status.paymentInfo?.fee}",
        )
        return answer.invoiceId.takeIf { status.status == InvoiceStatus.SUCCESS }
    }

    private fun refundVariants(
        mono: MonobankAcquiring,
        receiver: String?,
        invoiceId: String,
        label: String,
    ) {
        val paid = mono.invoices.status(invoiceId)
        val uah = paid.finalAmount ?: paid.amount
        val half = uah / 2

        fun item(
            qty: String,
            sum: Long,
        ) = FiscalizationItem.builder(name, BigDecimal(qty), sum, code).splitReceiverId(receiver).build()

        // Partial refunds: stop at the first that is accepted.
        val partials =
            listOf<Pair<String, () -> CancelInvoiceRequest>>(
                "partial $half (UAH) without items" to
                    {
                        CancelInvoiceRequest
                            .builder(invoiceId)
                            .amount(half)
                            .extRef(ref())
                            .build()
                    },
                "partial $half (UAH) with items qty 0.5 x 100" to {
                    CancelInvoiceRequest
                        .builder(
                            invoiceId,
                        ).amount(half)
                        .extRef(ref())
                        .items(listOf(item("0.5", 100)))
                        .build()
                },
                "partial 50 (original units) with items qty 0.5 x 100" to {
                    CancelInvoiceRequest
                        .builder(
                            invoiceId,
                        ).amount(50)
                        .extRef(ref())
                        .items(listOf(item("0.5", 100)))
                        .build()
                },
                "partial $half (UAH) with items qty 1 x $half" to {
                    CancelInvoiceRequest
                        .builder(
                            invoiceId,
                        ).amount(half)
                        .extRef(ref())
                        .items(listOf(item("1", half)))
                        .build()
                },
            )
        for ((what, request) in partials) {
            if (attempt(mono, invoiceId, "$label $what", request)) break
        }
        // Full refund of what remains: stop at the first that is accepted.
        val fulls =
            listOf<Pair<String, () -> CancelInvoiceRequest>>(
                "full: no amount, no items" to {
                    CancelInvoiceRequest.builder(invoiceId).extRef(ref()).build()
                },
                "full: amount = finalAmount, no items" to {
                    CancelInvoiceRequest
                        .builder(
                            invoiceId,
                        ).amount(mono.invoices.status(invoiceId).finalAmount ?: uah)
                        .extRef(ref())
                        .build()
                },
            )
        for ((what, request) in fulls) {
            if (attempt(mono, invoiceId, "$label $what", request)) break
        }
    }

    private fun attempt(
        mono: MonobankAcquiring,
        invoiceId: String,
        what: String,
        request: () -> CancelInvoiceRequest,
    ): Boolean {
        val ok =
            try {
                val answer = mono.invoices.cancel(request())
                log("$what: OK status=${answer.statusRaw}")
                true
            } catch (e: MonobankApiException) {
                log("$what: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
                false
            }
        Thread.sleep(3_000)
        val s = mono.invoices.status(invoiceId)
        log(
            "  -> status=${s.statusRaw} amount=${s.amount} ccy=${s.ccy} finalAmount=${s.finalAmount} " +
                "cancelList=${s.cancelList?.map { "${it.statusRaw}/${it.amount}/${it.ccy}" }}",
        )
        return ok
    }
}
