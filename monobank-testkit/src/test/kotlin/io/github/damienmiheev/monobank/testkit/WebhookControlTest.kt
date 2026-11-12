package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.webhook.VerificationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Selective webhook control: by kind and invoice, replay, hand-built bodies, scheduling on mode switches. */
class WebhookControlTest {
    private val receiver = WebhookReceiver()
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .webhookRetries(3, Duration.ofMillis(20))
            .build()
            .start()
    private val mono = clientFor(fake)
    private val timeout = Duration.ofSeconds(5)

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

    @Test
    fun `a lost card webhook, with automatic delivery - payment webhooks arrive, the card one is dropped`() {
        fake.dropNextWebhooks(1) { it.kind == WebhookKind.CARD }
        val invoiceId = create { saveCardData(SaveCardData.saveTo("customer")) }

        fake.payInvoice(invoiceId)

        val recorded = fake.awaitWebhooks(invoiceId, 3, timeout)
        assertThat(recorded.map { it.kind }).containsExactly(WebhookKind.PAYMENT, WebhookKind.PAYMENT, WebhookKind.CARD)
        assertThat(recorded.map { it.outcome })
            .containsExactly(WebhookOutcome.DELIVERED, WebhookOutcome.DELIVERED, WebhookOutcome.DROPPED)
        assertThat(recorded.map { it.walletStatus }).containsExactly("new", "new", "created")
        // the card is still saved: polling finds it
        assertThat(
            mono.invoices
                .status(invoiceId)
                .walletData!!
                .status,
        ).isEqualTo(CardTokenStatus.CREATED)
        assertThat(receiver.received).hasSize(2)
    }

    @Test
    fun `manual delivery - drop or deliver queued webhooks selectively`() {
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        val first = create { saveCardData(SaveCardData.saveTo("customer")) }
        val second = create()
        fake.payInvoice(first)
        fake.payInvoice(second)
        assertThat(fake.pendingWebhooks().map { it.invoiceId to it.kind }).containsExactly(
            first to WebhookKind.PAYMENT,
            first to WebhookKind.PAYMENT,
            first to WebhookKind.CARD,
            second to WebhookKind.PAYMENT,
            second to WebhookKind.PAYMENT,
        )

        val dropped = fake.dropPendingWebhooks { it.kind == WebhookKind.CARD }
        val delivered = fake.deliverPendingWebhooks { it.invoiceId == second }

        assertThat(dropped.single().outcome).isEqualTo(WebhookOutcome.DROPPED)
        assertThat(delivered.map { it.invoiceId }).containsOnly(second).hasSize(2)
        assertThat(fake.pendingWebhooks().map { it.status }).containsExactly("processing", "success")
        assertThat(fake.pendingWebhooks().first().toString()).contains(first)
        assertThat(fake.deliverWebhooksNow()).hasSize(2)
    }

    @Test
    fun `duplicates and corrupt signatures can target one kind`() {
        fake.duplicateNextWebhooks(1) { it.status == "success" }
        fake.corruptNextWebhookSignatures(1) { it.kind == WebhookKind.CARD }
        val invoiceId = create { saveCardData(SaveCardData.saveTo("customer")) }

        fake.payInvoice(invoiceId)

        val recorded = fake.awaitWebhooks(invoiceId, 4, timeout)
        assertThat(recorded.map { it.status to it.duplicate })
            .containsExactly("processing" to false, "success" to false, "success" to true, "success" to false)
        assertThat(recorded.map { it.signatureValid }).containsExactly(true, true, true, false)
    }

    @Test
    fun `redeliver sends the same bytes later, e_g_ after the receiver was down`() {
        receiver.answerNext(503, 503, 503)
        val invoiceId = create()
        fake.startPayment(invoiceId)
        val failed = fake.awaitWebhooks(invoiceId, 1, timeout).single()
        assertThat(failed.outcome).isEqualTo(WebhookOutcome.FAILED)

        val again = fake.redeliver(failed)

        assertThat(again.outcome).isEqualTo(WebhookOutcome.DELIVERED)
        assertThat(again.duplicate).isTrue()
        assertThat(again.bodyBytes()).isEqualTo(failed.bodyBytes())
        assertThat(receiver.received.last().body).isEqualTo(failed.bodyBytes())
        assertThat(mono.webhooks.check(again.xSign, again.bodyBytes())).isEqualTo(VerificationResult.VALID)
    }

    @Test
    fun `hand-built webhooks - signed with the merchant's key, or with any X-Sign`() {
        val body = """{"invoiceId":"unknown","status":"success","amount":1,"ccy":980,"reference":"nobody"}"""

        val signed = fake.sendSignedWebhook(receiver.url, body)
        val unsigned = fake.sendWebhook(receiver.url, body, null)
        val garbage = fake.sendWebhook(receiver.url, body, "not base64!")

        assertThat(signed.kind).isEqualTo(WebhookKind.CUSTOM)
        assertThat(signed.invoiceId).isEqualTo("unknown")
        assertThat(signed.status).isEqualTo("success")
        val received = receiver.await(3)
        assertThat(mono.webhooks.check(received[0].xSign, received[0].body)).isEqualTo(VerificationResult.VALID)
        assertThat(received[1].xSign).isNull()
        assertThat(received[2].xSign).isEqualTo("not base64!")
        assertThat(mono.webhooks.check(received[2].xSign, received[2].body))
            .isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(listOf(unsigned, garbage).map { it.outcome }).containsOnly(WebhookOutcome.DELIVERED)
    }

    @Test
    fun `switching to manual holds back scheduled webhooks, reset discards them`() {
        fake.setWebhookDelay(Duration.ofMillis(300))
        val held = create()
        fake.payInvoice(held)
        fake.setWebhookDelivery(WebhookDelivery.MANUAL)
        Thread.sleep(600)
        assertThat(receiver.received).isEmpty()
        assertThat(fake.deliverWebhooksInReverseOrder().map { it.status }).containsExactly("success", "processing")

        fake.setWebhookDelivery(WebhookDelivery.AUTOMATIC)
        val discarded = create()
        fake.payInvoice(discarded)
        fake.reset()
        Thread.sleep(600)
        assertThat(receiver.received).hasSize(2)
        assertThat(fake.sentWebhooks()).isEmpty()
    }

    @Test
    fun `advanceClock applies due changes - a hold lapses and its webhook is sent`() {
        val invoiceId = create { paymentType(PaymentType.HOLD) }
        fake.payInvoice(invoiceId)
        fake.awaitWebhooks(invoiceId, 2, timeout)

        fake.advanceClock(Duration.ofDays(9))

        assertThat(fake.awaitWebhooks(invoiceId, 3, timeout).last().status).isEqualTo("reversed")
        assertThat(mono.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.REVERSED)
    }

    @Test
    fun `applyDueChanges after moving a shared clock`() {
        val invoiceId = create { paymentType(PaymentType.HOLD) }
        fake.payInvoice(invoiceId)
        fake.clock.advance(Duration.ofDays(10))

        fake.applyDueChanges()

        assertThat(fake.invoice(invoiceId)!!.status).isEqualTo("reversed")
    }

    @Test
    fun `waiting by invoice ignores other invoices' webhooks`() {
        val mine = create()
        val other = create()
        fake.payInvoice(other)
        fake.awaitWebhooks(other, 2, timeout)

        fake.startPayment(mine)

        assertThat(fake.awaitWebhooks(mine, 1, timeout).single().invoiceId).isEqualTo(mine)
        assertThat(fake.awaitWebhooks(1, timeout) { it.invoiceId == mine && it.status == "processing" }).hasSize(1)
    }
}
