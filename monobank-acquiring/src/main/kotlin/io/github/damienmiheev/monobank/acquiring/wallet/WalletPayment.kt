package io.github.damienmiheev.monobank.acquiring.wallet

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.invoice.requireDebitOrHold
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.core.Redaction

/**
 * Request of `POST /api/merchant/wallet/payment`: charge a saved card token, or (host-to-host)
 * an encrypted Apple Pay / Google Pay token. [toString] hides every token.
 *
 * Start with [builder] for a saved card. [applePay] and [googlePay] follow monobank's prose pages
 * (`aToken`, `gToken`), which the API schema does not list yet.
 *
 * @property cardToken Saved card token (from `walletData.cardToken`).
 * @property aToken Apple Pay `paymentData` JSON, as a string (Apple Pay host-to-host).
 * @property gToken Google Pay payment token JSON, as a string.
 * @property amount Amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency. Required.
 * @property redirectUrl Where the customer returns after 3-D Secure.
 * @property webHookUrl Where monobank POSTs status changes.
 * @property initiationKind Merchant-initiated (no customer, e.g. recurring) or customer-initiated.
 *   Required for card-token payments.
 * @property merchantPaymInfo Order details. Set a unique `reference` per attempt so a timed-out
 *   payment can be found with `MerchantApi.findByReference`.
 * @property paymentType [PaymentType.DEBIT] (default) or [PaymentType.HOLD].
 */
@ConsistentCopyVisibility
public data class WalletPaymentRequest internal constructor(
    @get:JsonProperty("cardToken") public val cardToken: String?,
    @get:JsonProperty("aToken") public val aToken: String?,
    @get:JsonProperty("gToken") public val gToken: String?,
    @get:JsonProperty("amount") public val amount: Long,
    @get:JsonProperty("ccy") public val ccy: Int,
    @get:JsonProperty("redirectUrl") public val redirectUrl: String?,
    @get:JsonProperty("webHookUrl") public val webHookUrl: String?,
    @get:JsonProperty("initiationKind") public val initiationKind: InitiationKind?,
    @get:JsonProperty("merchantPaymInfo") public val merchantPaymInfo: MerchantPaymInfo?,
    @get:JsonProperty("paymentType") public val paymentType: PaymentType?,
) {
    /** Builder for [WalletPaymentRequest]. */
    public class Builder internal constructor(
        private val cardToken: String?,
        private val aToken: String?,
        private val gToken: String?,
        private val amount: Long,
        private val ccy: Int,
        private var initiationKind: InitiationKind?,
    ) {
        private var redirectUrl: String? = null
        private var webHookUrl: String? = null
        private var merchantPaymInfo: MerchantPaymInfo? = null
        private var paymentType: PaymentType? = null

        /** Return URL after 3-D Secure. */
        public fun redirectUrl(url: String?): Builder = apply { redirectUrl = url }

        /** Webhook URL for status changes. */
        public fun webHookUrl(url: String?): Builder = apply { webHookUrl = url }

        /** Order details. */
        public fun merchantPaymInfo(info: MerchantPaymInfo?): Builder = apply { merchantPaymInfo = info }

        /** Debit or hold. */
        public fun paymentType(type: PaymentType?): Builder = apply { paymentType = type }

        /** Customer- or merchant-initiated (required for card tokens, optional for Apple/Google Pay). */
        public fun initiationKind(kind: InitiationKind?): Builder = apply { initiationKind = kind }

        /** Builds the request. */
        public fun build(): WalletPaymentRequest {
            require(amount > 0) { "amount must be positive (minor units)" }
            requireDebitOrHold(paymentType)
            if (cardToken != null) requireNotNull(initiationKind) { "initiationKind is required for card tokens" }
            return WalletPaymentRequest(
                cardToken,
                aToken,
                gToken,
                amount,
                ccy,
                redirectUrl,
                webHookUrl,
                initiationKind,
                merchantPaymInfo,
                paymentType,
            )
        }
    }

    override fun toString(): String =
        "WalletPaymentRequest(cardToken=${Redaction.tail(cardToken)}, aToken=${Redaction.secret(aToken)}, " +
            "gToken=${Redaction.secret(gToken)}, amount=$amount, ccy=$ccy, redirectUrl=$redirectUrl, " +
            "webHookUrl=$webHookUrl, initiationKind=$initiationKind, merchantPaymInfo=$merchantPaymInfo, " +
            "paymentType=$paymentType)"

    public companion object {
        /**
         * Starts a charge of a saved card.
         *
         * @param cardToken Token from `walletData.cardToken` (usable once its status is `created`).
         * @param amount Amount in minor units (kopiyky).
         * @param ccy ISO 4217 numeric currency, e.g. `CurrencyCodes.UAH`.
         * @param initiationKind [InitiationKind.MERCHANT] for charges without the customer
         *   (recurring), [InitiationKind.CLIENT] when the customer is paying now.
         */
        @JvmStatic
        public fun builder(
            cardToken: String,
            amount: Long,
            ccy: Int,
            initiationKind: InitiationKind,
        ): Builder {
            require(cardToken.isNotBlank()) { "cardToken must not be blank" }
            return Builder(cardToken, null, null, amount, ccy, initiationKind)
        }

        /**
         * Starts an Apple Pay host-to-host payment: [aToken] is the Apple Pay `paymentData` JSON
         * as a string. Needs an Apple Pay payment processing certificate registered with monobank.
         * Experimental: documented only on monobank's Apple Pay page, not in the API schema.
         */
        @JvmStatic
        @ExperimentalMonobankApi
        public fun applePay(
            aToken: String,
            amount: Long,
            ccy: Int,
        ): Builder {
            require(aToken.isNotBlank()) { "aToken must not be blank" }
            return Builder(null, aToken, null, amount, ccy, null)
        }

        /**
         * Starts a Google Pay payment: [gToken] is the Google Pay payment token JSON as a string
         * (gateway `monobank`). Google test tokens fail with `EXTERNAL_ERROR`.
         * Experimental: documented only on monobank's Google Pay page, not in the API schema.
         */
        @JvmStatic
        @ExperimentalMonobankApi
        public fun googlePay(
            gToken: String,
            amount: Long,
            ccy: Int,
        ): Builder {
            require(gToken.isNotBlank()) { "gToken must not be blank" }
            return Builder(null, null, gToken, amount, ccy, null)
        }
    }
}

/**
 * A saved card in a wallet (`GET /api/merchant/wallet`). [toString] shows only the token's tail.
 *
 * @property cardToken Token to charge with `WalletApi.payment`. Store it like a secret.
 * @property maskedPan Masked card number.
 * @property country Card issuer country, ISO 3166-1 numeric.
 */
@ConsistentCopyVisibility
public data class WalletCard
    @JsonCreator
    internal constructor(
        @param:JsonProperty("cardToken") public val cardToken: String,
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
        @param:JsonProperty("country") public val country: String?,
    ) {
        override fun toString(): String =
            "WalletCard(cardToken=${Redaction.tail(cardToken)}, maskedPan=$maskedPan, country=$country)"
    }

internal class WalletWire
    @JsonCreator
    constructor(
        @param:JsonProperty("wallet") val wallet: List<WalletCard>?,
    )
