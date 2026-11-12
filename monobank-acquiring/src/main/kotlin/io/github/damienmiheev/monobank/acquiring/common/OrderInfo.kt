package io.github.damienmiheev.monobank.acquiring.common

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * A discount or surcharge, for fiscal receipts (Checkbox).
 *
 * @property type Discount or surcharge.
 * @property mode How [value] is read.
 * @property value Percent when [mode] is [DiscountMode.PERCENT]; amount in minor units (kopiyky) when [DiscountMode.VALUE].
 * @property tax Tax rate codes for fiscalization.
 */
@ConsistentCopyVisibility
public data class Discount internal constructor(
    @get:JsonProperty("type") public val type: DiscountType,
    @get:JsonProperty("mode") public val mode: DiscountMode,
    @get:JsonProperty("value") public val value: BigDecimal,
    @get:JsonProperty("tax") public val tax: List<Int>?,
) {
    public companion object {
        /** Creates a discount without tax codes. */
        @JvmStatic
        @JvmOverloads
        public fun of(
            type: DiscountType,
            mode: DiscountMode,
            value: BigDecimal,
            tax: List<Int>? = null,
        ): Discount = Discount(type, mode, value, tax)
    }
}

/**
 * One line of the order basket. Required when fiscal receipts are enabled.
 *
 * @property name Product name.
 * @property qty Quantity (may be fractional, e.g. kilograms).
 * @property sum Price of one unit, in minor units (kopiyky).
 * @property total Price of all units, in minor units (kopiyky). Optional.
 * @property code Product code; required for fiscalization.
 * @property splitReceiverId Split payments: the receiver that gets this item's money.
 */
@ConsistentCopyVisibility
public data class BasketItem internal constructor(
    @get:JsonProperty("name") public val name: String,
    @get:JsonProperty("qty") public val qty: BigDecimal,
    @get:JsonProperty("sum") public val sum: Long,
    @get:JsonProperty("code") public val code: String,
    @get:JsonProperty("total") public val total: Long?,
    @get:JsonProperty("icon") public val icon: String?,
    @get:JsonProperty("unit") public val unit: String?,
    @get:JsonProperty("barcode") public val barcode: String?,
    @get:JsonProperty("header") public val header: String?,
    @get:JsonProperty("footer") public val footer: String?,
    @get:JsonProperty("tax") public val tax: List<Int>?,
    @get:JsonProperty("uktzed") public val uktzed: String?,
    @get:JsonProperty("splitReceiverId") public val splitReceiverId: String?,
    @get:JsonProperty("discounts") public val discounts: List<Discount>?,
) {
    /** Builder for [BasketItem]. */
    public class Builder internal constructor(
        private val name: String,
        private val qty: BigDecimal,
        private val sum: Long,
        private val code: String,
    ) {
        private var total: Long? = null
        private var icon: String? = null
        private var unit: String? = null
        private var barcode: String? = null
        private var header: String? = null
        private var footer: String? = null
        private var tax: List<Int>? = null
        private var uktzed: String? = null
        private var splitReceiverId: String? = null
        private var discounts: List<Discount>? = null

        /** Total for all units, in minor units. */
        public fun total(total: Long?): Builder = apply { this.total = total }

        /** Product image URL. */
        public fun icon(icon: String?): Builder = apply { this.icon = icon }

        /** Unit name, e.g. `шт.`. */
        public fun unit(unit: String?): Builder = apply { this.unit = unit }

        /** Barcode. */
        public fun barcode(barcode: String?): Builder = apply { this.barcode = barcode }

        /** Text before the product name on the receipt. */
        public fun header(header: String?): Builder = apply { this.header = header }

        /** Text after the product on the receipt. */
        public fun footer(footer: String?): Builder = apply { this.footer = footer }

        /** Tax rate codes. */
        public fun tax(tax: List<Int>?): Builder = apply { this.tax = tax }

        /** UKTZED product code. */
        public fun uktzed(uktzed: String?): Builder = apply { this.uktzed = uktzed }

        /** Split payments: receiver of this item's money. */
        public fun splitReceiverId(splitReceiverId: String?): Builder = apply { this.splitReceiverId = splitReceiverId }

        /** Item-level discounts. */
        public fun discounts(discounts: List<Discount>?): Builder = apply { this.discounts = discounts }

        /** Builds the item. */
        public fun build(): BasketItem =
            BasketItem(
                name,
                qty,
                sum,
                code,
                total,
                icon,
                unit,
                barcode,
                header,
                footer,
                tax,
                uktzed,
                splitReceiverId,
                discounts,
            )
    }

    public companion object {
        /**
         * Starts a basket item.
         *
         * @param sum Price of one unit in minor units (kopiyky).
         */
        @JvmStatic
        public fun builder(
            name: String,
            qty: BigDecimal,
            sum: Long,
            code: String,
        ): Builder = Builder(name, qty, sum, code)
    }
}

/**
 * Order details attached to a payment.
 *
 * @property reference Your order or receipt number. Use a unique, deterministic value per payment
 *   attempt: it is the only way to find a payment whose creation timed out (see `MerchantApi.findByReference`).
 * @property destination Payment purpose shown to the customer.
 * @property comment Internal comment.
 * @property customerEmails Emails that receive the fiscal receipt (with Checkbox).
 * @property discounts Basket-level discounts.
 * @property basketOrder Order lines.
 * @property metadata Free-form key/value data, returned in the merchant statement.
 */
@ConsistentCopyVisibility
public data class MerchantPaymInfo internal constructor(
    @get:JsonProperty("reference") public val reference: String?,
    @get:JsonProperty("destination") public val destination: String?,
    @get:JsonProperty("comment") public val comment: String?,
    @get:JsonProperty("customerEmails") public val customerEmails: List<String>?,
    @get:JsonProperty("discounts") public val discounts: List<Discount>?,
    @get:JsonProperty("basketOrder") public val basketOrder: List<BasketItem>?,
    @get:JsonProperty("metadata") public val metadata: Map<String, String>?,
) {
    /** Builder for [MerchantPaymInfo]. */
    public class Builder internal constructor() {
        private var reference: String? = null
        private var destination: String? = null
        private var comment: String? = null
        private var customerEmails: List<String>? = null
        private var discounts: List<Discount>? = null
        private var basketOrder: List<BasketItem>? = null
        private var metadata: Map<String, String>? = null

        /** Your order reference. */
        public fun reference(reference: String?): Builder = apply { this.reference = reference }

        /** Payment purpose. */
        public fun destination(destination: String?): Builder = apply { this.destination = destination }

        /** Internal comment. */
        public fun comment(comment: String?): Builder = apply { this.comment = comment }

        /** Fiscal receipt recipients. */
        public fun customerEmails(customerEmails: List<String>?): Builder =
            apply { this.customerEmails = customerEmails }

        /** Basket-level discounts. */
        public fun discounts(discounts: List<Discount>?): Builder = apply { this.discounts = discounts }

        /** Order lines. */
        public fun basketOrder(basketOrder: List<BasketItem>?): Builder = apply { this.basketOrder = basketOrder }

        /** Free-form metadata. */
        public fun metadata(metadata: Map<String, String>?): Builder = apply { this.metadata = metadata }

        /** Builds the info. */
        public fun build(): MerchantPaymInfo =
            MerchantPaymInfo(reference, destination, comment, customerEmails, discounts, basketOrder, metadata)
    }

    public companion object {
        /** Starts building order details. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * Card saving (tokenization) settings for an invoice.
 *
 * @property saveCard Whether to save the card after payment.
 * @property walletId Your id for the customer's wallet; saved cards are grouped under it.
 *   Use one stable id per customer.
 */
@ConsistentCopyVisibility
public data class SaveCardData internal constructor(
    @get:JsonProperty("saveCard") public val saveCard: Boolean,
    @get:JsonProperty("walletId") public val walletId: String?,
) {
    public companion object {
        /** Saves the card into the wallet [walletId]. */
        @JvmStatic
        public fun saveTo(walletId: String): SaveCardData = SaveCardData(true, walletId)

        /** Creates card saving settings. */
        @JvmStatic
        public fun of(
            saveCard: Boolean,
            walletId: String?,
        ): SaveCardData = SaveCardData(saveCard, walletId)
    }
}
