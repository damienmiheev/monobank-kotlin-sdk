package io.github.damienmiheev.monobank.acquiring.employee

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp

/**
 * Employees who can receive tips (legacy).
 *
 * `GET /api/merchant/employee/list` is only in monobank's legacy (v2410) documentation and is
 * gone from the current one; it may stop working at any time.
 */
@Deprecated("Only in monobank's legacy (v2410) documentation; GET /api/merchant/employee/list may stop working.")
public class EmployeeApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Employees who can receive tips; pass `id` as `CreateInvoiceRequest.tipsEmployeeId`.
     *
     * `GET /api/merchant/employee/list`. Safe to retry. With a test token it returns dummy data.
     */
    @Deprecated("Only in monobank's legacy (v2410) documentation; GET /api/merchant/employee/list may stop working.")
    public fun list(): List<Employee> =
        http.get("/api/merchant/employee/list", EmployeeListWire::class.java).list.orEmpty()
}

/**
 * An employee who can receive tips (legacy).
 *
 * @property id Employee id in monobank's acquiring.
 * @property name Name given when the employee was added.
 * @property extRef Your id of the employee.
 */
@ConsistentCopyVisibility
public data class Employee
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("extRef") public val extRef: String?,
    )

internal class EmployeeListWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<Employee>?,
    )
