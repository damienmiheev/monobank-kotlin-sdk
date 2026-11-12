package io.github.damienmiheev.monobank.installments.guarantee

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank
import java.time.LocalDate

/**
 * Request of the guarantee letter endpoints (`OrderDataRequest` in the spec).
 *
 * @property orderId monobank order id.
 * @property invoice Invoice to print on the letter, if any.
 */
@ConsistentCopyVisibility
public data class GuaranteeLetterRequest internal constructor(
    @get:JsonProperty("order_id") public val orderId: String,
    @get:JsonProperty("invoice") public val invoice: GuaranteeLetterInvoice?,
) {
    public companion object {
        /** A request for [orderId] without invoice details. */
        @JvmStatic
        public fun of(orderId: String): GuaranteeLetterRequest =
            GuaranteeLetterRequest(requireNotBlank(orderId, "orderId"), null)

        /**
         * A request for [orderId] with the invoice to print.
         *
         * @param invoiceNumber Invoice or receipt number.
         * @param invoiceDate Invoice date.
         */
        @JvmStatic
        public fun of(
            orderId: String,
            invoiceNumber: String?,
            invoiceDate: LocalDate?,
        ): GuaranteeLetterRequest {
            require(invoiceNumber == null || invoiceNumber.isNotEmpty()) { "invoiceNumber must not be empty" }
            val hasInvoice = invoiceNumber != null || invoiceDate != null
            val invoice = if (hasInvoice) GuaranteeLetterInvoice(invoiceNumber, invoiceDate) else null
            return GuaranteeLetterRequest(requireNotBlank(orderId, "orderId"), invoice)
        }
    }
}

/**
 * Invoice part of [GuaranteeLetterRequest].
 *
 * @property number Invoice or receipt number.
 * @property date Invoice date (`YYYY-MM-DD`).
 */
@ConsistentCopyVisibility
public data class GuaranteeLetterInvoice internal constructor(
    @get:JsonProperty("number") public val number: String?,
    @get:JsonProperty("date") public val date: LocalDate?,
)
