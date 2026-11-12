package io.github.damienmiheev.monobank.installments.report

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Store-level reports.
 */
public class StoreApi internal constructor(
    private val http: InstallmentsHttp,
) {
    /**
     * All installment operations of the store on [date].
     *
     * `POST /api/store/report`. Retried (read-only).
     *
     * @param date Report day.
     * @return The operations; empty when there were none.
     */
    public fun report(date: LocalDate): List<ReportOrder> =
        http.post("/api/store/report", ReportBody(date), DailyReport::class.java, idempotent = true).orders.orEmpty()

    private class ReportBody(
        @get:JsonProperty("date") val date: LocalDate,
    )
}

/** Wire wrapper of `POST /api/store/report`: `{"orders": [...]}`. */
internal class DailyReport
    @JsonCreator
    constructor(
        @param:JsonProperty("orders") val orders: List<ReportOrder>?,
    )

/**
 * One operation in the daily store report. Amounts are in hryvnias, decimal.
 *
 * @property orderId monobank order id.
 * @property invoiceNumber Invoice number.
 * @property orderDate Invoice date.
 * @property payParts Number of monthly payments in the credit agreement.
 * @property commissionPercent Store commission, percent (e.g. `2.5`).
 * @property totalSum Credit amount in hryvnias.
 * @property transferredSum Amount transferred to the store in hryvnias.
 * @property commission Commission in hryvnias.
 * @property operationTimestamp When the operation happened (sent without an offset; read as Kyiv time).
 * @property odbContractNumber Credit agreement number.
 */
@ConsistentCopyVisibility
public data class ReportOrder
    @JsonCreator
    internal constructor(
        @param:JsonProperty("order_id") public val orderId: String,
        @param:JsonProperty("invoice_number") public val invoiceNumber: String?,
        @param:JsonProperty("order_date") public val orderDate: LocalDate?,
        @param:JsonProperty("pay_parts") public val payParts: Int?,
        @param:JsonProperty("commission_percent") public val commissionPercent: BigDecimal?,
        @param:JsonProperty("total_sum") public val totalSum: BigDecimal?,
        @param:JsonProperty("transferred_sum") public val transferredSum: BigDecimal?,
        @param:JsonProperty("commission") public val commission: BigDecimal?,
        @param:JsonProperty("operation_timestamp") public val operationTimestamp: OffsetDateTime?,
        @param:JsonProperty("odb_contract_number") public val odbContractNumber: String?,
    )
