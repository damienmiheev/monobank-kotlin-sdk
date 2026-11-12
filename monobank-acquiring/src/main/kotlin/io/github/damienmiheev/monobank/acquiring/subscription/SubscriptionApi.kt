package io.github.damienmiheev.monobank.acquiring.subscription

import io.github.damienmiheev.monobank.acquiring.common.Page
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Query of `GET /api/merchant/subscription/list`.
 *
 * @property dateFrom Start of the period. Required. Sent as RFC 3339 with whole seconds.
 * @property dateTo End of the period.
 * @property status Only subscriptions in this status.
 * @property page Page number, starting at 1 (monobank's default). monobank's docs call this parameter an
 *   "offset" but default it to 1 and answer with `pagination.currentPage`, so the SDK treats it as
 *   a 1-based page number.
 * @property limit Page size (monobank's default is 20).
 */
public class SubscriptionListQuery private constructor(
    public val dateFrom: OffsetDateTime,
    public val dateTo: OffsetDateTime?,
    public val status: SubscriptionStatus?,
    public val page: Int?,
    public val limit: Int?,
) {
    internal fun toQuery(): List<Pair<String, String?>> =
        listOf(
            "status" to status?.value,
            "limit" to limit?.toString(),
            "page" to page?.toString(),
            "dateFrom" to rfc3339(dateFrom),
            "dateTo" to dateTo?.let(::rfc3339),
        )

    override fun toString(): String =
        "SubscriptionListQuery(dateFrom=$dateFrom, dateTo=$dateTo, status=$status, page=$page, limit=$limit)"

    /** Builder for [SubscriptionListQuery]. */
    public class Builder internal constructor(
        private val dateFrom: OffsetDateTime,
    ) {
        private var dateTo: OffsetDateTime? = null
        private var status: SubscriptionStatus? = null
        private var page: Int? = null
        private var limit: Int? = null

        /** End of the period. */
        public fun dateTo(dateTo: OffsetDateTime?): Builder = apply { this.dateTo = dateTo }

        /** Only subscriptions in [status]. */
        public fun status(status: SubscriptionStatus?): Builder =
            apply {
                require(status != SubscriptionStatus.UNKNOWN) { "UNKNOWN cannot be used as a filter" }
                this.status = status
            }

        /** Page number, starting at 1. */
        public fun page(page: Int?): Builder = apply { this.page = page?.also { requirePositive(it, "page") } }

        /** Page size. */
        public fun limit(limit: Int?): Builder = apply { this.limit = limit?.also { requirePositive(it, "limit") } }

        /** Builds the query. */
        public fun build(): SubscriptionListQuery {
            requireOrder(dateFrom, dateTo)
            return SubscriptionListQuery(dateFrom, dateTo, status, page, limit)
        }
    }

    public companion object {
        /** Starts a query for subscriptions since [dateFrom]. */
        @JvmStatic
        public fun builder(dateFrom: OffsetDateTime): Builder = Builder(dateFrom)
    }
}

/**
 * Query of `GET /api/merchant/subscription/payments`.
 *
 * @property subscriptionId Subscription id.
 * @property dateFrom Start of the period. Required. Sent as RFC 3339 with whole seconds.
 * @property dateTo End of the period.
 * @property page Page number, starting at 1 (monobank's default). monobank's docs call this parameter an
 *   "offset" but default it to 1 and answer with `pagination.currentPage`, so the SDK treats it as
 *   a 1-based page number.
 * @property limit Page size (monobank's default is 20).
 */
public class SubscriptionPaymentsQuery private constructor(
    public val subscriptionId: String,
    public val dateFrom: OffsetDateTime,
    public val dateTo: OffsetDateTime?,
    public val page: Int?,
    public val limit: Int?,
) {
    internal fun toQuery(): List<Pair<String, String?>> =
        listOf(
            "subscriptionId" to subscriptionId,
            "limit" to limit?.toString(),
            "page" to page?.toString(),
            "dateFrom" to rfc3339(dateFrom),
            "dateTo" to dateTo?.let(::rfc3339),
        )

    override fun toString(): String =
        "SubscriptionPaymentsQuery(subscriptionId=$subscriptionId, dateFrom=$dateFrom, dateTo=$dateTo, " +
            "page=$page, limit=$limit)"

    /** Builder for [SubscriptionPaymentsQuery]. */
    public class Builder internal constructor(
        private val subscriptionId: String,
        private val dateFrom: OffsetDateTime,
    ) {
        private var dateTo: OffsetDateTime? = null
        private var page: Int? = null
        private var limit: Int? = null

        /** End of the period. */
        public fun dateTo(dateTo: OffsetDateTime?): Builder = apply { this.dateTo = dateTo }

        /** Page number, starting at 1. */
        public fun page(page: Int?): Builder = apply { this.page = page?.also { requirePositive(it, "page") } }

        /** Page size. */
        public fun limit(limit: Int?): Builder = apply { this.limit = limit?.also { requirePositive(it, "limit") } }

        /** Builds the query. */
        public fun build(): SubscriptionPaymentsQuery {
            requireOrder(dateFrom, dateTo)
            return SubscriptionPaymentsQuery(subscriptionId, dateFrom, dateTo, page, limit)
        }
    }

    public companion object {
        /** Starts a query for the charges of [subscriptionId] since [dateFrom]. */
        @JvmStatic
        public fun builder(
            subscriptionId: String,
            dateFrom: OffsetDateTime,
        ): Builder {
            require(subscriptionId.isNotBlank()) { "subscriptionId must not be blank" }
            return Builder(subscriptionId, dateFrom)
        }
    }
}

/** RFC 3339 with whole seconds, as in monobank's examples (`2024-06-26T18:12:44+03:00`). */
private fun rfc3339(value: OffsetDateTime): String =
    value.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private fun requireOrder(
    dateFrom: OffsetDateTime,
    dateTo: OffsetDateTime?,
) = require(dateTo == null || !dateTo.isBefore(dateFrom)) { "dateTo must not be before dateFrom" }

private fun requirePositive(
    value: Int,
    name: String,
) = require(value > 0) { "$name must be positive" }

/**
 * Subscriptions (recurring payments managed by monobank): the customer pays the first charge on
 * the payment page, then monobank charges the saved card every interval.
 */
public class SubscriptionApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Creates a subscription and returns the page for the first payment.
     *
     * `POST /api/merchant/subscription/create`. Not retried after an unknown outcome.
     */
    public fun create(request: CreateSubscriptionRequest): CreateSubscriptionResponse =
        http.post(PATH + "create", request, CreateSubscriptionResponse::class.java, idempotent = false)

    /**
     * Status, next charge and saved card of a subscription.
     *
     * `GET /api/merchant/subscription/status`. Safe to retry.
     */
    public fun status(subscriptionId: String): SubscriptionStatusResponse {
        require(subscriptionId.isNotBlank()) { "subscriptionId must not be blank" }
        return http.get(
            PATH + "status",
            SubscriptionStatusResponse::class.java,
            query = listOf("subscriptionId" to subscriptionId),
        )
    }

    /**
     * One page of a subscription's charges.
     *
     * `GET /api/merchant/subscription/payments`. Safe to retry.
     */
    public fun payments(query: SubscriptionPaymentsQuery): Page<SubscriptionPayment> {
        val wire = http.get(PATH + "payments", SubscriptionPaymentsWire::class.java, query = query.toQuery())
        return Page(wire.payments.orEmpty(), wire.pagination)
    }

    /**
     * One page of the merchant's subscriptions.
     *
     * `GET /api/merchant/subscription/list`. Safe to retry.
     */
    public fun list(query: SubscriptionListQuery): Page<SubscriptionListItem> {
        val wire = http.get(PATH + "list", SubscriptionListWire::class.java, query = query.toQuery())
        return Page(wire.list.orEmpty(), wire.pagination)
    }

    /**
     * Invalidates a subscription that has no payments yet. Fails with errCode `1001` when it
     * already has one; then use [edit] with `EditSubscriptionRequest.cancel`.
     *
     * `POST /api/merchant/subscription/remove`. Not retried after an unknown outcome.
     */
    public fun remove(subscriptionId: String) {
        require(subscriptionId.isNotBlank()) { "subscriptionId must not be blank" }
        http.postNoContent(PATH + "remove", SubscriptionIdRequest(subscriptionId), idempotent = false)
    }

    /**
     * Manages a subscription: cancels it, optionally refunding an amount.
     *
     * `POST /api/merchant/subscription/edit`. May refund money, so it is never retried after an
     * unknown outcome; check [status] first.
     */
    public fun edit(request: EditSubscriptionRequest) {
        http.postNoContent(PATH + "edit", request, idempotent = false)
    }

    private companion object {
        const val PATH = "/api/merchant/subscription/"
    }
}
