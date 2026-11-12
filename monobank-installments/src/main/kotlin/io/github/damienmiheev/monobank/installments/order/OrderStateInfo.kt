package io.github.damienmiheev.monobank.installments.order

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.common.OrderState
import io.github.damienmiheev.monobank.installments.common.OrderSubState

/**
 * State of an installment order. Returned by state, confirm and reject, and sent as the callback body.
 *
 * @property orderId monobank order id (UUID).
 * @property state Overall state.
 * @property subState Detailed state (`order_sub_state`).
 * @property message Additional description, if any.
 */
@ConsistentCopyVisibility
public data class OrderStateInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("order_id") public val orderId: String,
        @param:JsonProperty("state") public val state: OrderState,
        @param:JsonProperty("order_sub_state") public val subState: OrderSubState?,
        @param:JsonProperty("message") public val message: String?,
    ) {
        /**
         * `true` when the client approved and the store must now hand over the goods and call `orders.confirm`
         * (`IN_PROCESS` / `WAITING_FOR_STORE_CONFIRM`).
         */
        public val isWaitingForStoreConfirm: Boolean
            get() = state == OrderState.IN_PROCESS && subState == OrderSubState.WAITING_FOR_STORE_CONFIRM
    }

/**
 * Answer of `POST /api/order/state`: the order's state, or "not found".
 *
 * For a QR cart the order only exists after the client scans the QR code; until then monobank answers 404 and
 * this is [NotFound]. For an order created with `orders.create`, [NotFound] means the id is wrong. Any 404 counts,
 * including one with an HTML body (monobank's sandbox answers unknown orders that way), so a wrong `baseUrl` or proxy
 * also looks like [NotFound]: check your configuration if a QR cart is never found.
 */
public sealed class OrderStateResult {
    /** The order's state, or `null` for [NotFound]. */
    public abstract val stateOrNull: OrderStateInfo?

    /** The order exists. */
    @ConsistentCopyVisibility
    public data class Found internal constructor(
        /** The order's state. */
        public val info: OrderStateInfo,
    ) : OrderStateResult() {
        override val stateOrNull: OrderStateInfo get() = info
    }

    /** monobank answered 404: no such order (yet). */
    @ConsistentCopyVisibility
    public data class NotFound internal constructor(
        /** monobank's message, if any. */
        public val message: String?,
        /** monobank's trace id (`trace-id` header), to quote to monobank support. */
        public val traceId: String?,
    ) : OrderStateResult() {
        override val stateOrNull: OrderStateInfo? get() = null
    }
}
