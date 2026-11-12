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

    /** Full refund of an existing converted saved-card payment: tries the plausible forms in order. */
    @Test
    fun `full refund of a converted saved-card payment`() {
        val token = System.getenv("MONOBANK_ACQUIRING_TEST_TOKEN")
        val id = System.getenv("MONOBANK_LIVE_REFUND_INVOICE_ID")
        assumeTrue(!token.isNullOrBlank() && !id.isNullOrBlank(), "token or invoice id not set")
        assumeTrue(System.getenv("MONOBANK_LIVE_PAYMENTS") == "true", "MONOBANK_LIVE_PAYMENTS=true is not set")
        val mono = MonobankAcquiring.create(token!!)
        val receiver =
            mono.split
                .receivers()
                .firstOrNull()
                ?.id
        val line = FiscalizationItem.builder(name, BigDecimal.ONE, 100, code).splitReceiverId(receiver).build()

        fun uahLine(
            qty: String,
            sum: Long,
        ) = FiscalizationItem.builder(name, BigDecimal(qty), sum, code).splitReceiverId(receiver).build()
        val attempts =
            listOf<Pair<String, (CancelInvoiceRequest.Builder) -> CancelInvoiceRequest.Builder>>(
                "full: amount 5048, items qty 1 x sum 5048" to { it.amount(5048).items(listOf(uahLine("1", 5048))) },
                "full: amount 5048, items qty 50.48 x sum 100" to {
                    it.amount(5048).items(listOf(uahLine("50.48", 100)))
                },
                "full: no amount, items qty 1 x sum 5048" to { it.items(listOf(uahLine("1", 5048))) },
                "full: no amount, no items" to { it },
                "full: no amount, items = original line (qty 1 x sum 100)" to { it.items(listOf(line)) },
                "full: amount 5048, items = original line" to { it.amount(5048).items(listOf(line)) },
                "full: amount 5048, no items" to { it.amount(5048) },
            )
        for ((what, configure) in attempts) {
            val request =
                configure(
                    CancelInvoiceRequest.builder(id!!).extRef("sdk-wallet-" + UUID.randomUUID().toString().take(8)),
                )
            val ok =
                try {
                    log("$what: OK status=${mono.invoices.cancel(request.build()).statusRaw}")
                    true
                } catch (e: MonobankApiException) {
                    log("$what: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
                    false
                }
            if (ok) break
            Thread.sleep(1_000)
        }
        Thread.sleep(3_000)
        mono.merchant.statement(Instant.now().minusSeconds(86_400)).filter { it.invoiceId == id }.forEach {
            log("statement: amount=${it.amount} ccy=${it.ccy} refunds=${it.cancelList?.map { c -> c.amount }}")
        }
    }

    /**
     * Saved card (one 3-D Secure confirmation), then: A) 1.00 EUR charge WITHOUT a basket, refunded
     * partly and then fully; B) 50.00 UAH charge with a one-unit basket (qty = amount, sum 100),
     * refunded partly with a matching item.
     */
    @Test
    fun `saved-card charges - EUR without basket, UAH control`() {
        val token = System.getenv("MONOBANK_ACQUIRING_TEST_TOKEN")
        assumeTrue(!token.isNullOrBlank(), "MONOBANK_ACQUIRING_TEST_TOKEN is not set")
        assumeTrue(
            System.getenv("MONOBANK_LIVE_SAVED_CARD_RUN") == "true",
            "MONOBANK_LIVE_SAVED_CARD_RUN=true is not set",
        )
        val mono = MonobankAcquiring.create(token!!)
        val receiver =
            mono.split
                .receivers()
                .firstOrNull()
                ?.id
        val started = Instant.now().minusSeconds(300)
        val savedToken = saveCard(mono, receiver) ?: return

        log("--- A: 1.00 EUR saved-card charge without basket")
        val a =
            try {
                mono.wallet.payment(
                    WalletPaymentRequest
                        .builder(savedToken, 100, 978, InitiationKind.MERCHANT)
                        .merchantPaymInfo(
                            MerchantPaymInfo
                                .builder()
                                .reference(ref())
                                .destination("SDK no-basket test")
                                .build(),
                        ).build(),
                )
            } catch (e: MonobankApiException) {
                log("A refused: HTTP ${e.statusCode} ${e.errorCode} ${e.errorText}")
                null
            }
        if (a != null) {
            log("A ${a.invoiceId}: ${settled(mono, a.invoiceId)}")
            refund(mono, a.invoiceId, "A partial 2019 no items") { it.amount(2019) }
            refund(mono, a.invoiceId, "A rest: no amount, no items") { it }
            refund(mono, a.invoiceId, "A rest: amount 3029, no items") { it.amount(3029) }
        }

        log("--- B: 50.00 UAH saved-card charge with a one-unit basket")

        fun line(qty: String) =
            FiscalizationItem.builder(name, BigDecimal(qty), 100, code).splitReceiverId(receiver).build()
        val b =
            mono.wallet.payment(
                WalletPaymentRequest
                    .builder(savedToken, 5000, 980, InitiationKind.MERCHANT)
                    .merchantPaymInfo(
                        MerchantPaymInfo
                            .builder()
                            .reference(ref())
                            .destination("SDK UAH control")
                            .basketOrder(
                                listOf(
                                    BasketItem
                                        .builder(
                                            name,
                                            BigDecimal("50"),
                                            100,
                                            code,
                                        ).splitReceiverId(receiver)
                                        .build(),
                                ),
                            ).build(),
                    ).build(),
            )
        log("B ${b.invoiceId}: ${settled(mono, b.invoiceId)}")
        refund(
            mono,
            b.invoiceId,
            "B partial 20.19 items qty 20.19 x sum 100",
        ) { it.amount(2019).items(listOf(line("20.19"))) }
        refund(mono, b.invoiceId, "B partial 10.00 no items") { it.amount(1000) }
        mono.merchant
            .statement(
                started,
            ).filter { it.invoiceId == a?.invoiceId || it.invoiceId == b.invoiceId }
            .forEach {
                log(
                    "statement ${it.invoiceId}: amount=${it.amount} ccy=${it.ccy} refunds=${it.cancelList?.map { c ->
                        c.amount
                    }}",
                )
            }
        runCatching { mono.wallet.deleteCard(savedToken) }
    }

    private fun ref() = "sdk-wallet-" + UUID.randomUUID().toString().take(8)

    private fun settled(
        mono: MonobankAcquiring,
        invoiceId: String,
    ): String {
        var s = mono.invoices.status(invoiceId)
        repeat(40) {
            if (s.status.isFinal) return@repeat
            Thread.sleep(1_500)
            s = mono.invoices.status(invoiceId)
        }
        return "status=${s.statusRaw} amount=${s.amount} ccy=${s.ccy}"
    }

    private fun saveCard(
        mono: MonobankAcquiring,
        receiver: String?,
    ): String? {
        val walletId = "sdk-live-wallet-" + UUID.randomUUID().toString().take(8)
        val first =
            mono.invoices.paymentDirect(
                PaymentDirectRequest
                    .builder(
                        100,
                        CardData.of("4242424242424242", "1230", "123"),
                        InitiationKind.CLIENT,
                        "https://shop.example/r",
                    ).saveCardData(SaveCardData.saveTo(walletId))
                    .merchantPaymInfo(
                        MerchantPaymInfo
                            .builder()
                            .reference(ref())
                            .destination("SDK save card")
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
        first.tdsUrl?.let { log("3-D Secure: open and CONFIRM: $it") }
        repeat(120) {
            val wallet = mono.invoices.status(first.invoiceId).walletData
            if (wallet?.status == CardTokenStatus.CREATED && wallet.cardToken != null) {
                log("card saved")
                return wallet.cardToken
            }
            Thread.sleep(1_500)
        }
        log("no card token after waiting")
        return null
    }
}
