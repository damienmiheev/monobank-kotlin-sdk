package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Merchants with fiscal receipts and split payments, as monobank's test merchant answered live. */
class FiscalSplitMerchantTest {
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .tokens("fiscal")
            .fiscalization(true)
            .addMerchant(MerchantSetup.builder("split").splitPayments(true).build())
            .addMerchant(
                MerchantSetup
                    .builder("custom-receivers")
                    .splitPayments(true)
                    .splitReceivers(listOf(SplitReceiver.of("sr_1", "ФОП Перший", "1111111111")))
                    .build(),
            ).addMerchant(MerchantSetup.builder("plain").build())
            .build()
            .start()
    private val fiscal = clientFor(fake, token = "fiscal")
    private val split = clientFor(fake, token = "split")
    private val plain = clientFor(fake, token = "plain")

    @AfterEach
    fun close() = fake.close()

    private fun item(
        sum: Long,
        receiver: String? = null,
    ) = BasketItem.builder("Plan", BigDecimal.ONE, sum, "plan").splitReceiverId(receiver).build()

    private fun basket(vararg items: BasketItem) = MerchantPaymInfo.builder().basketOrder(items.toList()).build()

    private fun badRequest(call: () -> Unit): MonobankBadRequestException {
        val error = runCatching { call() }.exceptionOrNull()
        assertThat(error).isInstanceOf(MonobankBadRequestException::class.java)
        return error as MonobankBadRequestException
    }

    private fun MonobankAcquiring.paidInvoice(
        amount: Long,
        type: PaymentType = PaymentType.DEBIT,
        receiver: String? = null,
    ): String {
        val invoiceId =
            invoices
                .create(
                    CreateInvoiceRequest
                        .builder(amount)
                        .paymentType(type)
                        .merchantPaymInfo(basket(item(amount, receiver)))
                        .build(),
                ).invoiceId
        fake.payInvoice(invoiceId)
        return invoiceId
    }

    @Test
    fun `fiscalization - create without a basket, or with an empty one, is INVALID_MERCHANT_PAYM_INFO`() {
        for (info in listOf(null, MerchantPaymInfo.builder().reference("r").build(), basket())) {
            val error =
                badRequest { fiscal.invoices.create(CreateInvoiceRequest.builder(100).merchantPaymInfo(info).build()) }
            assertThat(error.errorCode).isEqualTo("INVALID_MERCHANT_PAYM_INFO")
            assertThat(error.errorText)
                .isEqualTo("'basketOrder' cannot be empty, it is mandatory for fiscalization and split payments")
        }
        assertThat(
            fiscal.invoices
                .create(
                    CreateInvoiceRequest.builder(100).merchantPaymInfo(basket(item(100))).build(),
                ).invoiceId,
        ).isNotBlank()
        // the merchant without these settings keeps the old behaviour
        assertThat(plain.invoices.create(CreateInvoiceRequest.builder(100).build()).invoiceId).isNotBlank()
        // a verification invoice charges nothing: no basket required
        assertThat(fiscal.invoices.create(CreateInvoiceRequest.verification("w").build()).invoiceId).isNotBlank()
    }

    @Test
    fun `split payments - a basket is required and every item needs a splitReceiverId`() {
        val noBasket = badRequest { split.invoices.create(CreateInvoiceRequest.builder(100).build()) }
        assertThat(noBasket.errorText).contains("mandatory for fiscalization and split payments")

        val noReceiver =
            badRequest {
                split.invoices.create(
                    CreateInvoiceRequest
                        .builder(
                            300,
                        ).merchantPaymInfo(basket(item(100, "sr_aB3dC5eF7g"), item(200)))
                        .build(),
                )
            }
        assertThat(noReceiver.errorCode).isEqualTo("INVALID_MERCHANT_PAYM_INFO")
        assertThat(noReceiver.errorText).isEqualTo("'splitReceiverId' is required")

        assertThat(split.paidInvoice(100, receiver = "sr_aB3dC5eF7g")).isNotBlank()
    }

    @Test
    fun `wallet payments follow the same basket rules (inferred)`() {
        val fiscalCard = fake.merchant("fiscal").addCard("customer")
        val splitCard = fake.merchant("split").addCard("customer")

        val noBasket =
            badRequest {
                fiscal.wallet.payment(
                    WalletPaymentRequest.builder(fiscalCard, 100, 980, InitiationKind.MERCHANT).build(),
                )
            }
        val noReceiver =
            badRequest {
                split.wallet.payment(
                    WalletPaymentRequest
                        .builder(
                            splitCard,
                            100,
                            980,
                            InitiationKind.MERCHANT,
                        ).merchantPaymInfo(basket(item(100)))
                        .build(),
                )
            }

        assertThat(noBasket.errorCode).isEqualTo("INVALID_MERCHANT_PAYM_INFO")
        assertThat(noReceiver.errorText).isEqualTo("'splitReceiverId' is required")
        val ok =
            fiscal.wallet.payment(
                WalletPaymentRequest
                    .builder(
                        fiscalCard,
                        100,
                        980,
                        InitiationKind.MERCHANT,
                    ).merchantPaymInfo(basket(item(100)))
                    .build(),
            )
        assertThat(ok.invoiceId).isNotBlank()
    }

    @Test
    fun `a partial finalize needs items, a full one does not`() {
        val partial = fiscal.paidInvoice(200, PaymentType.HOLD)

        val error = badRequest { fiscal.invoices.finalize(FinalizeInvoiceRequest.of(partial, 150)) }

        assertThat(error.errorCode).isEqualTo("1001")
        assertThat(error.errorText).isEqualTo("wrong amount for finalization without basket")
        assertThat(fiscal.invoices.status(partial).status).isEqualTo(InvoiceStatus.HOLD)
        fiscal.invoices.finalize(
            FinalizeInvoiceRequest
                .builder(partial, 150)
                .items(listOf(FiscalizationItem.builder("Plan", BigDecimal("0.75"), 200, "plan").build()))
                .build(),
        )
        assertThat(fiscal.invoices.status(partial).finalAmount).isEqualTo(150)

        val full = fiscal.paidInvoice(200, PaymentType.HOLD)
        fiscal.invoices.finalize(FinalizeInvoiceRequest.of(full, 200))
        assertThat(fiscal.invoices.status(full).status).isEqualTo(InvoiceStatus.SUCCESS)

        // without fiscalization or split, a partial finalize needs no items
        val plainHold =
            plain.invoices
                .create(
                    CreateInvoiceRequest.builder(200).paymentType(PaymentType.HOLD).build(),
                ).invoiceId
        fake.payInvoice(plainHold)
        plain.invoices.finalize(FinalizeInvoiceRequest.of(plainHold, 150))
    }

    @Test
    fun `a partial refund needs items (inferred), a full one does not`() {
        val invoiceId = split.paidInvoice(200, receiver = "sr_aB3dC5eF7g")

        val error = badRequest { split.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(50).build()) }

        assertThat(error.errorCode).isEqualTo("1001")
        split.invoices.cancel(
            CancelInvoiceRequest
                .builder(invoiceId)
                .amount(50)
                .items(
                    listOf(
                        FiscalizationItem
                            .builder(
                                "Plan",
                                BigDecimal("0.25"),
                                200,
                                "plan",
                            ).splitReceiverId("sr_aB3dC5eF7g")
                            .build(),
                    ),
                ).build(),
        )
        assertThat(split.invoices.status(invoiceId).finalAmount).isEqualTo(150)
        // after a partial refund, a refund without `amount` is refused (verified live for saved cards)
        val noAmount = badRequest { split.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).build()) }
        assertThat(noAmount.errorCode).isEqualTo("1001")
        assertThat(noAmount.errorText)
            .isEqualTo("split receiver's part of invoice has already been partially cancelled: sr_aB3dC5eF7g")
        // the rest, with its amount: a full refund of what is left needs no items
        split.invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(150).build())
        assertThat(split.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.REVERSED)
    }

    @Test
    fun `split receiver list - the default receiver, configured receivers, or none`() {
        val default = split.split.receivers().single()
        assertThat(default.id).isEqualTo(SplitReceiver.DEFAULT.id)
        assertThat(default.name).isEqualTo(SplitReceiver.DEFAULT.name)
        assertThat(default.edrpou).isEqualTo(SplitReceiver.DEFAULT.edrpou)

        val custom = clientFor(fake, token = "custom-receivers").split.receivers().single()
        assertThat(custom.id).isEqualTo("sr_1")
        assertThat(custom.name).isEqualTo("ФОП Перший")

        assertThat(fiscal.split.receivers()).isEmpty()
        assertThat(fake.requestCount(MonobankPaths.SPLIT_RECEIVERS)).isEqualTo(3)
    }
}
