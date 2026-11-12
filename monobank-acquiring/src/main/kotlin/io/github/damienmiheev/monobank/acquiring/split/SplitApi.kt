package io.github.damienmiheev.monobank.acquiring.split

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp

/**
 * Split payments: receivers (sub-merchants) that get part of a payment. Assign items to a
 * receiver with `BasketItem.splitReceiverId`.
 */
public class SplitApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * All split receivers.
     *
     * `GET /api/merchant/split-receiver/list`. Safe to retry.
     */
    public fun receivers(): List<SplitReceiver> =
        http.get("/api/merchant/split-receiver/list", SplitReceiverListWire::class.java).list.orEmpty()

    /**
     * Details and status of one split receiver.
     *
     * `GET /api/merchant/split-receiver/get-by-id`. Safe to retry.
     */
    public fun receiver(splitReceiverId: String): SplitReceiverDetails {
        require(splitReceiverId.isNotBlank()) { "splitReceiverId must not be blank" }
        return http.get(
            "/api/merchant/split-receiver/get-by-id",
            SplitReceiverDetails::class.java,
            query = listOf("splitReceiverId" to splitReceiverId),
        )
    }
}

/**
 * A split receiver in the list.
 *
 * @property id Receiver id, used as `splitReceiverId`.
 * @property edrpou Seller's EDRPOU.
 * @property name Seller's name.
 */
@ConsistentCopyVisibility
public data class SplitReceiver
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("edrpou") public val edrpou: String?,
        @param:JsonProperty("name") public val name: String?,
    )

/**
 * Status of a split receiver.
 *
 * @property value Wire value.
 */
public enum class SplitReceiverStatus(
    @get:JsonValue public val value: String,
) {
    /** Can receive payments. */
    ACTIVE("active"),

    /** Being set up. */
    PROCESSING("processing"),

    /** Not active. */
    NOT_ACTIVE("not_active"),

    /** Blocked. */
    BLOCKED("blocked"),

    /** Restricted. */
    RESTRICTED("restricted"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** Parses a wire value; an unknown value or `null` gives [UNKNOWN]. */
        @JvmStatic
        public fun fromValue(value: String?): SplitReceiverStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * Answer of `GET /api/merchant/split-receiver/get-by-id`.
 *
 * @property id Receiver id.
 * @property edrpou Seller's tax number.
 * @property name Seller's name.
 * @property statusRaw Status exactly as sent.
 * @property iban Seller's IBAN.
 */
@ConsistentCopyVisibility
public data class SplitReceiverDetails
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("edrpou") public val edrpou: String?,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("status") public val statusRaw: String,
        @param:JsonProperty("iban") public val iban: String?,
    ) {
        /** Typed status. */
        public val status: SplitReceiverStatus
            get() = SplitReceiverStatus.fromValue(statusRaw)
    }

internal class SplitReceiverListWire
    @JsonCreator
    constructor(
        @param:JsonProperty("list") val list: List<SplitReceiver>?,
    )
