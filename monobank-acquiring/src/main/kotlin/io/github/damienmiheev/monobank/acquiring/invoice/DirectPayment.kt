package io.github.damienmiheev.monobank.acquiring.invoice

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.core.CurrencyCodes
import io.github.damienmiheev.monobank.core.Redaction
import java.time.OffsetDateTime

/**
 * Card details for `invoice/payment-direct`. Only for PCI DSS certified merchants.
 *
 * [toString] never prints the card number, expiry or CVV.
 *
 * @property pan Card number.
 * @property exp Expiry in `MMYY` format, e.g. `0642`.
 * @property cvv CVV2/CVC2.
 */
public class CardData private constructor(
    @get:JsonProperty("pan") public val pan: String,
    @get:JsonProperty("exp") public val exp: String,
    @get:JsonProperty("cvv") public val cvv: String,
) {
    override fun equals(other: Any?): Boolean =
        other is CardData && other.pan == pan && other.exp == exp && other.cvv == cvv

    override fun hashCode(): Int = (pan.hashCode() * 31 + exp.hashCode()) * 31 + cvv.hashCode()

    override fun toString(): String = "CardData(pan=${Redaction.tail(pan)}, exp=***, cvv=***)"

    public companion object {
        /**
         * Creates card details.
         *
         * @param exp Expiry as `MMYY`.
         */
        @JvmStatic
        public fun of(
            pan: String,
            exp: String,
            cvv: String,
        ): CardData {
            require(pan.isNotBlank()) { "pan must not be blank" }
            requireExpiry(exp)
            require(cvv.isNotBlank()) { "cvv must not be blank" }
            return CardData(pan, exp, cvv)
        }
    }
}

/**
 * Request of `POST /api/merchant/invoice/payment-direct`: create and pay an invoice with raw card
 * data. Only for PCI DSS certified merchants. [toString] hides the card data.
 *
 * @property amount Amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency; monobank defaults to 980 (UAH).
 * @property cardData Card details.
 * @property merchantPaymInfo Order details; required when fiscal receipts are enabled.
 * @property webHookUrl Where monobank POSTs status changes.
 * @property paymentType [PaymentType.DEBIT] (default) or [PaymentType.HOLD].
 * @property saveCardData Card saving (tokenization) settings.
 * @property redirectUrl Where the customer returns after 3-D Secure.
 * @property initiationKind Customer- or merchant-initiated.
 */
@ConsistentCopyVisibility
public data class PaymentDirectRequest internal constructor(
    @get:JsonProperty("amount") public val amount: Long,
    @get:JsonProperty("ccy") public val ccy: Int?,
    @get:JsonProperty("cardData") public val cardData: CardData,
    @get:JsonProperty("merchantPaymInfo") public val merchantPaymInfo: MerchantPaymInfo?,
    @get:JsonProperty("webHookUrl") public val webHookUrl: String?,
    @get:JsonProperty("paymentType") public val paymentType: PaymentType?,
    @get:JsonProperty("saveCardData") public val saveCardData: SaveCardData?,
    @get:JsonProperty("redirectUrl") public val redirectUrl: String?,
    @get:JsonProperty("initiationKind") public val initiationKind: InitiationKind?,
) {
    /** Builder for [PaymentDirectRequest]. */
    public class Builder internal constructor(
        private val amount: Long,
        private val cardData: CardData,
    ) {
        private var ccy: Int? = null
        private var merchantPaymInfo: MerchantPaymInfo? = null
        private var webHookUrl: String? = null
        private var paymentType: PaymentType? = null
        private var saveCardData: SaveCardData? = null
        private var redirectUrl: String? = null
        private var initiationKind: InitiationKind? = null

        /** ISO 4217 numeric currency. */
        public fun ccy(ccy: Int?): Builder = apply { this.ccy = ccy }

        /** Order details. */
        public fun merchantPaymInfo(info: MerchantPaymInfo?): Builder = apply { merchantPaymInfo = info }

        /** Webhook URL for status changes. */
        public fun webHookUrl(url: String?): Builder = apply { webHookUrl = url }

        /** Debit or hold. */
        public fun paymentType(type: PaymentType?): Builder = apply { paymentType = type }

        /** Card saving settings. */
        public fun saveCardData(data: SaveCardData?): Builder = apply { saveCardData = data }

        /** Return URL after 3-D Secure. */
        public fun redirectUrl(url: String?): Builder = apply { redirectUrl = url }

        /** Customer- or merchant-initiated. */
        public fun initiationKind(kind: InitiationKind?): Builder = apply { initiationKind = kind }

        /** Builds the request. */
        public fun build(): PaymentDirectRequest {
            require(amount > 0) { "amount must be positive (minor units)" }
            requireDebitOrHold(paymentType)
            requireHoldCurrency(paymentType, ccy)
            return PaymentDirectRequest(
                amount,
                ccy,
                cardData,
                merchantPaymInfo,
                webHookUrl,
                paymentType,
                saveCardData,
                redirectUrl,
                initiationKind,
            )
        }
    }

    override fun toString(): String =
        "PaymentDirectRequest(amount=$amount, ccy=$ccy, cardData=$cardData, merchantPaymInfo=$merchantPaymInfo, " +
            "webHookUrl=$webHookUrl, paymentType=$paymentType, saveCardData=$saveCardData, " +
            "redirectUrl=$redirectUrl, initiationKind=$initiationKind)"

    public companion object {
        /**
         * Starts a request.
         *
         * @param amount Amount in minor units (kopiyky).
         */
        @JvmStatic
        public fun builder(
            amount: Long,
            cardData: CardData,
        ): Builder = Builder(amount, cardData)
    }
}

/** Card expiry as `MMYY`: four ASCII digits, month 01-12. */
internal fun requireExpiry(exp: String) {
    require(exp.length == 4 && exp.all { it in '0'..'9' } && exp.substring(0, 2).toInt() in 1..12) {
        "exp must be MMYY"
    }
}

/** Holds work only in UAH (980), per monobank's docs; `null` means monobank's default, UAH. */
internal fun requireHoldCurrency(
    paymentType: PaymentType?,
    ccy: Int?,
) {
    require(paymentType != PaymentType.HOLD || ccy == null || ccy == CurrencyCodes.UAH) {
        "paymentType HOLD works only with ccy 980 (UAH)"
    }
}

/** Payments by card data or token support only debit and hold. */
internal fun requireDebitOrHold(paymentType: PaymentType?) {
    require(paymentType == null || paymentType == PaymentType.DEBIT || paymentType == PaymentType.HOLD) {
        "paymentType must be DEBIT or HOLD"
    }
}

/**
 * Answer of `POST /api/merchant/invoice/payment-direct` and `POST /api/merchant/wallet/payment`.
 *
 * For a hold (`paymentType=hold`) monobank documents only `processing`, `success` and `failure`
 * here; if [statusRaw] is something else (typed [status] `UNKNOWN`), read `invoices.status(invoiceId)`.
 *
 * When [tdsUrl] is set and the payment is still [OperationStatus.PROCESSING], send the customer
 * there to pass 3-D Secure ([isThreeDsRequired]); the final status then comes in a webhook or from
 * `invoices.status(invoiceId)`.
 *
 * @property invoiceId Invoice created for this payment.
 * @property tdsUrl 3-D Secure page, when the card issuer asks for it.
 * @property statusRaw Payment status exactly as sent.
 * @property failureReason Why the payment failed.
 * @property amount Amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property createdDate When the payment was created.
 * @property modifiedDate Last change of the payment.
 * @property errCode Payment error code (see `PaymentErrorCodes.describe`). Not in monobank's schema
 *   for this answer, so usually `null`; `invoices.status(invoiceId).errCode` always carries it.
 */
@ConsistentCopyVisibility
public data class PaymentResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("invoiceId") public val invoiceId: String,
        @param:JsonProperty("tdsUrl") public val tdsUrl: String?,
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("failureReason") public val failureReason: String?,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("ccy") public val ccy: Int,
        @param:JsonProperty("createdDate") public val createdDate: OffsetDateTime?,
        @param:JsonProperty("modifiedDate") public val modifiedDate: OffsetDateTime?,
        @param:JsonProperty("errCode") public val errCode: String?,
    ) {
        /** Typed payment status; [OperationStatus.UNKNOWN] for a value this SDK version does not know. */
        public val status: OperationStatus
            get() = OperationStatus.fromValue(statusRaw)

        /** `true` when the customer must pass 3-D Secure at [tdsUrl] before the payment can complete. */
        public val isThreeDsRequired: Boolean
            get() = tdsUrl != null && status == OperationStatus.PROCESSING
    }
