package io.github.damienmiheev.monobank.personal

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.http.MonobankHttp
import io.github.damienmiheev.monobank.personal.internal.PersonalHttp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Base class: a MockWebServer and a client pointed at it, without real sleeps between retries. */
abstract class PersonalTestSupport {
    protected lateinit var server: MockWebServer
    protected lateinit var mono: MonobankPersonal

    /** Waits the transport asked for between retries. */
    protected val retryWaits = mutableListOf<Duration>()

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
        mono = client()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    protected fun options(retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ofSeconds(60))) =
        MonobankClientOptions
            .builder()
            .baseUrl(server.url("/").toString())
            .retryPolicy(retryPolicy)
            .build()

    protected fun client(
        retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ofSeconds(60)),
        rateLimiter: PersonalRateLimiter? = null,
        token: String? = TOKEN,
    ): MonobankPersonal {
        val builder = if (token == null) PersonalConfig.publicBuilder() else PersonalConfig.builder(token)
        val config = builder.options(options(retryPolicy)).rateLimiter(rateLimiter).build()
        return MonobankPersonal(config, PersonalHttp(config, MonobankHttp.Sleeper { retryWaits += it }))
    }

    protected fun enqueueJson(
        body: String,
        status: Int = 200,
    ) {
        server.enqueue(
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body),
        )
    }

    protected fun enqueueFixture(name: String) = enqueueJson(fixture(name))

    /** Takes the next request (fails fast when none was sent). */
    protected fun takeRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "no request was sent" }

    /** Takes the next request and checks it carries the token. */
    protected fun takeAuthenticatedRequest(): RecordedRequest {
        val request = takeRequest()
        assertThat(request.getHeader("X-Token")).isEqualTo(TOKEN)
        assertThat(request.getHeader("User-Agent")).startsWith("monobank-kotlin-sdk/").contains("personal")
        return request
    }

    /** A clock that only moves when [advance] is called, and a sleeper that advances it. */
    class FakeTime(
        start: Instant = Instant.parse("2025-01-01T00:00:00Z"),
    ) : Clock() {
        @Volatile
        var now: Instant = start
        val sleeps = mutableListOf<Duration>()

        val sleeper =
            PersonalRateLimiter.Sleeper {
                synchronized(this) { sleeps += it }
                advance(it)
            }

        @Synchronized
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    companion object {
        const val TOKEN = "personal-token-secret-123"

        fun fixture(name: String): String =
            requireNotNull(PersonalTestSupport::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }
                .readText()

        fun resource(name: String): String =
            requireNotNull(PersonalTestSupport::class.java.getResource(name)) { "missing resource $name" }.readText()
    }
}
