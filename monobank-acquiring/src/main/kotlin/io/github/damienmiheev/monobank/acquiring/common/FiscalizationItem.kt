package io.github.damienmiheev.monobank.acquiring.common

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * One product line of a refund (`invoice/cancel`) or hold finalization (`invoice/finalize`),
 * used to build the fiscal receipt. Required on refunds when fiscal receipts are enabled.
 *
 * @property name Product name.
 * @property qty Quantity (may be fractional, e.g. kilograms).
 * @property sum Price of one unit, in minor units (kopiyky).
 * @property code Product code; required for fiscalization.
 * @property barcode Barcode, may be needed for fiscalization.
 * @property header Text before the product name on the receipt.
 * @property footer Text after the product on the receipt.
 * @property tax Tax rate codes. Optional for Checkbox, required for Vchasno.Kasa.
 * @property uktzed UKTZED product code.
 * @property splitReceiverId Split payments: the receiver whose part is refunded. monobank's split
 *   payments page asks for it on partial refunds, although the API schema does not list it.
 */
@ConsistentCopyVisibility
public data class FiscalizationItem internal constructor(
    @get:JsonProperty("name") public val name: String,
    @get:JsonProperty("qty") public val qty: BigDecimal,
    @get:JsonProperty("sum") public val sum: Long,
    @get:JsonProperty("code") public val code: String,
    @get:JsonProperty("barcode") public val barcode: String?,
    @get:JsonProperty("header") public val header: String?,
    @get:JsonProperty("footer") public val footer: String?,
    @get:JsonProperty("tax") public val tax: List<Int>?,
    @get:JsonProperty("uktzed") public val uktzed: String?,
    @get:JsonProperty("splitReceiverId") public val splitReceiverId: String?,
) {
    /** Builder for [FiscalizationItem]. */
    public class Builder internal constructor(
        private val name: String,
        private val qty: BigDecimal,
        private val sum: Long,
        private val code: String,
    ) {
        private var barcode: String? = null
        private var header: String? = null
        private var footer: String? = null
        private var tax: List<Int>? = null
        private var uktzed: String? = null
        private var splitReceiverId: String? = null

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

        /** Split payments: receiver whose part is refunded. */
        public fun splitReceiverId(splitReceiverId: String?): Builder = apply { this.splitReceiverId = splitReceiverId }

        /** Builds the item. */
        public fun build(): FiscalizationItem =
            FiscalizationItem(name, qty, sum, code, barcode, header, footer, tax, uktzed, splitReceiverId)
    }

    public companion object {
        /**
         * Starts an item.
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
