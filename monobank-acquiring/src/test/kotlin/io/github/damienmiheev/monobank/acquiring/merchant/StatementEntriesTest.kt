package io.github.damienmiheev.monobank.acquiring.merchant

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class StatementEntriesTest : AcquiringTestSupport() {
    private val from = Instant.parse("2024-05-01T00:00:00Z")

    private fun entry(
        invoiceId: String,
        reference: String,
        amount: String = "4200",
    ) = """{"invoiceId":"$invoiceId","status":"success","date":"2024-05-01T12:30:00+03:00",""" +
        """"amount":$amount,"ccy":980,"reference":"$reference","maskedPan":"444403******1902"}"""

    private fun statementWith(vararg entries: String) = enqueueJson("""{"list":[${entries.joinToString(",")}]}""")

    @Test
    fun `an unreadable entry is returned raw and does not hide the others`() {
        statementWith(
            entry("inv-1", "ref-1"),
            entry("inv-2", "ref-2", amount = "\"not a number\""),
            entry("inv-3", "ref-3"),
        )

        val entries = mono.merchant.statementEntries(from)

        assertThat(entries.items.map { it.invoiceId }).containsExactly("inv-1", "inv-3")
        assertThat(entries.unreadable).singleElement().satisfies({
            assertThat(it.invoiceId).isEqualTo("inv-2")
            assertThat(it.reference).isEqualTo("ref-2")
            assertThat(it.rawJson).contains("not a number")
            assertThat(it.error).contains("amount").doesNotContain("not a number")
            assertThat(it.toString()).doesNotContain("444403")
        })
        assertThat(takeRequest().path).isEqualTo("/api/merchant/statement?from=${from.epochSecond}")
    }

    @Test
    fun `findByReference ignores unreadable entries of other payments`() {
        statementWith(entry("inv-1", "ref-1"), entry("inv-2", "ref-2", amount = "100.5"))

        assertThat(mono.merchant.findByReference("ref-1", from).map { it.invoiceId }).containsExactly("inv-1")
    }

    @Test
    fun `findByReference never reports an unreadable matching payment as missing`() {
        statementWith(entry("inv-1", "ref-1"), entry("inv-2", "ref-2", amount = "100.5"))

        assertThatThrownBy { mono.merchant.findByReference("ref-2", from) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
                assertThat(it.message).contains("inv-2")
            }
    }

    @Test
    fun `statement stays strict and empty answers are fine`() {
        statementWith(entry("inv-1", "ref-1"), entry("inv-2", "ref-2", amount = "100.5"))
        assertThatThrownBy { mono.merchant.statement(from) }.isInstanceOf(MonobankSerializationException::class.java)

        enqueueJson("{}")
        assertThat(mono.merchant.statementEntries(from).items).isEmpty()
        enqueueJson("""{"list":"oops"}""")
        assertThatThrownBy {
            mono.merchant.statementEntries(
                from,
            )
        }.isInstanceOf(MonobankSerializationException::class.java)
    }
}
