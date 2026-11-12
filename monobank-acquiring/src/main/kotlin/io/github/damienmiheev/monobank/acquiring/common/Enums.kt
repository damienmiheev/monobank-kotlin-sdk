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

    /**
     * A refund was made or the hold was released. Also after a PARTIAL refund (verified live:
     * a 0.50 refund of a 1.50 payment gives `reversed` with `finalAmount` 100), so it does not
     * mean "fully refunded": read `finalAmount` for what is still paid, and `cancelList` for the refunds.
     */
    REVERSED("reversed"),

    /** Invoice expired unpaid. */
    EXPIRED("expired"),

    /** A status this SDK version does not know. Treat as non-final and poll again. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    /**
     * `true` when the status can no longer change except through refunds ([SUCCESS] → [REVERSED],
     * and further refunds of a [REVERSED] invoice while `finalAmount` is above 0).
     */
    public val isFinal: Boolean
        get() = this == SUCCESS || this == FAILURE || this == REVERSED || this == EXPIRED

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): InvoiceStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
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

    /**
     * Hold (authorise) the amount for up to 9 days; then finalize it with `InvoiceApi.finalize` or
     * release it with `InvoiceApi.cancel`. A hold that is not finalized within 9 days is cancelled
     * automatically.
     *
     * Currency: on `invoice/create`, monobank documents and enforces UAH only (`ccy` 980; other
     * currencies get 400 `1001` "hold payments are allowed only for UAH", verified). For
     * `wallet/payment` and `payment-direct` monobank documents no currency limit; the SDK does not
     * check the currency itself, so monobank's answer decides.
     */
    HOLD("hold"),

    /**
     * Verify and save a card without charging it ("add payment method"): only for `invoice/create`,
     * with amount `0` and `saveCardData.saveCard = true`. Use `CreateInvoiceRequest.verification`.
     */
    VERIFICATION("verification"),

    /** A value this SDK version does not know. Never sent: request builders reject it. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Status of a refund (cancel), a finalize request, or a payment by card token / card data.
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
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): OperationStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
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
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): PaymentSystem = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
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
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): PaymentMethod = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
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
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): CardTokenStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
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
