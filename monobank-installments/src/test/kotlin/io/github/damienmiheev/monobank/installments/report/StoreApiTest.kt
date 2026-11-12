package io.github.damienmiheev.monobank.installments.report

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class StoreApiTest : InstallmentsTestSupport() {
    @Test
    fun `report sends the date and unwraps the orders`() {
        enqueueFixture("POST_api_store_report.response.json")

        val orders = chast.store.report(LocalDate.of(2024, 1, 15))

        assertThat(takeJson("/api/store/report"))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_store_report.request.json")))
        val order = orders.single()
        assertThat(order.orderId).isEqualTo(ORDER_ID)
        assertThat(order.invoiceNumber).isEqualTo("INV-001234")
        assertThat(order.orderDate).isEqualTo(LocalDate.of(2024, 1, 15))
        assertThat(order.payParts).isEqualTo(10)
        assertThat(order.commissionPercent).isEqualTo(BigDecimal("2.5"))
        assertThat(order.totalSum).isEqualTo(BigDecimal("2499.99"))
        assertThat(order.transferredSum).isEqualTo(BigDecimal("2437.49"))
        assertThat(order.commission).isEqualTo(BigDecimal("62.5"))
        assertThat(order.operationTimestamp).isEqualTo(OffsetDateTime.parse("2024-01-15T14:30:00+02:00"))
        assertThat(order.odbContractNumber).isEqualTo("КД-2024-001234")
    }

    @Test
    fun `amounts keep the scale monobank sends (answer recorded from the sandbox)`() {
        // Recorded with curl from u2-demo-ext.mono.st4g3.com on 2026-11-12. The sandbox answers with canned data,
        // hence the synthetic-looking ids, but the number formatting (3500.00, 3.50) is the server's own.
        enqueueFixture("sandbox_store_report.response.json")

        val order = chast.store.report(LocalDate.of(2026, 11, 11)).single()

        assertThat(order.totalSum!!.toPlainString()).isEqualTo("3500.00")
        assertThat(order.commissionPercent!!.toPlainString()).isEqualTo("3.50")
        assertThat(order.transferredSum!!.toPlainString()).isEqualTo("3377.50")
        assertThat(order.commission!!.toPlainString()).isEqualTo("122.50")
        assertThat(order.invoiceNumber).isEqualTo("СФGIT-0000000134")
        assertThat(order.payParts).isEqualTo(24)
    }

    @Test
    fun `report without operations is an empty list`() {
        enqueueJson("{}")
        enqueueJson("""{"orders":[]}""")

        assertThat(chast.store.report(LocalDate.of(2024, 1, 1))).isEmpty()
        assertThat(chast.store.report(LocalDate.of(2024, 1, 1))).isEmpty()
    }

    @Test
    fun `report is retried on server errors`() {
        enqueueJson("{}", status = 500)
        enqueueFixture("POST_api_store_report.response.json")

        assertThat(chast.store.report(LocalDate.of(2024, 1, 15))).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `report errors carry monobank's message`() {
        enqueueJson("""{"message":"date: must not be in the future"}""", status = 400)

        assertThatThrownBy { chast.store.report(LocalDate.of(2999, 1, 1)) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("date: must not be in the future")
            }
    }
}
