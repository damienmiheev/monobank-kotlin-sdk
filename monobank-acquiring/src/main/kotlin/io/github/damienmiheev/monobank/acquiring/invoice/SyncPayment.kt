package io.github.damienmiheev.monobank.acquiring.invoice

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.core.Redaction

/**
 * Kind of card number sent to `invoice/sync-payment`.
 *
 * @property value Wire value.
 */
public enum class SyncCardType(
    @get:JsonValue public val value: String,
) {
    /** Funding PAN: the real card number. */
    FPAN("FPAN"),

    /** Device PAN: a network token (e.g. from a decrypted Apple Pay or Google Pay container). */
    DPAN("DPAN"),
}

/**
 * Card data for `invoice/sync-payment`. [toString] hides the PAN, expiry, CVV and the
 * authentication values (CAVV, TAVV).
 *
 * @property pan Card number (FPAN or DPAN).
 * @property type Whether [pan] is a funding or a device PAN.
 * @property exp Expiry as `MMYY`.
 * @property cvv CVV2/CVC2.
 * @property eciIndicator Electronic Commerce Indicator: the authentication result, e.g. `02`.
 * @property cavv Cardholder Authentication Verification Value.
 * @property tavv Token Authentication Verification Value.
 * @property dsTranId 3-D Secure Directory Server transaction id (XID).
 * @property tReqID Token requestor id.
 * @property mit `1` for a merchant-initiated, `2` for a customer-initiated transaction.
 * @property sst Subsequent transaction indicator.
 * @property tid Trace id of the first operation in a series.
 */
@ConsistentCopyVisibility
public data class SyncCardData internal constructor(
    @get:JsonProperty("pan") public val pan: String,
    @get:JsonProperty("type") public val type: SyncCardType,
    @get:JsonProperty("exp") public val exp: String,
    @get:JsonProperty("cvv") public val cvv: String?,
    @get:JsonProperty("eciIndicator") public val eciIndicator: String,
    @get:JsonProperty("cavv") public val cavv: String?,
    @get:JsonProperty("tavv") public val tavv: String?,
    @get:JsonProperty("dsTranId") public val dsTranId: String?,
    @get:JsonProperty("tReqID") public val tReqID: String?,
    @get:JsonProperty("mit") public val mit: String?,
    @get:JsonProperty("sst") public val sst: Int?,
    @get:JsonProperty("tid") public val tid: String?,
) {
    /** Builder for [SyncCardData]. */
    public class Builder internal constructor(
        private val pan: String,
        private val type: SyncCardType,
        private val exp: String,
        private val eciIndicator: String,
    ) {
        private var cvv: String? = null
        private var cavv: String? = null
        private var tavv: String? = null
        private var dsTranId: String? = null
        private var tReqID: String? = null
        private var mit: String? = null
        private var sst: Int? = null
        private var tid: String? = null

        /** CVV2/CVC2. */
        public fun cvv(cvv: String?): Builder = apply { this.cvv = cvv }

        /** Cardholder Authentication Verification Value. */
        public fun cavv(cavv: String?): Builder = apply { this.cavv = cavv }

        /** Token Authentication Verification Value. */
        public fun tavv(tavv: String?): Builder = apply { this.tavv = tavv }

        /** 3-D Secure Directory Server transaction id. */
        public fun dsTranId(dsTranId: String?): Builder = apply { this.dsTranId = dsTranId }

        /** Token requestor id. */
        public fun tReqID(tReqID: String?): Builder = apply { this.tReqID = tReqID }

        /** `1` merchant-initiated, `2` customer-initiated. */
        public fun mit(mit: String?): Builder = apply { this.mit = mit }

        /** Subsequent transaction indicator. */
        public fun sst(sst: Int?): Builder = apply { this.sst = sst }

        /** Trace id of the first operation. */
        public fun tid(tid: String?): Builder = apply { this.tid = tid }

        /** Builds the card data. */
        public fun build(): SyncCardData =
            SyncCardData(pan, type, exp, cvv, eciIndicator, cavv, tavv, dsTranId, tReqID, mit, sst, tid)
    }

    override fun toString(): String =
        "SyncCardData(pan=${Redaction.tail(pan)}, type=$type, exp=***, cvv=${Redaction.secret(cvv)}, " +
            "eciIndicator=$eciIndicator, cavv=${Redaction.secret(cavv)}, tavv=${Redaction.secret(tavv)}, " +
            "dsTranId=$dsTranId, tReqID=$tReqID, mit=$mit, sst=$sst, tid=$tid)"

    public companion object {
        /**
         * Starts card data.
         *
         * @param exp Expiry as `MMYY`.
         * @param eciIndicator Electronic Commerce Indicator, e.g. `02`.
         */
        @JvmStatic
        public fun builder(
            pan: String,
            type: SyncCardType,
            exp: String,
            eciIndicator: String,
        ): Builder {
            require(pan.isNotBlank()) { "pan must not be blank" }
            requireExpiry(exp)
            return Builder(pan, type, exp, eciIndicator)
        }
    }
}

/**
 * Decrypted Apple Pay or Google Pay payment data for `invoice/sync-payment`. [toString] hides the
 * token, expiry and cryptogram.
 *
 * @property token Device card number (DPAN) from the decrypted container.
 * @property exp Expiry as `MMYY`.
 * @property eciIndicator Electronic Commerce Indicator, e.g. `02`.
 * @property cryptogram TAVV cryptogram.
 */
@ConsistentCopyVisibility
public data class DeviceTokenData internal constructor(
    @get:JsonProperty("token") public val token: String,
    @get:JsonProperty("exp") public val exp: String,
    @get:JsonProperty("eciIndicator") public val eciIndicator: String,
    @get:JsonProperty("cryptogram") public val cryptogram: String?,
) {
    override fun toString(): String =
        "DeviceTokenData(token=${Redaction.tail(token)}, exp=***, eciIndicator=$eciIndicator, " +
            "cryptogram=${Redaction.secret(cryptogram)})"

    public companion object {
        /** Creates the payment data. */
        @JvmStatic
        @JvmOverloads
        public fun of(
            token: String,
            exp: String,
            eciIndicator: String,
            cryptogram: String? = null,
        ): DeviceTokenData {
            require(token.isNotBlank()) { "token must not be blank" }
            requireExpiry(exp)
            return DeviceTokenData(token, exp, eciIndicator, cryptogram)
        }
    }
}

/**
 * Request of `POST /api/merchant/invoice/sync-payment`: a synchronous payment with card data or
 * decrypted Apple Pay / Google Pay data. Access is granted by monobank support and needs
 * certificates. Exactly one of [cardData], [applePay], [googlePay] is set: start with [card],
 * [applePay] or [googlePay]. [toString] hides all card data.
 *
 * This is a payment, not a status lookup: calling it again charges again.
 *
 * @property amount Amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency. Required.
 * @property merchantPaymInfo Order details; the docs list only `reference` and `destination` here.
 * @property cardData Card data.
 * @property applePay Decrypted Apple Pay data.
 * @property googlePay Decrypted Google Pay data.
 */
@ConsistentCopyVisibility
public data class SyncPaymentRequest internal constructor(
    @get:JsonProperty("amount") public val amount: Long,
    @get:JsonProperty("ccy") public val ccy: Int,
    @get:JsonProperty("merchantPaymInfo") public val merchantPaymInfo: MerchantPaymInfo?,
    @get:JsonProperty("cardData") public val cardData: SyncCardData?,
    @get:JsonProperty("applePay") public val applePay: DeviceTokenData?,
    @get:JsonProperty("googlePay") public val googlePay: DeviceTokenData?,
) {
    /** Builder for [SyncPaymentRequest]. */
    public class Builder internal constructor(
        private val amount: Long,
        private val ccy: Int,
        private val cardData: SyncCardData?,
        private val applePay: DeviceTokenData?,
        private val googlePay: DeviceTokenData?,
    ) {
        private var merchantPaymInfo: MerchantPaymInfo? = null

        /** Order details (`reference`, `destination`). */
        public fun merchantPaymInfo(info: MerchantPaymInfo?): Builder = apply { merchantPaymInfo = info }

        /** Builds the request. */
        public fun build(): SyncPaymentRequest {
            require(amount > 0) { "amount must be positive (minor units)" }
            return SyncPaymentRequest(amount, ccy, merchantPaymInfo, cardData, applePay, googlePay)
        }
    }

    public companion object {
        /**
         * Starts a payment with card data.
         *
         * @param amount Amount in minor units (kopiyky).
         */
        @JvmStatic
        public fun card(
            amount: Long,
            ccy: Int,
            cardData: SyncCardData,
        ): Builder = Builder(amount, ccy, cardData, null, null)

        /**
         * Starts a payment with decrypted Apple Pay data.
         *
         * @param amount Amount in minor units (kopiyky).
         */
        @JvmStatic
        public fun applePay(
            amount: Long,
            ccy: Int,
            data: DeviceTokenData,
        ): Builder = Builder(amount, ccy, null, data, null)

        /**
         * Starts a payment with decrypted Google Pay data.
         *
         * @param amount Amount in minor units (kopiyky).
         */
        @JvmStatic
        public fun googlePay(
            amount: Long,
            ccy: Int,
            data: DeviceTokenData,
        ): Builder = Builder(amount, ccy, null, null, data)
    }
}
