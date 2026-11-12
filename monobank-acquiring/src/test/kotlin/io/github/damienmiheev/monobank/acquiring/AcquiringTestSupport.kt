package io.github.damienmiheev.monobank.acquiring

import com.fasterxml.jackson.databind.JsonNode
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.json.MonobankJson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

/** Base class: a MockWebServer and a client pointed at it, without real sleeps between retries. */
abstract class AcquiringTestSupport {
    protected lateinit var server: MockWebServer
    protected lateinit var mono: MonobankAcquiring

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

    protected fun client(
        retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ZERO),
    ): MonobankAcquiring {
        val config =
            AcquiringConfig
                .builder(TOKEN)
                .cmsName("test-cms")
                .cmsVersion("1.2.3")
                .options(
                    MonobankClientOptions
                        .builder()
                        .baseUrl(server.url("/").toString())
                        .retryPolicy(retryPolicy)
                        .build(),
                ).build()
        return MonobankAcquiring(config, AcquiringHttp(config, sleeper = { }))
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

    /** Takes the next request and checks the common headers. */
    protected fun takeRequest(): RecordedRequest {
        val request = server.takeRequest()
        assertThat(request.getHeader("X-Token")).isEqualTo(TOKEN)
        assertThat(request.getHeader("X-Cms")).isEqualTo("test-cms")
        assertThat(request.getHeader("X-Cms-Version")).isEqualTo("1.2.3")
        return request
    }

    /** Takes the next request, checks the common headers, method and path (without query). */
    protected fun takeRequest(
        method: String,
        path: String,
    ): RecordedRequest {
        val request = takeRequest()
        assertThat(request.method).isEqualTo(method)
        assertThat(request.requestUrl!!.encodedPath).isEqualTo(path)
        return request
    }

    /** The request body parsed as JSON. */
    protected fun RecordedRequest.json(): JsonNode = MonobankJson.mapper.readTree(body.readUtf8())

    /** A query parameter of the request, decoded. */
    protected fun RecordedRequest.query(name: String): String? = requestUrl!!.queryParameter(name)

    companion object {
        const val TOKEN = "test-token-123"

        fun fixture(name: String): String =
            requireNotNull(AcquiringTestSupport::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }
                .readText()

        fun resource(name: String): String =
            requireNotNull(AcquiringTestSupport::class.java.getResource(name)) { "missing resource $name" }.readText()
    }
}
