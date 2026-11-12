package io.github.damienmiheev.monobank.testkit

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentMethod
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The live trap from the Kill Bill plugin's saved-card hold: the `wallet/payment` answer's
 * `modifiedDate` has microseconds, the `hold` status that follows at once has whole seconds, so the
 * older answer looks newer unless dates are compared to the second.
 */
class DatePrecisionTest {
    private val receiver = WebhookReceiver()
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .clock(TestClock.fixed(Instant.parse("2026-11-12T17:04:33.525289Z")))
            .build()
            .start()
    private val mono = clientFor(fake)
    private val json = ObjectMapper()

    @AfterEach
    fun close() {
        fake.close()
        receiver.close()
    }

    @Test
    fun `a saved-card hold answers processing with microseconds, the status is hold with whole seconds`() {
        val card = fake.addCard("customer")
        val body =
            """{"cardToken":"$card","amount":200,"ccy":980,"initiationKind":"merchant",""" +
                """"paymentType":"hold","webHookUrl":"${receiver.url}"}"""

        val raw = rawCall(fake, "POST", MonobankPaths.WALLET_PAYMENT, body)

        val answerJson = json.readTree(raw.body())
        assertThat(answerJson.path("status").asText()).isEqualTo("processing")
        assertThat(answerJson.has("tdsUrl")).isFalse()
        assertThat(answerJson.path("modifiedDate").asText()).isEqualTo("2026-11-12T19:04:33.525289+02:00")
        assertThat(answerJson.path("createdDate").asText()).isEqualTo("2026-11-12T19:04:33.525289+02:00")
        val invoiceId = answerJson.path("invoiceId").asText()

        val status = mono.invoices.status(invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.HOLD)
        assertThat(status.paymentInfo!!.paymentMethod).isEqualTo(PaymentMethod.WALLET)
        assertThat(status.paymentInfo!!.fee).isEqualTo(3) // 1.3 % of 200, the fake's fee rule
        val statusJson = json.readTree(fake.invoice(invoiceId)!!.statusJson)
        assertThat(statusJson.path("payMethod").asText()).isEqualTo("wallet")
        assertThat(statusJson.path("modifiedDate").asText()).isEqualTo("2026-11-12T17:04:33Z")

        // the trap: compared exactly, the older answer looks newer than the hold status
        val answer = WebhookParser.parse(raw.body())
        assertThat(answer.modifiedDate!!.toInstant()).isAfter(status.modifiedDate!!.toInstant())
        // compared to the second, as the SDK does, the status is not older and the answer not newer
        assertThat(status.isOlderThan(answer)).isFalse()
        assertThat(answer.isNewerThan(status)).isFalse()

        // the hold webhook is sent with whole seconds too
        val hold = fake.awaitWebhooks(invoiceId, 2, Duration.ofSeconds(5)).last()
        assertThat(hold.status).isEqualTo("hold")
        assertThat(WebhookParser.parse(hold.body).modifiedDate!!.nano).isZero()
    }

    @Test
    fun `through the SDK - the payment answer says processing for a hold, success for a debit`() {
        val card = fake.addCard("customer")

        fun charge(type: PaymentType) =
            mono.wallet.payment(
                WalletPaymentRequest.builder(card, 200, 980, InitiationKind.MERCHANT).paymentType(type).build(),
            )

        val hold = charge(PaymentType.HOLD)
        val debit = charge(PaymentType.DEBIT)

        assertThat(hold.status).isEqualTo(OperationStatus.PROCESSING)
        assertThat(hold.isThreeDsRequired).isFalse()
        assertThat(hold.modifiedDate!!.nano).isEqualTo(525_289_000)
        assertThat(mono.invoices.status(hold.invoiceId).status).isEqualTo(InvoiceStatus.HOLD)
        assertThat(debit.status).isEqualTo(OperationStatus.SUCCESS)
    }
}
