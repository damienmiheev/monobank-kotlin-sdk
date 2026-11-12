package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier
import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** Several merchants (tenants) behind one base URL, each with its own token, key, invoices and wallets. */
class MultiMerchantTest {
    private val receiver = WebhookReceiver()
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .tokens("tenant-a", "tenant-a-alias")
            .merchant("merchant-a", "Shop A", "11111111")
            .addMerchant(MerchantSetup.builder("tenant-b").details("merchant-b", "Shop B", "22222222").build())
            .build()
            .start()
    private val tenantA = clientFor(fake, token = "tenant-a")
    private val tenantB = clientFor(fake, token = "tenant-b")

    @AfterEach
    fun close() {
        fake.close()
        receiver.close()
    }

    @Test
    fun `each merchant has its own details, key and invoices`() {
        assertThat(tenantA.merchant.details().merchantId).isEqualTo("merchant-a")
        assertThat(tenantB.merchant.details().merchantId).isEqualTo("merchant-b")
        assertThat(clientFor(fake, token = "tenant-a-alias").merchant.details().merchantId).isEqualTo("merchant-a")
        assertThat(tenantA.merchant.publicKey().key).isNotEqualTo(tenantB.merchant.publicKey().key)
        assertThat(fake.merchants().map { it.merchantId }).containsExactly("merchant-a", "merchant-b")
        assertThat(fake.merchant).isSameAs(fake.merchant("tenant-a-alias"))

        val invoiceA = tenantA.invoices.create(CreateInvoiceRequest.builder(100).build()).invoiceId
        fake.payInvoice(invoiceA) // finds the owner by invoice id

        assertThatThrownBy { tenantB.invoices.status(invoiceA) }.isInstanceOf(MonobankNotFoundException::class.java)
        assertThatThrownBy { tenantB.invoices.cancel(CancelInvoiceRequest.builder(invoiceA).build()) }
            .isInstanceOf(MonobankNotFoundException::class.java)
        assertThat(fake.merchant("tenant-b").invoices()).isEmpty()
        assertThat(fake.invoice(invoiceA)!!.merchantId).isEqualTo("merchant-a")
        assertThatThrownBy { fake.merchant("nobody") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `webhooks are signed with the owning merchant's key, and rotation is per merchant`() {
        val b = fake.merchant("tenant-b")
        val invoiceB =
            tenantB.invoices.create(CreateInvoiceRequest.builder(100).webHookUrl(receiver.url).build()).invoiceId
        val keyA = fake.merchant.signer.publicKey
        val newKeyB = b.rotateKey()

        b.payInvoice(invoiceB)

        val webhook = fake.awaitWebhooks(invoiceB, 2, Duration.ofSeconds(5)).last()
        assertThat(webhook.merchantId).isEqualTo("merchant-b")
        assertThat(WebhookVerifier.ofPublicKey(newKeyB).verify(webhook.xSign, webhook.bodyBytes())).isTrue()
        assertThat(WebhookVerifier.ofPublicKey(keyA).verify(webhook.xSign, webhook.bodyBytes())).isFalse()
        assertThat(fake.merchant.signer.publicKey).isEqualTo(keyA)
        assertThat(tenantB.webhooks.verify(webhook.xSign, webhook.bodyBytes())).isTrue()
        assertThat(tenantA.webhooks.verify(webhook.xSign, webhook.bodyBytes())).isFalse()
        val custom = b.sendSignedWebhook(receiver.url, "{}")
        assertThat(WebhookVerifier.ofPublicKey(newKeyB).verify(custom.xSign, custom.bodyBytes())).isTrue()
    }

    @Test
    fun `saved cards belong to one merchant`() {
        val token = fake.merchant("tenant-b").addCard("customer")

        val error =
            runCatching {
                tenantA.wallet.payment(WalletPaymentRequest.builder(token, 100, 980, InitiationKind.MERCHANT).build())
            }.exceptionOrNull() as MonobankApiException
        assertThat(error.errorCode).isEqualTo("TOKEN_NOT_FOUND")
        assertThat(tenantB.wallet.cards("customer")).hasSize(1)
        assertThat(tenantA.wallet.cards("customer")).isEmpty()
    }

    @Test
    fun `tokens and merchant ids must be unique`() {
        assertThatThrownBy {
            FakeMonobankAcquiring
                .builder()
                .tokens("x")
                .addMerchant(MerchantSetup.builder("x").build())
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            FakeMonobankAcquiring
                .builder()
                .merchant("same", "A", "1")
                .addMerchant(MerchantSetup.builder("y").details("same", "B", "2").build())
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
