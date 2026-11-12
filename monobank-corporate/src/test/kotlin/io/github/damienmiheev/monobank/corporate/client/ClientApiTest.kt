package io.github.damienmiheev.monobank.corporate.client

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class ClientApiTest : CorporateTestSupport() {
    private val requestId = "uLkwh3NzFAfEkj7urj5C7AU_"

    @Test
    fun `info reads every documented field`() {
        enqueueFixture("GET_personal_client_info.response.json")

        val info = mono.client(requestId).info()

        val request = takeSignedRequest(::timeRequestIdAndPath)
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/client-info")
        assertThat(request.getHeader("X-Request-Id")).isEqualTo(requestId)
        assertThat(info.clientId).isEqualTo("3MSaMMtczs")
        assertThat(info.name).isEqualTo("Мазепа Іван")
        assertThat(info.webHookUrl).isEqualTo("https://mysomesite.copm/some_random_data_for_security")
        assertThat(info.permissions).isEqualTo("psf")
        assertThat(info.accounts).singleElement().satisfies({
            assertThat(it.id).isEqualTo("kKGVoZuHWzqVoZuH")
            assertThat(it.sendId).isEqualTo("uHWzqVoZuH")
            assertThat(it.balance).isEqualTo(10_000_000)
            assertThat(it.creditLimit).isEqualTo(10_000_000)
            assertThat(it.type).isEqualTo(AccountType.BLACK)
            assertThat(it.currencyCode).isEqualTo(980)
            assertThat(it.cashbackType).isEqualTo(CashbackType.UAH)
            assertThat(it.maskedPan).containsExactly("537541******1234")
            assertThat(it.iban).isEqualTo("UA733220010000026201234567890")
        })
    }

    @Test
    fun `info without personal-data permission, unknown enums and extra fields`() {
        enqueueJson(
            """{"accounts":[{"id":"a1","balance":5,"currencyCode":840,"type":"madeInUkraine",
               "cashbackType":"Stars","newField":true},
               {"id":"a2","balance":0,"currencyCode":980,"type":"eAid","cashbackType":"None"}],"jars":[]}""",
        )

        val info = mono.client(requestId).info()

        assertThat(info.clientId).isNull()
        assertThat(info.name).isNull()
        assertThat(info.accounts!!.map { it.type }).containsExactly(AccountType.UNKNOWN, AccountType.E_AID)
        assertThat(info.accounts!!.map { it.cashbackType }).containsExactly(CashbackType.UNKNOWN, CashbackType.NONE)
        assertThat(info.accounts!![0].iban).isNull()
        assertThat(info.accounts!![0].maskedPan).isNull()
    }

    @Test
    fun `statement builds the path from the range and reads every field`() {
        enqueueFixture("GET_personal_statement_account_from_to.response.json")

        val items =
            mono.client(requestId).statement(
                ClientApi.DEFAULT_ACCOUNT,
                Instant.ofEpochSecond(1_546_304_461, 999_000_000),
                Instant.ofEpochSecond(1_546_306_461),
            )

        val request = takeSignedRequest(::timeRequestIdAndPath)
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/statement/0/1546304461/1546306461")
        assertThat(request.getHeader("X-Request-Id")).isEqualTo(requestId)
        val item = items.single()
        assertThat(item.id).isEqualTo("ZuHWzqkKGVo=")
        assertThat(item.time).isEqualTo(Instant.ofEpochSecond(1_554_466_347))
        assertThat(item.description).isEqualTo("Покупка щастя")
        assertThat(item.mcc).isEqualTo(7997)
        assertThat(item.originalMcc).isEqualTo(7997)
        assertThat(item.hold).isFalse()
        assertThat(item.amount).isEqualTo(-95_000)
        assertThat(item.operationAmount).isEqualTo(-95_000)
        assertThat(item.currencyCode).isEqualTo(980)
        assertThat(item.commissionRate).isZero()
        assertThat(item.cashbackAmount).isEqualTo(19_000)
        assertThat(item.balance).isEqualTo(10_050_000)
        assertThat(item.comment).isEqualTo("За каву")
        assertThat(item.receiptId).isEqualTo("XXXX-XXXX-XXXX-XXXX")
        assertThat(item.counterEdrpou).isEqualTo("3096889974")
        assertThat(item.counterIban).isEqualTo("UA898999980000355639201001404")
        assertThat(item.counterName).isEqualTo("ТОВАРИСТВО З ОБМЕЖЕНОЮ ВІДПОВІДАЛЬНІСТЮ «ВОРОНА»")
    }

    @Test
    fun `statement without an end time leaves it out of the path`() {
        enqueueJson("""[{"id":"x","time":1,"amount":1,"currencyCode":980}]""")

        val item = mono.client(requestId).statement("acc1", Instant.ofEpochSecond(TIME - 100)).single()

        val request = takeSignedRequest(::timeRequestIdAndPath)
        assertThat(request.path).isEqualTo("/personal/statement/acc1/${TIME - 100}")
        assertThat(item.description).isNull()
        assertThat(item.balance).isNull()
    }

    @Test
    fun `statement validates its arguments before sending`() {
        val client = mono.client(requestId)
        val from = Instant.ofEpochSecond(1_000_000)
        assertThatThrownBy { client.statement(" ", from, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { client.statement("0", from, from.minusSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { client.statement("0", from, from.plusSeconds(ClientApi.MAX_RANGE_SECONDS + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()

        enqueueJson("[]")
        assertThat(client.statement("0", from, from.plusSeconds(ClientApi.MAX_RANGE_SECONDS))).isEmpty()
    }

    @Test
    fun `401 means not approved or revoked and 404 is not found`() {
        enqueueJson("""{"errorDescription":"Request is unauthorized yet"}""", status = 401)
        enqueueJson("""{"errorDescription":"Data is not exist"}""", status = 404)

        assertThatThrownBy { mono.client(requestId).info() }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(401)
                assertThat(it.errorText).isEqualTo("Request is unauthorized yet")
            }
        assertThatThrownBy { mono.client(requestId).statement("0", Instant.ofEpochSecond(TIME - 1)) }
            .isInstanceOfSatisfying(MonobankNotFoundException::class.java) {
                assertThat(it.errorText).isEqualTo("Data is not exist")
            }
    }

    @Test
    fun `client rejects blank or multi-line request ids`() {
        assertThatThrownBy { mono.client("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { mono.client("a\r\nX-Evil: 1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(mono.client("r").toString()).isEqualTo("ClientApi(requestId=r)")
    }

    @Test
    fun `dot segments are rejected instead of sent`() {
        val client = mono.client(requestId)
        val from = Instant.ofEpochSecond(TIME - 10)
        assertThatThrownBy { client.statement("..", from, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { client.statement(".", from, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `without an end time the range is checked against the config clock`() {
        val client = mono.client(requestId)
        assertThatThrownBy { client.statement("0", Instant.ofEpochSecond(TIME - ClientApi.MAX_RANGE_SECONDS - 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("31 days")
        assertThat(server.requestCount).isZero()

        enqueueJson("[]")
        client.statement("0", Instant.ofEpochSecond(TIME - ClientApi.MAX_RANGE_SECONDS))
        assertThat(server.takeRequest().path).isEqualTo("/personal/statement/0/${TIME - ClientApi.MAX_RANGE_SECONDS}")
    }
}
