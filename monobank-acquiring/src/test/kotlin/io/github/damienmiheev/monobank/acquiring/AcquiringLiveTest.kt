package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.subscription.SubscriptionListQuery
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier
import io.github.damienmiheev.monobank.core.CurrencyCodes
import io.github.damienmiheev.monobank.core.MonobankApiException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Calls the real acquiring API. Skipped unless `MONOBANK_ACQUIRING_TEST_TOKEN` is set to a
 * **test** token from https://api.monobank.ua/ (never a production token: this test creates and
 * removes a 1.00 UAH invoice). The read-only list calls are skipped when the token answers
 * 403/404 (feature not enabled).
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

    @Test
    fun `QR cash registers`() = readOnly { mono.qr.list() }

    @Test
    fun `split receivers`() = readOnly { mono.split.receivers() }

    @Test
    fun `Tap-to-Phone terminals`() = readOnly { mono.terminals.list() }

    @Test
    fun `monopay keys`() = readOnly { mono.monopay.keys() }

    @Test
    fun `subscriptions of the last 30 days`() =
        readOnly {
            val page =
                mono.subscriptions.list(
                    SubscriptionListQuery.builder(OffsetDateTime.now().minusDays(30)).limit(5).build(),
                )
            assertThat(page.items.size).isLessThanOrEqualTo(5)
        }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy employee list`() = readOnly { mono.employees.list() }

    /** Runs a read-only call; 403/404 mean the feature is not enabled for this token, so the test is skipped. */
    private fun readOnly(call: () -> Any?) {
        try {
            call()
        } catch (e: MonobankApiException) {
            assumeTrue(e.statusCode != 403 && e.statusCode != 404, "not enabled for this token: HTTP ${e.statusCode}")
            throw e
        }
    }

    private companion object {
        const val TOKEN_ENV = "MONOBANK_ACQUIRING_TEST_TOKEN"
    }
}
