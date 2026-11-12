package io.github.damienmiheev.monobank.personal.statement

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.personal.PersonalRateLimiter
import io.github.damienmiheev.monobank.personal.PersonalTestSupport
import io.github.damienmiheev.monobank.personal.statement.StatementApi.Companion.MAX_RANGE_SECONDS
import io.github.damienmiheev.monobank.personal.statement.StatementApi.Companion.PAGE_SIZE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class StatementApiTest : PersonalTestSupport() {
    private val from = Instant.ofEpochSecond(1_700_000_000)

    private fun item(
        id: String,
        time: Long,
    ) = """{"id":"$id","time":$time,"amount":-100,"currencyCode":980}"""

    private fun page(items: List<String>) = items.joinToString(",", "[", "]")

    private fun paths(): List<String> = List(server.requestCount) { takeAuthenticatedRequest().path!! }

    @Test
    fun `list reads every documented field`() {
        enqueueFixture("GET_personal_statement_account_from_to.response.json")

        val items = mono.statements.list("kKGVoZuHWzqVoZuH", from, from.plusSeconds(3600))

        val request = takeAuthenticatedRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/statement/kKGVoZuHWzqVoZuH/1700000000/1700003600")
        assertThat(items).singleElement().satisfies({
            assertThat(it.id).isEqualTo("ZuHWzqkKGVo=")
            assertThat(it.time).isEqualTo(Instant.ofEpochSecond(1554466347))
            assertThat(it.description).isEqualTo("Покупка щастя")
            assertThat(it.mcc).isEqualTo(7997)
            assertThat(it.originalMcc).isEqualTo(7997)
            assertThat(it.hold).isFalse()
            assertThat(it.amount).isEqualTo(-95000)
            assertThat(it.operationAmount).isEqualTo(-95000)
            assertThat(it.currencyCode).isEqualTo(980)
            assertThat(it.commissionRate).isEqualTo(0)
            assertThat(it.cashbackAmount).isEqualTo(19000)
            assertThat(it.balance).isEqualTo(10050000)
            assertThat(it.comment).isEqualTo("За каву")
            assertThat(it.receiptId).isEqualTo("XXXX-XXXX-XXXX-XXXX")
            assertThat(it.invoiceId).isEqualTo("2103.в.27")
            assertThat(it.counterEdrpou).isEqualTo("3096889974")
            assertThat(it.counterIban).isEqualTo("UA898999980000355639201001404")
            assertThat(it.counterName).isEqualTo("ТОВАРИСТВО З ОБМЕЖЕНОЮ ВІДПОВІДАЛЬНІСТЮ «ВОРОНА»")
        })
    }

    @Test
    fun `list survives missing optional fields and unknown fields`() {
        enqueueJson("""[{"id":"x","time":1700000001,"amount":2500,"currencyCode":840,"brandNew":{"a":1}}]""")

        val item = mono.statements.list(StatementApi.DEFAULT_ACCOUNT, from).single()

        assertThat(item.amount).isEqualTo(2500)
        assertThat(item.currencyCode).isEqualTo(840)
        assertThat(item.description).isNull()
        assertThat(item.mcc).isNull()
        assertThat(item.hold).isNull()
        assertThat(item.operationAmount).isNull()
        assertThat(item.balance).isNull()
        assertThat(item.comment).isNull()
        assertThat(item.receiptId).isNull()
        assertThat(item.counterName).isNull()
    }

    @Test
    fun `list without to omits the last path segment`() {
        enqueueJson("[]")

        assertThat(mono.statements.list(StatementApi.DEFAULT_ACCOUNT, from)).isEmpty()

        assertThat(takeAuthenticatedRequest().path).isEqualTo("/personal/statement/0/1700000000")
    }

    @Test
    fun `list sends whole seconds and encodes the account`() {
        enqueueJson("[]")

        mono.statements.list("a/b c+d", from.plusMillis(999), from.plusSeconds(10).plusNanos(1))

        assertThat(takeAuthenticatedRequest().path).isEqualTo("/personal/statement/a%2Fb%20c%2Bd/1700000000/1700000010")
    }

    @Test
    fun `list accepts exactly 31 days and 1 hour and rejects one second more`() {
        enqueueJson("[]")
        mono.statements.list("0", from, from.plusSeconds(2_682_000))
        assertThat(takeAuthenticatedRequest().path).isEqualTo("/personal/statement/0/1700000000/1702682000")

        assertThatThrownBy { mono.statements.list("0", from, from.plusSeconds(2_682_001)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("listAll")
        assertThatThrownBy { mono.statements.list("0", from, from.minusSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { mono.statements.list(" ", from) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `list maps errorDescription of a 400`() {
        enqueueJson("""{"errorDescription":"Invalid account"}""", status = 400)

        assertThatThrownBy { mono.statements.list("nope", from) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("Invalid account")
            }
    }

    @Test
    fun `token-less client refuses statements before sending`() {
        val publicClient = client(token = null)

        assertThatThrownBy { publicClient.statements.list("0", from) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { publicClient.statements.listAll("0", from, from) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `listAll splits long ranges into windows, newest first, and merges them`() {
        val to = from.plusSeconds(2 * MAX_RANGE_SECONDS + 100)
        val t = to.epochSecond
        enqueueJson(page(listOf(item("c", t - 5), item("b", t - MAX_RANGE_SECONDS))))
        // The boundary second is requested twice: the same transaction comes back again.
        enqueueJson(page(listOf(item("b", t - MAX_RANGE_SECONDS), item("a2", t - MAX_RANGE_SECONDS - 10))))
        enqueueJson(page(listOf(item("a1", from.epochSecond))))

        val items = mono.statements.listAll("acc", from, to)

        assertThat(paths()).containsExactly(
            "/personal/statement/acc/${t - MAX_RANGE_SECONDS}/$t",
            "/personal/statement/acc/${t - 2 * MAX_RANGE_SECONDS}/${t - MAX_RANGE_SECONDS}",
            "/personal/statement/acc/${from.epochSecond}/${t - 2 * MAX_RANGE_SECONDS}",
        )
        assertThat(items.map { it.id }).containsExactly("c", "b", "a2", "a1")
    }

    @Test
    fun `listAll makes one call for a short range`() {
        enqueueJson(page(listOf(item("a", from.epochSecond + 1))))

        assertThat(mono.statements.listAll("0", from, from.plusSeconds(60))).hasSize(1)
        assertThat(paths()).containsExactly("/personal/statement/0/1700000000/1700000060")
    }

    @Test
    fun `listAll pages backwards while 500 items come back and drops duplicates`() {
        val to = from.plusSeconds(10_000)
        val t = to.epochSecond
        // 500 items, newest first, one per second: t, t-1, ..., t-499.
        enqueueJson(page(List(PAGE_SIZE) { item("i$it", t - it) }))
        // Next call starts at the oldest second, so the oldest item is returned again.
        enqueueJson(page(listOf(item("i499", t - 499), item("j1", t - 600), item("j2", t - 700))))

        val items = mono.statements.listAll("0", from, to)

        assertThat(paths()).containsExactly(
            "/personal/statement/0/1700000000/$t",
            "/personal/statement/0/1700000000/${t - 499}",
        )
        assertThat(items).hasSize(PAGE_SIZE + 2)
        assertThat(items.map { it.id }).doesNotHaveDuplicates()
        assertThat(items.first().id).isEqualTo("i0")
        assertThat(items.takeLast(2).map { it.id }).containsExactly("j1", "j2")
        assertThat(items.map { it.time }).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `listAll pages through several full pages`() {
        val t = from.epochSecond + 100_000
        enqueueJson(page(List(PAGE_SIZE) { item("a$it", t - it) }))
        enqueueJson(page(List(PAGE_SIZE) { item("b$it", t - 499 - it) }))
        enqueueJson(page(List(10) { item("c$it", t - 998 - it) }))

        val items = mono.statements.listAll("0", from, Instant.ofEpochSecond(t))

        assertThat(server.requestCount).isEqualTo(3)
        assertThat(items.map { it.id }.toSet()).hasSize(items.size)
        assertThat(items.map { it.time }).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `listAll steps past a second that holds a full page`() {
        val t = from.epochSecond + 1000
        enqueueJson(page(List(PAGE_SIZE) { item("s$it", t) }))
        enqueueJson(page(listOf(item("older", t - 3))))

        val items = mono.statements.listAll("0", from, Instant.ofEpochSecond(t))

        assertThat(paths()).containsExactly(
            "/personal/statement/0/1700000000/$t",
            "/personal/statement/0/1700000000/${t - 1}",
        )
        assertThat(items).hasSize(PAGE_SIZE + 1)
    }

    @Test
    fun `listAll stops at the start of the range even when the page is full`() {
        enqueueJson(page(List(PAGE_SIZE) { item("s$it", from.epochSecond) }))

        assertThat(mono.statements.listAll("0", from, from)).hasSize(PAGE_SIZE)
        assertThat(paths()).containsExactly("/personal/statement/0/1700000000/1700000000")
    }

    @Test
    fun `listAll validates its arguments`() {
        assertThatThrownBy { mono.statements.listAll("0", from, from.minusSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            mono.statements.listAll(
                "",
                from,
                from,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `listAll waits between requests when a rate limiter is configured`() {
        val time = FakeTime()
        val limited = client(rateLimiter = PersonalRateLimiter.create(Duration.ofSeconds(60), time, time.sleeper))
        val to = from.plusSeconds(MAX_RANGE_SECONDS + 1)
        enqueueJson("[]")
        enqueueJson("[]")

        limited.statements.listAll("0", from, to)

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(60))
    }

    @Test
    fun `listAll without a rate limiter surfaces monobank's 429`() {
        val noRetry = client(retryPolicy = RetryPolicy.NONE)
        enqueueJson("[]")
        enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429)

        assertThatThrownBy { noRetry.statements.listAll("0", from, from.plusSeconds(MAX_RANGE_SECONDS + 1)) }
            .isInstanceOf(MonobankRateLimitException::class.java)
    }
}
