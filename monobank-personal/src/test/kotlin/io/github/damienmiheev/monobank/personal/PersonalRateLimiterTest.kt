package io.github.damienmiheev.monobank.personal

import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PersonalRateLimiterTest : PersonalTestSupport() {
    private val time = FakeTime()
    private val limiter = PersonalRateLimiter.create(Duration.ofSeconds(60), time, time.sleeper)

    @Test
    fun `first call passes, the next one waits for the rest of the interval`() {
        limiter.acquire("client-info")
        assertThat(time.sleeps).isEmpty()

        time.advance(Duration.ofSeconds(15))
        limiter.acquire("client-info")
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(45))

        time.advance(Duration.ofSeconds(61))
        limiter.acquire("client-info")
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(45))
    }

    @Test
    fun `endpoints are limited separately`() {
        limiter.acquire("client-info")
        limiter.acquire("statement")
        assertThat(time.sleeps).isEmpty()
        limiter.acquire("statement")
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(60))
    }

    @Test
    fun `concurrent callers are queued one interval apart`() {
        val frozen = FakeTime()
        val waits = Collections.synchronizedList(mutableListOf<Duration>())
        val shared = PersonalRateLimiter.create(Duration.ofSeconds(60), frozen) { waits += it }
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        repeat(4) {
            pool.execute {
                start.await()
                shared.acquire("statement")
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

        assertThat(waits).containsExactlyInAnyOrder(
            Duration.ofSeconds(60),
            Duration.ofSeconds(120),
            Duration.ofSeconds(180),
        )
    }

    @Test
    fun `factory validates and defaults to 60 seconds`() {
        assertThat(PersonalRateLimiter.create().interval).isEqualTo(Duration.ofSeconds(60))
        assertThat(PersonalRateLimiter.DEFAULT_INTERVAL).isEqualTo(Duration.ofSeconds(60))
        assertThat(PersonalRateLimiter.create(Duration.ZERO).interval).isZero()
        assertThatThrownBy { PersonalRateLimiter.create(Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(limiter.toString()).isEqualTo("PersonalRateLimiter(interval=PT1M)")
    }

    @Test
    fun `client waits before the second client-info call but not before public calls`() {
        val limited = client(rateLimiter = limiter)
        repeat(2) { enqueueFixture("GET_personal_client_info.response.json") }
        repeat(2) { enqueueFixture("GET_bank_currency.response.json") }
        enqueueJson("[]")

        limited.client.info()
        limited.client.info()
        limited.bank.currency()
        limited.bank.currency()
        limited.statements.list("0", Instant.ofEpochSecond(1))

        assertThat(server.requestCount).isEqualTo(5)
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(60))
    }

    @Test
    fun `retries of a limited endpoint wait too`() {
        val limited = client(rateLimiter = limiter)
        enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429)
        enqueueFixture("GET_personal_client_info.response.json")

        assertThat(limited.client.info().clientId).isEqualTo("3MSaMMtczs")
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(60))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `setWebhook is not limited`() {
        val limited = client(rateLimiter = limiter)
        enqueueJson("")
        enqueueJson("")

        limited.client.setWebhook("https://example.com/a")
        limited.client.setWebhook("https://example.com/a")

        assertThat(time.sleeps).isEmpty()
    }

    @Test
    fun `interrupt while waiting fails with NOT_SENT and sends nothing`() {
        val interrupting =
            PersonalRateLimiter.create(Duration.ofSeconds(60), time) { throw InterruptedException("stop") }
        val limited = client(retryPolicy = RetryPolicy.NONE, rateLimiter = interrupting)
        enqueueFixture("GET_personal_client_info.response.json")
        limited.client.info()

        try {
            assertThatThrownBy { limited.client.info() }
                .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                    assertThat(it.outcome).isEqualTo(RequestOutcome.NOT_SENT)
                }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        assertThat(server.requestCount).isEqualTo(1)
    }
}
