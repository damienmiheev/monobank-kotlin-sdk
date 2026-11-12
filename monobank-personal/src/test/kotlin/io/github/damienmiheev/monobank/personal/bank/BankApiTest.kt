package io.github.damienmiheev.monobank.personal.bank

import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.personal.MonobankPersonal
import io.github.damienmiheev.monobank.personal.PersonalTestSupport
import okhttp3.mockwebserver.MockResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

class BankApiTest : PersonalTestSupport() {
    @Test
    fun `currency reads every documented field without sending the token`() {
        enqueueFixture("GET_bank_currency.response.json")

        val rates = mono.bank.currency()

        val request = takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/bank/currency")
        assertThat(request.getHeader("X-Token")).isNull()
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
        assertThat(rates).singleElement().satisfies({
            assertThat(it.currencyCodeA).isEqualTo(840)
            assertThat(it.currencyCodeB).isEqualTo(980)
            assertThat(it.date).isEqualTo(Instant.ofEpochSecond(1552392228))
            assertThat(it.rateSell).isEqualByComparingTo("27")
            assertThat(it.rateBuy).isEqualByComparingTo("27.2")
            assertThat(it.rateCross).isEqualByComparingTo("27.1")
        })
    }

    @Test
    fun `currency keeps decimal rates exact and allows missing rates`() {
        enqueueJson(
            """[{"currencyCodeA":840,"currencyCodeB":980,"date":1700000000,"rateBuy":41.1,"rateSell":41.5003},
               {"currencyCodeA":985,"currencyCodeB":980,"date":1700000001,"rateCross":10.2186,"newField":"x"},
               {"currencyCodeA":978,"currencyCodeB":840}]""",
        )

        val rates = mono.bank.currency()

        assertThat(rates).hasSize(3)
        assertThat(rates[0].rateSell!!.toPlainString()).isEqualTo("41.5003")
        assertThat(rates[0].rateCross).isNull()
        assertThat(rates[1].rateBuy).isNull()
        assertThat(rates[1].rateSell).isNull()
        assertThat(rates[1].rateCross!!.toPlainString()).isEqualTo("10.2186")
        assertThat(rates[2].date).isNull()
        assertThat(rates[2].rateCross).isNull()
    }

    @Test
    fun `currency works on a client without a token`() {
        enqueueFixture("GET_bank_currency.response.json")
        val publicClient = client(token = null)

        assertThat(publicClient.bank.currency()).hasSize(1)
        assertThat(takeRequest().getHeader("X-Token")).isNull()
    }

    @Test
    fun `publicOnly creates a token-less client`() {
        val publicClient = MonobankPersonal.publicOnly()
        assertThat(publicClient.config.token).isNull()
        assertThat(publicClient.toString()).contains("token=null")
    }

    @Test
    fun `sync reads key, key id and millisecond server time`() {
        enqueueFixture("GET_bank_sync.response.json")

        val sync = mono.bank.sync()

        val request = takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/bank/sync")
        assertThat(request.getHeader("X-Token")).isNull()
        assertThat(sync.serverKeyId).isEqualTo("2626ff34473bb66260b930af946fa9641a06bcd4")
        assertThat(sync.serverPubKey)
            .isEqualTo("BNDZP+AGoRC+ER1plDSUCHOw2/aBNIocmD2gS/v34/b0iQ1HBo+oS3/f402e3OXA5uCxakSjuxGMP6X0XP9VIUk=")
        assertThat(sync.serverTimeMsec).isEqualTo(1755509467397)
        assertThat(sync.serverTime).isEqualTo(Instant.parse("2025-08-18T09:31:07.397Z"))
    }

    @Test
    fun `sync key is an uncompressed point and its id is the SHA-1 of it (docs example)`() {
        enqueueFixture("GET_bank_sync.response.json")
        val sync = mono.bank.sync()

        val point = Base64.getDecoder().decode(sync.serverPubKey)
        assertThat(point).hasSize(65)
        assertThat(point[0]).isEqualTo(4.toByte())
        val sha1 = MessageDigest.getInstance("SHA-1").digest(point).joinToString("") { "%02x".format(it) }
        assertThat(sha1).isEqualTo(sync.serverKeyId)
    }

    @Test
    fun `sync with a missing key fails as a serialization error`() {
        enqueueJson("""{"serverTimeMsec":1}""")

        assertThatThrownBy { mono.bank.sync() }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
    }

    @Test
    fun `sync tolerates a missing key and time`() {
        enqueueJson("""{"serverKeyId":"k"}""")

        val sync = mono.bank.sync()

        assertThat(sync.serverPubKey).isNull()
        assertThat(sync.serverTimeMsec).isNull()
        assertThat(sync.serverTime).isNull()
    }

    @Test
    fun `currency pair without currency codes fails instead of reading as 0`() {
        enqueueJson("""[{"currencyCodeB":980,"rateCross":1.5}]""")

        assertThatThrownBy { mono.bank.currency() }.isInstanceOf(MonobankSerializationException::class.java)
    }

    @Test
    fun `429 without Retry-After on a public endpoint is thrown at once with a 60 s wait`() {
        enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429)

        assertThatThrownBy { mono.bank.currency() }
            .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(60))
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `429 exposes Retry-After and errorDescription`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "42")
                .setBody("""{"errorDescription":"Too many requests"}"""),
        )

        assertThatThrownBy { client(retryPolicy = RetryPolicy.NONE).bank.currency() }
            .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                assertThat(it.statusCode).isEqualTo(429)
                assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(42))
                assertThat(it.errorText).isEqualTo("Too many requests")
                assertThat(it.errorCode).isNull()
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
    }

    @Test
    fun `429 is retried after the Retry-After wait`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("""{"errorDescription":"Too many requests"}"""),
        )
        enqueueFixture("GET_bank_currency.response.json")

        assertThat(mono.bank.currency()).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(retryWaits).containsExactly(Duration.ofSeconds(7))
    }

    @Test
    fun `server errors are retried, then thrown with an unknown outcome`() {
        enqueueJson("""{"errorDescription":"oops"}""", status = 502)
        enqueueJson("not json", status = 503)
        enqueueJson("", status = 500)

        assertThatThrownBy { mono.bank.sync() }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.statusCode).isEqualTo(500)
                assertThat(it.errorText).isNull()
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(3)
    }
}
