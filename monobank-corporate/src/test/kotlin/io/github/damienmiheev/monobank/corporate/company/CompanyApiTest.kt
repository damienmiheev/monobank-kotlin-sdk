package io.github.damienmiheev.monobank.corporate.company

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class CompanyApiTest : CorporateTestSupport() {
    @Test
    fun `register sends every field, without X-Key-Id, and reads the status`() {
        enqueueFixture("POST_personal_auth_registration.response.json")
        val expected = MonobankJson.mapper.readTree(fixture("POST_personal_auth_registration.request.json"))

        val response =
            mono.company.register(
                RegistrationRequest
                    .builder()
                    .pubkey(expected.path("pubkey").asText())
                    .name(expected.path("name").asText())
                    .description(expected.path("description").asText())
                    .contactPerson(expected.path("contactPerson").asText())
                    .phone(expected.path("phone").asText())
                    .email(expected.path("email").asText())
                    .logo(expected.path("logo").asText())
                    .build(),
            )

        assertThat(response.status).isEqualTo(RegistrationStatus.NEW)
        val request = takeSignedRequest(::timeAndPath, keyId = null)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/personal/auth/registration")
        assertThat(request.getHeader("Content-Type")).startsWith("application/json")
        assertThat(request.getHeader("X-Request-Id")).isNull()
        assertThat(MonobankJson.mapper.readTree(request.body.readUtf8())).isEqualTo(expected)
    }

    @Test
    fun `register is not retried after an unknown outcome`() {
        enqueueJson("""{"errorDescription":"oops"}""", status = 500)

        assertThatThrownBy { mono.company.register(minimalRegistration()) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
                assertThat(it.errorText).isEqualTo("oops")
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `registration request helpers encode the key and the logo`() {
        val request = minimalRegistration()
        assertThat(request.pubkey).isEqualTo(TEST_KEY.publicKey.publicKeyPemBase64)
        assertThat(Base64.getDecoder().decode(request.logo)).containsExactly(1, 2, 3)
        assertThat(request.toString()).doesNotContain(request.pubkey).doesNotContain(request.logo).contains("chars")
        assertThatThrownBy { RegistrationRequest.builder().name("x").build() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pubkey is required")
    }

    @Test
    fun `registration status posts the key and reads status and key id`() {
        enqueueJson("""{"status":"Approved","keyId":"$TEST_KEY_ID"}""")

        val status = mono.company.registrationStatus(TEST_KEY.publicKey)

        assertThat(status.status).isEqualTo(RegistrationStatus.APPROVED)
        assertThat(status.keyId).isEqualTo(TEST_KEY_ID)
        val request = takeSignedRequest(::timeAndPath, keyId = null)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/personal/auth/registration/status")
        val body = MonobankJson.mapper.readTree(request.body.readUtf8())
        assertThat(body.fieldNames().asSequence().toList()).containsExactly("pubkey")
        assertThat(body.path("pubkey").asText()).isEqualTo(TEST_KEY.publicKey.publicKeyPemBase64)
    }

    @Test
    fun `registration status reads the fixture, unknown statuses and is retried`() {
        enqueueJson("{}", status = 502)
        enqueueFixture("POST_personal_auth_registration_status.response.json")
        enqueueJson("""{"status":"UnderReview"}""")

        val fixture = mono.company.registrationStatus("cHVia2V5")
        val unknown = mono.company.registrationStatus("cHVia2V5")

        assertThat(fixture.status).isEqualTo(RegistrationStatus.APPROVED)
        assertThat(fixture.keyId).isEqualTo("string")
        assertThat(unknown.status).isEqualTo(RegistrationStatus.UNKNOWN)
        assertThat(unknown.keyId).isNull()
        assertThat(server.requestCount).isEqualTo(3)
        assertThatThrownBy { mono.company.registrationStatus(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `settings reads every field`() {
        enqueueFixture("GET_personal_corp_settings.response.json")

        val settings = mono.company.settings()

        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/corp/settings")
        assertThat(request.getHeader("X-Request-Id")).isNull()
        assertThat(request.bodySize).isZero()
        assertThat(settings.id).isNull()
        assertThat(settings.pubkey).startsWith("LS0tLS1CRUdJTiBQVUJMSUMgS0VZ")
        assertThat(settings.name).isEqualTo("Компанія")
        assertThat(settings.permission).isEqualTo("psf")
        assertThat(settings.logo).startsWith("iVBORw0KGgo")
        assertThat(settings.webhook).isEqualTo(
            "https://example.com/mono/corp/webhook/maybesomegibberishuniquestringbutnotnecessarily",
        )
        assertThat(settings.toString()).doesNotContain(settings.logo).contains("Компанія")
    }

    @Test
    fun `settings with a request id sends it without signing it, and tolerates a numeric id`() {
        enqueueJson("""{"id":42,"name":"x"}""")

        val settings = mono.company.settings("uLkwh3NzFAfEkj7urj5C7AU_")

        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.getHeader("X-Request-Id")).isEqualTo("uLkwh3NzFAfEkj7urj5C7AU_")
        assertThat(settings.id).isEqualTo("42")
        assertThat(settings.permission).isNull()
    }

    @Test
    fun `set webhook posts the url`() {
        enqueueFixture("POST_personal_corp_webhook.response.json")
        val url = "https://example.com/mono/corp/webhook/maybesomegibberishuniquestringbutnotnecessarily"

        mono.company.setWebhook(url)

        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/personal/corp/webhook")
        assertThat(MonobankJson.mapper.readTree(request.body.readUtf8()))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_personal_corp_webhook.request.json")))
        assertThat(request.getHeader("X-Request-Id")).isNull()
    }

    @Test
    fun `set webhook with an empty url sends it, and rejects multi-line values`() {
        enqueueJson("{}")
        mono.company.setWebhook("", "req-1")
        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.body.readUtf8()).isEqualTo("""{"webHookUrl":""}""")
        assertThat(request.getHeader("X-Request-Id")).isEqualTo("req-1")

        assertThatThrownBy { mono.company.setWebhook("https://a\r\nX: y") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { mono.company.settings("a\nb") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `errors expose errorDescription and map by status`() {
        enqueueJson("""{"errorDescription":"Invalid webHookUrl"}""", status = 400)
        assertThatThrownBy { mono.company.setWebhook("https://example.com") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("Invalid webHookUrl")
                assertThat(it.errorCode).isNull()
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
                assertThat(it.message).contains("HTTP 400").contains("Invalid webHookUrl")
            }

        enqueueJson("""{"errorDescription":"Forbidden"}""", status = 403)
        assertThatThrownBy { mono.company.settings() }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(403)
                assertThat(it.errorText).isEqualTo("Forbidden")
            }

        enqueueJson("<html>bad gateway</html>", status = 400)
        assertThatThrownBy { mono.company.settings() }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isNull()
                assertThat(it.responseBody).contains("bad gateway")
            }
    }

    @Test
    fun `429 is retried even for registration, then thrown`() {
        val mono = client(retryPolicy = RetryPolicy.of(1, java.time.Duration.ZERO, java.time.Duration.ZERO))
        enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429)
        enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429)

        assertThatThrownBy { mono.company.register(minimalRegistration()) }
            .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                assertThat(it.errorText).isEqualTo("Too many requests")
            }
        assertThat(server.requestCount).isEqualTo(2)
    }

    private fun minimalRegistration(): RegistrationRequest =
        RegistrationRequest
            .builder()
            .pubkey(TEST_KEY.publicKey)
            .name("ТОВ \"Ворона\"")
            .description("PFM")
            .contactPerson("Роман")
            .phone("380671234567")
            .email("a@example.com")
            .logo(byteArrayOf(1, 2, 3))
            .build()
}
