package io.github.damienmiheev.monobank.installments.client

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class ClientApiTest : InstallmentsTestSupport() {
    @Test
    fun `validate sends the phone and reads found`() {
        enqueueFixture("POST_api_v2_client_validate.response.json")

        val response = chast.clients.validate("+380501234567")

        assertThat(response.found).isTrue()
        assertThat(takeJson("/api/v2/client/validate"))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_v2_client_validate.request.json")))
    }

    @Test
    fun `validate reads not found`() {
        enqueueJson("""{"found":false}""")
        assertThat(chast.clients.validate("+380501234562").found).isFalse()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated validate reads the client and hides the tax number in toString`() {
        enqueueFixture("POST_api_client_validate.response.json")

        val response = chast.clients.validateWithDetails("+380501234567")

        assertThat(takeJson("/api/client/validate"))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_client_validate.request.json")))
        assertThat(response.found).isTrue()
        val client = response.client!!
        assertThat(client.firstName).isEqualTo("Олександр")
        assertThat(client.lastName).isEqualTo("Сідоренко")
        assertThat(client.middleName).isEqualTo("Ігорович")
        assertThat(client.inn).isEqualTo("1234567890")
        assertThat(response.toString()).doesNotContain("1234567890").contains("Олександр")
    }

    @Test
    fun `rate limit is retried and then thrown`() {
        enqueueJson("""{"message":"Перевищено ліміт запитів"}""", status = 429)
        enqueueJson("""{"message":"Перевищено ліміт запитів"}""", status = 429)

        assertThatThrownBy {
            client(RetryPolicy.of(1, Duration.ZERO, Duration.ZERO)).clients.validate("+380501234567")
        }.isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
            assertThat(it.errorText).isEqualTo("Перевищено ліміт запитів")
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `invalid signature is an auth error`() {
        enqueueJson("""{"message":"Підпис не передано або не валідний"}""", status = 401)

        assertThatThrownBy { chast.clients.validate("+380501234567") }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(401)
                assertThat(it.errorText).isEqualTo("Підпис не передано або не валідний")
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `blank phone is rejected locally`() {
        assertThatThrownBy { chast.clients.validate(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }
}
