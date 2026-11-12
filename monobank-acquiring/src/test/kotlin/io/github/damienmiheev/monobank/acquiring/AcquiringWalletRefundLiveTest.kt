package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CardData
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentDirectRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankApiException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Live: partial refunds of a converted (EUR → UAH) SAVED-CARD payment (`wallet/payment`). Saves a
 * test card with a 1.00 EUR direct payment (one 3-D Secure confirmation), charges 1.00 EUR on the
 * saved card, then tries refund shapes and prints every answer. Learns, does not assert.
 *
 * Runs only with `MONOBANK_ACQUIRING_TEST_TOKEN` (a TEST token) AND `MONOBANK_LIVE_PAYMENTS=true`.
 */
@Tag("live")
class AcquiringWalletRefundLiveTest {
    private fun log(message: String) {
        println("[wallet-live] $message")
        System.out.flush()
    }

    private val name = "SDK wallet refund"
    private val code = "sdk-wallet"

    @Test
    fun `partial refunds of a converted saved-card payment`() {
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
        val walletId = "sdk-live-wallet-" + UUID.randomUUID().toString().take(8)

        fun basket() =
            MerchantPaymInfo
                .builder()
                .reference("sdk-wallet-" + UUID.randomUUID().toString().take(8))
                .destination("SDK wallet refund test")
                .basketOrder(
                    listOf(
                        BasketItem
                            .builder(name, BigDecimal.ONE, 100, code)
                            .total(100)
                            .splitReceiverId(receiver)
                            .build(),
                    ),
                ).build()

        val first =
            try {
                mono.invoices.paymentDirect(
                    PaymentDirectRequest
                        .builder(
                            100,
                            CardData.of("4242424242424242", "1230", "123"),
                            InitiationKind.CLIENT,
                            "https://shop.example/r",
                        ).ccy(978)
                        .saveCardData(SaveCardData.saveTo(walletId))
                        .merchantPaymInfo(basket())
                        .build(),
                )
            } catch (e: MonobankApiException) {
                log("saving payment refused: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
                return
            }
        first.tdsUrl?.let { log("3-D Secure: open and CONFIRM: $it") }
        var cardToken: String? = null
        repeat(120) {
            val s = mono.invoices.status(first.invoiceId)
            val wallet = s.walletData
            if (wallet?.status == CardTokenStatus.CREATED && wallet.cardToken != null) {
                cardToken = wallet.cardToken
                return@repeat
            }
            if (s.status == InvoiceStatus.FAILURE || wallet?.status == CardTokenStatus.FAILED) {
                log("saving failed: status=${s.statusRaw} wallet=${wallet?.statusRaw}")
                return
            }
            Thread.sleep(1_500)
        }
        val savedToken = cardToken ?: return log("no card token after waiting")
        log("card saved")

        val charge =
            mono.wallet.payment(
                WalletPaymentRequest
                    .builder(
                        savedToken,
                        100,
                        978,
                        InitiationKind.MERCHANT,
                    ).merchantPaymInfo(basket())
                    .build(),
            )
        log(
            "wallet payment answer: ${charge.statusRaw} amount=${charge.amount} ccy=${charge.ccy} " +
                "tds=${charge.tdsUrl != null}",
        )
        var paid = mono.invoices.status(charge.invoiceId)
        repeat(60) {
            if (paid.status.isFinal) return@repeat
            Thread.sleep(1_500)
            paid = mono.invoices.status(charge.invoiceId)
        }
        log("wallet payment ${charge.invoiceId}: status=${paid.statusRaw} amount=${paid.amount} ccy=${paid.ccy}")
        if (paid.status != InvoiceStatus.SUCCESS) return

        fun item(
            qty: String,
            sum: Long,
        ) = FiscalizationItem.builder(name, BigDecimal(qty), sum, code).splitReceiverId(receiver).build()
        val id = charge.invoiceId
        refund(mono, id, "2019 no items") { it.amount(2019) }
        refund(mono, id, "2524 (50%) no items") { it.amount(2524) }
        refund(mono, id, "1009 items qty 0.2 x sum 5048") { it.amount(1009).items(listOf(item("0.2", 5048))) }
        refund(mono, id, "1009 items qty 1 x sum 1009") { it.amount(1009).items(listOf(item("1", 1009))) }
        refund(mono, id, "1009 items qty 10.09 x sum 100") { it.amount(1009).items(listOf(item("10.09", 100))) }
        refund(
            mono,
            id,
            "20 items qty 0.2 x sum 100 (original EUR line)",
        ) { it.amount(20).items(listOf(item("0.2", 100))) }
        mono.merchant.statement(started).filter { it.invoiceId == id }.forEach {
            log("statement: amount=${it.amount} ccy=${it.ccy} refunds=${it.cancelList?.map { c -> c.amount }}")
        }
        runCatching { mono.wallet.deleteCard(savedToken) }.onFailure { log("card delete failed: ${it.message}") }
    }

    private fun refund(
        mono: MonobankAcquiring,
        invoiceId: String,
        what: String,
        configure: (CancelInvoiceRequest.Builder) -> CancelInvoiceRequest.Builder,
    ) {
        val request =
            configure(
                CancelInvoiceRequest.builder(invoiceId).extRef("sdk-wallet-" + UUID.randomUUID().toString().take(8)),
            )
        try {
            log("$what: OK status=${mono.invoices.cancel(request.build()).statusRaw}")
        } catch (e: MonobankApiException) {
            log("$what: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
        }
        Thread.sleep(2_000)
    }
}
