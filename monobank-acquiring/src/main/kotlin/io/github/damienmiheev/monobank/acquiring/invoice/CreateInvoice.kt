package io.github.damienmiheev.monobank.acquiring.invoice

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.core.CurrencyCodes

/**
 * Request of `POST /api/merchant/invoice/create`.
 *
 * Build it with [builder]: `CreateInvoiceRequest.builder(4200).redirectUrl(...).build()`.
 *
 * @property amount Amount in minor units (kopiyky): 4200 is 42.00 UAH.
 * @property ccy ISO 4217 numeric currency code; monobank defaults to 980 (UAH).
 * @property merchantPaymInfo Order details; required when fiscal receipts are enabled.
 * @property redirectUrl Where the customer returns after paying (GET).
 * @property successUrl Where the customer returns after a successful payment (GET).
 * @property failUrl Where the customer returns after a failed payment (GET).
 * @property webHookUrl Where monobank POSTs status changes (verify them with `WebhookVerifier`).
 * @property validity Invoice lifetime in seconds; default 24 h, at most 30 days.
 * @property paymentType Debit (default), hold, or card verification.
 * @property qrId Sets the amount on an existing QR cash register.
 * @property code Submerchant terminal code (from the submerchant list).
 * @property saveCardData Card saving (tokenization) settings.
 * @property agentFeePercent Agent fee percent (agents only).
 * @property displayType `iframe` to get a page that can be embedded in an iframe.
 * @property withAppUrl `true` to also receive [CreateInvoiceResponse.appUrl], a monobank app deep link.
 *   Not supported for QR cash registers or verification invoices.
 * @property tipsEmployeeId Legacy: employee who receives tips, from `EmployeeApi.list`. Only in the
 *   legacy (v2410) documentation.
 *
 * ### Saving a card without charging it (add payment method)
 * ```kotlin
 * val request = CreateInvoiceRequest.verification("customer-42")
 *     .webHookUrl("https://shop.example/mono/webhook")
 *     .build()
 * val invoice = mono.invoices.create(request) // send the customer to invoice.pageUrl
 * ```
 * The card token then arrives in `walletData` of the webhook or `invoices.status(invoiceId)`; it can
 * be charged once `walletData.status` is `created`. Card saving must be enabled by monobank support.
 */
@ConsistentCopyVisibility
public data class CreateInvoiceRequest internal constructor(
    @get:JsonProperty("amount") public val amount: Long,
    @get:JsonProperty("ccy") public val ccy: Int?,
    @get:JsonProperty("merchantPaymInfo") public val merchantPaymInfo: MerchantPaymInfo?,
    @get:JsonProperty("redirectUrl") public val redirectUrl: String?,
    @get:JsonProperty("successUrl") public val successUrl: String?,
    @get:JsonProperty("failUrl") public val failUrl: String?,
    @get:JsonProperty("webHookUrl") public val webHookUrl: String?,
    @get:JsonProperty("validity") public val validity: Long?,
    @get:JsonProperty("paymentType") public val paymentType: PaymentType?,
    @get:JsonProperty("qrId") public val qrId: String?,
    @get:JsonProperty("code") public val code: String?,
    @get:JsonProperty("saveCardData") public val saveCardData: SaveCardData?,
    @get:JsonProperty("agentFeePercent") public val agentFeePercent: Double?,
    @get:JsonProperty("displayType") public val displayType: String?,
    @get:JsonProperty("withAppUrl") public val withAppUrl: Boolean?,
    @get:JsonProperty("tipsEmployeeId") public val tipsEmployeeId: String?,
) {
    /** Builder for [CreateInvoiceRequest]. */
    public class Builder internal constructor(
        private val amount: Long,
    ) {
        private var ccy: Int? = null
        private var merchantPaymInfo: MerchantPaymInfo? = null
        private var redirectUrl: String? = null
        private var successUrl: String? = null
        private var failUrl: String? = null
        private var webHookUrl: String? = null
        private var validity: Long? = null
        private var paymentType: PaymentType? = null
        private var qrId: String? = null
        private var code: String? = null
        private var saveCardData: SaveCardData? = null
        private var agentFeePercent: Double? = null
        private var displayType: String? = null
        private var withAppUrl: Boolean? = null
        private var tipsEmployeeId: String? = null

        /** ISO 4217 numeric currency, e.g. [CurrencyCodes.UAH]. */
        public fun ccy(ccy: Int?): Builder = apply { this.ccy = ccy }

        /** Order details. */
        public fun merchantPaymInfo(info: MerchantPaymInfo?): Builder = apply { merchantPaymInfo = info }

        /** Return URL after payment. */
        public fun redirectUrl(url: String?): Builder = apply { redirectUrl = url }

        /** Return URL after a successful payment. */
        public fun successUrl(url: String?): Builder = apply { successUrl = url }

        /** Return URL after a failed payment. */
        public fun failUrl(url: String?): Builder = apply { failUrl = url }

        /** Webhook URL for status changes. */
        public fun webHookUrl(url: String?): Builder = apply { webHookUrl = url }

        /** Invoice lifetime in seconds. */
        public fun validity(seconds: Long?): Builder = apply { validity = seconds }

        /** Debit, hold or verification. */
        public fun paymentType(type: PaymentType?): Builder = apply { paymentType = type }

        /** Existing QR cash register to put the amount on. */
        public fun qrId(qrId: String?): Builder = apply { this.qrId = qrId }

        /** Submerchant terminal code. */
        public fun code(code: String?): Builder = apply { this.code = code }

        /** Card saving settings. */
        public fun saveCardData(data: SaveCardData?): Builder = apply { saveCardData = data }

        /** Agent fee percent. */
        public fun agentFeePercent(percent: Double?): Builder = apply { agentFeePercent = percent }

        /** `iframe` for an embeddable page. */
        public fun displayType(type: String?): Builder = apply { displayType = type }

        /** Ask for a monobank app deep link in the answer. */
        public fun withAppUrl(withAppUrl: Boolean?): Builder = apply { this.withAppUrl = withAppUrl }

        /** Legacy: employee who receives tips (from the legacy employee list). */
        @Deprecated("Only in monobank's legacy (v2410) documentation; may stop working.")
        public fun tipsEmployeeId(employeeId: String?): Builder = apply { tipsEmployeeId = employeeId }

        /**
         * Builds the request.
         *
         * @throws IllegalArgumentException when the amount is not positive (or not `0` for a
         *   [PaymentType.VERIFICATION] invoice), or a verification invoice does not save the card.
         */
        public fun build(): CreateInvoiceRequest {
            require(paymentType != PaymentType.UNKNOWN) { "paymentType UNKNOWN cannot be sent" }
            if (paymentType == PaymentType.VERIFICATION) {
                require(amount == 0L) { "a verification invoice must have amount 0" }
                require(
                    saveCardData?.saveCard == true,
                ) { "a verification invoice needs saveCardData with saveCard=true" }
            } else {
                require(amount > 0) { "amount must be positive (minor units)" }
            }
            requireHoldCurrency(paymentType, ccy)
            return CreateInvoiceRequest(
                amount,
                ccy,
                merchantPaymInfo,
                redirectUrl,
                successUrl,
                failUrl,
                webHookUrl,
                validity,
                paymentType,
                qrId,
                code,
                saveCardData,
                agentFeePercent,
                displayType,
                withAppUrl,
                tipsEmployeeId,
            )
        }
    }

    public companion object {
        /**
         * Starts a request.
         *
         * @param amount Amount in minor units (kopiyky).
         */
        @JvmStatic
        public fun builder(amount: Long): Builder = Builder(amount)

        /**
         * Starts a card verification invoice: amount `0`, [PaymentType.VERIFICATION], and the card
         * saved into [walletId]. The customer enters the card on the payment page; nothing is charged.
         *
         * @param walletId Your stable id for the customer; saved cards are grouped under it.
         */
        @JvmStatic
        public fun verification(walletId: String): Builder {
            require(walletId.isNotBlank()) { "walletId must not be blank" }
            return Builder(0).paymentType(PaymentType.VERIFICATION).saveCardData(SaveCardData.saveTo(walletId))
        }
    }
}

/**
 * Answer of `POST /api/merchant/invoice/create`.
 *
 * @property invoiceId Unique invoice id; store it before redirecting the customer.
 * @property pageUrl Payment page to send the customer to.
 * @property appUrl monobank app deep link, only when requested with `withAppUrl`.
 */
@ConsistentCopyVisibility
public data class CreateInvoiceResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("invoiceId") public val invoiceId: String,
        @param:JsonProperty("pageUrl") public val pageUrl: String,
        @param:JsonProperty("appUrl") public val appUrl: String?,
    )
