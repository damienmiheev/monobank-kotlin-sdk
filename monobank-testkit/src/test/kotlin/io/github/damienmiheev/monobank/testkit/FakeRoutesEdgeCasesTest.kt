package io.github.damienmiheev.monobank.testkit

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentMethod
import io.github.damienmiheev.monobank.acquiring.common.PaymentSystem
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Holds, refunds, statement mapping, saved cards, hosted page and request records: edge cases. */
class FakeRoutesEdgeCasesTest {
    private val start = Instant.parse("2026-11-12T10:00:00Z")
    private val clock = TestClock.fixed(start)
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .clock(clock)
            .webhookDelivery(WebhookDelivery.MANUAL)
            .merchant("merchant-1", "Shop", "12345678")
            .build()
            .start()
    private val mono: MonobankAcquiring = clientFor(fake)
    private val json = ObjectMapper()

    @AfterEach
    fun close() = fake.close()

    private fun create(
        amount: Long = 4200,
        configure: CreateInvoiceRequest.Builder.() -> Unit = {},
    ): String = mono.invoices.create(CreateInvoiceRequest.builder(amount).apply(configure).build()).invoiceId

    private fun apiError(call: ThrowingCallable): MonobankApiException {
        var caught: Throwable? = null
        try {
            call.call()
        } catch (e: Throwable) {
            caught = e
        }
        assertThat(caught).isInstanceOf(MonobankApiException::class.java)
        return caught as MonobankApiException
    }

    @Test
    fun `a hold cannot be released twice or finalized while a release is pending`() {
        fake.setRefundSettlement(RefundSettlement.MANUAL)
        val invoiceId = create(10_000) { paymentType(PaymentType.HOLD) }
        fake.payInvoice(invoiceId)
        mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).extRef("void-1").build())

        val second = apiError { mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).extRef("void-2").build()) }
        val capture = apiError { mono.invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, 10_000)) }
        fake.settleRefund(invoiceId, "void-1", true)

        assertThat(second.errorCode).isEqualTo("WRONG_CANCEL_AMOUNT")
        assertThat(capture.errorCode).isEqualTo("BAD_REQUEST")
        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(status.finalAmount).isEqualTo(0)
        assertThat(status.cancelList).hasSize(1)
    }

    @Test
    fun `statement - a released or lapsed hold is listed as hold, a refunded payment as success`() {
        val released = create(1000) { paymentType(PaymentType.HOLD) }
        fake.payInvoice(released)
        mono.invoices.cancel(CancelInvoiceRequest.builder(released).build())
        val lapsed = create(2000) { paymentType(PaymentType.HOLD) }
        fake.payInvoice(lapsed)
        val refunded = create(3000)
        fake.payInvoice(refunded)
        mono.invoices.cancel(CancelInvoiceRequest.builder(refunded).build())
        fake.advanceClock(Duration.ofDays(9))

        val items = mono.merchant.statement(start.minusSeconds(60)).associateBy { it.invoiceId }

        assertThat(items.getValue(released).status).isEqualTo(InvoiceStatus.HOLD)
        assertThat(items.getValue(released).profitAmount).isNull()
        assertThat(
            items
                .getValue(released)
                .cancelList!!
                .single()
                .amount,
        ).isEqualTo(1000)
        assertThat(items.getValue(lapsed).status).isEqualTo(InvoiceStatus.HOLD)
        assertThat(items.getValue(refunded).status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(items.getValue(refunded).profitAmount).isEqualTo(3000 - 39)
    }

    @Test
    fun `refund answers can be success or failure at once`() {
        val invoiceId = create(10_000)
        fake.payInvoice(invoiceId)

        fake.setRefundSettlement(RefundSettlement.IMMEDIATE_SUCCESS)
        val ok =
            mono.invoices.cancel(
                CancelInvoiceRequest
                    .builder(invoiceId)
                    .amount(1000)
                    .extRef("ok")
                    .build(),
            )
        fake.setRefundSettlement(RefundSettlement.IMMEDIATE_FAILURE)
        val failed =
            mono.invoices.cancel(
                CancelInvoiceRequest
                    .builder(invoiceId)
                    .amount(1000)
                    .extRef("no")
                    .build(),
            )

        assertThat(ok.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(failed.status).isEqualTo(OperationStatus.FAILURE)
        val status = mono.invoices.status(invoiceId)
        assertThat(status.finalAmount).isEqualTo(9000)
        assertThat(status.cancelFor("no")!!.status).isEqualTo(OperationStatus.FAILURE)
    }

    @Test
    fun `a saved card remembers its network for later charges`() {
        val invoiceId = create { saveCardData(SaveCardData.saveTo("w-mc")) }
        fake.payInvoice(
            invoiceId,
            CustomerPayment
                .builder()
                .maskedPan("537541******1234")
                .paymentSystem("mastercard")
                .build(),
        )
        val token =
            mono.invoices
                .status(invoiceId)
                .walletData!!
                .cardToken!!

        val charge = mono.wallet.payment(WalletPaymentRequest.builder(token, 100, 980, InitiationKind.MERCHANT).build())

        val info = mono.invoices.status(charge.invoiceId).paymentInfo!!
        assertThat(info.paymentSystem).isEqualTo(PaymentSystem.MASTERCARD)
        assertThat(info.maskedPan).isEqualTo("537541******1234")
    }

    @Test
    fun `the hosted page lets a browser pay or decline, then redirects`() {
        val invoiceId = create { redirectUrl("https://shop.example/done") }
        val pageUrl = "/pay/$invoiceId"

        val page = rawCall(fake, "GET", pageUrl, token = null)
        assertThat(page.statusCode()).isEqualTo(200)
        assertThat(page.body()).contains(invoiceId, "Pay", "Decline")

        val paid = rawCall(fake, "POST", pageUrl, "action=pay", token = null)
        assertThat(paid.statusCode()).isEqualTo(303)
        assertThat(paid.headers().firstValue("Location")).hasValue("https://shop.example/done")
        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(rawCall(fake, "POST", pageUrl, "action=pay", token = null).statusCode()).isEqualTo(409)
        assertThat(rawCall(fake, "GET", "/pay/nope", token = null).statusCode()).isEqualTo(404)

        val declined = create()
        assertThat(rawCall(fake, "POST", "/pay/$declined", "action=decline", token = null).statusCode()).isEqualTo(200)
        assertThat(mono.invoices.status(declined).status).isEqualTo(InvoiceStatus.FAILURE)
    }

    @Test
    fun `requests record headers but never the token`() {
        mono.merchant.details()

        val request = fake.requests().single()
        assertThat(request.header("User-Agent")).contains("monobank")
        assertThat(request.header("x-token")).isNull()
        assertThat(request.headers.keys).allMatch { it == it.lowercase() }
        assertThat(request.headers.values).noneMatch { it.contains(fake.token) }
    }
}

class HoldCurrencyTest {
    @org.junit.jupiter.api.Test
    fun `a non-UAH invoice hold gets monobank's live answer`() {
        FakeMonobankAcquiring().start().use { fake ->
            val mono = clientFor(fake)
            org.assertj.core.api.Assertions
                .assertThatThrownBy {
                    mono.invoices.create(
                        io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
                            .builder(100)
                            .ccy(978)
                            .paymentType(io.github.damienmiheev.monobank.acquiring.common.PaymentType.HOLD)
                            .build(),
                    )
                }.isInstanceOfSatisfying(io.github.damienmiheev.monobank.core.MonobankBadRequestException::class.java) {
                    org.assertj.core.api.Assertions
                        .assertThat(it.errorCode)
                        .isEqualTo("1001")
                    org.assertj.core.api.Assertions
                        .assertThat(
                            it.errorText,
                        ).isEqualTo("hold payments are allowed only for UAH")
                }
            val eurDebit =
                mono.invoices.create(
                    io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
                        .builder(100)
                        .ccy(978)
                        .build(),
                )
            org.assertj.core.api.Assertions
                .assertThat(mono.invoices.status(eurDebit.invoiceId).ccy)
                .isEqualTo(978)
        }
    }
}

class AcquiringCurrencyTest {
    @org.junit.jupiter.api.Test
    fun `only UAH, USD and EUR are accepted, like the live API`() {
        FakeMonobankAcquiring().start().use { fake ->
            val mono = clientFor(fake)
            listOf(980, 840, 978).forEach { ccy ->
                val id =
                    mono.invoices
                        .create(
                            io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
                                .builder(100)
                                .ccy(ccy)
                                .build(),
                        ).invoiceId
                org.assertj.core.api.Assertions
                    .assertThat(mono.invoices.status(id).ccy)
                    .isEqualTo(ccy)
            }
            org.assertj.core.api.Assertions
                .assertThatThrownBy {
                    mono.invoices.create(
                        io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
                            .builder(100)
                            .ccy(985)
                            .build(),
                    )
                }.isInstanceOfSatisfying(io.github.damienmiheev.monobank.core.MonobankBadRequestException::class.java) {
                    org.assertj.core.api.Assertions
                        .assertThat(it.errorCode)
                        .isEqualTo("1001")
                    org.assertj.core.api.Assertions
                        .assertThat(it.errorText)
                        .isEqualTo("invalid 'ccy'")
                }
        }
    }
}
