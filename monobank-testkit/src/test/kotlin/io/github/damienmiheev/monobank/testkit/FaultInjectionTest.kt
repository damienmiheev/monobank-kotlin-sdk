package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankException
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Every fault mode, seen through the SDK's real client. */
class FaultInjectionTest {
    private val fake = FakeMonobankAcquiring().start()
    private val mono = clientFor(fake)
    private val impatient = clientFor(fake, timeout = Duration.ofMillis(500))

    @AfterEach
    fun close() = fake.close()

    private fun createRequest(reference: String = "order-1") =
        CreateInvoiceRequest
            .builder(4200)
            .merchantPaymInfo(MerchantPaymInfo.builder().reference(reference).build())
            .build()

    private inline fun <reified T : MonobankException> failure(call: () -> Unit): T {
        val error =
            try {
                call()
                null
            } catch (e: MonobankException) {
                e
            }
        assertThat(error).isInstanceOf(T::class.java)
        return error as T
    }

    @Test
    fun `delay - the request is performed and answered late`() {
        fake.injectFault(MonobankPaths.DETAILS, Fault.delay(Duration.ofMillis(300)))

        val started = System.nanoTime()
        mono.merchant.details()

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300))
    }

    @Test
    fun `delay longer than the client timeout - the charge happens but the client sees an unknown outcome`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.delay(Duration.ofSeconds(2)))

        val error = failure<MonobankNetworkException> { impatient.invoices.create(createRequest()) }

        assertThat(error.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        await { fake.invoices().size == 1 }
    }

    @Test
    fun `timeout after processing - unknown outcome, the invoice exists, exactly one request`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.timeout())

        val error = failure<MonobankNetworkException> { impatient.invoices.create(createRequest("ref-timeout")) }

        assertThat(error.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CREATE)).isEqualTo(1)
        assertThat(fake.invoices().single().reference).isEqualTo("ref-timeout")
        // once paid, the payment is found by reference, the plugin's recovery path
        fake.payInvoice(fake.invoices().single().invoiceId)
        assertThat(mono.merchant.findByReference("ref-timeout", Instant.now().minusSeconds(60))).hasSize(1)
    }

    @Test
    fun `timeout of a saved-card charge - exactly one charge at the fake`() {
        val token = fake.addCard("customer")
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeout())

        val error =
            failure<MonobankNetworkException> {
                impatient.wallet.payment(WalletPaymentRequest.builder(token, 100, 980, InitiationKind.MERCHANT).build())
            }

        assertThat(error.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT)).isEqualTo(1)
        assertThat(fake.invoices().single { it.viaWallet }.status).isEqualTo("success")
    }

    @Test
    fun `timeout without processing - nothing happened`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.timeoutWithoutProcessing())

        val error = failure<MonobankNetworkException> { impatient.invoices.create(createRequest()) }

        assertThat(error.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        assertThat(fake.invoices()).isEmpty()
    }

    @Test
    fun `a read that times out is retried by the SDK and then succeeds`() {
        val invoiceId = mono.invoices.create(createRequest()).invoiceId
        fake.injectFault(MonobankPaths.INVOICE_STATUS, Fault.timeoutWithoutProcessing())

        assertThat(impatient.invoices.status(invoiceId).status).isEqualTo(InvoiceStatus.CREATED)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_STATUS)).isEqualTo(2)
    }

    @Test
    fun `dropped connection after processing - unknown outcome, the invoice exists`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.dropConnection())

        val error = failure<MonobankNetworkException> { mono.invoices.create(createRequest()) }

        assertThat(error.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        assertThat(fake.invoices()).hasSize(1)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CREATE)).isEqualTo(1)
    }

    @Test
    fun `dropped connection without processing - nothing happened`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.dropConnectionWithoutProcessing())

        failure<MonobankNetworkException> { mono.invoices.create(createRequest()) }

        assertThat(fake.invoices()).isEmpty()
    }

    @Test
    fun `5xx on a payment is not retried and its outcome is unknown`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.serverError())

        val error = failure<MonobankServerException> { mono.invoices.create(createRequest()) }

        assertThat(error.statusCode).isEqualTo(500)
        assertThat(error.errorCode).isEqualTo("INTERNAL_ERROR")
        assertThat(error.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        assertThat(fake.requestCount(MonobankPaths.INVOICE_CREATE)).isEqualTo(1)
        assertThat(fake.invoices()).isEmpty()
    }

    @Test
    fun `5xx after processing - the invoice exists although the client saw an error`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.serverErrorAfterProcessing())

        failure<MonobankServerException> { mono.invoices.create(createRequest()) }

        assertThat(fake.invoices()).hasSize(1)
    }

    @Test
    fun `5xx on a read is retried by the SDK`() {
        fake.injectFault(MonobankPaths.DETAILS, Fault.serverError(503, "INTERNAL_ERROR", "unavailable"), 2)

        assertThat(mono.merchant.details().merchantId).isNotBlank()
        assertThat(fake.requestCount(MonobankPaths.DETAILS)).isEqualTo(3)

        fake.injectFault(MonobankPaths.DETAILS, Fault.serverError(), 3)
        val error = failure<MonobankServerException> { mono.merchant.details() }
        assertThat(error.statusCode).isEqualTo(500)
    }

    @Test
    fun `429 without Retry-After is retried, even for a payment, since it was not performed`() {
        fake.injectFault(MonobankPaths.INVOICE_CREATE, Fault.tooManyRequests())

        mono.invoices.create(createRequest())

        assertThat(fake.requestCount(MonobankPaths.INVOICE_CREATE)).isEqualTo(2)
        assertThat(fake.invoices()).hasSize(1)
    }

    @Test
    fun `429 with Retry-After is reported with the wait`() {
        fake.injectFault(MonobankPaths.STATEMENT, Fault.tooManyRequests(Duration.ofSeconds(30)))

        val error = failure<MonobankRateLimitException> { mono.merchant.statement(Instant.now().minusSeconds(60)) }

        assertThat(error.retryAfter).isEqualTo(Duration.ofSeconds(30))
        assertThat(error.errorCode).isEqualTo("TMR")
        assertThat(error.outcome).isEqualTo(RequestOutcome.REJECTED)
        assertThat(fake.requestCount(MonobankPaths.STATEMENT)).isEqualTo(1)
    }

    @Test
    fun `429 without Retry-After exhausts retries`() {
        fake.injectFault(MonobankPaths.DETAILS, Fault.tooManyRequests(), 3)
        val noRetry = clientFor(fake, retryPolicy = RetryPolicy.NONE)

        val error = failure<MonobankRateLimitException> { noRetry.merchant.details() }

        assertThat(error.retryAfter).isNull()
        assertThat(fake.requestCount(MonobankPaths.DETAILS)).isEqualTo(1)
    }

    @Test
    fun `nextResponse answers with any status and body without performing the request`() {
        fake.nextResponse(MonobankPaths.INVOICE_CREATE, 400, """{"errCode":"1001","errText":"custom"}""")

        val error = failure<MonobankBadRequestException> { mono.invoices.create(createRequest()) }

        assertThat(error.errorCode).isEqualTo("1001")
        assertThat(error.errorText).isEqualTo("custom")
        assertThat(fake.invoices()).isEmpty()
        assertThat(mono.invoices.create(createRequest()).invoiceId).isNotBlank()
    }

    @Test
    fun `a canned answer can carry headers, such as a trace id`() {
        fake.injectFault(
            MonobankPaths.DETAILS,
            Fault.response(
                500,
                """{"errCode":"INTERNAL_ERROR","errText":"boom"}""",
                mapOf("trace-id" to "trace-1"),
            ),
            3,
        )

        val error = failure<MonobankServerException> { mono.merchant.details() }

        assertThat(error.traceId).isEqualTo("trace-1")
    }

    @Test
    fun `faults for any path, counted, path-specific first, and clearable`() {
        fake.injectFault(MonobankPaths.ANY, Fault.serverError(), 1)
        fake.injectFault(MonobankPaths.PUBKEY, Fault.response(404, """{"errCode":"NOT_FOUND","errText":"x"}"""))

        failure<MonobankException> { clientFor(fake, retryPolicy = RetryPolicy.NONE).merchant.publicKey() }
        failure<MonobankServerException> { clientFor(fake, retryPolicy = RetryPolicy.NONE).merchant.details() }
        assertThat(mono.merchant.details().merchantId).isNotBlank()

        fake.injectFault(MonobankPaths.DETAILS, Fault.serverError(), 5)
        fake.clearFaults()
        assertThat(mono.merchant.details().merchantId).isNotBlank()
    }

    @Test
    fun `fault arguments are validated`() {
        assertThatThrownBy { Fault.serverError(400, "X", "y") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Fault.delay(Duration.ofSeconds(-1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            fake.injectFault(
                "api/x",
                Fault.timeout(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { fake.injectFault(MonobankPaths.DETAILS, Fault.timeout(), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Fault.timeout().toString()).contains("HANG", "processed=true")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(condition()).isTrue()
    }
}
