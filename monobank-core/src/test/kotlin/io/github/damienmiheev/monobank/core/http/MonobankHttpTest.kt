package io.github.damienmiheev.monobank.core.http

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.json.MonobankJson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration

class MonobankHttpTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Duration>()

    private object JsonErrors : ErrorDecoder {
        override fun decode(body: ByteArray): ApiError {
            val node = MonobankJson.readTree(body)
            return ApiError(node.path("errCode").textValue(), node.path("errText").textValue())
        }

        override fun serverErrorOutcome(errorCode: String?): RequestOutcome =
            if (errorCode == "DEFINITE") RequestOutcome.REJECTED else RequestOutcome.UNKNOWN
    }

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
        retry: RetryPolicy = RetryPolicy.of(2, Duration.ofMillis(100), Duration.ofSeconds(1)),
        baseUrl: String = server.url("/").toString(),
        requestTimeout: Duration = Duration.ofSeconds(5),
    ) = MonobankHttp(
        options =
            MonobankClientOptions
                .builder()
                .baseUrl(baseUrl)
                .retryPolicy(retry)
                .requestTimeout(requestTimeout)
                .userAgentSuffix("test-app/1.0")
                .build(),
        defaultBaseUrl = "https://example.invalid",
        module = "test",
        authenticator = { mapOf("X-Token" to "secret") },
        errorDecoder = JsonErrors,
        sleeper = { sleeps += it },
    )

    private fun get(idempotent: Boolean = true) =
        ApiRequest(
            HttpMethod.GET,
            "/api/thing",
            query = listOf("id" to "a b&c", "skip" to null),
            idempotent = idempotent,
        )

    private fun post(idempotent: Boolean = false) =
        ApiRequest(HttpMethod.POST, "/api/pay", body = """{"a":1}""".toByteArray(), idempotent = idempotent)

    @Test
    fun `sends auth, user agent, encoded query and body`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        http().execute(get())
        http().execute(post())

        val getRequest = server.takeRequest()
        assertThat(getRequest.path).isEqualTo("/api/thing?id=a%20b%26c")
        assertThat(getRequest.getHeader("X-Token")).isEqualTo("secret")
        assertThat(
            getRequest.getHeader("User-Agent"),
        ).startsWith("monobank-kotlin-sdk/").contains("(test;").endsWith("test-app/1.0")
        val postRequest = server.takeRequest()
        assertThat(postRequest.method).isEqualTo("POST")
        assertThat(postRequest.getHeader("Content-Type")).isEqualTo("application/json")
        assertThat(postRequest.body.readUtf8()).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `maps statuses to exceptions with outcome and error fields`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errCode":"BAD_REQUEST","errText":"nope"}"""))
        assertThatThrownBy { http().execute(post()) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.statusCode).isEqualTo(400)
                assertThat(it.errorCode).isEqualTo("BAD_REQUEST")
                assertThat(it.errorText).isEqualTo("nope")
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
                assertThat(it.message).contains("HTTP 400").contains("BAD_REQUEST: nope")
            }
        server.enqueue(MockResponse().setResponseCode(403).setBody("not json"))
        assertThatThrownBy { http().execute(post()) }.isInstanceOfSatisfying(MonobankAuthException::class.java) {
            assertThat(it.errorCode).isNull()
            assertThat(it.responseBody).isEqualTo("not json")
        }
        server.enqueue(MockResponse().setResponseCode(401))
        assertThatThrownBy { http().execute(post()) }.isInstanceOf(MonobankAuthException::class.java)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"errCode":"NOT_FOUND"}"""))
        assertThatThrownBy { http().execute(post()) }.isInstanceOf(MonobankNotFoundException::class.java)
        server.enqueue(MockResponse().setResponseCode(409))
        assertThatThrownBy { http().execute(post()) }.isInstanceOf(MonobankBadRequestException::class.java)
    }

    @Test
    fun `5xx outcome is unknown unless the error code is definite`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"errCode":"INTERNAL_ERROR"}"""))
        assertThatThrownBy { http().execute(post()) }.isInstanceOfSatisfying(MonobankServerException::class.java) {
            assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        }
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"errCode":"DEFINITE"}"""))
        assertThatThrownBy { http().execute(post()) }.isInstanceOfSatisfying(MonobankServerException::class.java) {
            assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `trace id from the response headers is kept on API exceptions`() {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("trace-id", "abc-123").setBody("{}"))
        assertThatThrownBy { http().execute(post()) }.isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
            assertThat(it.traceId).isEqualTo("abc-123")
        }
    }

    @Test
    fun `accepted non-2xx statuses are returned`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val response =
            http().execute(
                ApiRequest(HttpMethod.POST, "/x", idempotent = true, acceptedStatuses = setOf(404)),
            )
        assertThat(response.statusCode).isEqualTo(404)
    }

    @Test
    fun `idempotent requests are retried on 5xx`() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val response = http().execute(get())

        assertThat(response.statusCode).isEqualTo(200)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(sleeps).hasSize(2)
        assertThat(sleeps).allSatisfy { assertThat(it).isLessThanOrEqualTo(Duration.ofSeconds(1)) }
    }

    @Test
    fun `retries stop after maxRetries`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        assertThatThrownBy { http().execute(get()) }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `non-idempotent requests are not retried on 5xx or read timeout`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThatThrownBy { http().execute(post()) }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThatThrownBy { http(requestTimeout = Duration.ofMillis(300)).execute(post()) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `429 is retried for any request and honours Retry-After`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"errCode":"TMR"}"""),
        )
        server.enqueue(MockResponse().setBody("{}"))

        http(retry = RetryPolicy.of(1, Duration.ofMillis(10), Duration.ofSeconds(30))).execute(post())

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7))
    }

    @Test
    fun `429 after retries carries retryAfter`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        assertThatThrownBy { http(retry = RetryPolicy.NONE).execute(post()) }
            .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(60))
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
    }

    @Test
    fun `connection refused is NOT_SENT and retried even for payments`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertThatThrownBy { http(baseUrl = "http://127.0.0.1:$closedPort").execute(post()) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.NOT_SENT)
            }
        assertThat(sleeps).hasSize(2)
    }

    @Test
    fun `redirects are not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.invalid/"))
        assertThatThrownBy { http().execute(get()) }.isInstanceOf(MonobankBadRequestException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `authenticator runs on every attempt`() {
        var calls = 0
        val client =
            MonobankHttp(
                options = MonobankClientOptions.builder().baseUrl(server.url("/").toString()).build(),
                defaultBaseUrl = "https://example.invalid",
                module = "test",
                authenticator = { mapOf("X-Sign" to "sig-${++calls}") },
                errorDecoder = JsonErrors,
                sleeper = {},
            )
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("{}"))

        client.execute(get())

        assertThat(server.takeRequest().getHeader("X-Sign")).isEqualTo("sig-1")
        assertThat(server.takeRequest().getHeader("X-Sign")).isEqualTo("sig-2")
    }
}
