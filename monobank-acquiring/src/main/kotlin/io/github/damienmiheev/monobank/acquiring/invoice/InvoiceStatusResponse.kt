package io.github.damienmiheev.monobank.acquiring.invoice

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentMethod
import io.github.damienmiheev.monobank.acquiring.common.PaymentSystem
import io.github.damienmiheev.monobank.core.Redaction
import java.time.OffsetDateTime

/**
 * Invoice state, returned by `GET /api/merchant/invoice/status` and `POST /api/merchant/invoice/sync-payment`,
 * and sent as the webhook body.
 *
 * Fields are nullable unless monobank always sends them, so a webhook is never rejected
 * because an optional detail is missing. Enum fields keep the wire value (`statusRaw`), so a
 * status this SDK version does not know is still visible; the typed [status] is then
 * [InvoiceStatus.UNKNOWN].
 *
 * @property invoiceId Invoice id.
 * @property statusRaw Status exactly as monobank sent it.
 * @property amount Invoice amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property finalAmount Amount after payment and refunds, in minor units (kopiyky).
 * @property failureReason Human-readable failure reason.
 * @property errCode Payment error code; see `PaymentErrorCodes.describe`.
 * @property createdDate When the payment was made.
 * @property modifiedDate Last change. Webhooks can arrive out of order: ignore a webhook only when it is
 *   strictly older than the state you have ([isOlderThan]). Apply one with the **same** date: monobank
 *   may send separate webhooks for the saved card (`walletData`) and refunds (`cancelList`) without
 *   changing this date.
 *   Precision differs between endpoints (`invoice/status` and webhooks: whole seconds; payment
 *   answers: fractions of a second), so never order events on sub-second differences;
 *   [isNewerThan] and [isOlderThan] compare to the second.
 * @property reference Your [io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo.reference].
 * @property destination Payment purpose.
 * @property cancelList Refunds requested on this invoice.
 * @property paymentInfo Card and transaction details.
 * @property walletData Saved card (when the invoice was created with card saving).
 * @property tipsInfo Tips details (legacy; only some merchants).
 * @property payMethod How it was paid, e.g. `wallet` for a saved card. Sent by monobank but not in
 *   its schema (seen live); prefer [PaymentInfo.paymentMethod].
 */
@ConsistentCopyVisibility
public data class InvoiceStatusResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("invoiceId") public val invoiceId: String,
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("ccy") public val ccy: Int,
        @param:JsonProperty("finalAmount") public val finalAmount: Long?,
        @param:JsonProperty("failureReason") public val failureReason: String?,
        @param:JsonProperty("errCode") public val errCode: String?,
        @param:JsonProperty("createdDate") public val createdDate: OffsetDateTime?,
        @param:JsonProperty("modifiedDate") public val modifiedDate: OffsetDateTime?,
        @param:JsonProperty("reference") public val reference: String?,
        @param:JsonProperty("destination") public val destination: String?,
        @param:JsonProperty("cancelList") public val cancelList: List<CancelListItem>?,
        @param:JsonProperty("paymentInfo") public val paymentInfo: PaymentInfo?,
        @param:JsonProperty("walletData") public val walletData: WalletData?,
        @param:JsonProperty("tipsInfo") public val tipsInfo: TipsInfo?,
        @param:JsonProperty("payMethod") public val payMethod: String?,
    ) {
        /** Set only by the SDK's statement fallback; never read from JSON. */
        @get:com.fasterxml.jackson.annotation.JsonIgnore
        internal var reconstructedFlag: Boolean = false

        /**
         * `true` when monobank's `invoice/status` failed and this state was rebuilt from the merchant
         * statement by [InvoiceApi.status] with a statement window. Such a state has no `walletData`,
         * fewer `paymentInfo` details, and refunds without `extRef` (match them by amount).
         */
        public val isReconstructedFromStatement: Boolean
            get() = reconstructedFlag

        /** Typed status; [InvoiceStatus.UNKNOWN] for a value this SDK version does not know (see [statusRaw]). */
        public val status: InvoiceStatus
            get() = InvoiceStatus.fromValue(statusRaw)

        /**
         * `true` when this state is strictly newer than [other] (by [modifiedDate]). A state
         * without a date is never newer. Equal dates give `false`: to decide whether to apply an
         * incoming webhook use [isOlderThan] instead, so same-date events are not dropped.
         */
        public fun isNewerThan(other: InvoiceStatusResponse): Boolean {
            val mine = modifiedDate ?: return false
            val theirs = other.modifiedDate ?: return true
            return wholeSeconds(mine) > wholeSeconds(theirs)
        }

        /**
         * `true` when this state is strictly older than [other] (by [modifiedDate]): the only case
         * in which an incoming webhook should be ignored. Equal dates give `false` (apply it, and
         * merge `walletData` and `cancelList`); when either date is missing it also gives `false`.
         */
        public fun isOlderThan(other: InvoiceStatusResponse): Boolean {
            val mine = modifiedDate ?: return false
            val theirs = other.modifiedDate ?: return false
            return wholeSeconds(mine) < wholeSeconds(theirs)
        }

        /**
         * Dates are compared to the second: `invoice/status` and webhooks send whole seconds while
         * payment answers (`wallet/payment`) carry fractions, so a newer status can look older by a
         * fraction of a second (seen live).
         */
        private fun wholeSeconds(date: java.time.OffsetDateTime): Long = date.toEpochSecond()

        /**
         * The refund requested with [extRef] (your `CancelInvoiceRequest.extRef`), or `null`.
         * Refunds are asynchronous: follow its status here or in webhooks.
         */
        public fun cancelFor(extRef: String): CancelListItem? = cancelList?.lastOrNull { it.extRef == extRef }
    }

/**
 * One refund (cancel) request on an invoice.
 *
 * @property statusRaw Refund status exactly as monobank sent it.
 * @property amount Refunded amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property createdDate When the refund was requested.
 * @property modifiedDate Last change of the refund.
 * @property approvalCode Authorization code.
 * @property rrn Transaction id in the card network.
 * @property extRef Your refund reference from the cancel request.
 */
@ConsistentCopyVisibility
public data class CancelListItem
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val statusRaw: String?,
        @param:JsonProperty("amount") public val amount: Long?,
        @param:JsonProperty("ccy") public val ccy: Int?,
        @param:JsonProperty("createdDate") public val createdDate: OffsetDateTime?,
        @param:JsonProperty("modifiedDate") public val modifiedDate: OffsetDateTime?,
        @param:JsonProperty("approvalCode") public val approvalCode: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("extRef") public val extRef: String?,
    ) {
        /** Typed refund status; [OperationStatus.UNKNOWN] for an unknown or missing value. */
        public val status: OperationStatus
            get() = OperationStatus.fromValue(statusRaw)
    }

/**
 * Card and transaction details of a payment.
 *
 * @property maskedPan Masked card number, e.g. `444403******1902`.
 * @property approvalCode Authorization code.
 * @property rrn Transaction id in the card network.
 * @property tranId Transaction id.
 * @property terminal Terminal that processed the payment.
 * @property bank Card issuer name.
 * @property paymentSystemRaw Card network exactly as sent.
 * @property paymentMethodRaw Payment method exactly as sent.
 * @property fee Acquiring fee in minor units (kopiyky).
 * @property country Card issuer country, ISO 3166-1 numeric.
 * @property agentFee Agent fee in minor units (kopiyky).
 */
@ConsistentCopyVisibility
public data class PaymentInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
        @param:JsonProperty("approvalCode") public val approvalCode: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("tranId") public val tranId: String?,
        @param:JsonProperty("terminal") public val terminal: String?,
        @param:JsonProperty("bank") public val bank: String?,
        @param:JsonProperty("paymentSystem") public val paymentSystemRaw: String?,
        @param:JsonProperty("paymentMethod") public val paymentMethodRaw: String?,
        @param:JsonProperty("fee") public val fee: Long?,
        @param:JsonProperty("country") public val country: String?,
        @param:JsonProperty("agentFee") public val agentFee: Long?,
    ) {
        /** Typed card network, or `null` when not sent. */
        public val paymentSystem: PaymentSystem?
            get() = paymentSystemRaw?.let { PaymentSystem.fromValue(it) }

        /** Typed payment method, or `null` when not sent. */
        public val paymentMethod: PaymentMethod?
            get() = paymentMethodRaw?.let { PaymentMethod.fromValue(it) }
    }

/**
 * Saved card of an invoice created with card saving, or of a subscription. Webhooks also arrive
 * when [status] changes; the token is usable once it is [CardTokenStatus.CREATED].
 *
 * @property cardToken Token to charge with `WalletApi.payment`. It allows charging the card
 *   without the customer: store it like a secret. Hidden in [toString].
 * @property walletId Your wallet id.
 * @property statusRaw Tokenization status exactly as sent.
 * @property maskedPan Masked card number (sent in practice, not in the current spec).
 * @property country Card country (sent in practice, not in the current spec).
 * @property failureDescription Why the card could not be saved (subscriptions).
 */
@ConsistentCopyVisibility
public data class WalletData
    @JsonCreator
    internal constructor(
        @param:JsonProperty("cardToken") public val cardToken: String?,
        @param:JsonProperty("walletId") public val walletId: String?,
        @param:JsonProperty("status") public val statusRaw: String?,
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
        @param:JsonProperty("country") public val country: String?,
        @param:JsonProperty("failureDescription") public val failureDescription: String?,
    ) {
        /** Typed tokenization status, or `null` when not sent. */
        public val status: CardTokenStatus?
            get() = statusRaw?.let { CardTokenStatus.fromValue(it) }

        /** `true` when the card is saved and [cardToken] can be charged. */
        public val isCardSaved: Boolean
            get() = status == CardTokenStatus.CREATED && cardToken != null

        override fun toString(): String =
            "WalletData(cardToken=${Redaction.tail(cardToken)}, walletId=$walletId, status=$statusRaw, " +
                "maskedPan=$maskedPan, country=$country, failureDescription=$failureDescription)"
    }

/**
 * Tips left for an employee (legacy API).
 *
 * @property employeeId Employee id.
 * @property amount Tips amount in minor units (kopiyky).
 */
@ConsistentCopyVisibility
public data class TipsInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("employeeId") public val employeeId: String?,
        @param:JsonProperty("amount") public val amount: Long?,
    )
