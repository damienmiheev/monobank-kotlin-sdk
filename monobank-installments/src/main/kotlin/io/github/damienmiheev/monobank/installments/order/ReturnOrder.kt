package io.github.damienmiheev.monobank.installments.order

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.internal.requireHryvnias
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank
import java.math.BigDecimal

/**
 * Request of `POST /api/order/return`: a full or partial return of goods. The order must be in state `SUCCESS`;
 * several partial returns are allowed.
 *
 * @property orderId monobank order id.
 * @property sum Returned amount in hryvnias, decimal, not kopiyky, at least 1. For a **full** return pass the whole
 *   order sum.
 * @property storeReturnId Your unique id of this return.
 * @property returnMoneyToCard `true` to return the money to the client's card, `false` when the store returns cash.
 * @property additionalParams VAT of the return.
 */
@ConsistentCopyVisibility
public data class ReturnOrderRequest internal constructor(
    @get:JsonProperty("order_id") public val orderId: String,
    @get:JsonProperty("sum") public val sum: BigDecimal,
    @get:JsonProperty("store_return_id") public val storeReturnId: String,
    @get:JsonProperty("return_money_to_card") public val returnMoneyToCard: Boolean,
    @get:JsonProperty("additional_params") public val additionalParams: ReturnAdditionalParams?,
) {
    /** Builder for [ReturnOrderRequest]. */
    public class Builder internal constructor(
        private val orderId: String,
        private val sum: BigDecimal,
        private val storeReturnId: String,
        private val returnMoneyToCard: Boolean,
    ) {
        private var nds: BigDecimal? = null

        /** VAT of the return in hryvnias (decimal). */
        public fun nds(nds: BigDecimal?): Builder = apply { this.nds = nds?.let { requireHryvnias(it, "nds", true) } }

        /** Builds the request. */
        public fun build(): ReturnOrderRequest {
            requireNotBlank(orderId, "orderId")
            requireHryvnias(sum, "sum")
            require(sum >= BigDecimal.ONE) { "sum must be at least 1 (hryvnias)" }
            requireNotBlank(storeReturnId, "storeReturnId")
            return ReturnOrderRequest(
                orderId,
                sum,
                storeReturnId,
                returnMoneyToCard,
                nds?.let { ReturnAdditionalParams(it) },
            )
        }
    }

    public companion object {
        /**
         * Starts a return.
         *
         * @param orderId monobank order id.
         * @param sum Returned amount in hryvnias (decimal), at most 2 decimal places.
         * @param storeReturnId Your unique id of this return.
         * @param returnMoneyToCard `true`: money goes back to the card; `false`: the store pays cash.
         */
        @JvmStatic
        public fun builder(
            orderId: String,
            sum: BigDecimal,
            storeReturnId: String,
            returnMoneyToCard: Boolean,
        ): Builder = Builder(orderId, sum, storeReturnId, returnMoneyToCard)
    }
}

/**
 * Optional parameters of a return.
 *
 * @property nds VAT of the return in hryvnias, decimal.
 */
@ConsistentCopyVisibility
public data class ReturnAdditionalParams internal constructor(
    @get:JsonProperty("nds") public val nds: BigDecimal?,
)

/**
 * Answer of `POST /api/order/return`. Receiving it at all means monobank accepted the return: every failure is thrown
 * as an exception. Never repeat a return because of [status].
 *
 * @property status Informational: `OK` per the docs; `null` when monobank sent an empty body.
 */
@ConsistentCopyVisibility
public data class ReturnOrderResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val status: String?,
    )
