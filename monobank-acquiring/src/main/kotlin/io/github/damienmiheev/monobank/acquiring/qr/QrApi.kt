package io.github.damienmiheev.monobank.acquiring.qr

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp

/**
 * QR cash registers. To put an amount on a QR, create an invoice with `CreateInvoiceRequest.qrId`.
 */
public class QrApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Details of an activated QR cash register, including the invoice of the amount set on it.
     *
     * `GET /api/merchant/qr/details`. Safe to retry.
     */
    public fun details(qrId: String): QrDetails {
        require(qrId.isNotBlank()) { "qrId must not be blank" }
        return http.get("/api/merchant/qr/details", QrDetails::class.java, query = listOf("qrId" to qrId))
    }

    /**
     * Removes the amount set on a QR cash register. Fails with `ORDER_IN_PROGRESS` when the
     * amount is already being paid.
     *
     * `POST /api/merchant/qr/reset-amount`. Changes state, so not retried after an unknown outcome.
     */
    public fun resetAmount(qrId: String) {
        require(qrId.isNotBlank()) { "qrId must not be blank" }
        http.postNoContent("/api/merchant/qr/reset-amount", QrIdRequest(qrId), idempotent = false)
    }

    /**
     * All QR cash registers of the merchant.
     *
     * `GET /api/merchant/qr/list`. Safe to retry.
     */
    public fun list(): List<QrCashRegister> = http.get("/api/merchant/qr/list", QrListWire::class.java).list.orEmpty()
}

/**
 * Answer of `GET /api/merchant/qr/details`.
 *
 * @property shortQrId Short id of the QR cash register.
 * @property invoiceId Invoice to pay; only present when an amount is set.
 * @property amount Amount set on the QR, in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 */
@ConsistentCopyVisibility
public data class QrDetails
    @JsonCreator
    internal constructor(
        @param:JsonProperty("shortQrId") public val shortQrId: String,
        @param:JsonProperty("invoiceId") public val invoiceId: String?,
        @param:JsonProperty("amount") public val amount: Long?,
        @param:JsonProperty("ccy") public val ccy: Int?,
    )

/**
 * Who sets the amount of a QR cash register.
 *
 * @property value Wire value.
 */
public enum class QrAmountType(
    @get:JsonValue public val value: String,
) {
    /** The merchant sets the amount (via an invoice with `qrId`). */
    MERCHANT("merchant"),

    /** The customer enters the amount. */
    CLIENT("client"),

    /** The amount is fixed. */
    FIX("fix"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): QrAmountType = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * A QR cash register (`GET /api/merchant/qr/list`).
 *
 * @property shortQrId Short id.
 * @property qrId Id to use as `CreateInvoiceRequest.qrId` and in [QrApi.details].
 * @property amountTypeRaw Amount type exactly as sent.
 * @property pageUrl Payment page of the QR.
 */
@ConsistentCopyVisibility
public data class QrCashRegister
    @JsonCreator
    internal constructor(
        @param:JsonProperty("shortQrId") public val shortQrId: String?,
        @param:JsonProperty("qrId") public val qrId: String,
        @param:JsonProperty("amountType") public val amountTypeRaw: String?,
        @param:JsonProperty("pageUrl") public val pageUrl: String?,
    ) {
        /** Typed amount type. */
        public val amountType: QrAmountType
            get() = QrAmountType.fromValue(amountTypeRaw)
    }

internal class QrIdRequest(
    @get:JsonProperty("qrId") val qrId: String,
)

internal class QrListWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<QrCashRegister>?,
    )
