package io.github.damienmiheev.monobank.installments

import com.fasterxml.jackson.databind.JsonNode
import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Base class: a MockWebServer and a client pointed at it, without real sleeps between retries. */
abstract class InstallmentsTestSupport {
    protected lateinit var server: MockWebServer
    protected lateinit var chast: MonobankInstallments

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
        chast = client()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    protected fun client(
        retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ZERO),
    ): MonobankInstallments {
        val config =
            InstallmentsConfig
                .builder(STORE_ID, STORE_SECRET)
                .environment(InstallmentsEnvironment.SANDBOX)
                .options(
                    MonobankClientOptions
                        .builder()
                        .baseUrl(server.url("/").toString())
                        .retryPolicy(retryPolicy)
                        .build(),
                ).build()
        return MonobankInstallments(config, InstallmentsHttp(config) { })
    }

    protected fun enqueueJson(
        body: String,
        status: Int = 200,
    ) {
        server.enqueue(
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body),
        )
    }

    protected fun enqueueFixture(
        name: String,
        status: Int = 200,
    ) = enqueueJson(fixture(name), status)

    protected fun enqueueBytes(
        bytes: ByteArray,
        contentType: String,
    ) {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(Buffer().write(bytes)),
        )
    }

    /**
     * Takes the next request and checks what every request must carry: POST, JSON, `store-id`, and a `signature`
     * that is the HMAC of exactly the bytes received (computed here independently of the SDK).
     */
    protected fun takeRequest(path: String): RecordedRequest {
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo(path)
        assertThat(request.getHeader("store-id")).isEqualTo(STORE_ID)
        assertThat(request.getHeader("Content-Type")).startsWith("application/json")
        val body = request.body.clone().readByteArray()
        assertThat(request.getHeader("signature")).isEqualTo(hmacBase64(STORE_SECRET, body))
        return request
    }

    /** Takes the next request (see [takeRequest]) and parses its body. */
    protected fun takeJson(path: String): JsonNode = MonobankJson.mapper.readTree(takeRequest(path).body.readUtf8())

    companion object {
        const val STORE_ID = "test_store"
        const val STORE_SECRET = "test-store-secret"
        const val ORDER_ID = "fa4a8249-336e-4e6d-9b85-79bc8be62377"

        fun hmacBase64(
            secret: String,
            body: ByteArray,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(body))
        }

        fun fixture(name: String): String =
            requireNotNull(
                InstallmentsTestSupport::class.java.getResource("/fixtures/$name"),
            ) { "missing fixture $name" }
                .readText()

        fun fixtureBytes(name: String): ByteArray =
            requireNotNull(
                InstallmentsTestSupport::class.java.getResource("/fixtures/$name"),
            ) { "missing fixture $name" }
                .readBytes()
    }
}
