package io.github.damienmiheev.monobank.installments.common

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Overall state of an installment order (`state`). Unknown future values are read as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class OrderState(
    @get:JsonValue public val value: String,
) {
    /** The installment is active, fully repaid or returned; see [OrderSubState]. */
    SUCCESS("SUCCESS"),

    /** The order failed or was rejected; [OrderSubState] says why. */
    FAIL("FAIL"),

    /** The order is waiting for the client or for the store to confirm. */
    IN_PROCESS("IN_PROCESS"),

    /** A state this SDK version does not know. Treat as non-final and poll again. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    /** `true` for [SUCCESS] and [FAIL]: the order is no longer in process. */
    public val isFinal: Boolean
        get() = this == SUCCESS || this == FAIL
}

/**
 * Detailed state of an installment order (`order_sub_state`). Unknown future values are read as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class OrderSubState(
    @get:JsonValue public val value: String,
) {
    /** [OrderState.SUCCESS]: goods handed over, the installment is active. */
    ACTIVE("ACTIVE"),

    /** [OrderState.SUCCESS]: the client repaid the installment in full. */
    DONE("DONE"),

    /** [OrderState.SUCCESS]: goods returned, money returned to the client. */
    RETURNED("RETURNED"),

    /** [OrderState.IN_PROCESS]: waiting for the client to approve in the monobank app. No callback is sent. */
    WAITING_FOR_CLIENT("WAITING_FOR_CLIENT"),

    /**
     * [OrderState.IN_PROCESS]: the client approved and the first payment was taken. Hand over the goods and
     * call `orders.confirm`, or `orders.reject` if you cannot.
     */
    WAITING_FOR_STORE_CONFIRM("WAITING_FOR_STORE_CONFIRM"),

    /** [OrderState.FAIL]: no monobank client with this phone (check the `+380…` format). */
    CLIENT_NOT_FOUND("CLIENT_NOT_FOUND"),

    /** [OrderState.FAIL]: the amount exceeds the client's installment limit. */
    EXCEEDED_SUM_LIMIT("EXCEEDED_SUM_LIMIT"),

    /** [OrderState.FAIL]: the store's number of payments is not acceptable. */
    PAY_PARTS_ARE_NOT_ACCEPTABLE("PAY_PARTS_ARE_NOT_ACCEPTABLE"),

    /** [OrderState.FAIL]: the client has another open installment order (wait 15 minutes or cancel it). */
    EXISTS_OTHER_OPEN_ORDER("EXISTS_OTHER_OPEN_ORDER"),

    /** [OrderState.FAIL]: not enough money on the card for the first payment. */
    NOT_ENOUGH_MONEY_FOR_INIT_DEBIT("NOT_ENOUGH_MONEY_FOR_INIT_DEBIT"),

    /** [OrderState.FAIL]: the client did not answer within 15 minutes. */
    CLIENT_PUSH_TIMEOUT("CLIENT_PUSH_TIMEOUT"),

    /** [OrderState.FAIL]: rejected by fraud prevention. */
    FRAUD_REJECTED("FRAUD_REJECTED"),

    /** [OrderState.FAIL]: the client declined. */
    REJECTED_BY_CLIENT("REJECTED_BY_CLIENT"),

    /** [OrderState.FAIL]: the store rejected the order (`orders.reject`). */
    REJECTED_BY_STORE("REJECTED_BY_STORE"),

    /** [OrderState.FAIL]: restricted by the bank's risk management. */
    RESTRICTED_BY_RISKS("RESTRICTED_BY_RISKS"),

    /**
     * [OrderState.FAIL]: QR flow only: the cart was not scanned and confirmed within 15 minutes.
     * Described in monobank's docs but missing from the spec's enum.
     */
    CONFIRM_TIME_EXPIRED("CONFIRM_TIME_EXPIRED"),

    /** [OrderState.FAIL]: internal bank error; monobank suggests trying again in 5 minutes. */
    FAIL("FAIL"),

    /** A sub-state this SDK version does not know. Decide by [OrderState] instead. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Sales channel of a new order (`invoice.source` of `POST /api/order/create`).
 *
 * @property value Wire value.
 */
public enum class InvoiceSource(
    @get:JsonValue public val value: String,
) {
    /** Online sale. */
    INTERNET("INTERNET"),

    /** Sale in a physical store. */
    STORE("STORE"),
}

/**
 * Sales channel of an existing order (`source` of `POST /api/order/data`). Unknown future values are read
 * as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class OrderSource(
    @get:JsonValue public val value: String,
) {
    /** Online sale. */
    INTERNET("INTERNET"),

    /** Sale in a physical store. */
    STORE("STORE"),

    /** monobank checkout. */
    CHECKOUT("CHECKOUT"),

    /** Marketplace. */
    MARKETPLACE("MARKETPLACE"),

    /** A channel this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Credit product of a guarantee letter (`bank.product_types`). Unknown future values are read as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class CreditProductType(
    @get:JsonValue public val value: String,
) {
    /** Покупка частинами (pay in parts), currently the only product. */
    PAYMENT_INSTALLMENTS("payment_installments"),

    /** A product this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}
