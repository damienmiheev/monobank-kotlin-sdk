package io.github.damienmiheev.monobank.installments.common

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.internal.requireHryvnias
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank
import java.math.BigDecimal

/**
 * One product line of an order or QR cart.
 *
 * @property name Product name, 1–500 characters.
 * @property count Number of units, at least 1.
 * @property sum Price of **one unit** in hryvnias, decimal (e.g. `9999.99`), not kopiyky; at least 0.01.
 */
@ConsistentCopyVisibility
public data class Product internal constructor(
    @get:JsonProperty("name") public val name: String,
    @get:JsonProperty("count") public val count: Int,
    @get:JsonProperty("sum") public val sum: BigDecimal,
) {
    public companion object {
        private const val MAX_NAME_LENGTH = 500

        /**
         * Creates a product line.
         *
         * @param name Product name, 1–500 characters.
         * @param count Number of units, at least 1.
         * @param sum Price of one unit in hryvnias (e.g. `BigDecimal("9999.99")`), at most 2 decimal places.
         */
        @JvmStatic
        public fun of(
            name: String,
            count: Int,
            sum: BigDecimal,
        ): Product {
            requireNotBlank(name, "name")
            require(name.length <= MAX_NAME_LENGTH) { "name must be at most $MAX_NAME_LENGTH characters" }
            require(count >= 1) { "count must be at least 1" }
            return Product(name, count, requireHryvnias(sum, "sum"))
        }
    }
}
