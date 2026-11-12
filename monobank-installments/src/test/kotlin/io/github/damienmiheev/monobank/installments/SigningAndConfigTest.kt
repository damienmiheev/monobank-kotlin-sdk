package io.github.damienmiheev.monobank.installments

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.installments.internal.HmacSigner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class SigningAndConfigTest : InstallmentsTestSupport() {
    @Test
    fun `HMAC matches RFC 4231 test case 2`() {
        // RFC 4231 4.3: key "Jefe", data "what do ya want for nothing?",
        // HMAC-SHA-256 = 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843 (Base64 below).
        assertThat(HmacSigner("Jefe").sign("what do ya want for nothing?".toByteArray()))
            .isEqualTo("W9zBRr9gdU5qBCQmCJV1x1oAPwidJzmDnexYuWTsOEM=")
    }

    @Test
    fun `HMAC matches the body from monobank's PHP example, checked with openssl`() {
        // printf '%s' "$body" | openssl dgst -sha256 -hmac your_store_secret -binary | base64
        val body = """{"store_order_id":"ORD-001","client_phone":"+380501234567","total_sum":1500.00}"""
        assertThat(HmacSigner("your_store_secret").sign(body.toByteArray()))
            .isEqualTo("6DrekG4eI9/SKDY5ARM/o3MSUwU0Br5APqE22CmfKtI=")
    }

    @Test
    fun `secret is used as UTF-8 and an empty body is signed too`() {
        assertThat(HmacSigner("секрет").sign("""{"order_id":"o-1"}""".toByteArray()))
            .isEqualTo("ms+oH0FrF4r070t12sl4mDZkQTcBIwAfOsugY29sWd8=")
        assertThat(HmacSigner("k").sign(ByteArray(0))).isEqualTo("i7mQxAp9YcuXWXqUISUCW+UKyL63RDbjc1uYiTp/ZiA=")
    }

    @Test
    fun `requests carry the documented header names and the signature of the exact bytes sent`() {
        enqueueJson("""{"found":true}""")

        chast.clients.validate("+380501234567")

        val request = server.takeRequest()
        val body = request.body.readByteArray()
        assertThat(String(body)).isEqualTo("""{"phone":"+380501234567"}""")
        assertThat(request.headers.names()).contains("store-id", "signature")
        assertThat(request.getHeader("store-id")).isEqualTo(STORE_ID)
        assertThat(request.getHeader("signature")).isEqualTo(hmacBase64(STORE_SECRET, body))
        assertThat(request.getHeader("User-Agent")).contains("installments")
    }

    @Test
    fun `order id body signature matches an openssl vector`() {
        enqueueFixture("POST_api_order_check_paid.response.json")

        chast.orders.checkPaid("o-1")

        val request = server.takeRequest()
        assertThat(request.body.readUtf8()).isEqualTo("""{"order_id":"o-1"}""")
        // printf '%s' '{"order_id":"o-1"}' | openssl dgst -sha256 -hmac test-store-secret -binary | base64
        assertThat(request.getHeader("signature")).isEqualTo("vus4McBCt4T4vz9uyTJJTK0EEV+OaZtn74BX3dY+XWU=")
    }

    @Test
    fun `non-ASCII bodies are signed as the UTF-8 bytes on the wire`() {
        enqueueJson("""{"found":true}""")
        chast.clients.validate("+380 Іван")
        val request = server.takeRequest()
        val bytes = request.body.readByteArray()
        assertThat(String(bytes, Charsets.UTF_8)).contains("Іван")
        assertThat(request.getHeader("signature")).isEqualTo(hmacBase64(STORE_SECRET, bytes))
    }

    @Test
    fun `every retry attempt is signed`() {
        enqueueJson("{}", status = 500)
        enqueueJson("""{"found":true}""")

        chast.clients.validate("+380501234567")

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("signature")).isNotBlank().isEqualTo(second.getHeader("signature"))
    }

    @Test
    fun `environments point at the documented hosts and baseUrl overrides them`() {
        assertThat(InstallmentsEnvironment.PRODUCTION.baseUrl).isEqualTo("https://u2.monobank.com.ua")
        assertThat(InstallmentsEnvironment.SANDBOX.baseUrl).isEqualTo("https://u2-demo-ext.mono.st4g3.com")
        assertThat(InstallmentsEnvironment.STAGE.baseUrl).isEqualTo("https://u2-ext.mono.st4g3.com")

        val default = InstallmentsConfig.builder("s", "k").build()
        assertThat(default.environment).isEqualTo(InstallmentsEnvironment.PRODUCTION)
        assertThat(default.baseUrl).isEqualTo("https://u2.monobank.com.ua")
        val stage = InstallmentsConfig.builder("s", "k").environment(InstallmentsEnvironment.STAGE).build()
        assertThat(stage.baseUrl).isEqualTo("https://u2-ext.mono.st4g3.com")
        val proxied =
            InstallmentsConfig
                .builder("s", "k")
                .environment(InstallmentsEnvironment.SANDBOX)
                .options(MonobankClientOptions.builder().baseUrl("https://proxy.example/").build())
                .build()
        assertThat(proxied.baseUrl).isEqualTo("https://proxy.example")
    }

    @Test
    fun `toString never prints the store secret`() {
        val config = InstallmentsConfig.builder("store-42", "super-secret-value").build()
        assertThat(config.toString()).contains("store-42").doesNotContain("super-secret-value")
        assertThat(InstallmentsConfig.builder("store-42", "super-secret-value").toString())
            .doesNotContain("super-secret-value")
        val client = MonobankInstallments(config)
        assertThat(client.toString()).doesNotContain("super-secret-value")
        assertThat(client.callbacks.toString()).doesNotContain("super-secret-value")
        assertThat(chast.toString()).doesNotContain(STORE_SECRET)
        assertThat(MonobankInstallments.create("s", "secret-xyz").toString()).doesNotContain("secret-xyz")
    }

    @Test
    fun `config rejects blank credentials and header injection`() {
        assertThatThrownBy { InstallmentsConfig.builder(" ", "k") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InstallmentsConfig.builder("s", "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InstallmentsConfig.builder("s\r\nX: y", "k") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `retries can be disabled`() {
        enqueueJson("{}", status = 500)
        val noRetry = client(RetryPolicy.NONE)
        assertThatThrownBy { noRetry.orders.state("o") }.isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(RetryPolicy.of(1, Duration.ZERO, Duration.ZERO).maxRetries).isEqualTo(1)
    }
}
