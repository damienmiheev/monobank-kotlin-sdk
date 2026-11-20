package io.github.damienmiheev.monobank.acquiring.webhook

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import okhttp3.mockwebserver.MockResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
import java.util.concurrent.ConcurrentHashMap
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

    /** A clock that can stop a named thread at its next [instant] call until released. */
    private class PausingClock(
        @Volatile var now: Instant,
    ) : Clock() {
        class Hold {
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
        }

        private val holds = ConcurrentHashMap<String, Hold>()

        fun holdNext(thread: String): Hold = Hold().also { holds[thread] = it }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant {
            holds.remove(Thread.currentThread().name)?.let {
                it.reached.countDown()
                it.release.await() // untimed: WAITING here, TIMED_WAITING only inside the verifier's own wait
            }
            return now
        }
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

    private fun assertFresh(
        result: PublicKeyRefresh,
        key: String,
    ) {
        assertThat(result.key).isEqualTo(key)
        assertThat(result.isUnavailable).isFalse()
    }

    private fun assertUnavailable(
        result: PublicKeyRefresh,
        key: String?,
    ) {
        assertThat(result.key).isEqualTo(key)
        assertThat(result.isUnavailable).isTrue()
    }

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
    fun `refreshPublicKey picks up a rotated key without a failed check`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        val served = AtomicReference(oldPair)
        val calls = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder {
                    calls.incrementAndGet()
                    keyValue(served.get())
                }.clock(clock)
                .build()
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(oldPair))

        served.set(newPair)
        clock.now = clock.now.plusSeconds(61)
        assertFresh(verifier.refreshPublicKey(), keyValue(newPair))
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(newPair))
        assertThat(calls.get()).isEqualTo(2)

        // The new key verifies without another fetch; the old one stays accepted within the grace window.
        assertThat(verifier.check(sign(newPair), body)).isEqualTo(VerificationResult.VALID)
        assertThat(verifier.check(sign(oldPair), body)).isEqualTo(VerificationResult.VALID)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `refreshPublicKey loads the key when none is loaded yet`() {
        val pair = keyPair()
        val verifier = WebhookVerifier(WebhookKeyProvider { keyValue(pair) })
        assertFresh(verifier.refreshPublicKey(), keyValue(pair))
        assertThat(verifier.check(sign(pair), body)).isEqualTo(VerificationResult.VALID)
    }

    @Test
    fun `refreshPublicKey fetches at most once per minRefreshInterval`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        val served = AtomicReference(oldPair)
        val calls = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier(
                WebhookKeyProvider {
                    calls.incrementAndGet()
                    keyValue(served.get())
                },
                Duration.ofMinutes(1),
                clock,
            )
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(oldPair))
        served.set(newPair)

        clock.now = clock.now.plusSeconds(30)
        repeat(5) { assertFresh(verifier.refreshPublicKey(), keyValue(oldPair)) }
        assertThat(calls.get()).isEqualTo(1)

        clock.now = clock.now.plusSeconds(31)
        assertFresh(verifier.refreshPublicKey(), keyValue(newPair))
        assertFresh(verifier.refreshPublicKey(), keyValue(newPair))
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `refreshPublicKey from many threads fetches once`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        val served = AtomicReference(oldPair)
        val calls = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder {
                    calls.incrementAndGet()
                    Thread.sleep(50)
                    keyValue(served.get())
                }.clock(clock)
                .build()
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(oldPair))
        served.set(newPair)
        clock.now = clock.now.plusSeconds(61)

        val results = concurrently(16) { verifier.refreshPublicKey() }
        assertThat(results.map { it.key }).containsOnly(keyValue(newPair))
        assertThat(results.map { it.isUnavailable }).containsOnly(false)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `refreshPublicKey keeps the loaded key when monobank cannot be read, and backs off`() {
        val pair = keyPair()
        val down = AtomicReference(false)
        val calls = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder {
                    calls.incrementAndGet()
                    check(!down.get()) { "monobank down" }
                    keyValue(pair)
                }.failureBackoff(Duration.ofSeconds(10))
                .clock(clock)
                .build()
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(pair))
        down.set(true)
        clock.now = clock.now.plusSeconds(61)

        assertUnavailable(verifier.refreshPublicKey(), keyValue(pair))
        repeat(5) { assertUnavailable(verifier.refreshPublicKey(), keyValue(pair)) }
        assertThat(calls.get()).isEqualTo(2)
        assertThat(verifier.check(sign(pair), body)).isEqualTo(VerificationResult.VALID)

        clock.now = clock.now.plusSeconds(11)
        assertUnavailable(verifier.refreshPublicKey(), keyValue(pair)) // still down: a new fetch, failed again
        assertThat(calls.get()).isEqualTo(3)
        down.set(false)
        clock.now = clock.now.plusSeconds(11)
        assertFresh(verifier.refreshPublicKey(), keyValue(pair))
        assertThat(calls.get()).isEqualTo(4)
    }

    @Test
    fun `refreshPublicKey is null when no key was ever loaded`() {
        val verifier = WebhookVerifier(WebhookKeyProvider { throw IllegalStateException("monobank down") })
        assertUnavailable(verifier.refreshPublicKey(), null)
    }

    @Test
    fun `refreshPublicKey waiting longer than maxWaitForKey gets the loaded key, marked unavailable`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        val served = AtomicReference(oldPair)
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder {
                    if (calls.incrementAndGet() > 1) release.await(5, TimeUnit.SECONDS)
                    keyValue(served.get())
                }.maxWaitForKey(Duration.ofMillis(200))
                .clock(clock)
                .build()
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(oldPair))
        served.set(newPair)
        clock.now = clock.now.plusSeconds(61)
        val pool = Executors.newSingleThreadExecutor()
        val fetcher = pool.submit<PublicKeyRefresh> { verifier.refreshPublicKey() }
        while (calls.get() < 2) Thread.sleep(5)

        assertUnavailable(verifier.refreshPublicKey(), keyValue(oldPair))
        release.countDown()
        assertFresh(fetcher.get(5, TimeUnit.SECONDS), keyValue(newPair))
        pool.shutdown()
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    @Timeout(10)
    fun `a caller waiting on a fetch that stops for the failure back-off is told the key is unavailable`() {
        // A and B both passed the first gate before C's fetch failed; A then claims the fetch, sees the
        // back-off and stops. B, waiting on A, must not take A's old key for a fresh one.
        val pair = keyPair()
        val down = AtomicReference(false)
        val clock = PausingClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier
                .builder {
                    check(!down.get()) { "monobank down" }
                    keyValue(pair)
                }.failureBackoff(Duration.ofSeconds(10))
                .maxWaitForKey(Duration.ofSeconds(5))
                .clock(clock)
                .build()
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(pair))
        clock.now = clock.now.plusSeconds(61)
        down.set(true)
        val results = ConcurrentHashMap<String, PublicKeyRefresh>()

        fun caller(name: String) =
            Thread({ results[name] = verifier.refreshPublicKey() }, name).apply {
                isDaemon = true // a failed run leaves no thread parked on a latch
                start()
            }

        val aFirstGate = clock.holdNext("A")
        val a = caller("A")
        aFirstGate.reached.await()
        val bFirstGate = clock.holdNext("B")
        val b = caller("B")
        bFirstGate.reached.await()

        assertUnavailable(verifier.refreshPublicKey(), keyValue(pair)) // C: fetches, fails, back-off starts

        val aSecondGate = clock.holdNext("A")
        aFirstGate.release.countDown() // A claims the fetch and stops in its second gate
        aSecondGate.reached.await()
        bFirstGate.release.countDown() // B finds A's fetch running and waits on it
        while (b.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
        aSecondGate.release.countDown()
        a.join()
        b.join()

        assertUnavailable(results.getValue("A"), keyValue(pair))
        assertUnavailable(results.getValue("B"), keyValue(pair))
    }

    @Test
    fun `re-reading the same key after a rotation does not restart the previous key's grace`() {
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
        assertThat(verifier.currentPublicKey()).isEqualTo(keyValue(oldPair))
        served.set(newPair)
        clock.now = clock.now.plusSeconds(61)
        assertFresh(verifier.refreshPublicKey(), keyValue(newPair)) // rotation seen here

        clock.now = clock.now.plus(Duration.ofMinutes(6))
        assertFresh(verifier.refreshPublicKey(), keyValue(newPair)) // the same key read again
        assertThat(verifier.check(sign(oldPair), body)).isEqualTo(VerificationResult.VALID)

        clock.now = clock.now.plus(Duration.ofMinutes(5)) // 11 minutes after the rotation was seen
        assertThat(verifier.check(sign(oldPair), body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.check(sign(newPair), body)).isEqualTo(VerificationResult.VALID)
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
