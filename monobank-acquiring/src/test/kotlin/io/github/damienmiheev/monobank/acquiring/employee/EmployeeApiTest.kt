package io.github.damienmiheev.monobank.acquiring.employee

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.core.MonobankServerException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class EmployeeApiTest : AcquiringTestSupport() {
    @Test
    fun `legacy employee list is unwrapped`() {
        enqueueFixture("GET_api_merchant_employee_list.response.json")
        val employee = mono.employees.list().single()
        takeRequest("GET", "/api/merchant/employee/list")
        assertThat(employee.id).isEqualTo("3QFX7e7mZfo3R")
        assertThat(employee.name).isEqualTo("Іван")
        assertThat(employee.extRef).isEqualTo("emp-1")
        enqueueJson("{}")
        assertThat(mono.employees.list()).isEmpty()
    }

    @Test
    fun `server errors are retried for the read`() {
        enqueueJson("{}", status = 500)
        enqueueJson("{}", status = 500)
        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.employees.list() }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `tips employee id is sent on create`() {
        enqueueFixture("POST_api_merchant_invoice_create.response.json")
        mono.invoices.create(CreateInvoiceRequest.builder(100).tipsEmployeeId("3QFX7e7mZfo3R").build())
        assertThat(takeRequest().json().path("tipsEmployeeId").asText()).isEqualTo("3QFX7e7mZfo3R")
    }

    @Test
    fun `the API is marked deprecated`() {
        assertThat(EmployeeApi::class.java.isAnnotationPresent(Deprecated::class.java)).isTrue()
        assertThat(EmployeeApi::class.java.getMethod("list").isAnnotationPresent(Deprecated::class.java))
            .isTrue()
    }
}
