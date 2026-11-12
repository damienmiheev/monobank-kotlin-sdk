package io.github.damienmiheev.monobank.acquiring.pos

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import java.time.OffsetDateTime

/**
 * Language of monobank's error texts, sent as the required `Accept-Language` header.
 *
 * @property value Header value.
 */
public enum class ResponseLanguage(
    public val value: String,
) {
    /** Ukrainian. */
    UK("uk"),

    /** English. */
    EN("en"),
}

/**
 * Request of `POST /api/merchant/pos-transaction-cancel`: refund a card-present (POS terminal)
 * transaction.
 *
 * @property rrn RRN of the original POS transaction.
 * @property amount Refund amount in minor units (kopiyky); at most what is left after earlier refunds.
 */
@ConsistentCopyVisibility
public data class PosRefundRequest internal constructor(
    @get:JsonProperty("rrn") public val rrn: String,
    @get:JsonProperty("amount") public val amount: Long,
) {
    public companion object {
        /**
         * Creates the request.
         *
         * @param amount Refund amount in minor units (kopiyky).
         */
        @JvmStatic
        public fun of(
            rrn: String,
            amount: Long,
        ): PosRefundRequest {
            require(rrn.isNotBlank()) { "rrn must not be blank" }
            require(amount > 0) { "amount must be positive (minor units)" }
            return PosRefundRequest(rrn, amount)
        }
    }
}

/**
 * Answer of `POST /api/merchant/pos-transaction-cancel`.
 *
 * @property status Status of starting the refund. monobank does not list the values; its example
 *   is `ok`, meaning the refund was accepted for processing (not that it is complete).
 * @property tranId Id of the refund transaction; may be absent.
 */
@ConsistentCopyVisibility
public data class PosRefundResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val status: String,
        @param:JsonProperty("tranId") public val tranId: String?,
    )

/**
 * Status of a POS refund.
 *
 * @property value Wire value.
 */
public enum class PosRefundStatus(
    @get:JsonValue public val value: String,
) {
    /** Created. */
    NEW("new"),

    /** Being processed. */
    PROCESSING("processing"),

    /** Refunded. */
    SUCCESS("success"),

    /** Rejected. */
    REJECTED("rejected"),

    /** Reversed. */
    REVERSED("reversed"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): PosRefundStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Answer of `GET /api/merchant/pos-transaction-cancel/status`.
 *
 * @property amount Refund amount in minor units (kopiyky).
 * @property cancelRef Cancel reference the refund was found by.
 * @property createTime When the refund was created.
 * @property modifyTime Last change.
 * @property originalTranId Id of the refunded transaction.
 * @property rrn RRN of the refunded transaction.
 * @property statusRaw Status exactly as sent.
 */
@ConsistentCopyVisibility
public data class PosRefundStatusResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("amount") public val amount: Long?,
        @param:JsonProperty("cancelRef") public val cancelRef: String?,
        @param:JsonProperty("createTime") public val createTime: OffsetDateTime?,
        @param:JsonProperty("modifyTime") public val modifyTime: OffsetDateTime?,
        @param:JsonProperty("originalTranId") public val originalTranId: String?,
        @param:JsonProperty("rrn") public val rrn: String?,
        @param:JsonProperty("status") public val statusRaw: String?,
    ) {
        /** Typed status ([PosRefundStatus.UNKNOWN] when missing or unknown). */
        public val status: PosRefundStatus
            get() = PosRefundStatus.fromValue(statusRaw)
    }

/**
 * Refunds of card-present (POS terminal) transactions.
 */
public class PosRefundApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Starts a refund of a POS transaction by its RRN.
     *
     * `POST /api/merchant/pos-transaction-cancel` with the required `Accept-Language` header
     * ([language] only changes the language of error texts).
     *
     * A refund: never retried after an unknown outcome, and **you must not send it again** after
     * one either (a timeout, a connection reset or a 5xx). monobank offers no lookup by `rrn`, and
     * [status] needs a `cancelRef` that this call does not return. Reconcile through the merchant
     * statement, the `pos_refund` webhook or monobank support before refunding again; otherwise
     * the customer may be refunded twice.
     */
    @JvmOverloads
    public fun create(
        request: PosRefundRequest,
        language: ResponseLanguage = ResponseLanguage.UK,
    ): PosRefundResponse =
        http.post(
            "/api/merchant/pos-transaction-cancel",
            request,
            PosRefundResponse::class.java,
            idempotent = false,
            headers = mapOf("Accept-Language" to language.value),
        )

    /**
     * Status of a POS refund, for when the `pos_refund` webhook did not arrive. Only this
     * merchant's recent refunds are searched (monobank does not say how far back).
     *
     * `GET /api/merchant/pos-transaction-cancel/status`. Safe to retry.
     *
     * monobank does not document where `cancelRef` comes from: it is neither in the refund request
     * nor in [PosRefundResponse]. A 404 therefore does **not** prove that no refund was made.
     *
     * @param cancelRef Unique id of the refund request, as monobank names it.
     */
    public fun status(cancelRef: String): PosRefundStatusResponse {
        require(cancelRef.isNotBlank()) { "cancelRef must not be blank" }
        return http.get(
            "/api/merchant/pos-transaction-cancel/status",
            PosRefundStatusResponse::class.java,
            query = listOf("cancelRef" to cancelRef),
        )
    }
}
