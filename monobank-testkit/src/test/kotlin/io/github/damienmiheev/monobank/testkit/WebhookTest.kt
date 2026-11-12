package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.acquiring.webhook.VerificationResult
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookKeyProvider
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier
import io.github.damienmiheev.monobank.core.MonobankSignatureException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Webhooks: delivery, signatures (verified with the SDK's WebhookVerifier), and their faults. */
class WebhookTest {
    private val receiver = WebhookReceiver()
    private val clock = TestClock.system()
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .clock(clock)
            .webhookRetries(3, Duration.ofMillis(20))
            .build()
            .start()
    private val mono = clientFor(fake)

    @AfterEach
    fun close() {
        fake.close()
        receiver.close()
    }

    private fun create(configure: CreateInvoiceRequest.Builder.() -> Unit = {}): String =
        mono.invoices
            .create(
                CreateInvoiceRequest
                    .builder(4200)
                    .webHookUrl(receiver.url)
                    .apply(configure)
                    .build(),
            ).invoiceId

    private fun parse(webhook: ReceivedWebhook) = mono.webhooks.verifyAndParse(webhook.xSign, webhook.body)

    @Test
    fun `paying sends processing then success, signed like monobank and equal to the status body`() {
        val invoiceId = create()

        fake.payInvoice(invoiceId)

        val received = receiver.await(2)
        assertThat(received).allSatisfy {
            assertThat(it.contentType).isEqualTo("application/json")
            assertThat(mono.webhooks.check(it.xSign, it.body)).isEqualTo(VerificationResult.VALID)
        }
        assertThat(received.map { parse(it).status }).containsExactly(InvoiceStatus.PROCESSING, InvoiceStatus.SUCCESS)
        val last = parse(received.last())
        assertThat(last).isEqualTo(mono.invoices.status(invoiceId))
        assertThat(last.isNewerThan(parse(received.first()))).isTrue()
        assertThat(received.last().text).isEqualTo(fake.invoice(invoiceId)!!.statusJson)

        val recorded = fake.awaitWebhooks(2, Duration.ofSeconds(5))
        assertThat(recorded.map { it.status }).containsExactly("processing", "success")
        assertThat(recorded).allSatisfy {
            assertThat(it.outcome).isEqualTo(WebhookOutcome.DELIVERED)
            assertThat(it.attempts).isEqualTo(1)
            assertThat(it.responseStatus).isEqualTo(200)
            assertThat(it.url).isEqualTo(receiver.url)
            assertThat(it.invoiceId).isEqualTo(invoiceId)
            assertThat(it.sentAt).isNotNull()
        }
        assertThat(recorded.last().bodyBytes()).isEqualTo(received.last().body)
        assertThat(recorded.last().body).isEqualTo(received.last().text)
    }

    @Test
    fun `card saving sends a separate card webhook moving walletData from new to created`() {
        val invoiceId = create { saveCardData(SaveCardData.saveTo("customer-1")) }

        fake.payInvoice(invoiceId)

        val parsed = receiver.await(3).map { parse(it) }
        assertThat(parsed.map { it.status })
            .containsExactly(InvoiceStatus.PROCESSING, InvoiceStatus.SUCCESS, InvoiceStatus.SUCCESS)
        assertThat(parsed.map { it.walletData!!.status })
            .containsExactly(CardTokenStatus.NEW, CardTokenStatus.NEW, CardTokenStatus.CREATED)
        // the card webhook does not change the invoice: same status and modifiedDate as the payment webhook
        assertThat(parsed[2].modifiedDate).isEqualTo(parsed[1].modifiedDate)
        assertThat(parsed[2].walletData!!.isCardSaved).isTrue()
        assertThat(parsed[2].walletData!!.cardToken).isEqualTo(parsed[0].walletData!!.cardToken)
        assertThat(
            fake.sentWebhooks().map { it.toString() },
        ).noneMatch { it.contains(parsed[2].walletData!!.cardToken!!) }
    }

    @Test
    fun `no webhook for expired, by validity or by remove`() {
        val byValidity = create { validity(60) }
        val removed = create()
        mono.invoices.remove(removed)
        clock.advance(Duration.ofMinutes(2))

        assertThat(mono.invoices.status(byValidity).status).isEqualTo(InvoiceStatus.EXPIRED)
        val paid = create()
        fake.payInvoice(paid)
        val received = receiver.await(2)
        Thread.sleep(100)
        assertThat(receiver.received).hasSize(2)
        assertThat(received.map { parse(it).invoiceId }).containsOnly(paid)
    }

    @Test
    fun `refunds, finalize, hold lapse and wallet charges send webhooks`() {
        val paid = create()
        fake.payInvoice(paid)
        mono.invoices.cancel(
            CancelInvoiceRequest
                .builder(paid)
                .amount(1000)
                .extRef("r-1")
                .build(),
        )
        val refund = parse(receiver.await(3)[2])
        assertThat(refund.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(refund.finalAmount).isEqualTo(3200)
        assertThat(refund.cancelFor("r-1")!!.status).isEqualTo(OperationStatus.SUCCESS)

        val held = create { paymentType(PaymentType.HOLD) }
        fake.payInvoice(held)
        mono.invoices.finalize(FinalizeInvoiceRequest.of(held, 4200))
        assertThat(parse(receiver.await(6)[5]).status).isEqualTo(InvoiceStatus.SUCCESS)

        val lapsing = create { paymentType(PaymentType.HOLD) }
        fake.payInvoice(lapsing)
        receiver.await(8)
        clock.advance(Duration.ofDays(9))
        assertThat(mono.invoices.status(lapsing).status).isEqualTo(InvoiceStatus.REVERSED)
        assertThat(parse(receiver.await(9)[8]).status).isEqualTo(InvoiceStatus.REVERSED)

        val token = fake.addCard("customer")
        mono.wallet.payment(
            WalletPaymentRequest.builder(token, 500, 980, InitiationKind.MERCHANT).webHookUrl(receiver.url).build(),
        )
        assertThat(receiver.await(11).drop(9).map { parse(it).status })
            .containsExactly(InvoiceStatus.PROCESSING, InvoiceStatus.SUCCESS)
    }

    @Test
    fun `processing webhooks can be switched off`() {
        FakeMonobankAcquiring.builder().sendProcessingWebhooks(false).build().start().use { quiet ->
            val client = clientFor(quiet)
            val invoiceId =
                client.invoices
                    .create(
                        CreateInvoiceRequest.builder(100).webHookUrl(receiver.url).build(),
                    ).invoiceId
            quiet.payInvoice(invoiceId)
            assertThat(quiet.awaitWebhooks(1, Duration.ofSeconds(5)).single().status).isEqualTo("success")
        }
    }

    @Test
    fun `manual delivery holds webhooks until delivered, in order or reversed`() {
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        val first = create()
        fake.payInvoice(first)
        assertThat(fake.pendingWebhookCount()).isEqualTo(2)
        Thread.sleep(100)
        assertThat(receiver.received).isEmpty()

        val delivered = fake.deliverWebhooksInReverseOrder()

        assertThat(delivered.map { it.status }).containsExactly("success", "processing")
        assertThat(
            receiver.received.map { parse(it).status },
        ).containsExactly(InvoiceStatus.SUCCESS, InvoiceStatus.PROCESSING)
        val second = create()
        fake.payInvoice(second)
        assertThat(fake.deliverWebhooksNow().map { it.status }).containsExactly("processing", "success")
        assertThat(fake.pendingWebhookCount()).isEqualTo(0)

        val third = create()
        fake.payInvoice(third)
        fake.setWebhookDelivery(WebhookDelivery.AUTOMATIC)
        assertThat(receiver.await(6).takeLast(2).map { parse(it).invoiceId }).containsOnly(third)
    }

    @Test
    fun `dropped webhooks are recorded but never sent`() {
        fake.dropNextWebhooks(1)
        val invoiceId = create()

        fake.payInvoice(invoiceId)

        val recorded = fake.awaitWebhooks(2, Duration.ofSeconds(5))
        assertThat(recorded.map { it.outcome }).containsExactly(WebhookOutcome.DROPPED, WebhookOutcome.DELIVERED)
        assertThat(recorded.first().attempts).isEqualTo(0)
        assertThat(recorded.first().xSign).isNull()
        assertThat(receiver.await(1).map { parse(it).status }).containsExactly(InvoiceStatus.SUCCESS)
    }

    @Test
    fun `duplicated webhooks carry byte-identical bodies`() {
        fake.duplicateNextWebhooks(1)
        val invoiceId = create()

        fake.payInvoice(invoiceId)

        val received = receiver.await(3)
        assertThat(received[0].body).isEqualTo(received[1].body)
        assertThat(received.map { parse(it).status })
            .containsExactly(InvoiceStatus.PROCESSING, InvoiceStatus.PROCESSING, InvoiceStatus.SUCCESS)
        assertThat(
            fake.awaitWebhooks(3, Duration.ofSeconds(5)).map { it.duplicate },
        ).containsExactly(false, true, false)
    }

    @Test
    fun `delayed webhooks arrive after the synchronous answer`() {
        fake.setWebhookDelay(Duration.ofMillis(300))
        val token = fake.addCard("customer")

        val payment =
            mono.wallet.payment(
                WalletPaymentRequest.builder(token, 500, 980, InitiationKind.MERCHANT).webHookUrl(receiver.url).build(),
            )

        assertThat(payment.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(receiver.received).isEmpty()
        assertThat(receiver.await(2).map { parse(it).invoiceId }).containsOnly(payment.invoiceId)
    }

    @Test
    fun `a corrupted signature is rejected by the SDK verifier`() {
        fake.corruptNextWebhookSignatures(1)
        val invoiceId = create()

        fake.payInvoice(invoiceId)

        val received = receiver.await(2)
        assertThat(
            mono.webhooks.check(received[0].xSign, received[0].body),
        ).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThatThrownBy { parse(received[0]) }.isInstanceOf(MonobankSignatureException::class.java)
        assertThat(mono.webhooks.verify(received[1].xSign, received[1].body)).isTrue()
        assertThat(fake.awaitWebhooks(2, Duration.ofSeconds(5)).map { it.signatureValid }).containsExactly(false, true)
    }

    @Test
    fun `retries until the receiver answers 200, at most 3 attempts`() {
        receiver.answerNext(500, 503)
        val invoiceId = create()
        fake.startPayment(invoiceId)

        val first = fake.awaitWebhooks(1, Duration.ofSeconds(5)).single()
        assertThat(first.outcome).isEqualTo(WebhookOutcome.DELIVERED)
        assertThat(first.attempts).isEqualTo(3)
        assertThat(receiver.received).hasSize(3)

        receiver.answerNext(500, 500, 500)
        fake.payInvoice(invoiceId)
        val second = fake.awaitWebhooks(2, Duration.ofSeconds(5))[1]
        assertThat(second.outcome).isEqualTo(WebhookOutcome.FAILED)
        assertThat(second.attempts).isEqualTo(3)
        assertThat(second.responseStatus).isEqualTo(500)
    }

    @Test
    fun `an unreachable receiver is a failed webhook`() {
        val url = receiver.url
        receiver.close()
        val invoiceId = mono.invoices.create(CreateInvoiceRequest.builder(100).webHookUrl(url).build()).invoiceId

        fake.startPayment(invoiceId)

        val webhook = fake.awaitWebhooks(1, Duration.ofSeconds(5)).single()
        assertThat(webhook.outcome).isEqualTo(WebhookOutcome.FAILED)
        assertThat(webhook.responseStatus).isNull()
        assertThat(webhook.attempts).isEqualTo(3)
    }

    @Test
    fun `key rotation - the SDK verifier re-fetches the new key, and a fresh client gets it directly`() {
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        val invoiceId = create()
        fake.startPayment(invoiceId)
        val beforeRotation = fake.deliverWebhooksNow().single()
        // a verifier backed by the SDK's own pubkey call, re-fetching on every mismatch
        val verifier = WebhookVerifier(WebhookKeyProvider { mono.merchant.publicKey().key }, Duration.ZERO)
        assertThat(verifier.verify(beforeRotation.xSign, beforeRotation.bodyBytes())).isTrue()
        assertThat(mono.webhooks.verify(beforeRotation.xSign, beforeRotation.bodyBytes())).isTrue()

        val newKey = fake.rotateKey()
        fake.payInvoice(invoiceId)
        val afterRotation = fake.deliverWebhooksNow().single()

        assertThat(mono.merchant.publicKey().key).isEqualTo(newKey)
        assertThat(
            WebhookVerifier.ofPublicKey(newKey).verify(beforeRotation.xSign, beforeRotation.bodyBytes()),
        ).isFalse()
        assertThat(verifier.check(afterRotation.xSign, afterRotation.bodyBytes())).isEqualTo(VerificationResult.VALID)
        assertThat(verifier.currentPublicKey()).isEqualTo(newKey)
        // the previous key stays accepted for webhooks signed just before the rotation
        assertThat(verifier.verify(beforeRotation.xSign, beforeRotation.bodyBytes())).isTrue()
        // a client that has not cached the old key verifies with the new one at once
        val fresh = clientFor(fake)
        assertThat(fresh.webhooks.verify(afterRotation.xSign, afterRotation.bodyBytes())).isTrue()
        assertThat(fresh.webhooks.currentPublicKey()).isEqualTo(newKey)
        assertThat(WebhookParser.parse(afterRotation.body).status).isEqualTo(InvoiceStatus.SUCCESS)
    }

    @Test
    fun `invoices without webHookUrl send nothing`() {
        val invoiceId = mono.invoices.create(CreateInvoiceRequest.builder(100).build()).invoiceId

        fake.payInvoice(invoiceId)

        assertThat(fake.sentWebhooks()).isEmpty()
        assertThat(fake.pendingWebhookCount()).isEqualTo(0)
        assertThatThrownBy {
            fake.awaitWebhooks(
                1,
                Duration.ofMillis(50),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
