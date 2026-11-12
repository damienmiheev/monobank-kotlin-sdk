package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

/**
 * The SDK's live hold test on monobank's test merchant (fiscal receipts and split payments on): hold 2.00,
 * capture 1.50 as 0.75 × 2.00, a settling window, then refund 0.50 as 0.25 × 2.00, which leaves the
 * invoice `reversed` with 1.00 left.
 */
class CaptureSettlementTest {
    private val receiverId = "sr_aB3dC5eF7g"
    private val webhooks = WebhookReceiver()
    private val timeout = Duration.ofSeconds(5)

    private fun fake(delay: Duration?) =
        FakeMonobankAcquiring
            .builder()
            .fiscalization(true)
            .splitPayments(true)
            .refundSettlement(RefundSettlement.IMMEDIATE_SUCCESS) // the live answer was `success`
            .finalizeSettlementDelay(delay)
            .build()
            .start()

    @AfterEach
    fun close() = webhooks.close()

    private fun item(qty: String) =
        FiscalizationItem.builder("Plan", BigDecimal(qty), 200, "plan").splitReceiverId(receiverId).build()

    private fun hold(fake: FakeMonobankAcquiring): String {
        val mono = clientFor(fake)
        val basket = BasketItem.builder("Plan", BigDecimal.ONE, 200, "plan").splitReceiverId(receiverId).build()
        val invoiceId =
            mono.invoices
                .create(
                    CreateInvoiceRequest
                        .builder(200)
                        .paymentType(PaymentType.HOLD)
                        .webHookUrl(webhooks.url)
                        .merchantPaymInfo(MerchantPaymInfo.builder().basketOrder(listOf(basket)).build())
                        .build(),
                ).invoiceId
        fake.payInvoice(invoiceId)
        return invoiceId
    }

    @Test
    fun `partial capture stays processing, refunds answer 1004 until it settles by hand`() {
        fake(null).use { fake ->
            val mono = clientFor(fake)
            val invoiceId = hold(fake)

            val answer =
                mono.invoices.finalize(
                    FinalizeInvoiceRequest.builder(invoiceId, 150).items(listOf(item("0.75"))).build(),
                )

            assertThat(answer.status).isEqualTo(OperationStatus.SUCCESS)
            val settling = mono.invoices.status(invoiceId)
            assertThat(settling.status).isEqualTo(InvoiceStatus.PROCESSING)
            assertThat(settling.finalAmount).isEqualTo(150)
            assertThatThrownBy {
                mono.invoices.cancel(
                    CancelInvoiceRequest
                        .builder(invoiceId)
                        .amount(50)
                        .items(listOf(item("0.25")))
                        .build(),
                )
            }.isInstanceOfSatisfying(MonobankNotFoundException::class.java) {
                assertThat(it.errorCode).isEqualTo("1004")
                assertThat(it.errorText).isEqualTo("order not finished")
            }
            assertThatThrownBy { fake.expireInvoice(invoiceId) }.isInstanceOf(IllegalStateException::class.java)

            fake.settleFinalize(invoiceId)

            assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.SUCCESS)
            val statuses = fake.awaitWebhooks(invoiceId, 4, timeout).map { it.status }
            assertThat(statuses).containsExactly("processing", "hold", "processing", "success")
            assertThatThrownBy { fake.settleFinalize(invoiceId) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `partial capture settles by itself after the configured delay`() {
        fake(Duration.ofMillis(300)).use { fake ->
            val mono = clientFor(fake)
            val invoiceId = hold(fake)

            mono.invoices.finalize(FinalizeInvoiceRequest.builder(invoiceId, 150).items(listOf(item("0.75"))).build())

            val settled = fake.awaitWebhooks(invoiceId, 4, timeout)
            assertThat(settled.map { it.status }).containsExactly("processing", "hold", "processing", "success")
            assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.SUCCESS)
        }
    }

    @Test
    fun `a full capture is success at once`() {
        fake(null).use { fake ->
            val mono = clientFor(fake)
            val invoiceId = hold(fake)

            mono.invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, 200))

            assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.SUCCESS)
        }
    }

    @Test
    fun `a partial refund makes the invoice reversed with the rest in finalAmount, and it can be refunded further`() {
        fake(null).use { fake ->
            val mono = clientFor(fake)
            val invoiceId = hold(fake)
            mono.invoices.finalize(FinalizeInvoiceRequest.builder(invoiceId, 150).items(listOf(item("0.75"))).build())
            fake.settleFinalize(invoiceId)

            val refund =
                mono.invoices.cancel(
                    CancelInvoiceRequest
                        .builder(
                            invoiceId,
                        ).amount(50)
                        .extRef("r-1")
                        .items(listOf(item("0.25")))
                        .build(),
                )

            assertThat(refund.status).isEqualTo(OperationStatus.SUCCESS)
            assertThat(refund.createdDate).isNotNull()
            assertThat(refund.modifiedDate).isNotNull()
            val status = mono.invoices.status(invoiceId)
            assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
            assertThat(status.finalAmount).isEqualTo(100)
            val cancel = status.cancelList!!.single()
            assertThat(cancel.status).isEqualTo(OperationStatus.SUCCESS)
            assertThat(cancel.amount).isEqualTo(50)
            assertThat(fake.awaitWebhooks(invoiceId, 5, timeout).last().status).isEqualTo("reversed")
            // the statement keeps showing the captured payment as success
            assertThat(
                mono.merchant
                    .statement(
                        java.time.Instant
                            .now()
                            .minusSeconds(60),
                    ).single()
                    .status,
            ).isEqualTo(InvoiceStatus.SUCCESS)

            mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).build())
            val emptied = mono.invoices.status(invoiceId)
            assertThat(emptied.status).isEqualTo(InvoiceStatus.REVERSED)
            assertThat(emptied.finalAmount).isEqualTo(0)
            assertThat(emptied.cancelList).hasSize(2)
        }
    }
}
