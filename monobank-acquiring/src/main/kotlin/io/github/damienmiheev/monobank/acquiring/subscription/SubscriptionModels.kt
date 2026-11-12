package io.github.damienmiheev.monobank.acquiring.subscription

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.common.Pagination
import io.github.damienmiheev.monobank.acquiring.invoice.WalletData
import java.time.OffsetDateTime

/**
 * Callback URLs of a subscription. monobank does not document the callbacks' body or signature,
 * so the SDK does not parse or verify them.
 *
 * @property chargeUrl Called after each charge, successful or not.
 * @property statusUrl Called when the subscription status changes.
 */
@ConsistentCopyVisibility
public data class SubscriptionWebHookUrls internal constructor(
    @get:JsonProperty("chargeUrl") public val chargeUrl: String?,
    @get:JsonProperty("statusUrl") public val statusUrl: String?,
) {
    public companion object {
        /** Creates the callback URLs; either may be `null`. */
        @JvmStatic
        public fun of(
            chargeUrl: String?,
            statusUrl: String?,
        ): SubscriptionWebHookUrls = SubscriptionWebHookUrls(chargeUrl, statusUrl)
    }
}

/**
 * Request of `POST /api/merchant/subscription/create`.
 *
 * @property amount Amount of each charge, in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency; monobank defaults to 980 (UAH).
 * @property redirectUrl Where the customer returns after the first payment.
 * @property webHookUrls Callback URLs.
 * @property interval `{number}{unit}`, unit `d`, `w`, `m` or `y`: `1d` daily, `2w` every two weeks,
 *   `1m` monthly, `1y` yearly.
 * @property validity Lifetime of the payment page in seconds; default 24 h, at most 30 days.
 */
@ConsistentCopyVisibility
public data class CreateSubscriptionRequest internal constructor(
    @get:JsonProperty("amount") public val amount: Long,
    @get:JsonProperty("ccy") public val ccy: Int?,
    @get:JsonProperty("redirectUrl") public val redirectUrl: String?,
    @get:JsonProperty("webHookUrls") public val webHookUrls: SubscriptionWebHookUrls?,
    @get:JsonProperty("interval") public val interval: String,
    @get:JsonProperty("validity") public val validity: Long?,
) {
    /** Builder for [CreateSubscriptionRequest]. */
    public class Builder internal constructor(
        private val amount: Long,
        private val interval: String,
    ) {
        private var ccy: Int? = null
        private var redirectUrl: String? = null
        private var webHookUrls: SubscriptionWebHookUrls? = null
        private var validity: Long? = null

        /** ISO 4217 numeric currency. */
        public fun ccy(ccy: Int?): Builder = apply { this.ccy = ccy }

        /** Return URL after the first payment. */
        public fun redirectUrl(url: String?): Builder = apply { redirectUrl = url }

        /** Callback URLs. */
        public fun webHookUrls(urls: SubscriptionWebHookUrls?): Builder = apply { webHookUrls = urls }

        /** Payment page lifetime in seconds. */
        public fun validity(seconds: Long): Builder = apply { validity = seconds }

        /** Builds the request. */
        public fun build(): CreateSubscriptionRequest {
            require(amount > 0) { "amount must be positive (minor units)" }
            return CreateSubscriptionRequest(amount, ccy, redirectUrl, webHookUrls, interval, validity)
        }
    }

    public companion object {
        /**
         * Starts a subscription.
         *
         * @param amount Amount of each charge in minor units (kopiyky).
         * @param interval e.g. `1m` for monthly.
         */
        @JvmStatic
        public fun builder(
            amount: Long,
            interval: String,
        ): Builder {
            require(interval.isNotBlank()) { "interval must not be blank" }
            return Builder(amount, interval)
        }
    }
}

/**
 * Answer of `POST /api/merchant/subscription/create`.
 *
 * @property subscriptionId Subscription id.
 * @property pageUrl Page where the customer pays the first charge and saves the card.
 */
@ConsistentCopyVisibility
public data class CreateSubscriptionResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("subscriptionId") public val subscriptionId: String,
        @param:JsonProperty("pageUrl") public val pageUrl: String,
    )

/**
 * Subscription status.
 *
 * @property value Wire value.
 */
public enum class SubscriptionStatus(
    @get:JsonValue public val value: String,
) {
    /** Active: charges continue. */
    ACTIVE("active"),

    /** Cancelled. */
    CANCELLED("cancelled"),

    /** A value this SDK version does not know. Cannot be used as a filter. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): SubscriptionStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Charge counters of a subscription.
 *
 * @property totalPaid Number of successful charges.
 * @property totalFailed Number of failed charges.
 */
@ConsistentCopyVisibility
public data class SubscriptionSummary
    @JsonCreator
    internal constructor(
        @param:JsonProperty("totalPaid") public val totalPaid: Int?,
        @param:JsonProperty("totalFailed") public val totalFailed: Int?,
    )

/**
 * Answer of `GET /api/merchant/subscription/status`.
 *
 * @property subscriptionId Subscription id.
 * @property statusRaw Status exactly as sent.
 * @property startDate First charge date.
 * @property endDate When the subscription was cancelled.
 * @property amount Amount of each charge in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property interval Charge interval, e.g. `1m`.
 * @property nextChargeDate Next charge date.
 * @property cancellationDesc Why it was cancelled.
 * @property summary Charge counters.
 * @property walletData The saved card that is charged (token hidden in `toString`).
 */
@ConsistentCopyVisibility
public data class SubscriptionStatusResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("subscriptionId") public val subscriptionId: String,
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("startDate") public val startDate: OffsetDateTime?,
        @param:JsonProperty("endDate") public val endDate: OffsetDateTime?,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("ccy") public val ccy: Int,
        @param:JsonProperty("interval") public val interval: String?,
        @param:JsonProperty("nextChargeDate") public val nextChargeDate: OffsetDateTime?,
        @param:JsonProperty("cancellationDesc") public val cancellationDesc: String?,
        @param:JsonProperty("summary") public val summary: SubscriptionSummary?,
        @param:JsonProperty("walletData") public val walletData: WalletData?,
    ) {
        /** Typed status. */
        public val status: SubscriptionStatus
            get() = SubscriptionStatus.fromValue(statusRaw)
    }

/**
 * Status of one subscription charge.
 *
 * @property value Wire value.
 */
public enum class SubscriptionPaymentStatus(
    @get:JsonValue public val value: String,
) {
    /** Created, not charged yet. */
    NEW("new"),

    /** Charged. */
    SUCCESS("success"),

    /** The charge failed. */
    FAILED("failed"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): SubscriptionPaymentStatus =
            entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * One charge of a subscription (`GET /api/merchant/subscription/payments`).
 *
 * @property amount Amount in minor units (kopiyky).
 * @property statusRaw Status exactly as sent.
 * @property chargedAt When the card was charged.
 * @property ccy ISO 4217 numeric currency.
 */
@ConsistentCopyVisibility
public data class SubscriptionPayment
    @JsonCreator
    internal constructor(
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("chargedAt") public val chargedAt: OffsetDateTime?,
        @param:JsonProperty("ccy") public val ccy: Int,
    ) {
        /** Typed status. */
        public val status: SubscriptionPaymentStatus
            get() = SubscriptionPaymentStatus.fromValue(statusRaw)
    }

/**
 * One subscription in `GET /api/merchant/subscription/list`.
 *
 * @property subscriptionId Subscription id.
 * @property amount Amount of each charge in minor units (kopiyky).
 * @property interval Charge interval, e.g. `1m`.
 * @property startDate Start date.
 * @property created Creation date.
 * @property nextChargeDate Next charge date.
 * @property endDate End date.
 * @property statusRaw Status exactly as sent.
 */
@ConsistentCopyVisibility
public data class SubscriptionListItem
    @JsonCreator
    internal constructor(
        @param:JsonProperty("subscriptionId") public val subscriptionId: String,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("interval") public val interval: String?,
        @param:JsonProperty("startDate") public val startDate: OffsetDateTime?,
        @param:JsonProperty("created") public val created: OffsetDateTime?,
        @param:JsonProperty("nextChargeDate") public val nextChargeDate: OffsetDateTime?,
        @param:JsonProperty("endDate") public val endDate: OffsetDateTime?,
        @param:JsonProperty("status") public val statusRaw: String,
    ) {
        /** Typed status. */
        public val status: SubscriptionStatus
            get() = SubscriptionStatus.fromValue(statusRaw)
    }

/**
 * Action of `POST /api/merchant/subscription/edit`.
 *
 * @property value Wire value.
 */
public enum class SubscriptionAction(
    @get:JsonValue public val value: String,
) {
    /** Cancel the subscription, optionally refunding an amount. */
    CANCEL("cancel"),
}

/**
 * Request of `POST /api/merchant/subscription/edit`. Build with [cancel].
 *
 * @property subscriptionId Subscription id.
 * @property action What to do (only [SubscriptionAction.CANCEL] exists).
 * @property refundAmount Amount to refund in minor units (kopiyky); `null` refunds nothing.
 */
@ConsistentCopyVisibility
public data class EditSubscriptionRequest internal constructor(
    @get:JsonProperty("subscriptionId") public val subscriptionId: String,
    @get:JsonProperty("action") public val action: SubscriptionAction,
    @get:JsonProperty("refundAmount") public val refundAmount: Long?,
) {
    public companion object {
        /**
         * Cancels a subscription without a refund.
         */
        @JvmStatic
        public fun cancel(subscriptionId: String): EditSubscriptionRequest = cancelWith(subscriptionId, null)

        /** Cancels the subscription and refunds [refundAmount] (minor units, kopiyky) of the last payment. */
        @JvmStatic
        public fun cancel(
            subscriptionId: String,
            refundAmount: Long,
        ): EditSubscriptionRequest = cancelWith(subscriptionId, refundAmount)

        private fun cancelWith(
            subscriptionId: String,
            refundAmount: Long?,
        ): EditSubscriptionRequest {
            require(subscriptionId.isNotBlank()) { "subscriptionId must not be blank" }
            refundAmount?.let { require(it > 0) { "refundAmount must be positive (minor units)" } }
            return EditSubscriptionRequest(subscriptionId, SubscriptionAction.CANCEL, refundAmount)
        }
    }
}

internal class SubscriptionIdRequest(
    @get:JsonProperty("subscriptionId") val subscriptionId: String,
)

internal class SubscriptionPaymentsWire
    @JsonCreator
    constructor(
        @param:JsonProperty("payments") val payments: List<SubscriptionPayment>?,
        @param:JsonProperty("pagination") val pagination: Pagination?,
    )

internal class SubscriptionListWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<SubscriptionListItem>?,
        @param:JsonProperty("pagination") val pagination: Pagination?,
    )
