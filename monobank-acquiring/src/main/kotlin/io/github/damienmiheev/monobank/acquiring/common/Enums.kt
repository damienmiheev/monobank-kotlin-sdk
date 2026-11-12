package io.github.damienmiheev.monobank.acquiring.common

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Invoice (payment) status. Unknown future values are read as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class InvoiceStatus(
    @get:JsonValue public val value: String,
) {
    /** Invoice created, waiting for payment. */
    CREATED("created"),

    /** Payment is being processed. */
    PROCESSING("processing"),

    /** Amount is held (authorised); finalize or cancel it within 9 days. */
    HOLD("hold"),

    /** Paid. */
    SUCCESS("success"),

    /** Payment failed. */
    FAILURE("failure"),

    /** Payment was fully refunded or the hold was released. */
    REVERSED("reversed"),

    /** Invoice expired unpaid. */
    EXPIRED("expired"),

    /** A status this SDK version does not know. Treat as non-final and poll again. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    /** `true` when the status can no longer change except through a refund ([SUCCESS] → [REVERSED]). */
    public val isFinal: Boolean
        get() = this == SUCCESS || this == FAILURE || this == REVERSED || this == EXPIRED
}

/**
 * Kind of operation an invoice performs.
 *
 * @property value Wire value.
 */
public enum class PaymentType(
    @get:JsonValue public val value: String,
) {
    /** Charge immediately (default). */
    DEBIT("debit"),

    /** Hold the amount for up to 9 days; then finalize or cancel. UAH only. */
    HOLD("hold"),

    /** Verify (and save) a card without charging it. */
    VERIFICATION("verification"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Status of a refund (cancel) or finalize request.
 *
 * @property value Wire value.
 */
public enum class OperationStatus(
    @get:JsonValue public val value: String,
) {
    /** Being processed. */
    PROCESSING("processing"),

    /** Done. */
    SUCCESS("success"),

    /** Failed. */
    FAILURE("failure"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Card network.
 *
 * @property value Wire value.
 */
public enum class PaymentSystem(
    @get:JsonValue public val value: String,
) {
    /** Visa. */
    VISA("visa"),

    /** Mastercard. */
    MASTERCARD("mastercard"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * How the customer paid.
 *
 * @property value Wire value.
 */
public enum class PaymentMethod(
    @get:JsonValue public val value: String,
) {
    /** Card number entered on the payment page. */
    PAN("pan"),

    /** Apple Pay. */
    APPLE("apple"),

    /** Google Pay. */
    GOOGLE("google"),

    /** monobank app. */
    MONOBANK("monobank"),

    /** Saved card token (wallet). */
    WALLET("wallet"),

    /** Direct card payment (payment-direct). */
    DIRECT("direct"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Card tokenization status.
 *
 * @property value Wire value.
 */
public enum class CardTokenStatus(
    @get:JsonValue public val value: String,
) {
    /** Tokenization requested. */
    NEW("new"),

    /** Card saved; the token can be charged. */
    CREATED("created"),

    /** Tokenization failed. */
    FAILED("failed"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Whether the customer is present for a saved-card charge.
 *
 * @property value Wire value.
 */
public enum class InitiationKind(
    @get:JsonValue public val value: String,
) {
    /** Merchant-initiated (MIT): recurring or unscheduled charge without the customer. */
    MERCHANT("merchant"),

    /** Customer-initiated (CIT): the customer is present and may pass 3-D Secure. */
    CLIENT("client"),
}

/**
 * Discount or surcharge.
 *
 * @property value Wire value.
 */
public enum class DiscountType(
    @get:JsonValue public val value: String,
) {
    /** Discount. */
    DISCOUNT("DISCOUNT"),

    /** Surcharge. */
    EXTRA_CHARGE("EXTRA_CHARGE"),
}

/**
 * How a discount's value is read.
 *
 * @property value Wire value.
 */
public enum class DiscountMode(
    @get:JsonValue public val value: String,
) {
    /** Percent of the item or basket sum. */
    PERCENT("PERCENT"),

    /** Fixed amount in minor units. */
    VALUE("VALUE"),
}
