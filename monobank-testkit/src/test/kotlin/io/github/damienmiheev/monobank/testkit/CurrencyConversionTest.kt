package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankServerException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * USD/EUR payments are converted to UAH, and a refund of one breaks `invoice/status`, as monobank's test
 * merchant (fiscal receipts and split payments on) answered live.
 */
class CurrencyConversionTest {
    private val receiverId = "sr_aB3dC5eF7g"
    private val receiver = WebhookReceiver()
    private val fake = fake(broken = true)
    private val mono = clientFor(fake)
    private val from: Instant = Instant.now().minusSeconds(300)

    private fun fake(broken: Boolean) =
        FakeMonobankAcquiring
            .builder()
            .fiscalization(true)
            .splitPayments(true)
            .brokenStatusAfterConvertedRefund(broken)
            .build()
            .start()

    @AfterEach
    fun close() {
        fake.close()
        receiver.close()
    }

    private fun basket(sum: Long) =
        MerchantPaymInfo
            .builder()
            .basketOrder(
                listOf(BasketItem.builder("Plan", BigDecimal.ONE, sum, "plan").splitReceiverId(receiverId).build()),
            ).build()

    /** A hosted invoice of 100 minor units in [ccy], paid by the customer. */
    private fun paid(
        ccy: Int,
        fake: FakeMonobankAcquiring = this.fake,
    ): String {
        val invoiceId =
            clientFor(fake)
                .invoices
                .create(
                    CreateInvoiceRequest
                        .builder(100)
                        .ccy(ccy)
                        .webHookUrl(receiver.url)
                        .merchantPaymInfo(basket(100))
                        .build(),
                ).invoiceId
        fake.payInvoice(invoiceId)
        return invoiceId
    }

    @Test
    fun `an unpaid invoice keeps its currency, a paid one is converted to UAH in status, statement and webhooks`() {
        val invoiceId =
            mono.invoices
                .create(
                    CreateInvoiceRequest
                        .builder(
                            100,
                        ).ccy(978)
                        .webHookUrl(receiver.url)
                        .merchantPaymInfo(basket(100))
                        .build(),
                ).invoiceId
        val unpaid = mono.invoices.status(invoiceId)
        assertThat(unpaid.amount).isEqualTo(100)
        assertThat(unpaid.ccy).isEqualTo(978)

        fake.payInvoice(invoiceId)

        val status = mono.invoices.status(invoiceId)
        assertThat(status.amount).isEqualTo(5048)
        assertThat(status.finalAmount).isEqualTo(5048)
        assertThat(status.ccy).isEqualTo(980)
        assertThat(status.paymentInfo!!.fee).isEqualTo(66)
        val entry = mono.merchant.statement(from).single { it.invoiceId == invoiceId }
        assertThat(entry.amount).isEqualTo(5048)
        assertThat(entry.ccy).isEqualTo(980)
        val last = fake.awaitWebhooks(invoiceId, 2, Duration.ofSeconds(30)).last()
        val webhook = mono.webhooks.verifyAndParse(last.xSign, last.bodyBytes())
        assertThat(webhook.amount).isEqualTo(5048)
        assertThat(webhook.ccy).isEqualTo(980)

        val usd = paid(840)
        assertThat(mono.invoices.status(usd).amount).isEqualTo(4551)
    }

    @Test
    fun `the payment answer echoes the requested amount and currency`() {
        val card = fake.addCard("customer")

        val payment =
            mono.wallet.payment(
                WalletPaymentRequest
                    .builder(
                        card,
                        100,
                        978,
                        InitiationKind.MERCHANT,
                    ).merchantPaymInfo(basket(100))
                    .build(),
            )

        assertThat(payment.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(payment.amount).isEqualTo(100)
        assertThat(payment.ccy).isEqualTo(978)
        val status = mono.invoices.status(payment.invoiceId)
        assertThat(status.amount).isEqualTo(5048)
        assertThat(status.ccy).isEqualTo(980)
    }

    @Test
    fun `a partial UAH refund without items works, then status is broken and is rebuilt from the statement`() {
        val invoiceId = paid(978)

        val refund = mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(2524).build())

        assertThat(refund.status).isIn(OperationStatus.PROCESSING, OperationStatus.SUCCESS)
        repeat(2) {
            assertThatThrownBy { mono.invoices.status(invoiceId) }
                .isInstanceOfSatisfying(MonobankServerException::class.java) {
                    assertThat(it.statusCode).isEqualTo(500)
                    assertThat(it.errorCode).isEqualTo("INTERNAL_ERROR")
                    assertThat(it.errorText).isEqualTo("unexpected end of JSON input")
                }
        }
        val entry = mono.merchant.statement(from).single { it.invoiceId == invoiceId }
        assertThat(entry.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(entry.cancelList!!.single().amount).isEqualTo(2524)
        assertThat(entry.cancelList!!.single().ccy).isEqualTo(980)

        val rebuilt = mono.invoices.status(invoiceId, from)

        assertThat(rebuilt.isReconstructedFromStatement).isTrue()
        assertThat(rebuilt.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(rebuilt.amount).isEqualTo(5048)
        assertThat(rebuilt.finalAmount).isEqualTo(2524)
        assertThat(fake.invoice(invoiceId)!!.finalAmount).isEqualTo(2524)
    }

    @Test
    fun `a full refund without amount or items refunds the whole UAH amount`() {
        val invoiceId = paid(840)

        mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).build())

        assertThatThrownBy { mono.invoices.status(invoiceId) }.isInstanceOf(MonobankServerException::class.java)
        val rebuilt = mono.invoices.status(invoiceId, from)
        assertThat(rebuilt.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(rebuilt.finalAmount).isEqualTo(0)
        assertThat(rebuilt.cancelList!!.single().amount).isEqualTo(4551)
    }

    @Test
    fun `refund items of a converted payment are refused with 1001`() {
        val invoiceId = paid(978)
        val items =
            listOf(
                FiscalizationItem.builder("Plan", BigDecimal("0.5"), 100, "plan").splitReceiverId(receiverId).build(),
            )

        val error =
            runCatching {
                mono.invoices.cancel(
                    CancelInvoiceRequest
                        .builder(invoiceId)
                        .amount(50)
                        .items(items)
                        .build(),
                )
            }.exceptionOrNull()

        assertThat(error).isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
            assertThat(it.errorCode).isEqualTo("1001")
            assertThat(it.errorText).isEqualTo("whole discount is expected to be cancelled with the last item")
        }
        assertThat(mono.invoices.status(invoiceId).finalAmount).isEqualTo(5048) // nothing refunded, status intact
    }

    @Test
    fun `the broken status can be switched off, and UAH payments are never affected`() {
        fake(broken = false).use { working ->
            val client = clientFor(working)
            val invoiceId = paid(978, working)
            client.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(2524).build())
            val status = client.invoices.status(invoiceId)
            assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
            assertThat(status.finalAmount).isEqualTo(2524)
            assertThat(status.cancelList!!.single().ccy).isEqualTo(980)
        }

        val uah = paid(980)
        mono.invoices.cancel(CancelInvoiceRequest.builder(uah).build())
        assertThat(mono.invoices.status(uah).status).isEqualTo(InvoiceStatus.REVERSED)
    }

    @Test
    fun `rates are configurable`() {
        FakeMonobankAcquiring.builder().conversionRate(978, BigDecimal("42.125")).build().start().use { custom ->
            val client = clientFor(custom)
            val invoiceId = client.invoices.create(CreateInvoiceRequest.builder(100).ccy(978).build()).invoiceId
            custom.payInvoice(invoiceId)
            assertThat(client.invoices.status(invoiceId).amount).isEqualTo(4213) // 4212.5 rounded half up
        }
        assertThatThrownBy { FakeMonobankAcquiring.builder().conversionRate(980, BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
