package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier
import io.github.damienmiheev.monobank.core.CurrencyCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Calls the real acquiring API. Skipped unless `MONOBANK_ACQUIRING_TEST_TOKEN` is set to a
 * **test** token from https://api.monobank.ua/ (never a production token: this test creates and
 * removes a 1.00 UAH invoice).
 *
 * Run: `MONOBANK_ACQUIRING_TEST_TOKEN=... ./gradlew :monobank-acquiring:liveTest`
 */
@Tag("live")
class AcquiringLiveTest {
    private lateinit var mono: MonobankAcquiring

    @BeforeEach
    fun setUp() {
        val token = System.getenv(TOKEN_ENV)
        assumeTrue(!token.isNullOrBlank(), "$TOKEN_ENV is not set")
        mono = MonobankAcquiring(AcquiringConfig.builder(token!!).cmsName("monobank-kotlin-sdk-live-test").build())
    }

    @Test
    fun `merchant details`() {
        val details = mono.merchant.details()
        assertThat(details.merchantId).isNotBlank()
    }

    @Test
    fun `public key parses and the verifier exposes it`() {
        val key = mono.merchant.publicKey().key
        WebhookVerifier.ofPublicKey(key)
        assertThat(mono.webhooks.currentPublicKey()).isEqualTo(key)
        assertThat(WebhookParser.parse("""{"invoiceId":"i","status":"created","amount":1,"ccy":980}""").invoiceId)
            .isEqualTo("i")
    }

    @Test
    fun `create, check and remove a small invoice`() {
        val reference = "sdk-live-" + UUID.randomUUID()
        val created =
            mono.invoices.create(
                CreateInvoiceRequest
                    .builder(100)
                    .ccy(CurrencyCodes.UAH)
                    .validity(600)
                    .merchantPaymInfo(
                        MerchantPaymInfo
                            .builder()
                            .reference(reference)
                            .destination("SDK live test")
                            .build(),
                    ).build(),
            )
        assertThat(created.pageUrl).startsWith("https://")

        val status = mono.invoices.status(created.invoiceId)
        assertThat(status.invoiceId).isEqualTo(created.invoiceId)
        assertThat(status.status).isEqualTo(InvoiceStatus.CREATED)
        assertThat(status.amount).isEqualTo(100)

        mono.invoices.remove(created.invoiceId)
    }

    @Test
    fun `statement for the last day`() {
        val now = Instant.now()
        val items = mono.merchant.statement(now.minus(Duration.ofDays(1)), now)
        assertThat(items).allSatisfy { assertThat(it.invoiceId).isNotBlank() }
    }

    private companion object {
        const val TOKEN_ENV = "MONOBANK_ACQUIRING_TEST_TOKEN"
    }
}
