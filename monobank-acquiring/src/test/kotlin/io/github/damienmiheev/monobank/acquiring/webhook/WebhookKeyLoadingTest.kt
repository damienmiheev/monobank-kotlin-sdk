package io.github.damienmiheev.monobank.acquiring.webhook

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import okhttp3.mockwebserver.MockResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Key loading under failure: back-off, single fetch, bounded waiting, rotation grace. */
class WebhookKeyLoadingTest : AcquiringTestSupport() {
    private class MutableClock(
        @Volatile var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val body = """{"invoiceId":"i","status":"success","amount":1,"ccy":980}""".toByteArray()

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun keyValue(pair: KeyPair): String {
        val pem =
            "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pair.public.encoded) +
                "\n-----END PUBLIC KEY-----\n"
        return Base64.getEncoder().encodeToString(pem.toByteArray())
    }

    private fun sign(
        pair: KeyPair,
        data: ByteArray = body,
    ): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(data)
                sign()
            },
        )

    private fun <T> concurrently(
        threads: Int,
        task: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<T> {
                        start.await()
                        task()
                    }
                }
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a failing provider is called once per back-off window, even from many threads`() {
        val calls = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder {
                    calls.incrementAndGet()
                    throw IllegalStateException("monobank down")
                }.failureBackoff(Duration.ofSeconds(10))
                .clock(clock)
                .build()
        val signature = sign(keyPair())

        val results = concurrently(16) { verifier.check(signature, body) }

        assertThat(results).containsOnly(VerificationResult.KEY_UNAVAILABLE)
        assertThat(calls.get()).isEqualTo(1)
        repeat(20) { assertThat(verifier.currentPublicKey()).isNull() }
        assertThat(calls.get()).isEqualTo(1)

        clock.now = clock.now.plusSeconds(11)
        assertThat(verifier.check(signature, body)).isEqualTo(VerificationResult.KEY_UNAVAILABLE)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `a slow provider does not hold other threads longer than maxWaitForKey`() {
        val pair = keyPair()
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val verifier =
            WebhookVerifier
                .builder {
                    calls.incrementAndGet()
                    release.await(5, TimeUnit.SECONDS)
                    keyValue(pair)
                }.maxWaitForKey(Duration.ofMillis(200))
                .build()
        val signature = sign(pair)
        val pool = Executors.newSingleThreadExecutor()
        val fetcher = pool.submit<VerificationResult> { verifier.check(signature, body) }
        while (calls.get() == 0) Thread.sleep(5)

        val started = System.nanoTime()
        val waiters = concurrently(8) { verifier.check(signature, body) }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertThat(waiters).containsOnly(VerificationResult.KEY_UNAVAILABLE)
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2))
        release.countDown()
        assertThat(fetcher.get(5, TimeUnit.SECONDS)).isEqualTo(VerificationResult.VALID)
        pool.shutdown()
        assertThat(calls.get()).isEqualTo(1)
        assertThat(verifier.check(signature, body)).isEqualTo(VerificationResult.VALID)
    }

    @Test
    fun `waiters that arrive during a quick fetch get its key`() {
        val pair = keyPair()
        val calls = AtomicInteger()
        val verifier =
            WebhookVerifier(
                WebhookKeyProvider {
                    calls.incrementAndGet()
                    Thread.sleep(100)
                    keyValue(pair)
                },
            )
        val signature = sign(pair)
        assertThat(concurrently(16) { verifier.check(signature, body) }).containsOnly(VerificationResult.VALID)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `the client's key fetch is not retried and fails fast afterwards`() {
        repeat(3) { enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500) }
        val signature = sign(keyPair())

        assertThat(mono.webhooks.check(signature, body)).isEqualTo(VerificationResult.KEY_UNAVAILABLE)
        assertThat(mono.webhooks.check(signature, body)).isEqualTo(VerificationResult.KEY_UNAVAILABLE)
        assertThat(mono.webhooks.currentPublicKey()).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `the client's key fetch times out quickly`() {
        server.enqueue(
            MockResponse()
                .setBody("""{"key":"x"}""")
                .setHeadersDelay(8, TimeUnit.SECONDS),
        )
        val started = System.nanoTime()
        assertThat(mono.webhooks.currentPublicKey()).isNull()
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7))
    }

    @Test
    fun `the previous key is accepted only within the grace window`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        val served = AtomicReference(oldPair)
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder { keyValue(served.get()) }
                .previousKeyGrace(Duration.ofMinutes(10))
                .clock(clock)
                .build()
        assertThat(verifier.verify(sign(oldPair), body)).isTrue()

        served.set(newPair)
        clock.now = clock.now.plusSeconds(120)
        assertThat(verifier.verify(sign(newPair), body)).isTrue() // rotation detected at +2 min
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        assertThat(verifier.verify(sign(oldPair), body)).isTrue()
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        assertThat(verifier.check(sign(oldPair), body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.verify(sign(newPair), body)).isTrue()
    }

    @Test
    fun `a clock that goes backwards does not block refreshing`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        val served = AtomicReference(oldPair)
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier = WebhookVerifier.builder { keyValue(served.get()) }.clock(clock).build()
        assertThat(verifier.verify(sign(oldPair), body)).isTrue()
        served.set(newPair)
        clock.now = clock.now.minus(Duration.ofHours(1))
        assertThat(verifier.verify(sign(newPair), body)).isTrue()
    }
}
