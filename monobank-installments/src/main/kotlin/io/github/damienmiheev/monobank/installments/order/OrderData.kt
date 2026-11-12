package io.github.damienmiheev.monobank.installments.order

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.common.OrderSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Order details, returned by `POST /api/order/data` (and the deprecated `POST /api/order/info`).
 *
 * monobank sends timestamps without an offset; they are read as Kyiv time (`Europe/Kyiv`).
 *
 * @property totalSum Order total in hryvnias, decimal.
 * @property source Sales channel.
 * @property invoiceNumber Invoice or receipt number.
 * @property invoiceDate Invoice date.
 * @property pointId Point of sale id.
 * @property storeOrderId Your order id.
 * @property createTimestamp When the order was created.
 * @property reverseList Returns made on the order.
 * @property maskedCard Masked card number of the client, e.g. `5375 41** **** 1234`.
 * @property iban Client's IBAN.
 */
@ConsistentCopyVisibility
public data class OrderData
    @JsonCreator
    internal constructor(
        @param:JsonProperty("total_sum") public val totalSum: BigDecimal?,
        @param:JsonProperty("source") public val source: OrderSource?,
        @param:JsonProperty("invoice_number") public val invoiceNumber: String?,
        @param:JsonProperty("invoice_date") public val invoiceDate: LocalDate?,
        @param:JsonProperty("point_id") public val pointId: String?,
        @param:JsonProperty("store_order_id") public val storeOrderId: String?,
        @param:JsonProperty("create_timestamp") public val createTimestamp: OffsetDateTime?,
        @param:JsonProperty("reverse_list") public val reverseList: List<OrderReverse>?,
        @param:JsonProperty("maskedCard") public val maskedCard: String?,
        @param:JsonProperty("iban") public val iban: String?,
    )

/**
 * One return on an order.
 *
 * @property sum Returned amount in hryvnias, decimal.
 * @property timestamp When the return was made (Kyiv time).
 */
@ConsistentCopyVisibility
public data class OrderReverse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("sum") public val sum: BigDecimal?,
        @param:JsonProperty("timestamp") public val timestamp: OffsetDateTime?,
    )

/**
 * Answer of `POST /api/order/check/paid`.
 *
 * @property fullyPaid `true` when the client repaid the installment in full (`SUCCESS` / `DONE`); `false` while
 *   payments remain (also when monobank omits the field).
 * @property bankCanReturnMoneyToCard Whether the bank can return money to the card when goods are returned.
 */
@ConsistentCopyVisibility
public data class CheckPaidResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("fully_paid") public val fullyPaid: Boolean,
        @param:JsonProperty("bank_can_return_money_to_card") public val bankCanReturnMoneyToCard: Boolean?,
    )
