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

/** Every route through the SDK's real client. */
class FakeRoutesTest {
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
    fun `pubkey returns the signer's key and details the merchant`() {
        assertThat(mono.merchant.publicKey().key).isEqualTo(fake.signer.publicKey)
        val details = mono.merchant.details()
        assertThat(details.merchantId).isEqualTo("merchant-1")
        assertThat(details.merchantName).isEqualTo("Shop")
        assertThat(details.edrpou).isEqualTo("12345678")
    }

    @Test
    fun `create returns an id and page, status shows a created invoice with order details`() {
        val response =
            mono.invoices.create(
                CreateInvoiceRequest
                    .builder(4200)
                    .ccy(980)
                    .validity(3600)
                    .withAppUrl(true)
                    .merchantPaymInfo(
                        MerchantPaymInfo
                            .builder()
                            .reference("order-1")
                            .destination("Покупка щастя")
                            .basketOrder(listOf(BasketItem.builder("Табуретка", BigDecimal(2), 2100, "stool").build()))
                            .build(),
                    ).build(),
            )

        assertThat(response.invoiceId).matches("261112[A-Za-z0-9]{14}")
        assertThat(response.pageUrl).isEqualTo("${fake.baseUrl}/pay/${response.invoiceId}")
        assertThat(response.appUrl).isEqualTo("monobank://pay/${response.invoiceId}")
        val status = mono.invoices.status(response.invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.CREATED)
        assertThat(status.amount).isEqualTo(4200)
        assertThat(status.ccy).isEqualTo(980)
        assertThat(status.reference).isEqualTo("order-1")
        assertThat(status.destination).isEqualTo("Покупка щастя")
        assertThat(status.createdDate!!.toInstant()).isEqualTo(start)
        assertThat(status.finalAmount).isNull()
        assertThat(status.paymentInfo).isNull()
        assertThat(status.walletData).isNull()
        assertThat(fake.invoice(response.invoiceId)!!.validUntil).isEqualTo(start.plusSeconds(3600))
    }

    @Test
    fun `create validates like monobank`() {
        // the SDK refuses this one itself; the fake refuses it too
        val holdInUsd =
            rawCall(fake, "POST", MonobankPaths.INVOICE_CREATE, """{"amount":100,"ccy":840,"paymentType":"hold"}""")
        assertThat(holdInUsd.statusCode()).isEqualTo(400)
        assertThat(json.readTree(holdInUsd.body()).path("errCode").asText()).isEqualTo("BAD_REQUEST")

        val negativeValidity =
            apiError { create { merchantPaymInfo(MerchantPaymInfo.builder().reference("r").build()).validity(-5) } }
        assertThat(negativeValidity.statusCode).isEqualTo(400)
        assertThat(negativeValidity.errorCode).isEqualTo("BAD_REQUEST")
        assertThat(negativeValidity.outcome).isEqualTo(RequestOutcome.REJECTED)

        val badBasket =
            apiError {
                create {
                    merchantPaymInfo(
                        MerchantPaymInfo
                            .builder()
                            .basketOrder(listOf(BasketItem.builder("Stool", BigDecimal.ONE, 1000, "stool").build()))
                            .build(),
                    )
                }
            }
        assertThat(badBasket).isInstanceOf(MonobankBadRequestException::class.java)
        assertThat(badBasket.errorCode).isEqualTo("INVALID_MERCHANT_PAYM_INFO")

        val longDestination =
            apiError { create { merchantPaymInfo(MerchantPaymInfo.builder().destination("x".repeat(281)).build()) } }
        assertThat(longDestination.errorCode).isEqualTo("INVALID_MERCHANT_PAYM_INFO")

        val raw = rawCall(fake, "POST", MonobankPaths.INVOICE_CREATE, """{"amount":0}""")
        assertThat(raw.statusCode()).isEqualTo(400)
        assertThat(json.readTree(raw.body()).path("errCode").asText()).isEqualTo("BAD_REQUEST")
        assertThat(rawCall(fake, "POST", MonobankPaths.INVOICE_CREATE, """{"amount":42.5}""").statusCode())
            .isEqualTo(400)
        assertThat(rawCall(fake, "POST", MonobankPaths.INVOICE_CREATE, "not json").statusCode()).isEqualTo(400)
        assertThat(
            rawCall(
                fake,
                "POST",
                MonobankPaths.INVOICE_CREATE,
                """{"amount":0,"paymentType":"verification"}""",
            ).statusCode(),
        ).isEqualTo(400)
        assertThat(fake.invoices()).isEmpty()
    }

    @Test
    fun `validity is clamped to 30 days and defaults to 24 hours`() {
        val long = create { validity(Duration.ofDays(90).seconds) }
        val default = create()

        assertThat(fake.invoice(long)!!.validUntil).isEqualTo(start.plus(Duration.ofDays(30)))
        assertThat(fake.invoice(default)!!.validUntil).isEqualTo(start.plus(Duration.ofHours(24)))
    }

    @Test
    fun `status of an unknown invoice is 404 NOT_FOUND`() {
        val error = apiError { mono.invoices.status("nope") }

        assertThat(error).isInstanceOf(MonobankNotFoundException::class.java)
        assertThat(error.errorCode).isEqualTo("NOT_FOUND")
        assertThat(error.responseBody).isEqualTo("""{"errCode":"NOT_FOUND","errText":"invoice not found"}""")
    }

    @Test
    fun `paying moves the invoice through processing to success with payment info`() {
        val invoiceId = create { merchantPaymInfo(MerchantPaymInfo.builder().reference("order-2").build()) }
        clock.advance(Duration.ofSeconds(5))

        fake.payInvoice(invoiceId)

        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(status.finalAmount).isEqualTo(4200)
        assertThat(status.modifiedDate!!.toInstant()).isAfter(status.createdDate!!.toInstant())
        val info = status.paymentInfo!!
        assertThat(info.maskedPan).isEqualTo("444403******1902")
        assertThat(info.paymentSystem).isEqualTo(PaymentSystem.VISA)
        assertThat(info.paymentMethod).isEqualTo(PaymentMethod.PAN)
        assertThat(info.approvalCode).matches("\\d{6}")
        assertThat(info.rrn).matches("\\d{12}")
        assertThat(info.terminal).isNotBlank()
        assertThat(info.fee).isEqualTo(55) // 1.3 % of 4200, rounded
        assertThat(info.country).isEqualTo("804")
    }

    @Test
    fun `a declined payment is failure with the error code`() {
        val invoiceId = create()

        fake.payInvoice(invoiceId, CustomerPayment.declined("6", "Do not honor"))

        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.FAILURE)
        assertThat(status.errCode).isEqualTo("6")
        assertThat(status.failureReason).isEqualTo("Do not honor")
        assertThat(status.finalAmount).isEqualTo(0)
    }

    @Test
    fun `modifiedDate strictly increases even when the clock stands still`() {
        val invoiceId = create()
        val created = mono.invoices.status(invoiceId).modifiedDate!!
        fake.startPayment(invoiceId)
        val processing = mono.invoices.status(invoiceId).modifiedDate!!
        fake.payInvoice(invoiceId)
        val success = mono.invoices.status(invoiceId).modifiedDate!!

        assertThat(processing).isAfter(created)
        assertThat(success).isAfter(processing)
    }

    @Test
    fun `remove invalidates an unpaid invoice and is refused afterwards or once paying started`() {
        val invoiceId = create()

        mono.invoices.remove(invoiceId)

        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.EXPIRED)
        // removing again is idempotent: still expired, 200
        mono.invoices.remove(invoiceId)
        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.EXPIRED)

        val paying = create()
        fake.startPayment(paying)
        val refused = apiError { mono.invoices.remove(paying) }
        assertThat(refused.errorCode).isEqualTo("BAD_REQUEST")
        assertThat(mono.invoices.status(paying).status).isEqualTo(InvoiceStatus.PROCESSING)
        assertThat(apiError { mono.invoices.remove("nope") }).isInstanceOf(MonobankNotFoundException::class.java)
    }

    @Test
    fun `an unpaid invoice expires when its validity passes`() {
        val invoiceId = create { validity(60) }
        clock.advance(Duration.ofSeconds(59))
        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.CREATED)

        clock.advance(Duration.ofSeconds(1))

        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.EXPIRED)
        assertThatThrownBy { fake.payInvoice(invoiceId) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `hold, then finalize part of it`() {
        val invoiceId = create(10_000) { paymentType(PaymentType.HOLD) }
        fake.payInvoice(invoiceId)
        val held = mono.invoices.status(invoiceId)
        assertThat(held.status).isEqualTo(InvoiceStatus.HOLD)
        assertThat(held.finalAmount).isEqualTo(10_000)
        assertThat(fake.invoice(invoiceId)!!.holdUntil).isEqualTo(start.plus(Duration.ofDays(9)))

        val tooMuch = apiError { mono.invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, 10_001)) }
        assertThat(tooMuch.statusCode).isEqualTo(400)
        val finalized = mono.invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, 7_000))

        assertThat(finalized.status).isEqualTo(OperationStatus.SUCCESS)
        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(status.finalAmount).isEqualTo(7_000)
        assertThat(apiError { mono.invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, 1)) }.errorCode)
            .isEqualTo("BAD_REQUEST")
    }

    @Test
    fun `a hold lapses after 9 days and cannot be finalized then`() {
        val invoiceId = create(10_000) { paymentType(PaymentType.HOLD) }
        fake.payInvoice(invoiceId)
        clock.advance(Duration.ofDays(9).minusSeconds(1))
        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.HOLD)

        clock.advance(Duration.ofSeconds(1))

        val error = apiError { mono.invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, 10_000)) }
        assertThat(error.statusCode).isEqualTo(400)
        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(status.finalAmount).isEqualTo(0)
    }

    @Test
    fun `cancel without an amount releases a hold, with an amount it is refused`() {
        val invoiceId = create(10_000) { paymentType(PaymentType.HOLD) }
        fake.payInvoice(invoiceId)

        val partial = apiError { mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(5_000).build()) }
        assertThat(partial.errorCode).isEqualTo("HOLD_INVOICE_NOT_FINALIZED")
        val released = mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).extRef("void-1").build())

        assertThat(released.status).isEqualTo(OperationStatus.PROCESSING)
        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(status.cancelFor("void-1")!!.status).isEqualTo(OperationStatus.SUCCESS)
    }

    @Test
    fun `partial then full refund lower finalAmount and end reversed`() {
        val invoiceId = create(10_000)
        fake.payInvoice(invoiceId)

        val first =
            mono.invoices.cancel(
                CancelInvoiceRequest
                    .builder(invoiceId)
                    .amount(3_000)
                    .extRef("r-1")
                    .build(),
            )
        assertThat(first.status).isEqualTo(OperationStatus.PROCESSING)
        assertThat(first.createdDate).isNotNull()
        var status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(status.finalAmount).isEqualTo(7_000)
        val refund = status.cancelFor("r-1")!!
        assertThat(refund.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(refund.amount).isEqualTo(3_000)
        assertThat(refund.ccy).isEqualTo(980)

        val retry = apiError { mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(8_000).build()) }
        assertThat(retry.errorCode).isEqualTo("WRONG_CANCEL_AMOUNT")

        mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).extRef("r-2").build())
        status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(status.finalAmount).isEqualTo(0)
        assertThat(status.cancelFor("r-2")!!.amount).isEqualTo(7_000)
        assertThat(status.cancelList).hasSize(2)
        assertThat(apiError { mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).build()) }.errorCode)
            .isEqualTo("WRONG_CANCEL_AMOUNT")
    }

    @Test
    fun `cancel of an unpaid invoice is 500 CANCEL_NOT_AVAILABLE, a definite rejection`() {
        val invoiceId = create()

        val error = apiError { mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).build()) }

        assertThat(error).isInstanceOf(MonobankServerException::class.java)
        assertThat(error.statusCode).isEqualTo(500)
        assertThat(error.errorCode).isEqualTo("CANCEL_NOT_AVAILABLE")
        assertThat(error.outcome).isEqualTo(RequestOutcome.REJECTED)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CANCEL)).isEqualTo(1)
    }

    @Test
    fun `manual refund settlement keeps the refund processing until settled`() {
        fake.setRefundSettlement(RefundSettlement.MANUAL)
        val invoiceId = create(10_000)
        fake.payInvoice(invoiceId)

        mono.invoices.cancel(
            CancelInvoiceRequest
                .builder(invoiceId)
                .amount(4_000)
                .extRef("r-1")
                .build(),
        )
        var status = mono.invoices.status(invoiceId)
        assertThat(status.cancelFor("r-1")!!.status).isEqualTo(OperationStatus.PROCESSING)
        assertThat(status.finalAmount).isEqualTo(10_000)
        // a pending refund counts against what can still be refunded
        assertThat(
            apiError { mono.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(7_000).build()) }.errorCode,
        ).isEqualTo("WRONG_CANCEL_AMOUNT")

        fake.settleRefund(invoiceId, "r-1", success = false)
        status = mono.invoices.status(invoiceId)
        assertThat(status.cancelFor("r-1")!!.status).isEqualTo(OperationStatus.FAILURE)
        assertThat(status.finalAmount).isEqualTo(10_000)

        mono.invoices.cancel(
            CancelInvoiceRequest
                .builder(invoiceId)
                .amount(4_000)
                .extRef("r-2")
                .build(),
        )
        fake.settleRefund(invoiceId, null, success = true)
        assertThat(mono.invoices.status(invoiceId).finalAmount).isEqualTo(6_000)
        assertThatThrownBy { fake.settleRefund(invoiceId, "r-2", true) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `an external refund shows in cancelList without extRef`() {
        val invoiceId = create(10_000)
        fake.payInvoice(invoiceId)

        fake.refundExternally(invoiceId, 2_500)

        val status = mono.invoices.status(invoiceId)
        assertThat(status.finalAmount).isEqualTo(7_500)
        assertThat(status.cancelList!!.single().extRef).isNull()
        assertThat(status.cancelList!!.single().status).isEqualTo(OperationStatus.SUCCESS)
    }

    @Test
    fun `card saving gives a token that wallet lists, charges and deletes`() {
        val invoiceId = create { saveCardData(SaveCardData.saveTo("customer-1")) }
        fake.payInvoice(invoiceId)

        val walletData = mono.invoices.status(invoiceId).walletData!!
        assertThat(walletData.status).isEqualTo(CardTokenStatus.CREATED)
        assertThat(walletData.walletId).isEqualTo("customer-1")
        val token = walletData.cardToken!!
        val cards = mono.wallet.cards("customer-1")
        assertThat(cards).hasSize(1)
        assertThat(cards.single().cardToken).isEqualTo(token)
        assertThat(cards.single().maskedPan).isEqualTo("444403******1902")
        assertThat(cards.single().country).isEqualTo("804")
        assertThat(mono.wallet.cards("someone-else")).isEmpty()

        val payment =
            mono.wallet.payment(
                WalletPaymentRequest
                    .builder(token, 1500, 980, InitiationKind.MERCHANT)
                    .merchantPaymInfo(MerchantPaymInfo.builder().reference("renewal-1").build())
                    .build(),
            )
        assertThat(payment.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(payment.amount).isEqualTo(1500)
        assertThat(payment.ccy).isEqualTo(980)
        assertThat(payment.tdsUrl).isNull()
        val charge = mono.invoices.status(payment.invoiceId)
        assertThat(charge.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(charge.paymentInfo!!.paymentMethod).isEqualTo(PaymentMethod.WALLET)
        assertThat(charge.reference).isEqualTo("renewal-1")

        mono.wallet.deleteCard(token)
        assertThat(mono.wallet.cards("customer-1")).isEmpty()
        assertThat(apiError { mono.wallet.deleteCard(token) }.errorCode).isEqualTo("TOKEN_NOT_FOUND")
        val unknown =
            apiError {
                mono.wallet.payment(
                    WalletPaymentRequest.builder(token, 1500, 980, InitiationKind.MERCHANT).build(),
                )
            }
        assertThat(unknown.statusCode).isEqualTo(400)
        assertThat(unknown.errorCode).isEqualTo("TOKEN_NOT_FOUND")
    }

    @Test
    fun `the same card saved twice into one wallet keeps one token, a verification invoice saves without charging`() {
        val first = create { saveCardData(SaveCardData.saveTo("customer-2")) }
        fake.payInvoice(first)
        val verification = mono.invoices.create(CreateInvoiceRequest.verification("customer-2").build()).invoiceId
        fake.payInvoice(verification)

        val firstToken =
            mono.invoices
                .status(first)
                .walletData!!
                .cardToken
        val verified = mono.invoices.status(verification)
        assertThat(verified.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(verified.amount).isEqualTo(0)
        assertThat(verified.walletData!!.cardToken).isEqualTo(firstToken)
        assertThat(mono.wallet.cards("customer-2")).hasSize(1)
    }

    @Test
    fun `card saving can fail, and a token is not chargeable before it is created`() {
        val invoiceId = create { saveCardData(SaveCardData.saveTo("customer-3")) }
        fake.startPayment(invoiceId)
        val pending = mono.invoices.status(invoiceId).walletData!!
        assertThat(pending.status).isEqualTo(CardTokenStatus.NEW)
        val early =
            apiError {
                mono.wallet.payment(
                    WalletPaymentRequest.builder(pending.cardToken!!, 100, 980, InitiationKind.MERCHANT).build(),
                )
            }
        assertThat(early.errorCode).isEqualTo("TOKEN_NOT_FOUND")

        fake.payInvoice(invoiceId, CustomerPayment.builder().cardSaving(CardSaving.FAILED).build())

        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(status.walletData!!.status).isEqualTo(CardTokenStatus.FAILED)
        assertThat(mono.wallet.cards("customer-3")).isEmpty()
    }

    @Test
    fun `wallet payment outcomes - declined, processing, 3-D Secure, hold`() {
        val token = fake.addCard("customer-4")

        fun charge(type: PaymentType = PaymentType.DEBIT) =
            mono.wallet.payment(
                WalletPaymentRequest.builder(token, 2000, 980, InitiationKind.CLIENT).paymentType(type).build(),
            )

        fake.enqueueWalletPaymentOutcome(WalletPaymentOutcome.declined("6", "Do not honor"))
        val declined = charge()
        assertThat(declined.status).isEqualTo(OperationStatus.FAILURE)
        assertThat(declined.failureReason).isEqualTo("Do not honor")
        assertThat(mono.invoices.status(declined.invoiceId).errCode).isEqualTo("6")

        fake.enqueueWalletPaymentOutcome(WalletPaymentOutcome.processing())
        val processing = charge()
        assertThat(processing.status).isEqualTo(OperationStatus.PROCESSING)
        assertThat(processing.isThreeDsRequired).isFalse()
        fake.payInvoice(processing.invoiceId)
        assertThat(mono.invoices.status(processing.invoiceId).status).isEqualTo(InvoiceStatus.SUCCESS)

        fake.enqueueWalletPaymentOutcome(WalletPaymentOutcome.threeDs())
        val tds = charge()
        assertThat(tds.isThreeDsRequired).isTrue()
        assertThat(tds.tdsUrl).isEqualTo("${fake.baseUrl}/tds/${tds.invoiceId}")
        assertThat(mono.invoices.status(tds.invoiceId).status).isEqualTo(InvoiceStatus.PROCESSING)
        fake.expireInvoice(tds.invoiceId)
        assertThat(mono.invoices.status(tds.invoiceId).status).isEqualTo(InvoiceStatus.EXPIRED)

        val hold = charge(PaymentType.HOLD)
        assertThat(hold.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(mono.invoices.status(hold.invoiceId).status).isEqualTo(InvoiceStatus.HOLD)
        assertThat(fake.invoices().filter { it.viaWallet }).hasSize(4)
    }

    @Test
    fun `statement lists paid invoices in the window, newest first, with reference and refunds`() {
        val created = create { merchantPaymInfo(MerchantPaymInfo.builder().reference("never-paid").build()) }
        val first = create(1000) { merchantPaymInfo(MerchantPaymInfo.builder().reference("ref-1").build()) }
        fake.payInvoice(first)
        clock.advance(Duration.ofMinutes(10))
        val second = create(2000) { merchantPaymInfo(MerchantPaymInfo.builder().reference("ref-2").build()) }
        fake.payInvoice(second)
        mono.invoices.cancel(CancelInvoiceRequest.builder(second).amount(500).build())
        val failed = create(3000)
        fake.failInvoice(failed)

        val all = mono.merchant.statement(start.minusSeconds(60))

        assertThat(all.map { it.invoiceId }).containsExactly(second, failed, first).doesNotContain(created)
        val item = all.first { it.invoiceId == second }
        assertThat(item.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(item.amount).isEqualTo(2000)
        assertThat(item.profitAmount).isEqualTo(2000 - 26)
        assertThat(item.reference).isEqualTo("ref-2")
        assertThat(item.maskedPan).isEqualTo("444403******1902")
        assertThat(item.cancelList!!.single().amount).isEqualTo(500)
        assertThat(all.first { it.invoiceId == failed }.status).isEqualTo(InvoiceStatus.FAILURE)

        assertThat(mono.merchant.statement(start.minusSeconds(60), start.plusSeconds(60)).map { it.invoiceId })
            .containsExactly(first)
        assertThat(
            mono.merchant
                .findByReference("ref-2", start)
                .single()
                .invoiceId,
        ).isEqualTo(second)
        assertThat(mono.merchant.findByReference("never-paid", start)).isEmpty()
        assertThat(rawCall(fake, "GET", MonobankPaths.STATEMENT).statusCode()).isEqualTo(400)
    }

    @Test
    fun `token is required - wrong is 403 FORBIDDEN, missing is 400`() {
        val wrong = apiError { clientFor(fake, token = "stolen").merchant.details() }
        assertThat(wrong).isInstanceOf(MonobankAuthException::class.java)
        assertThat(wrong.statusCode).isEqualTo(403)
        assertThat(wrong.errorCode).isEqualTo("FORBIDDEN")

        val missing = rawCall(fake, "GET", MonobankPaths.DETAILS, token = null)
        assertThat(missing.statusCode()).isEqualTo(400)
        assertThat(json.readTree(missing.body()).path("errCode").asText()).isEqualTo("BAD_REQUEST")
    }

    @Test
    fun `several tokens can be accepted`() {
        FakeMonobankAcquiring.builder().tokens("a", "b").build().start().use { multi ->
            assertThat(multi.token).isEqualTo("a")
            assertThat(clientFor(multi, token = "b").merchant.details().merchantId).isNotBlank()
            assertThat(
                apiError {
                    clientFor(
                        multi,
                        token = FakeMonobankAcquiring.DEFAULT_TOKEN,
                    ).merchant.details()
                }.statusCode,
            ).isEqualTo(403)
        }
    }

    @Test
    fun `unknown path is 404, wrong method is 405, missing query parameters are 400`() {
        val unknown = rawCall(fake, "GET", "/api/merchant/nothing")
        assertThat(unknown.statusCode()).isEqualTo(404)
        assertThat(json.readTree(unknown.body()).path("errCode").asText()).isEqualTo("NOT_FOUND")

        val wrongMethod = rawCall(fake, "POST", MonobankPaths.DETAILS, "{}")
        assertThat(wrongMethod.statusCode()).isEqualTo(405)
        assertThat(json.readTree(wrongMethod.body()).path("errCode").asText()).isEqualTo("METHOD_NOT_ALLOWED")

        for (path in listOf(MonobankPaths.INVOICE_STATUS, MonobankPaths.WALLET)) {
            val missing = rawCall(fake, "GET", path)
            assertThat(missing.statusCode()).isEqualTo(400)
            assertThat(missing.body()).contains("BAD_REQUEST")
        }
        assertThat(rawCall(fake, "DELETE", MonobankPaths.WALLET_CARD).statusCode()).isEqualTo(400)
        assertThat(rawCall(fake, "POST", MonobankPaths.INVOICE_REMOVE, "{}").body())
            .isEqualTo("""{"errCode":"BAD_REQUEST","errText":"empty 'invoiceId'"}""")
        assertThat(contentType()).isEqualTo("application/json")
    }

    private fun contentType(): String? =
        rawCall(fake, "GET", MonobankPaths.DETAILS).headers().firstValue("Content-Type").orElse(null)

    @Test
    fun `requests are recorded without the token and counted per path`() {
        create()
        mono.merchant.details()
        mono.merchant.details()

        assertThat(fake.requestCount(MonobankPaths.DETAILS)).isEqualTo(2)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CREATE)).isEqualTo(1)
        assertThat(fake.requestCount(MonobankPaths.STATEMENT)).isEqualTo(0)
        val request = fake.requests().first()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo(MonobankPaths.INVOICE_CREATE)
        assertThat(json.readTree(request.body).path("amount").asLong()).isEqualTo(4200)
        assertThat(request.receivedAt).isEqualTo(start)
        assertThat(fake.requests().map { it.toString() + it.body + it.query }).noneMatch { it.contains(fake.token) }
        mono.wallet.cards("w-1")
        assertThat(fake.requests().last().query).containsEntry("walletId", "w-1")
    }

    @Test
    fun `reset forgets invoices, cards and records`() {
        create()
        fake.addCard("w")
        fake.reset()

        assertThat(fake.invoices()).isEmpty()
        assertThat(fake.requests()).isEmpty()
        assertThat(mono.wallet.cards("w")).isEmpty()
    }
}
