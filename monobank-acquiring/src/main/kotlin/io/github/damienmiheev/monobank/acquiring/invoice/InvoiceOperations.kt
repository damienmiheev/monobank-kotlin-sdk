package io.github.damienmiheev.monobank.acquiring.invoice

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import java.time.OffsetDateTime

/**
 * Request of `POST /api/merchant/invoice/cancel`: refund a successful payment (fully or partly),
 * or release a hold.
 *
 * @property invoiceId Invoice to refund.
 * @property extRef Your reference of this refund. Use a unique value (e.g. your refund transaction
 *   id) and follow the refund in `InvoiceStatusResponse.cancelFor(extRef)`.
 * @property amount Refund amount in minor units (kopiyky) for a partial refund; `null` refunds the
 *   whole remaining amount.
 * @property items Products being refunded, for the return receipt. Required when fiscal receipts
 *   are enabled.
 */
@ConsistentCopyVisibility
public data class CancelInvoiceRequest internal constructor(
    @get:JsonProperty("invoiceId") public val invoiceId: String,
    @get:JsonProperty("extRef") public val extRef: String?,
    @get:JsonProperty("amount") public val amount: Long?,
    @get:JsonProperty("items") public val items: List<FiscalizationItem>?,
) {
    /** Builder for [CancelInvoiceRequest]. */
    public class Builder internal constructor(
        private val invoiceId: String,
    ) {
        private var extRef: String? = null
        private var amount: Long? = null
        private var items: List<FiscalizationItem>? = null

        /** Your reference of this refund. */
        public fun extRef(extRef: String?): Builder = apply { this.extRef = extRef }

        /** Partial refund amount in minor units (kopiyky); `null` for a full refund. */
        public fun amount(amount: Long): Builder = apply { this.amount = amount }

        /** Refunded products, for the return receipt. */
        public fun items(items: List<FiscalizationItem>?): Builder = apply { this.items = items }

        /** Builds the request. */
        public fun build(): CancelInvoiceRequest {
            amount?.let { require(it > 0) { "amount must be positive (minor units)" } }
            return CancelInvoiceRequest(invoiceId, extRef, amount, items)
        }
    }

    public companion object {
        /** Starts a refund of [invoiceId]. */
        @JvmStatic
        public fun builder(invoiceId: String): Builder {
            require(invoiceId.isNotBlank()) { "invoiceId must not be blank" }
            return Builder(invoiceId)
        }
    }
}

/**
 * Answer of `POST /api/merchant/invoice/cancel`. A refund is often [OperationStatus.PROCESSING]:
 * the final result comes in a webhook or `invoices.status(...).cancelList`.
 *
 * @property statusRaw Refund status exactly as sent.
 * @property createdDate When the refund was requested.
 * @property modifiedDate Last change of the refund.
 */
@ConsistentCopyVisibility
public data class CancelInvoiceResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("createdDate") public val createdDate: OffsetDateTime?,
        @param:JsonProperty("modifiedDate") public val modifiedDate: OffsetDateTime?,
    ) {
        /** Typed refund status; [OperationStatus.UNKNOWN] for a value this SDK version does not know. */
        public val status: OperationStatus
            get() = OperationStatus.fromValue(statusRaw)
    }

/**
 * Request of `POST /api/merchant/invoice/finalize`: capture a hold.
 *
 * @property invoiceId Held invoice.
 * @property amount Amount to capture, in minor units (kopiyky); at most the held amount. Required.
 * @property items Products, for the fiscal receipt, when [amount] differs from the held amount.
 */
@ConsistentCopyVisibility
public data class FinalizeInvoiceRequest internal constructor(
    @get:JsonProperty("invoiceId") public val invoiceId: String,
    @get:JsonProperty("amount") public val amount: Long,
    @get:JsonProperty("items") public val items: List<FiscalizationItem>?,
) {
    /** Builder for [FinalizeInvoiceRequest]. */
    public class Builder internal constructor(
        private val invoiceId: String,
        private val amount: Long,
    ) {
        private var items: List<FiscalizationItem>? = null

        /** Products, for the fiscal receipt. */
        public fun items(items: List<FiscalizationItem>?): Builder = apply { this.items = items }

        /** Builds the request. */
        public fun build(): FinalizeInvoiceRequest = FinalizeInvoiceRequest(invoiceId, amount, items)
    }

    public companion object {
        /**
         * Starts a finalize request.
         *
         * @param amount Amount to capture in minor units (kopiyky).
         */
        @JvmStatic
        public fun builder(
            invoiceId: String,
            amount: Long,
        ): Builder {
            require(invoiceId.isNotBlank()) { "invoiceId must not be blank" }
            require(amount > 0) { "amount must be positive (minor units)" }
            return Builder(invoiceId, amount)
        }

        /** Finalizes [amount] (minor units) of [invoiceId] without receipt items. */
        @JvmStatic
        public fun of(
            invoiceId: String,
            amount: Long,
        ): FinalizeInvoiceRequest = builder(invoiceId, amount).build()
    }
}

/**
 * Answer of `POST /api/merchant/invoice/finalize`.
 *
 * @property statusRaw Status exactly as sent; monobank documents only `success` ("request accepted").
 */
@ConsistentCopyVisibility
public data class FinalizeInvoiceResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val statusRaw: String,
    ) {
        /** Typed status; [OperationStatus.SUCCESS] means the finalize request was accepted. */
        public val status: OperationStatus
            get() = OperationStatus.fromValue(statusRaw)
    }

/** Body `{"invoiceId": ...}` of `invoice/remove`. */
internal class InvoiceIdRequest(
    @get:JsonProperty("invoiceId") val invoiceId: String,
)

/** Wire answer `{"file": "<base64 PDF>"}` of `invoice/receipt`. */
internal class ReceiptWire
    @JsonCreator
    constructor(
        @param:JsonProperty("file") val file: String?,
    )

/**
 * Type of a fiscal check.
 *
 * @property value Wire value.
 */
public enum class FiscalCheckType(
    @get:JsonValue public val value: String,
) {
    /** Sale receipt. */
    SALE("sale"),

    /** Return (refund) receipt. */
    RETURN("return"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): FiscalCheckType = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Status of a fiscal check.
 *
 * @property value Wire value.
 */
public enum class FiscalCheckStatus(
    @get:JsonValue public val value: String,
) {
    /** Created. */
    NEW("new"),

    /** Being fiscalized. */
    PROCESS("process"),

    /** Fiscalized. */
    DONE("done"),

    /** Fiscalization failed. */
    FAILED("failed"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): FiscalCheckStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Service that fiscalized a check.
 *
 * @property value Wire value.
 */
public enum class FiscalizationSource(
    @get:JsonValue public val value: String,
) {
    /** Checkbox. */
    CHECKBOX("checkbox"),

    /** monopay's own software cash register. */
    MONOPAY("monopay"),

    /** Vchasno.Kasa. */
    VCHASNOKASA("vchasnokasa"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): FiscalizationSource = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * A fiscal check (receipt registered with the tax service) of an invoice.
 *
 * @property id Check id.
 * @property typeRaw Check type exactly as sent.
 * @property statusRaw Check status exactly as sent.
 * @property statusDescription Status details.
 * @property taxUrl Link to the check on the tax service's site.
 * @property file Base64 of the check's PDF; decode it with [decodeFile].
 * @property fiscalizationSourceRaw Fiscalization service exactly as sent.
 */
@ConsistentCopyVisibility
public data class FiscalCheck
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("type") public val typeRaw: String?,
        @param:JsonProperty("status") public val statusRaw: String?,
        @param:JsonProperty("statusDescription") public val statusDescription: String?,
        @param:JsonProperty("taxUrl") public val taxUrl: String?,
        @param:JsonProperty("file") public val file: String?,
        @param:JsonProperty("fiscalizationSource") public val fiscalizationSourceRaw: String?,
    ) {
        /** Typed check type. */
        public val type: FiscalCheckType
            get() = FiscalCheckType.fromValue(typeRaw)

        /** Typed check status. */
        public val status: FiscalCheckStatus
            get() = FiscalCheckStatus.fromValue(statusRaw)

        /** Typed fiscalization service. */
        public val fiscalizationSource: FiscalizationSource
            get() = FiscalizationSource.fromValue(fiscalizationSourceRaw)

        /**
         * The check's PDF bytes, or `null` when [file] is absent.
         *
         * @throws io.github.damienmiheev.monobank.core.MonobankSerializationException when [file] is not base64.
         */
        public fun decodeFile(): ByteArray? = file?.let { Base64Pdf.decode(it) }

        override fun toString(): String =
            "FiscalCheck(id=$id, type=$typeRaw, status=$statusRaw, statusDescription=$statusDescription, " +
                "taxUrl=$taxUrl, file=${file?.let { "<${it.length} base64 chars>" }}, " +
                "fiscalizationSource=$fiscalizationSourceRaw)"
    }

/** Wire answer `{"checks": [...]}` of `invoice/fiscal-checks`. */
internal class FiscalChecksWire
    @JsonCreator
    constructor(
        @param:JsonProperty("checks") val checks: List<FiscalCheck>?,
    )
