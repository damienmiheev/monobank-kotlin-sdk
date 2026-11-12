package io.github.damienmiheev.monobank.acquiring.terminal

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Tap-to-Phone ("Термінал by mono") terminals: payments are started in the monobank terminal app
 * by deep link; this API lists terminals and reads payment results.
 */
public class TerminalApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * The merchant's Tap-to-Phone terminals; `terminal` goes into the payment deep link.
     *
     * `GET /api/merchant/t2p/terminal/list`. Safe to retry.
     */
    public fun list(): List<T2pTerminal> =
        http.get("/api/merchant/t2p/terminal/list", T2pTerminalListWire::class.java).list.orEmpty()

    /**
     * Status and details of a Tap-to-Phone payment by the id you passed in the deep link (`id`).
     * The body equals the `callbackSuccess`/`callbackFail` callback. Only the last 90 days are
     * searched; poll at most once every 5 seconds per payment.
     *
     * `GET /api/merchant/t2p/terminal/payment/external/status`. Safe to retry.
     */
    public fun paymentStatus(externalPaymentId: String): T2pPaymentStatusResponse {
        require(externalPaymentId.isNotBlank()) { "externalPaymentId must not be blank" }
        return http.get(
            "/api/merchant/t2p/terminal/payment/external/status",
            T2pPaymentStatusResponse::class.java,
            query = listOf("externalPaymentId" to externalPaymentId),
        )
    }
}

/**
 * A Tap-to-Phone terminal.
 *
 * @property code Terminal code.
 * @property name Terminal name.
 * @property terminal Terminal id (`MT…`), used in deep links.
 */
@ConsistentCopyVisibility
public data class T2pTerminal
    @JsonCreator
    internal constructor(
        @param:JsonProperty("code") public val code: String?,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("terminal") public val terminal: String,
    )

/**
 * Kind of a Tap-to-Phone operation.
 *
 * @property value Wire value.
 */
public enum class T2pPaymentType(
    @get:JsonValue public val value: String,
) {
    /** Purchase. */
    PURCHASE("purchase"),

    /** Refund. */
    REFUND("refund"),

    /** Reversal. */
    REVERSE("reverse"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): T2pPaymentType = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Status of a Tap-to-Phone payment.
 *
 * @property value Wire value.
 */
public enum class T2pPaymentStatus(
    @get:JsonValue public val value: String,
) {
    /** Not finished yet. */
    PENDING("pending"),

    /** Paid. */
    SUCCESS("success"),

    /** Declined. */
    REJECTED("rejected"),

    /** The terminal app did not answer. */
    TXP_DOES_NOT_ANSWER("txp_does_not_answer"),

    /** The transaction was not completed. */
    TRANSACTION_NOT_COMPLETED("transaction_not_completed"),

    /** Failed (appears in monobank's callback examples, not in the API schema). */
    FAILED("failed"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): T2pPaymentStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Answer of `GET /api/merchant/t2p/terminal/payment/external/status` (same body as the T2P callbacks).
 *
 * @property bank Acquiring bank.
 * @property terminal Terminal id.
 * @property paymentTypeRaw Operation kind exactly as sent.
 * @property amount Amount in minor units (kopiyky).
 * @property ccy Currency as a letter code; currently always `UAH`.
 * @property statusRaw Status exactly as sent.
 * @property dataTime Creation time as sent, `yyyy-MM-dd HH:mm:ss` in UTC; see [dataTimeUtc].
 * @property internalPaymentId monobank's payment id.
 * @property externalPaymentId Your payment id from the deep link.
 * @property transactionId Bank transaction id; filled after settlement. It can differ from the
 *   app's; match on [approvalCode] and [rrn] instead.
 * @property maskedPan Masked card number.
 * @property cardMask Card network, e.g. `visa`.
 * @property approvalCode Authorization code.
 * @property rrn RRN.
 * @property responseCode Processing response code (`1` = success).
 * @property errorMessage Error text for rejected or incomplete payments.
 * @property countryCard Card issuer country, ISO numeric.
 */
@ConsistentCopyVisibility
public data class T2pPaymentStatusResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("bank") public val bank: String?,
        @param:JsonProperty("terminal") public val terminal: String?,
        @param:JsonProperty("paymentType") public val paymentTypeRaw: String?,
        @param:JsonProperty("amount") public val amount: Long?,
        @param:JsonProperty("ccy") public val ccy: String?,
        @param:JsonProperty("status") public val statusRaw: String?,
        @param:JsonProperty("dataTime") public val dataTime: String?,
        @param:JsonProperty("internalPaymentId") public val internalPaymentId: String?,
        @param:JsonProperty("externalPaymentId") public val externalPaymentId: String?,
        @param:JsonProperty("transactionId") public val transactionId: String?,
        @param:JsonProperty("maskedPan") public val maskedPan: String?,
        @param:JsonProperty("cardMask") public val cardMask: String?,
        @param:JsonProperty("approvalCode") public val approvalCode: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("responseCode") public val responseCode: String?,
        @param:JsonProperty("errorMessage") public val errorMessage: String?,
        @param:JsonProperty("countryCard") public val countryCard: String?,
    ) {
        /** Typed operation kind. */
        public val paymentType: T2pPaymentType
            get() = T2pPaymentType.fromValue(paymentTypeRaw)

        /** Typed status. */
        public val status: T2pPaymentStatus
            get() = T2pPaymentStatus.fromValue(statusRaw)

        /** [dataTime] as an instant, or `null` when it is missing or not in the documented format. */
        public fun dataTimeUtc(): Instant? =
            dataTime?.let {
                try {
                    LocalDateTime.parse(it.trim(), DATA_TIME_FORMAT).toInstant(ZoneOffset.UTC)
                } catch (
                    @Suppress("SwallowedException") e: DateTimeParseException,
                ) {
                    null
                }
            }

        private companion object {
            val DATA_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        }
    }

internal class T2pTerminalListWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<T2pTerminal>?,
    )
