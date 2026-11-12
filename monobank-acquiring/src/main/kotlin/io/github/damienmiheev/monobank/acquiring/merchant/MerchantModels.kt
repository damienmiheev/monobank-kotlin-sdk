package io.github.damienmiheev.monobank.acquiring.merchant

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import java.time.OffsetDateTime

/**
 * Answer of `GET /api/merchant/details`.
 *
 * @property merchantId Merchant id.
 * @property merchantName Merchant name.
 * @property edrpou EDRPOU (company registration code).
 */
@ConsistentCopyVisibility
public data class MerchantDetails
    @JsonCreator
    internal constructor(
        @param:JsonProperty("merchantId") public val merchantId: String,
        @param:JsonProperty("merchantName") public val merchantName: String?,
        @param:JsonProperty("edrpou") public val edrpou: String?,
    )

/**
 * Payment scheme of a statement entry.
 *
 * @property value Wire value.
 */
public enum class PaymentScheme(
    @get:JsonValue public val value: String,
) {
    /** Full payment. */
    FULL("full"),

    /** "Pay later" in 30 days (BNPL). */
    BNPL_LATER_30("bnpl_later_30"),

    /** Payment in 4 parts (BNPL). */
    BNPL_PARTS_4("bnpl_parts_4"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): PaymentScheme = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * One payment in the merchant statement (`GET /api/merchant/statement`). Statements list only
 * payments that reached `hold`, `processing`, `success` or `failure` (no `created`/`expired`
 * invoices).
 *
 * @property invoiceId Invoice of the payment.
 * @property statusRaw Status exactly as sent: `hold`, `processing`, `success` or `failure`.
 * @property maskedPan Masked card number.
 * @property date Date and time of the operation.
 * @property paymentSchemeRaw Payment scheme exactly as sent.
 * @property amount Payment amount in minor units (kopiyky).
 * @property profitAmount Amount the merchant receives after settlement, in minor units (kopiyky).
 * @property agentFee Agent fee in minor units (kopiyky), when the invoice set `agentFeePercent`.
 * @property ccy ISO 4217 numeric currency.
 * @property approvalCode Authorization code.
 * @property rrn Transaction id in the card network.
 * @property reference Your `merchantPaymInfo.reference`.
 * @property shortQrId Short id of the QR cash register, for QR payments.
 * @property destination Payment purpose.
 * @property cancelList Refunds of this payment.
 * @property splitReceiverList Split payments: how the amount was split.
 * @property metadata Your `merchantPaymInfo.metadata`. Values are strings, numbers, booleans,
 *   lists or maps, as monobank returns them.
 */
@ConsistentCopyVisibility
public data class StatementItem
    @JsonCreator
    internal constructor(
        @param:JsonProperty("invoiceId") public val invoiceId: String,
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
        @param:JsonProperty("date") public val date: OffsetDateTime?,
        @param:JsonProperty("paymentScheme") public val paymentSchemeRaw: String?,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("profitAmount") public val profitAmount: Long?,
        @param:JsonProperty("agentFee") public val agentFee: Long?,
        @param:JsonProperty("ccy") public val ccy: Int,
        @param:JsonProperty("approvalCode") public val approvalCode: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("reference") public val reference: String?,
        @param:JsonProperty("shortQrId") public val shortQrId: String?,
        @param:JsonProperty("destination") public val destination: String?,
        @param:JsonProperty("cancelList") public val cancelList: List<StatementCancel>?,
        @param:JsonProperty("splitReceiverList") public val splitReceiverList: List<StatementSplitReceiver>?,
        @param:JsonProperty("metadata") public val metadata: Map<String, Any?>?,
    ) {
        /** Typed status ([InvoiceStatus.UNKNOWN] for a value this SDK version does not know). */
        public val status: InvoiceStatus
            get() = InvoiceStatus.fromValue(statusRaw)

        /** Typed payment scheme, or `null` when not sent. */
        public val paymentScheme: PaymentScheme?
            get() = paymentSchemeRaw?.let { PaymentScheme.fromValue(it) }
    }

/**
 * A refund in the merchant statement.
 *
 * @property amount Refunded amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property date Date and time of the refund.
 * @property approvalCode Authorization code.
 * @property rrn Transaction id in the card network.
 * @property maskedPan Masked card number that received the refund.
 */
@ConsistentCopyVisibility
public data class StatementCancel
    @JsonCreator
    internal constructor(
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("ccy") public val ccy: Int,
        @param:JsonProperty("date") public val date: OffsetDateTime?,
        @param:JsonProperty("approvalCode") public val approvalCode: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
    )

/**
 * One receiver of a split payment in the merchant statement.
 *
 * @property splitReceiverId Receiver id.
 * @property amount Amount split to this receiver, in minor units (kopiyky).
 * @property profit Amount the receiver gets after settlement, in minor units (kopiyky).
 * @property agentFee Agent fee on this part, in minor units (kopiyky).
 */
@ConsistentCopyVisibility
public data class StatementSplitReceiver
    @JsonCreator
    internal constructor(
        @param:JsonProperty("splitReceiverId") public val splitReceiverId: String,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("profit") public val profit: Long?,
        @param:JsonProperty("agentFee") public val agentFee: Long?,
    )

/** Wire answer `{"list": [...]}` of `merchant/statement`. */
internal class StatementWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<StatementItem>?,
    )

/**
 * A submerchant terminal (`GET /api/merchant/submerchant/list`). Pass [code] as
 * `CreateInvoiceRequest.code` or as the statement's `code`.
 *
 * @property code Terminal code.
 * @property edrpou Owner's EDRPOU.
 * @property iban Owner's IBAN.
 * @property owner Owner's name.
 */
@ConsistentCopyVisibility
public data class Submerchant
    @JsonCreator
    internal constructor(
        @param:JsonProperty("code") public val code: String,
        @param:JsonProperty("edrpou") public val edrpou: String?,
        @param:JsonProperty("iban") public val iban: String?,
        @param:JsonProperty("owner") public val owner: String?,
    )

/** Wire answer `{"list": [...]}` of `merchant/submerchant/list`. */
internal class SubmerchantWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<Submerchant>?,
    )
