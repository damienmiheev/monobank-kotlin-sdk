package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Concurrency, start/stop, threads and the clock. */
class LifecycleTest {
    private fun fakeThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("fake-monobank") }

    @Test
    fun `serves concurrent requests consistently`() {
        FakeMonobankAcquiring().start().use { fake ->
            val mono = clientFor(fake)
            val token = fake.addCard("customer")
            val pool = Executors.newFixedThreadPool(16)
            try {
                val created =
                    pool
                        .invokeAll(
                            (1..200).map { i ->
                                Callable {
                                    val ref = "order-$i"
                                    val invoiceId =
                                        mono.invoices
                                            .create(
                                                CreateInvoiceRequest
                                                    .builder(1000)
                                                    .merchantPaymInfo(MerchantPaymInfo.builder().reference(ref).build())
                                                    .build(),
                                            ).invoiceId
                                    fake.payInvoice(invoiceId)
                                    mono.wallet.payment(
                                        WalletPaymentRequest.builder(token, 10, 980, InitiationKind.MERCHANT).build(),
                                    )
                                    invoiceId
                                }
                            },
                        ).map { it.get() }

                assertThat(created.toSet()).hasSize(200)
                assertThat(fake.invoices()).hasSize(400)
                assertThat(fake.requestCount(MonobankPaths.INVOICE_CREATE)).isEqualTo(200)
                assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT)).isEqualTo(200)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `concurrent refunds never refund more than was paid`() {
        FakeMonobankAcquiring().start().use { fake ->
            val mono = clientFor(fake)
            val invoiceId = mono.invoices.create(CreateInvoiceRequest.builder(1000).build()).invoiceId
            fake.payInvoice(invoiceId)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val results =
                    pool
                        .invokeAll(
                            (1..20).map {
                                Callable {
                                    try {
                                        mono.invoices.cancel(
                                            CancelInvoiceRequest.builder(invoiceId).amount(100).build(),
                                        )
                                        true
                                    } catch (e: MonobankApiException) {
                                        assertThat(e.errorCode).isIn("WRONG_CANCEL_AMOUNT", "CANCEL_NOT_AVAILABLE")
                                        false
                                    }
                                }
                            },
                        ).map { it.get() }

                assertThat(results.count { it }).isEqualTo(10)
                assertThat(mono.invoices.status(invoiceId).finalAmount).isEqualTo(0)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `close stops every thread, also with hanging requests and pending webhooks`() {
        val receiver = WebhookReceiver()
        val fake =
            FakeMonobankAcquiring
                .builder()
                .webhookDelay(Duration.ofSeconds(30))
                .build()
                .start()
        val mono = clientFor(fake, timeout = Duration.ofMillis(300))
        val invoiceId =
            mono.invoices
                .create(
                    CreateInvoiceRequest.builder(100).webHookUrl(receiver.url).build(),
                ).invoiceId
        fake.payInvoice(invoiceId)
        fake.injectFault(MonobankPaths.DETAILS, Fault.timeout(), 3)
        assertThatThrownBy { mono.merchant.details() }.isInstanceOf(MonobankNetworkException::class.java)
        assertThat(fakeThreads()).isNotEmpty()
        assertThat(fake.pendingWebhookCount()).isEqualTo(2)

        val started = System.nanoTime()
        fake.close()
        fake.close() // idempotent

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5))
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (fakeThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(fakeThreads()).isEmpty()
        assertThat(receiver.received).isEmpty()
        assertThatThrownBy { mono.merchant.details() }.isInstanceOf(MonobankNetworkException::class.java)
        assertThatThrownBy { fake.start() }.isInstanceOf(IllegalStateException::class.java)
        receiver.close()
    }

    @Test
    fun `start once, port and baseUrl only after start, fixed port supported`() {
        val fake = FakeMonobankAcquiring.builder().build()
        assertThatThrownBy { fake.baseUrl }.isInstanceOf(IllegalStateException::class.java)
        assertThat(fake.toString()).contains("not started")
        fake.start().use {
            assertThat(fake.baseUrl).isEqualTo("http://127.0.0.1:${fake.port}")
            assertThatThrownBy { fake.start() }.isInstanceOf(IllegalStateException::class.java)
            assertThat(fake.toString()).contains(fake.baseUrl).doesNotContain(fake.token)
            assertThat(fake.clientOptions().baseUrl.toString()).isEqualTo(fake.baseUrl)
        }

        val free = ServerSocket(0).use { it.localPort }
        FakeMonobankAcquiring.builder().port(free).build().start().use { fixed ->
            assertThat(fixed.port).isEqualTo(free)
            assertThat(clientFor(fixed).merchant.details().merchantId).isNotBlank()
        }
    }

    @Test
    fun `test clock - system follows real time with an offset, fixed stands still`() {
        val system = TestClock.system()
        system.advance(Duration.ofDays(1))
        assertThat(
            Duration.between(Instant.now(), system.instant()),
        ).isBetween(Duration.ofHours(23), Duration.ofHours(25))
        system.set(Instant.parse("2030-01-01T00:00:00Z"))
        assertThat(
            system.instant(),
        ).isBetween(Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:01:00Z"))

        val fixed = TestClock.fixed(Instant.parse("2026-01-01T00:00:00Z"))
        Thread.sleep(5)
        assertThat(fixed.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        fixed.advance(Duration.ofSeconds(10))
        assertThat(
            fixed.withZone(java.time.ZoneId.of("Europe/Kyiv")).instant(),
        ).isEqualTo(Instant.parse("2026-01-01T00:00:10Z"))
        assertThatThrownBy { fixed.advance(Duration.ofSeconds(-1)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `control calls on unknown invoices and invalid arguments fail fast`() {
        FakeMonobankAcquiring().start().use { fake ->
            assertThatThrownBy { fake.payInvoice("nope") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(fake.invoice("nope")).isNull()
            assertThatThrownBy { fake.dropNextWebhooks(0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { FakeMonobankAcquiring.builder().tokens() }.isInstanceOf(
                IllegalArgumentException::class.java,
            )
            val invoiceId = clientFor(fake).invoices.create(CreateInvoiceRequest.builder(100).build()).invoiceId
            assertThatThrownBy { fake.refundExternally(invoiceId, 10) }.isInstanceOf(IllegalStateException::class.java)
            fake.payInvoice(invoiceId)
            assertThatThrownBy { fake.refundExternally(invoiceId, 101) }.isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { fake.expireInvoice(invoiceId) }.isInstanceOf(IllegalStateException::class.java)
            assertThat(fake.invoice(invoiceId).toString()).contains(invoiceId, "success")
        }
    }

    @Test
    fun `a port in use fails start with an unchecked exception`() {
        FakeMonobankAcquiring().start().use { first ->
            val second = FakeMonobankAcquiring.builder().port(first.port).build()
            assertThatThrownBy { second.start() }
                .isInstanceOf(java.io.UncheckedIOException::class.java)
                .hasMessageContaining(first.port.toString())
            second.close()
        }
    }

    @Test
    fun `an interrupted wait for webhooks throws unchecked and keeps the interrupt`() {
        FakeMonobankAcquiring().start().use { fake ->
            Thread.currentThread().interrupt()
            assertThatThrownBy { fake.awaitWebhooks(1, Duration.ofSeconds(5)) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(Thread.interrupted()).isTrue()
        }
    }

    @Test
    fun `close cuts off a webhook delivery to a receiver that hangs`() {
        val hanging = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val acceptor = Thread { runCatching { while (true) accepted.add(hanging.accept()) } }.apply { start() }
        val fake =
            FakeMonobankAcquiring
                .builder()
                .webhookTimeout(Duration.ofSeconds(30))
                .build()
                .start()
        try {
            val url = "http://127.0.0.1:${hanging.localPort}/notification"
            val invoiceId =
                clientFor(fake).invoices.create(CreateInvoiceRequest.builder(100).webHookUrl(url).build()).invoiceId
            fake.startPayment(invoiceId)
            Thread.sleep(300) // the delivery is now waiting for an answer

            val started = System.nanoTime()
            fake.close()

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5))
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (fakeThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertThat(fakeThreads()).isEmpty()
        } finally {
            fake.close()
            hanging.close()
            acceptor.join(1000)
            accepted.forEach { it.close() }
        }
    }

    @Test
    fun `a seed makes ids reproducible`() {
        fun ids(): List<String> =
            FakeMonobankAcquiring
                .builder()
                .seed(42)
                .clock(TestClock.fixed(Instant.parse("2026-01-01T00:00:00Z")))
                .build()
                .start()
                .use { fake ->
                    val mono = clientFor(fake)
                    listOf(mono.invoices.create(CreateInvoiceRequest.builder(100).build()).invoiceId, fake.addCard("w"))
                }

        assertThat(ids()).isEqualTo(ids())
    }

    @Test
    fun `bind address and advertised host - urls for containers, local client options`() {
        FakeMonobankAcquiring
            .builder()
            .bindAddress(java.net.InetAddress.getByName("0.0.0.0"))
            .advertisedHost("host.testcontainers.internal")
            .build()
            .start()
            .use { fake ->
                assertThat(fake.baseUrl).isEqualTo("http://host.testcontainers.internal:${fake.port}")
                assertThat(fake.localBaseUrl).isEqualTo("http://127.0.0.1:${fake.port}")
                val mono = clientFor(fake)
                val created = mono.invoices.create(CreateInvoiceRequest.builder(100).build())
                assertThat(created.pageUrl).startsWith("http://host.testcontainers.internal:${fake.port}/pay/")
                // What an application in a container gets: the advertised host, plain http allowed.
                val containerOptions = fake.containerClientOptionsBuilder().build()
                assertThat(
                    containerOptions.baseUrl.toString(),
                ).isEqualTo("http://host.testcontainers.internal:${fake.port}")
                assertThat(containerOptions.allowPlainHttp).isTrue()
            }
        assertThatThrownBy { FakeMonobankAcquiring.builder().advertisedHost("http://x/") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
