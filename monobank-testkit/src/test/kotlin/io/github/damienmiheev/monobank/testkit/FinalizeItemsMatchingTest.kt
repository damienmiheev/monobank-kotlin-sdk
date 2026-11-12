package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Finalize and refund items are matched against the basket by (name, code, unit sum, receiver),
 * as monobank's test merchant (fiscalization and split on) answered the Kill Bill plugin live.
 */
class FinalizeItemsMatchingTest {
    private val receiver = "sr_aB3dC5eF7g"
    private val name = "Kill Bill plugin live test"
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .fiscalization(true)
            .splitPayments(true)
            .finalizeSettlementDelay(null)
            .build()
            .start()
    private val mono = clientFor(fake)

    @AfterEach
    fun close() = fake.close()

    private fun create(type: PaymentType): String {
        val basket = BasketItem.builder(name, BigDecimal.ONE, 200, "payment").splitReceiverId(receiver).build()
        val invoiceId =
            mono.invoices
                .create(
                    CreateInvoiceRequest
                        .builder(200)
                        .paymentType(type)
                        .merchantPaymInfo(MerchantPaymInfo.builder().basketOrder(listOf(basket)).build())
                        .build(),
                ).invoiceId
        fake.payInvoice(invoiceId)
        return invoiceId
    }

    private fun item(
        qty: String = "1",
        sum: Long = 200,
        itemName: String = name,
        code: String = "payment",
        receiverId: String? = receiver,
    ): FiscalizationItem =
        FiscalizationItem.builder(itemName, BigDecimal(qty), sum, code).splitReceiverId(receiverId).build()

    private fun finalize(
        invoiceId: String,
        amount: Long,
        vararg items: FiscalizationItem,
    ) = mono.invoices.finalize(FinalizeInvoiceRequest.builder(invoiceId, amount).items(items.toList()).build())

    private fun refund(
        invoiceId: String,
        amount: Long,
        vararg items: FiscalizationItem,
    ) = mono.invoices.cancel(
        CancelInvoiceRequest
            .builder(invoiceId)
            .amount(amount)
            .items(items.toList())
            .build(),
    )

    private fun error(call: () -> Unit): MonobankBadRequestException {
        val error = runCatching { call() }.exceptionOrNull()
        assertThat(error).isInstanceOf(MonobankBadRequestException::class.java)
        return (error as MonobankBadRequestException).also { assertThat(it.errorCode).isEqualTo("1001") }
    }

    @Test
    fun `the live case - an item with another unit sum is not found in the hold`() {
        val hold = create(PaymentType.HOLD)

        val error = error { finalize(hold, 150, item(sum = 150)) }

        assertThat(error.errorText)
            .isEqualTo(
                "finalization basket contains item not found in hold: Kill Bill plugin live test_payment_150_$receiver",
            )
        assertThat(mono.invoices.status(hold).status).isEqualTo(InvoiceStatus.HOLD)
    }

    @Test
    fun `a mismatched name, code or receiver is not found either`() {
        val hold = create(PaymentType.HOLD)

        assertThat(error { finalize(hold, 200, item(itemName = "Other")) }.errorText)
            .endsWith(": Other_payment_200_$receiver")
        assertThat(error { finalize(hold, 200, item(code = "other")) }.errorText)
            .endsWith(": ${name}_other_200_$receiver")
        assertThat(error { finalize(hold, 200, item(receiverId = "sr_other")) }.errorText)
            .endsWith(": ${name}_payment_200_sr_other")
    }

    @Test
    fun `a partial capture with a fractional qty of the same line works (verified live)`() {
        val hold = create(PaymentType.HOLD)

        finalize(hold, 150, item(qty = "0.75"))

        assertThat(mono.invoices.status(hold).status).isEqualTo(InvoiceStatus.PROCESSING)
        fake.settleFinalize(hold)
        val status = mono.invoices.status(hold)
        assertThat(status.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(status.finalAmount).isEqualTo(150)
    }

    @Test
    fun `a wrong total or a qty above the hold is refused (inferred)`() {
        val hold = create(PaymentType.HOLD)

        assertThat(
            error { finalize(hold, 150, item(qty = "0.5")) }.errorText,
        ).isEqualTo("wrong amount for finalization")
        assertThat(error { finalize(hold, 200, item(qty = "2")) }.errorText)
            .isEqualTo("finalization basket item exceeds the hold: ${name}_payment_200_$receiver")
        finalize(hold, 200, item())
    }

    @Test
    fun `refund items are matched against the paid basket (inferred)`() {
        val paid = create(PaymentType.DEBIT)

        assertThat(error { refund(paid, 50, item(qty = "1", sum = 50)) }.errorText)
            .isEqualTo("refund basket contains item not found in invoice: ${name}_payment_50_$receiver")
        assertThat(error { refund(paid, 50, item(qty = "0.5")) }.errorText).isEqualTo("wrong amount for refund")
        assertThat(
            error { refund(paid, 50, item(qty = "3")) }.errorText,
        ).startsWith("refund basket item exceeds the invoice")

        refund(paid, 50, item(qty = "0.25"))
        assertThat(mono.invoices.status(paid).finalAmount).isEqualTo(150)
    }

    @Test
    fun `after a partial capture, refunds match the finalized basket`() {
        val hold = create(PaymentType.HOLD)
        finalize(hold, 150, item(qty = "0.75"))
        fake.settleFinalize(hold)

        assertThat(error { refund(hold, 100, item(qty = "1", sum = 100)) }.errorText)
            .startsWith("refund basket contains item not found in invoice")
        assertThat(error { refund(hold, 100, item(qty = "1")) }.errorText).startsWith("refund basket item exceeds")

        refund(hold, 100, item(qty = "0.5"))
        assertThat(mono.invoices.status(hold).finalAmount).isEqualTo(50)
    }
}
