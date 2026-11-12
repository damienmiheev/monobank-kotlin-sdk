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
 * Invoice state, returned by `GET /api/merchant/invoice/status` and sent as the webhook body.
 *
 * Fields are nullable unless monobank always sends them, so a webhook is never rejected
 * because an optional detail is missing.
 *
 * @property invoiceId Invoice id.
 * @property status Current status.
 * @property amount Invoice amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property finalAmount Amount after payment and refunds, in minor units.
 * @property failureReason Human-readable failure reason.
 * @property errCode Payment error code (see monobank's error code table).
 * @property createdDate When the payment was made.
 * @property modifiedDate Last change. Webhooks can arrive out of order: keep the one with the latest value.
 * @property reference Your [io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo.reference].
 * @property destination Payment purpose.
 * @property cancelList Refunds requested on this invoice.
 * @property paymentInfo Card and transaction details.
 * @property walletData Saved card (when the invoice was created with card saving).
 * @property tipsInfo Tips details (legacy; only some merchants).
 */
@ConsistentCopyVisibility
public data class InvoiceStatusResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("invoiceId") public val invoiceId: String,
        @param:JsonProperty("status") public val status: InvoiceStatus,
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
    ) {
        /**
         * `true` when this state is newer than [other] (by [modifiedDate]). Use it to ignore
         * webhooks that arrive out of order. A state without a date is never newer.
         */
        public fun isNewerThan(other: InvoiceStatusResponse): Boolean {
            val mine = modifiedDate ?: return false
            val theirs = other.modifiedDate ?: return true
            return mine.isAfter(theirs)
        }
    }

/**
 * One refund (cancel) request on an invoice.
 *
 * @property amount Refunded amount in minor units.
 * @property extRef Your refund reference from the cancel request.
 */
@ConsistentCopyVisibility
public data class CancelListItem
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val status: OperationStatus,
        @param:JsonProperty("amount") public val amount: Long?,
        @param:JsonProperty("ccy") public val ccy: Int?,
        @param:JsonProperty("createdDate") public val createdDate: OffsetDateTime?,
        @param:JsonProperty("modifiedDate") public val modifiedDate: OffsetDateTime?,
        @param:JsonProperty("approvalCode") public val approvalCode: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("extRef") public val extRef: String?,
    )

/**
 * Card and transaction details of a payment.
 *
 * @property maskedPan Masked card number, e.g. `444403******1902`.
 * @property fee Acquiring fee in minor units.
 * @property country Card issuer country, ISO 3166-1 numeric.
 * @property agentFee Agent fee in minor units.
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
        @param:JsonProperty("paymentSystem") public val paymentSystem: PaymentSystem?,
        @param:JsonProperty("paymentMethod") public val paymentMethod: PaymentMethod?,
        @param:JsonProperty("fee") public val fee: Long?,
        @param:JsonProperty("country") public val country: String?,
        @param:JsonProperty("agentFee") public val agentFee: Long?,
    )

/**
 * Saved card of an invoice created with card saving. Webhooks also arrive when [status] changes.
 *
 * @property cardToken Token to charge with `WalletApi.payment`. It allows charging the card
 *   without the customer: store it like a secret. Hidden in [toString].
 * @property walletId Your wallet id.
 * @property status Tokenization status; the token is usable once it is [CardTokenStatus.CREATED].
 * @property maskedPan Masked card number (sent in practice, not in the current spec).
 * @property country Card country (sent in practice, not in the current spec).
 */
@ConsistentCopyVisibility
public data class WalletData
    @JsonCreator
    internal constructor(
        @param:JsonProperty("cardToken") public val cardToken: String?,
        @param:JsonProperty("walletId") public val walletId: String?,
        @param:JsonProperty("status") public val status: CardTokenStatus?,
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
        @param:JsonProperty("country") public val country: String?,
    ) {
        override fun toString(): String =
            "WalletData(cardToken=${Redaction.tail(cardToken)}, walletId=$walletId, status=$status, " +
                "maskedPan=$maskedPan, country=$country)"
    }

/**
 * Tips left for an employee (legacy API).
 *
 * @property employeeId Employee id.
 * @property amount Tips amount in minor units.
 */
@ConsistentCopyVisibility
public data class TipsInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("employeeId") public val employeeId: String?,
        @param:JsonProperty("amount") public val amount: Long?,
    )
