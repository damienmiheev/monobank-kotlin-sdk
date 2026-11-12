package io.github.damienmiheev.monobank.personal.webhook

import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.personal.PersonalTestSupport
import io.github.damienmiheev.monobank.personal.statement.StatementItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class WebhookParserTest {
    private val body = PersonalTestSupport.resource("/webhook/statement-item.json")

    @Test
    fun `parses a StatementItem event with every field`() {
        val event = WebhookParser.parse(body)

        assertThat(event.type).isEqualTo(WebhookEventType.STATEMENT_ITEM)
        val data = event.data!!
        assertThat(data.account).isEqualTo("kKGVoZuHWzqVoZuH")
        val item = data.statementItem
        assertThat(item.id).isEqualTo("ZuHWzqkKGVo=")
        assertThat(item.time).isEqualTo(Instant.ofEpochSecond(1554466347))
        assertThat(item.description).isEqualTo("Покупка щастя")
        assertThat(item.mcc).isEqualTo(7997)
        assertThat(item.originalMcc).isEqualTo(7997)
        assertThat(item.hold).isFalse()
        assertThat(item.amount).isEqualTo(-95000)
        assertThat(item.operationAmount).isEqualTo(-95000)
        assertThat(item.currencyCode).isEqualTo(980)
        assertThat(item.commissionRate).isEqualTo(0)
        assertThat(item.cashbackAmount).isEqualTo(19000)
        assertThat(item.balance).isEqualTo(10050000)
        assertThat(item.comment).isEqualTo("За каву")
        assertThat(item.receiptId).isEqualTo("XXXX-XXXX-XXXX-XXXX")
        assertThat(item.invoiceId).isEqualTo("2103.в.27")
        assertThat(item.counterEdrpou).isEqualTo("3096889974")
        assertThat(item.counterIban).isEqualTo("UA898999980000355639201001404")
        assertThat(item.counterName).isEqualTo("ТОВАРИСТВО З ОБМЕЖЕНОЮ ВІДПОВІДАЛЬНІСТЮ «ВОРОНА»")
    }

    @Test
    fun `the statement item equals the one from the statement fixture`() {
        val fromStatement =
            MonobankJson
                .read(
                    PersonalTestSupport.fixture("GET_personal_statement_account_from_to.response.json").toByteArray(),
                    Array<StatementItem>::class.java,
                ).single()

        assertThat(WebhookParser.parse(body.toByteArray()).data!!.statementItem).isEqualTo(fromStatement)
    }

    @Test
    fun `parses an event with only the required fields`() {
        val event =
            WebhookParser.parse(
                """{"type":"StatementItem","data":{"account":"a",
                   "statementItem":{"id":"x","time":1700000000,"amount":100,"currencyCode":980,"new":true}},
                   "extra":1}""",
            )

        assertThat(event.data!!.statementItem.amount).isEqualTo(100)
        assertThat(event.data!!.statementItem.hold).isNull()
        assertThat(event.data!!.statementItem.comment).isNull()
    }

    @Test
    fun `unknown event types do not fail`() {
        val event = WebhookParser.parse("""{"type":"BalanceChanged","data":{"whatever":[1,2,3]}}""")

        assertThat(event.type).isEqualTo(WebhookEventType.UNKNOWN)
        assertThat(event.data).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not json",
            "[1]",
            "{}",
            """{"foo":1}""",
            """{"type":123}""",
            """{"type":null,"data":{}}""",
            """{"type":"StatementItem","data":null}""",
            // No amount.
            """{"type":"StatementItem","data":{"account":"a","statementItem":{"id":"x","time":1,"currencyCode":9}}}""",
            """{"type":"StatementItem"}""",
            """{"type":"StatementItem","data":{"account":"a"}}""",
            """{"type":"StatementItem","data":{"account":"a","statementItem":{"time":1,"amount":1}}}""",
        ],
    )
    fun `invalid events fail with a serialization error`(raw: String) {
        assertThatThrownBy { WebhookParser.parse(raw) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isNull()
            }
    }
}
