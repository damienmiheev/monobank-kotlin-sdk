package io.github.damienmiheev.monobank.core.http

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.http.HttpClient
import java.time.Duration

class MonobankHttpEdgeCasesTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Duration>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun http(
        retry: RetryPolicy = RetryPolicy.of(2, Duration.ofMillis(100), Duration.ofSeconds(10)),
        baseUrl: String = server.url("/").toString(),
        httpClient: HttpClient? = null,
    ) = MonobankHttp(
        options =
            MonobankClientOptions
                .builder()
                .baseUrl(baseUrl)
                .retryPolicy(retry)
                .requestTimeout(Duration.ofSeconds(5))
                .httpClient(httpClient)
                .build(),
        defaultBaseUrl = "https://example.invalid",
        module = "test",
        authenticator = { mapOf("X-Token" to "super-secret-token") },
        errorDecoder =
            object : ErrorDecoder {
                override fun decode(body: ByteArray) = ApiError(null, null)
            },
        sleeper = { sleeps += it },
    )

    private fun post() =
        ApiRequest(HttpMethod.POST, "/api/pay", body = """{"cardToken":"tok-123"}""".toByteArray(), idempotent = false)

    @Test
    fun `connection dropped after the request was written is UNKNOWN and a payment is not retried`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertThatThrownBy { http().execute(post()) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `unknown host is NOT_SENT`() {
        assertThatThrownBy { http(baseUrl = "https://no-such-host.invalid", retry = RetryPolicy.NONE).execute(post()) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.NOT_SENT)
            }
    }

    @Test
    fun `Retry-After longer than maxBackoff is not retried`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))

        assertThatThrownBy { http().execute(post()) }
            .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(60))
            }
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `backoff grows and stays within maxBackoff`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        val get = ApiRequest(HttpMethod.GET, "/x", idempotent = true)

        assertThatThrownBy {
            http(retry = RetryPolicy.of(3, Duration.ofSeconds(4), Duration.ofSeconds(10))).execute(get)
        }.isInstanceOf(Exception::class.java)

        assertThat(sleeps).hasSize(3)
        assertThat(sleeps[0]).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4))
        assertThat(sleeps[1]).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8))
        assertThat(sleeps[2]).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10))
    }

    @Test
    fun `an interrupted thread does not retry`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val get = ApiRequest(HttpMethod.GET, "/x", idempotent = true)
        try {
            Thread.currentThread().interrupt()
            assertThatThrownBy { http().execute(get) }.isInstanceOf(Exception::class.java)
        } finally {
            Thread.interrupted()
        }
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `a custom HttpClient is used and one that follows redirects is refused`() {
        server.enqueue(MockResponse().setBody("{}"))
        val custom = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        assertThat(http(httpClient = custom).execute(post()).statusCode).isEqualTo(200)

        val redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        assertThatThrownBy { MonobankClientOptions.builder().httpClient(redirecting) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `plain http is only allowed for localhost`() {
        MonobankClientOptions.builder().baseUrl("http://localhost:8080").baseUrl("http://127.0.0.1:1")
        assertThatThrownBy { MonobankClientOptions.builder().baseUrl("http://api.monobank.ua") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonobankClientOptions.builder().baseUrl("https://api.monobank.ua?x=1") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `paths cannot smuggle a query and segments are encoded`() {
        assertThatThrownBy {
            ApiRequest(HttpMethod.GET, "/a?b=c", idempotent = true)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ApiRequest(HttpMethod.GET, "/a b", idempotent = true)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(UrlEncoding.pathSegment("acc/../x?y#z")).isEqualTo("acc%2F..%2Fx%3Fy%23z")
        assertThatThrownBy { UrlEncoding.pathSegment("..") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `logs never contain the token, bodies or query values`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"errCode":"X","errText":"pan 4444111122223333"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, "UTF-8"))
        try {
            runCatching { http().execute(post()) }
            runCatching {
                http().execute(
                    ApiRequest(
                        HttpMethod.DELETE,
                        "/api/merchant/wallet/card",
                        listOf("cardToken" to "tok-secret-456"),
                        idempotent = true,
                    ),
                )
            }
        } finally {
            System.setErr(original)
        }
        val logs = captured.toString("UTF-8")
        assertThat(logs).contains("/api/pay").contains("/api/merchant/wallet/card")
        assertThat(
            logs,
        ).doesNotContain("super-secret-token", "tok-123", "tok-secret-456", "4444111122223333", "cardToken")
    }
}

class DefaultRetryAfterTest {
    @Test
    fun `a module default wait applies to 429 without Retry-After and stops pointless retries`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            val sleeps = mutableListOf<Duration>()
            val http =
                MonobankHttp(
                    options = MonobankClientOptions.builder().baseUrl(server.url("/").toString()).build(),
                    defaultBaseUrl = "https://example.invalid",
                    module = "test",
                    authenticator = { emptyMap() },
                    errorDecoder =
                        object : ErrorDecoder {
                            override fun decode(body: ByteArray) = ApiError(null, null)
                        },
                    sleeper = { sleeps += it },
                    defaultRetryAfter = Duration.ofSeconds(60),
                )
            assertThatThrownBy { http.execute(ApiRequest(HttpMethod.GET, "/x", idempotent = true)) }
                .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                    assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(60))
                }
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(sleeps).isEmpty()
        } finally {
            server.shutdown()
        }
    }
}

class HeaderSafetyTest {
    @Test
    fun `credentials with control characters are refused without echoing them`() {
        assertThat(
            io.github.damienmiheev.monobank.core.Redaction
                .requireHeaderSafe("abc-123_XYZ", "token"),
        ).isEqualTo("abc-123_XYZ")
        assertThatThrownBy {
            io.github.damienmiheev.monobank.core.Redaction
                .requireHeaderSafe("sec\u0001ret", "token")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining("sec")
        assertThatThrownBy {
            io.github.damienmiheev.monobank.core.Redaction
                .requireHeaderSafe("a b", "token")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
