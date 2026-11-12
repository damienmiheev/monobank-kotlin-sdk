package io.github.damienmiheev.monobank.personal.client

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.personal.PersonalTestSupport
import okhttp3.mockwebserver.MockResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class ClientApiTest : PersonalTestSupport() {
    @Test
    fun `info reads every documented field`() {
        enqueueFixture("GET_personal_client_info.response.json")

        val info = mono.client.info()

        val request = takeAuthenticatedRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/client-info")
        assertThat(request.bodySize).isZero()
        assertThat(info.clientId).isEqualTo("3MSaMMtczs")
        assertThat(info.name).isEqualTo("Мазепа Іван")
        assertThat(info.webHookUrl).isEqualTo("https://example.com/some_random_data_for_security")
        assertThat(info.permissions).isEqualTo("psfj")
        assertThat(info.accounts).singleElement().satisfies({
            assertThat(it.id).isEqualTo("kKGVoZuHWzqVoZuH")
            assertThat(it.sendId).isEqualTo("uHWzqVoZuH")
            assertThat(it.balance).isEqualTo(10_000_000L)
            assertThat(it.creditLimit).isEqualTo(10_000_000L)
            assertThat(it.type).isEqualTo(AccountType.BLACK)
            assertThat(it.currencyCode).isEqualTo(980)
            assertThat(it.cashbackType).isEqualTo(CashbackType.UAH)
            assertThat(it.maskedPan).containsExactly("537541******1234")
            assertThat(it.iban).isEqualTo("UA733220010000026201234567890")
        })
        assertThat(info.jars).singleElement().satisfies({
            assertThat(it.id).isEqualTo("kKGVoZuHWzqVoZuH")
            assertThat(it.sendId).isEqualTo("uHWzqVoZuH")
            assertThat(it.title).isEqualTo("На тепловізор")
            assertThat(it.description).isEqualTo("На тепловізор")
            assertThat(it.currencyCode).isEqualTo(980)
            assertThat(it.balance).isEqualTo(1_000_000L)
            assertThat(it.goal).isEqualTo(10_000_000L)
        })
        assertThat(info.managedClients).singleElement().satisfies({ managed ->
            assertThat(managed.clientId).isEqualTo("kKGVoZuHWzqVoZuH")
            // The docs' example sends the tax number as a JSON number.
            assertThat(managed.tin).isEqualTo("1234567890")
            assertThat(managed.name).isEqualTo("Шевченко Роман Петрович")
            assertThat(managed.accounts).singleElement().satisfies({
                assertThat(it.id).isEqualTo("kKGVoZuHWzqVoZuH")
                assertThat(it.balance).isEqualTo(10_000_000L)
                assertThat(it.creditLimit).isEqualTo(10_000_000L)
                assertThat(it.type).isEqualTo(AccountType.FOP)
                assertThat(it.currencyCode).isEqualTo(980)
                assertThat(it.iban).isEqualTo("UA733220010000026201234567890")
            })
        })
    }

    @Test
    fun `info reads a tax number sent as a string`() {
        enqueueJson("""{"clientId":"c","managedClients":[{"clientId":"m","tin":"0123456789"}]}""")

        assertThat(
            mono.client
                .info()
                .managedClients!!
                .single()
                .tin,
        ).isEqualTo("0123456789")
    }

    @Test
    fun `info survives missing optional fields`() {
        enqueueJson(
            """{"clientId":"c","accounts":[{"id":"a","balance":-150,"currencyCode":840}],
               "jars":[{"id":"j","balance":0,"currencyCode":980}],"managedClients":[{"clientId":"m"}]}""",
        )

        val info = mono.client.info()

        assertThat(info.name).isNull()
        assertThat(info.webHookUrl).isNull()
        assertThat(info.permissions).isNull()
        val account = info.accounts!!.single()
        assertThat(account.balance).isEqualTo(-150)
        assertThat(account.type).isNull()
        assertThat(account.cashbackType).isNull()
        assertThat(account.maskedPan).isNull()
        assertThat(account.iban).isNull()
        assertThat(account.creditLimit).isNull()
        assertThat(info.jars!!.single().goal).isNull()
        assertThat(info.managedClients!!.single().accounts).isNull()
    }

    @Test
    fun `info survives an empty client`() {
        enqueueJson("""{"clientId":"c"}""")

        val info = mono.client.info()

        assertThat(info.accounts).isNull()
        assertThat(info.jars).isNull()
        assertThat(info.managedClients).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "black, BLACK",
        "white, WHITE",
        "platinum, PLATINUM",
        "iron, IRON",
        "fop, FOP",
        "yellow, YELLOW",
        "eAid, E_AID",
        "madeInUkraine, UNKNOWN",
        "BLACK, UNKNOWN",
    )
    fun `every account type is read, unknown ones as UNKNOWN`(
        wire: String,
        expected: AccountType,
    ) {
        enqueueJson("""{"clientId":"c","accounts":[{"id":"a","balance":1,"currencyCode":980,"type":"$wire"}]}""")

        assertThat(
            mono.client
                .info()
                .accounts!!
                .single()
                .type,
        ).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("None, NONE", "UAH, UAH", "Miles, MILES", "Points, UNKNOWN", "none, UNKNOWN")
    fun `every cashback type is read, unknown ones as UNKNOWN`(
        wire: String,
        expected: CashbackType,
    ) {
        enqueueJson(
            """{"clientId":"c","accounts":[{"id":"a","balance":1,"currencyCode":980,"cashbackType":"$wire"}]}""",
        )

        assertThat(
            mono.client
                .info()
                .accounts!!
                .single()
                .cashbackType,
        ).isEqualTo(expected)
    }

    @Test
    fun `managed account with an unknown type is read as UNKNOWN`() {
        enqueueJson(
            """{"clientId":"c","managedClients":[{"clientId":"m",
               "accounts":[{"id":"a","balance":1,"currencyCode":980,"type":"tov"}]}]}""",
        )

        assertThat(
            mono.client
                .info()
                .managedClients!!
                .single()
                .accounts!!
                .single()
                .type,
        ).isEqualTo(AccountType.UNKNOWN)
    }

    @Test
    fun `info is retried on server errors`() {
        enqueueJson("", status = 502)
        enqueueFixture("GET_personal_client_info.response.json")

        assertThat(mono.client.info().clientId).isEqualTo("3MSaMMtczs")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `403 maps to MonobankAuthException with errorDescription`() {
        enqueueJson("""{"errorDescription":"Unknown 'X-Token'"}""", status = 403)

        assertThatThrownBy { mono.client.info() }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(403)
                assertThat(it.errorText).isEqualTo("Unknown 'X-Token'")
                assertThat(it.errorCode).isNull()
                assertThat(it.responseBody).contains("Unknown 'X-Token'")
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
                assertThat(it.message).contains("403").contains("Unknown 'X-Token'").doesNotContain(TOKEN)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `400 maps to MonobankBadRequestException with errorDescription`() {
        enqueueJson("""{"errorDescription":"Missing one of required headers 'X-Token' or 'X-Key-Id'"}""", 400)

        assertThatThrownBy { mono.client.info() }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.statusCode).isEqualTo(400)
                assertThat(it.errorText).isEqualTo("Missing one of required headers 'X-Token' or 'X-Key-Id'")
            }
    }

    @Test
    fun `429 without Retry-After is retried with backoff and then thrown`() {
        repeat(2) { enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429) }

        val oneRetry = client(retryPolicy = RetryPolicy.of(1, Duration.ZERO, Duration.ZERO))
        assertThatThrownBy { oneRetry.client.info() }
            .isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
                assertThat(it.retryAfter).isNull()
                assertThat(it.errorText).isEqualTo("Too many requests")
            }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `setWebhook posts the URL and ignores the answer body`() {
        enqueueJson("""{"status":"ok"}""")

        mono.client.setWebhook("https://example.com/mono/s3cr3t")

        val request = takeAuthenticatedRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/personal/webhook")
        assertThat(request.getHeader("Content-Type")).startsWith("application/json")
        val body = MonobankJson.mapper.readTree(request.body.readUtf8())
        assertThat(body.size()).isEqualTo(1)
        assertThat(body.path("webHookUrl").asText()).isEqualTo("https://example.com/mono/s3cr3t")
    }

    @Test
    fun `setWebhook matches the docs request fixture`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val expected = MonobankJson.mapper.readTree(fixture("POST_personal_webhook.request.json"))

        mono.client.setWebhook(expected.path("webHookUrl").asText())

        assertThat(MonobankJson.mapper.readTree(takeRequest().body.readUtf8())).isEqualTo(expected)
    }

    @Test
    fun `setWebhook is retried after an unknown outcome because it is idempotent`() {
        enqueueJson("", status = 500)
        enqueueJson("")

        mono.client.setWebhook("https://example.com/hook")

        assertThat(server.requestCount).isEqualTo(2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "example.com/hook", "ftp://example.com/hook", "https://", "https://exa mple.com"])
    fun `setWebhook rejects invalid URLs before sending`(url: String) {
        assertThatThrownBy { mono.client.setWebhook(url) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `token-less client refuses personal calls before sending`() {
        val publicClient = client(token = null)

        assertThatThrownBy { publicClient.client.info() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("token")
        assertThatThrownBy { publicClient.client.setWebhook("https://example.com") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(server.requestCount).isZero()
    }
}
