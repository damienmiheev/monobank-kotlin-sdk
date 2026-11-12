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
import java.time.Duration
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

    /** Paces `listAll` when the client has no rate limiter; never really sleeps. */
    protected val pacerTime = FakeTime()

    protected fun options(retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ofSeconds(10))) =
        MonobankClientOptions
            .builder()
            .baseUrl(server.url("/").toString())
            .retryPolicy(retryPolicy)
            .build()

    protected fun client(
        retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ofSeconds(10)),
        rateLimiter: PersonalRateLimiter? = null,
        token: String? = TOKEN,
    ): MonobankPersonal {
        val builder = if (token == null) PersonalConfig.publicBuilder() else PersonalConfig.builder(token)
        val config = builder.options(options(retryPolicy)).rateLimiter(rateLimiter).build()
        val http =
            PersonalHttp(config, MonobankHttp.Sleeper { retryWaits += it }) {
                pacerTime.limiter(PersonalRateLimiter.DEFAULT_INTERVAL)
            }
        return MonobankPersonal(config, http)
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

    /** A nanosecond time source that only moves when [advance] is called, and a sleeper that advances it. */
    class FakeTime(
        @Volatile var nanos: Long = 1_000_000_000_000L,
    ) {
        val sleeps: MutableList<Duration> = java.util.Collections.synchronizedList(mutableListOf<Duration>())

        internal val sleeper =
            PersonalRateLimiter.Sleeper {
                sleeps += Duration.ofNanos(it)
                advance(Duration.ofNanos(it))
            }

        @Synchronized
        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }

        internal fun limiter(interval: Duration = Duration.ofSeconds(60)): PersonalRateLimiter =
            PersonalRateLimiter.create(interval, { nanos }, sleeper)
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
