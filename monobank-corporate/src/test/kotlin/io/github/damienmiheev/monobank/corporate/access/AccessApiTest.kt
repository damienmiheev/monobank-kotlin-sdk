package io.github.damienmiheev.monobank.corporate.access

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccessApiTest : CorporateTestSupport() {
    @Test
    fun `request creates an access request without a body`() {
        enqueueFixture("POST_personal_auth_request.response.json")

        val access = mono.access.request()

        assertThat(access.tokenRequestId).isEqualTo("uLkwh3NzFAfEkj7urj5C7AU_")
        assertThat(access.acceptUrl).isEqualTo("https://mbnk.app/auth/uLkwh3NzFAfEkj7urj5C7AU_")
        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/personal/auth/request")
        assertThat(request.bodySize).isZero()
        assertThat(request.getHeader("X-Callback")).isNull()
        assertThat(request.getHeader("X-Request-Id")).isNull()
        assertThat(request.getHeader("X-Permissions")).isNull()
    }

    @Test
    fun `request sends the optional callback`() {
        enqueueFixture("POST_personal_auth_request.response.json")

        mono.access.request("https://example.com/some_random_data_for_security")

        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.getHeader("X-Callback")).isEqualTo("https://example.com/some_random_data_for_security")
        assertThatThrownBy { mono.access.request("https://a\nb") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `request is not retried after an unknown outcome`() {
        enqueueJson("{}", status = 503)

        assertThatThrownBy { mono.access.request() }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `request fails cleanly when the answer lacks the request id`() {
        enqueueJson("""{"acceptUrl":"https://mbnk.app/auth/x"}""")

        assertThatThrownBy { mono.access.request() }.isInstanceOf(MonobankSerializationException::class.java)
    }

    @Test
    fun `status is APPROVED on 200 and signs the request id`() {
        enqueueFixture("GET_personal_auth_request.response.json")

        val status = mono.access.status("uLkwh3NzFAfEkj7urj5C7AU_")

        assertThat(status).isEqualTo(AccessRequestStatus.APPROVED)
        val request = takeSignedRequest(::timeRequestIdAndPath)
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/auth/request")
        assertThat(request.getHeader("X-Request-Id")).isEqualTo("uLkwh3NzFAfEkj7urj5C7AU_")
    }

    @Test
    fun `status is PENDING on 401 and NOT_FOUND on 404, without retries`() {
        enqueueJson("""{"errorDescription":"Request is unauthorized yet"}""", status = 401)
        enqueueJson("""{"errorDescription":"Request is not exist"}""", status = 404)

        assertThat(mono.access.status("r1")).isEqualTo(AccessRequestStatus.PENDING)
        assertThat(mono.access.status("r2")).isEqualTo(AccessRequestStatus.NOT_FOUND)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `status throws on 403 and is retried on 5xx`() {
        enqueueJson("{}", status = 502)
        enqueueJson("""{"errorDescription":"Forbidden"}""", status = 403)

        assertThatThrownBy { mono.access.status("r1") }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(403)
                assertThat(it.errorText).isEqualTo("Forbidden")
            }
        assertThat(server.requestCount).isEqualTo(2)
        assertThatThrownBy { mono.access.status("") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a 401 that is not 'unauthorized yet' is thrown, not reported as pending`() {
        enqueueJson("""{"errorDescription":"Invalid signature"}""", status = 401)
        enqueueJson("", status = 401)
        enqueueJson("""{"errorDescription":"request is UNAUTHORIZED YET"}""", status = 401)

        assertThatThrownBy { mono.access.status("r1") }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(401)
                assertThat(it.errorText).isEqualTo("Invalid signature")
                assertThat(it.responseBody).contains("Invalid signature")
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
        assertThatThrownBy { mono.access.status("r1") }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) { assertThat(it.errorText).isNull() }
        assertThat(mono.access.status("r1")).isEqualTo(AccessRequestStatus.PENDING)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `status enum keeps room for future states`() {
        assertThat(AccessRequestStatus.values().map { it.name })
            .containsExactly("APPROVED", "PENDING", "NOT_FOUND", "UNKNOWN")
    }
}
