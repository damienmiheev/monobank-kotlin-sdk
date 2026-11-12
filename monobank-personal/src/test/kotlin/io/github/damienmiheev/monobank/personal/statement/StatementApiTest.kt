package io.github.damienmiheev.monobank.personal.statement

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankRateLimitException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RetryPolicy
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

    private var taken = 0

    /** Paths of the requests sent since the previous call. */
    private fun paths(): List<String> {
        val count = server.requestCount - taken
        taken = server.requestCount
        return List(count) { takeAuthenticatedRequest().path!! }
    }

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
        val to = from.plusSeconds(2 * W + 100)
        val t = to.epochSecond
        enqueueJson(page(listOf(item("c", t - 5), item("b", t - W))))
        // The boundary second is requested twice: the same transaction comes back again.
        enqueueJson(page(listOf(item("b", t - W), item("a2", t - W - 10))))
        enqueueJson(page(listOf(item("a1", from.epochSecond))))

        val items = mono.statements.listAll("acc", from, to)

        assertThat(paths()).containsExactly(
            "/personal/statement/acc/${t - W}/$t",
            "/personal/statement/acc/${t - 2 * W}/${t - W}",
            "/personal/statement/acc/${from.epochSecond}/${t - 2 * W}",
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
    fun `listAll uses the client's rate limiter and no private pacer`() {
        val time = FakeTime()
        val limited = client(rateLimiter = time.limiter(Duration.ofSeconds(60)))
        enqueueJson("[]")
        enqueueJson("[]")

        limited.statements.listAll("0", from, from.plusSeconds(W + 1))

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(60))
        assertThat(pacerTime.sleeps).isEmpty()
    }

    @Test
    fun `listAll paces itself 61 s apart when the client has no rate limiter`() {
        val defaultRetries = client(retryPolicy = RetryPolicy.DEFAULT)
        enqueueJson("[]")
        enqueueJson("[]")
        enqueueJson("[]")

        defaultRetries.statements.listAll("0", from, from.plusSeconds(2 * W + 1))

        assertThat(server.requestCount).isEqualTo(3)
        assertThat(pacerTime.sleeps).containsExactly(Duration.ofSeconds(61), Duration.ofSeconds(61))
    }

    @Test
    fun `listAll with a 429 from another caller fails at once with retryAfter 60 s`() {
        enqueueJson("[]")
        enqueueJson("""{"errorDescription":"Too many requests"}""", status = 429)

        assertThatThrownBy {
            client(retryPolicy = RetryPolicy.DEFAULT).statements.listAll(
                "0",
                from,
                from.plusSeconds(W + 1),
            )
        }.isInstanceOfSatisfying(MonobankRateLimitException::class.java) {
            assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(60))
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `listAll windows are one second below the limit`() {
        assertThat(W).isEqualTo(MAX_RANGE_SECONDS - 1)

        enqueueJson("[]")
        mono.statements.listAll("0", from, from.plusSeconds(W))
        assertThat(paths()).containsExactly("/personal/statement/0/1700000000/${from.epochSecond + W}")

        val f = from.epochSecond
        enqueueJson("[]")
        enqueueJson("[]")
        mono.statements.listAll("0", from, from.plusSeconds(MAX_RANGE_SECONDS))
        assertThat(paths()).containsExactly(
            "/personal/statement/0/${f + 1}/${f + MAX_RANGE_SECONDS}",
            "/personal/statement/0/$f/${f + 1}",
        )

        enqueueJson("[]")
        enqueueJson("[]")
        mono.statements.listAll("0", from, from.plusSeconds(W + 1))
        assertThat(paths()).containsExactly(
            "/personal/statement/0/${f + 1}/${f + W + 1}",
            "/personal/statement/0/$f/${f + 1}",
        )
    }

    @Test
    fun `listAll handles a full page that ends exactly at a window edge`() {
        val t = from.epochSecond + W + 10
        val edge = t - W
        // Window 1: a full page whose oldest item sits on the window start.
        enqueueJson(page(List(PAGE_SIZE - 1) { item("n$it", t - it) } + item("edge1", edge)))
        // Same window, paged at the edge second: the edge item again plus one more at that second.
        enqueueJson(page(listOf(item("edge1", edge), item("edge2", edge))))
        // Window 2 shares the edge second.
        enqueueJson(page(listOf(item("edge2", edge), item("edge1", edge), item("old", from.epochSecond))))

        val items = mono.statements.listAll("0", from, Instant.ofEpochSecond(t))

        assertThat(paths()).containsExactly(
            "/personal/statement/0/$edge/$t",
            "/personal/statement/0/$edge/$edge",
            "/personal/statement/0/${from.epochSecond}/$edge",
        )
        assertThat(items.map { it.id }).doesNotHaveDuplicates().contains("edge1", "edge2", "old", "n0")
        assertThat(items).hasSize(PAGE_SIZE + 2)
        assertThat(items.last().id).isEqualTo("old")
    }

    @Test
    fun `listAll stops when a full page already reaches before the start`() {
        val t = from.epochSecond + 300
        // The server ignored "from" and returned older items too.
        enqueueJson(page(List(PAGE_SIZE) { item("x$it", t - it) }))

        assertThat(mono.statements.listAll("0", from, Instant.ofEpochSecond(t))).hasSize(PAGE_SIZE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `listAll page consumer gets only new items and keeps them when a later call fails`() {
        val t = from.epochSecond + 10_000
        enqueueJson(page(List(PAGE_SIZE) { item("p$it", t - it) }))
        enqueueJson(page(List(PAGE_SIZE) { item("q$it", t - 499 - it) }))
        enqueueJson("""{"errorDescription":"oops"}""", status = 500)
        val pages = mutableListOf<List<StatementItem>>()

        assertThatThrownBy {
            client(
                retryPolicy = RetryPolicy.NONE,
            ).statements.listAll("0", from, Instant.ofEpochSecond(t)) { pages += it }
        }.isInstanceOf(MonobankServerException::class.java)

        assertThat(pages).hasSize(2)
        assertThat(pages[0]).hasSize(PAGE_SIZE)
        assertThat(pages[1]).hasSize(PAGE_SIZE)
        assertThat((pages[0] + pages[1]).map { it.id }).doesNotHaveDuplicates()
        // Resume point: the oldest delivered transaction.
        assertThat(pages[1].minOf { it.time }).isEqualTo(Instant.ofEpochSecond(t - 998))
    }

    @Test
    fun `listAll without to ends now`() {
        enqueueJson("[]")
        val before = Instant.now().epochSecond

        mono.statements.listAll("0", Instant.now().minusSeconds(3600))

        val to = takeAuthenticatedRequest().path!!.substringAfterLast('/').toLong()
        assertThat(to).isBetween(before, Instant.now().epochSecond)
    }

    @Test
    fun `dot segments are rejected before sending`() {
        listOf(".", "..").forEach { account ->
            assertThatThrownBy {
                mono.statements.list(
                    account,
                    from,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { mono.statements.listAll(account, from, from) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `missing required statement fields fail instead of reading as 0`() {
        val bodies =
            listOf(
                """[{"id":"x","time":1700000001,"currencyCode":980}]""",
                """[{"id":"x","time":1700000001,"amount":null,"currencyCode":980}]""",
                """[{"id":"x","time":1700000001,"amount":1}]""",
                """[{"id":"x","amount":1,"currencyCode":980}]""",
                """[{"time":1700000001,"amount":1,"currencyCode":980}]""",
            )
        bodies.forEach { enqueueJson(it) }

        bodies.forEach { body ->
            assertThatThrownBy { mono.statements.list("0", from) }
                .`as`(body)
                .isInstanceOf(MonobankSerializationException::class.java)
        }
    }

    private companion object {
        const val W = StatementApi.WINDOW_SECONDS
    }
}
