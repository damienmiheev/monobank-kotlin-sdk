package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Refunds of saved-card (`wallet/payment`) payments, as monobank's test merchant (fiscal receipts and split
 * payments on) answered live on 12.11.2026.
 */
class SavedCardRefundTest {
    private val receiverId = "sr_aB3dC5eF7g"
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .fiscalization(true)
            .splitPayments(true)
            .build()
            .start()
    private val mono = clientFor(fake)
    private val card = fake.addCard("customer")

    @AfterEach
    fun close() = fake.close()

    private fun basket(
        qty: String,
        sum: Long,
    ) = MerchantPaymInfo
        .builder()
        .basketOrder(
            listOf(BasketItem.builder("Plan", BigDecimal(qty), sum, "plan").splitReceiverId(receiverId).build()),
        ).build()

    /** A saved-card debit of [amount] in [ccy] with one basket line [qty] × [sum]. */
    private fun charge(
        amount: Long,
        qty: String,
        sum: Long,
        ccy: Int = UAH,
    ): String =
        mono.wallet
            .payment(
                WalletPaymentRequest
                    .builder(card, amount, ccy, InitiationKind.MERCHANT)
                    .merchantPaymInfo(basket(qty, sum))
                    .build(),
            ).invoiceId

    private fun item(
        qty: String,
        sum: Long,
        receiver: String = receiverId,
    ) = FiscalizationItem.builder("Plan", BigDecimal(qty), sum, "plan").splitReceiverId(receiver).build()

    private fun refund(
        invoiceId: String,
        amount: Long?,
        vararg items: FiscalizationItem,
    ) {
        val request = CancelInvoiceRequest.builder(invoiceId).items(items.toList())
        amount?.let { request.amount(it) }
        mono.invoices.cancel(request.build())
    }

    private fun refused(
        code: String,
        call: () -> Unit,
    ): String {
        val error = runCatching { call() }.exceptionOrNull()
        assertThat(error).isInstanceOf(MonobankBadRequestException::class.java)
        return (error as MonobankBadRequestException)
            .also {
                assertThat(
                    it.errorCode,
                ).isEqualTo(code)
            }.errorText
            .orEmpty()
    }

    private fun finalAmount(invoiceId: String): Long? = fake.invoice(invoiceId)!!.finalAmount

    @Test
    fun `a saved-card refund without items is refused, in UAH and converted, with or without amount`() {
        val uah = charge(45000, "1", 45000)
        val eur = charge(100, "100", 1, EUR)

        for (invoiceId in listOf(uah, eur)) {
            assertThat(refused("1001") { refund(invoiceId, 1000) }).isEqualTo("'items' cannot be empty")
            assertThat(refused("1001") { refund(invoiceId, null) }).isEqualTo("'items' cannot be empty")
        }
        assertThat(finalAmount(uah)).isEqualTo(45000)
        assertThat(finalAmount(eur)).isEqualTo(5048)

        // a payment page payment is still refunded without items
        val page =
            mono.invoices
                .create(CreateInvoiceRequest.builder(45000).merchantPaymInfo(basket("1", 45000)).build())
                .invoiceId
        fake.payInvoice(page)
        refund(page, null)
        assertThat(finalAmount(page)).isEqualTo(0)
    }

    @Test
    fun `refund items must match a paid line by name, code and unit sum, only qty may differ`() {
        val invoiceId = charge(45000, "1", 45000)

        assertThat(refused("INVALID_MERCHANT_PAYM_INFO") { refund(invoiceId, 13742, item("1", 13742)) })
            .isEqualTo("invalid item 'Plan' with code 'plan'")
        val otherCode = FiscalizationItem.builder("Plan", BigDecimal.ONE, 45000, "other").build()
        assertThat(refused("INVALID_MERCHANT_PAYM_INFO") { refund(invoiceId, 45000, otherCode) })
            .isEqualTo("invalid item 'Plan' with code 'other'")

        // monobank's test merchant accepted refund items with any of its receiver ids
        refund(invoiceId, 45000, item("1", 45000, receiver = "sr_other"))
        assertThat(finalAmount(invoiceId)).isEqualTo(0)
    }

    @Test
    fun `qty x sum must equal the amount exactly, without rounding`() {
        val invoiceId = charge(45000, "1", 45000)

        // 0.30538 × 45000 = 13742.1
        assertThat(refused("INVALID_MERCHANT_PAYM_INFO") { refund(invoiceId, 13742, item("0.30538", 45000)) })
            .isEqualTo(DISCOUNT)
        // no amount: everything that is left
        assertThat(refused("INVALID_MERCHANT_PAYM_INFO") { refund(invoiceId, null, item("0.5", 45000)) })
            .isEqualTo(DISCOUNT)
        assertThat(finalAmount(invoiceId)).isEqualTo(45000)

        refund(invoiceId, null, item("1", 45000))
        assertThat(finalAmount(invoiceId)).isEqualTo(0)
    }

    @Test
    fun `after a partial refund, a refund without amount is refused - send the rest with its amount`() {
        val invoiceId = charge(45000, "45000", 1)
        refund(invoiceId, 1000, item("1000", 1))

        assertThat(refused("1001") { refund(invoiceId, null, item("44000", 1)) })
            .isEqualTo("split receiver's part of invoice has already been partially cancelled: $receiverId")

        refund(invoiceId, 44000, item("44000", 1))
        assertThat(finalAmount(invoiceId)).isEqualTo(0)
    }

    @Test
    fun `the working pattern for UAH - a basket line in kopiyky refunds any amount as qty R x sum 1`() {
        val invoiceId = charge(45000, "45000", 1)

        for (amount in listOf(13742L, 1000L, 30258L)) refund(invoiceId, amount, item(amount.toString(), 1))

        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(status.finalAmount).isEqualTo(0)
        assertThat(status.cancelList!!.map { it.amount }).containsExactly(13742L, 1000L, 30258L)
    }

    @Test
    fun `a converted saved-card payment refunds at most its basket units, as kopiyky`() {
        // EUR 1.00 → 5048 kopiyky, basket 100 × 1 euro cent
        val invoiceId = charge(100, "100", 1, EUR)

        refund(invoiceId, 50, item("50", 1))
        refund(invoiceId, 49, item("49", 1))
        refund(invoiceId, 1, item("1", 1))
        assertThat(finalAmount(invoiceId)).isEqualTo(4948) // ₴1.00 refunded, ₴49.48 stuck

        assertThat(refused("INVALID_MERCHANT_PAYM_INFO") { refund(invoiceId, 1, item("1", 1)) }).isEqualTo(DISCOUNT)
        assertThat(refused("INVALID_MERCHANT_PAYM_INFO") { refund(invoiceId, null, item("1", 1)) })
            .isEqualTo(DISCOUNT)
        assertThat(refused("1001") { refund(invoiceId, 4948) }).isEqualTo("'items' cannot be empty")
        assertThat(finalAmount(invoiceId)).isEqualTo(4948)
    }

    @Test
    fun `a converted saved-card payment with a single-unit line cannot be refunded with items`() {
        val invoiceId = charge(100, "1", 100, EUR)

        assertThat(refused("1001") { refund(invoiceId, 100, item("1", 100)) })
            .isEqualTo("whole discount is expected to be cancelled with the last item")
        assertThat(refused("1001") { refund(invoiceId, 50, item("0.5", 100)) })
            .isEqualTo("whole discount is expected to be cancelled with the last item")
        assertThat(finalAmount(invoiceId)).isEqualTo(5048)
    }

    @Test
    fun `a converted saved-card payment needs a basket in the original currency`() {
        val error =
            refused("INVALID_MERCHANT_PAYM_INFO") {
                charge(100, "1", 5048, EUR) // a UAH basket for EUR 1.00
            }

        assertThat(error).isEqualTo(DISCOUNT)
        assertThat(fake.invoices()).isEmpty()
    }

    private companion object {
        const val UAH = 980
        const val EUR = 978
        const val DISCOUNT =
            "total amount of ('sum' * 'qty')-calculated 'discount' does not equal 'amount' field from request"
    }
}
