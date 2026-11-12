package io.github.damienmiheev.monobank.personal.statement

import io.github.damienmiheev.monobank.personal.internal.PersonalHttp
import java.time.Instant

/**
 * Account and jar statements. Needs a token.
 *
 * monobank limits statements to **one call per 60 seconds** (an earlier call is answered with
 * HTTP 429), to **31 days + 1 hour** (2,682,000 seconds) per call, and to **500 transactions**
 * per call. [listAll] handles the last two; for the first, configure a
 * [io.github.damienmiheev.monobank.personal.PersonalRateLimiter].
 */
public class StatementApi internal constructor(
    private val http: PersonalHttp,
) {
    /**
     * Transactions of one account or jar between [from] and [to], newest first, at most
     * [PAGE_SIZE] of them (the newest ones). If exactly [PAGE_SIZE] come back there may be more:
     * call again with `to` set to the time of the oldest one, or use [listAll].
     *
     * `GET /personal/statement/{account}/{from}/{to}`, times as Unix seconds (sub-second parts are
     * dropped). At most once per 60 seconds. Safe to retry.
     *
     * @param account Account or jar id from `ClientApi.info`, or [DEFAULT_ACCOUNT].
     * @param from Start of the period.
     * @param to End of the period; `null` means now (as monobank's clock sees it). At most
     *   [MAX_RANGE_SECONDS] after [from].
     * @throws IllegalArgumentException for a blank account, `to` before `from`, or a longer range.
     * @throws IllegalStateException when the client has no token.
     */
    @JvmOverloads
    public fun list(
        account: String,
        from: Instant,
        to: Instant? = null,
    ): List<StatementItem> {
        http.requireToken("statement")
        require(account.isNotBlank()) { "account must not be blank (use \"$DEFAULT_ACCOUNT\" for the default account)" }
        if (to != null) checkRange(from.epochSecond, to.epochSecond)
        return fetch(account, from.epochSecond, to?.epochSecond)
    }

    /**
     * All transactions of one account or jar between [from] and [to], newest first, without
     * duplicates.
     *
     * Splits a range longer than [MAX_RANGE_SECONDS] into windows (newest first) and pages each
     * window backwards: while a call returns [PAGE_SIZE] transactions, it asks again with `to`
     * set to the oldest transaction's time. Transactions seen twice (windows and pages overlap by
     * one second so that nothing is lost) are kept once, by [StatementItem.id].
     *
     * **Slow and blocking:** every call is one request, and monobank allows one statement request
     * per 60 seconds. Without a [io.github.damienmiheev.monobank.personal.PersonalRateLimiter] the
     * second request will usually fail with `MonobankRateLimitException`; with one, a 90-day
     * range takes at least 2 minutes. If more than [PAGE_SIZE] transactions share one second,
     * the API cannot return the rest of that second.
     *
     * @param account Account or jar id from `ClientApi.info`, or [DEFAULT_ACCOUNT].
     * @param from Start of the period.
     * @param to End of the period.
     * @throws IllegalArgumentException for a blank account or `to` before `from`.
     * @throws IllegalStateException when the client has no token.
     */
    public fun listAll(
        account: String,
        from: Instant,
        to: Instant,
    ): List<StatementItem> {
        http.requireToken("statement")
        require(account.isNotBlank()) { "account must not be blank (use \"$DEFAULT_ACCOUNT\" for the default account)" }
        val fromSec = from.epochSecond
        require(to.epochSecond >= fromSec) { "to must not be before from" }
        val byId = LinkedHashMap<String, StatementItem>()
        var windowTo = to.epochSecond
        while (true) {
            val windowFrom = maxOf(fromSec, windowTo - MAX_RANGE_SECONDS)
            pageWindow(account, windowFrom, windowTo, byId)
            if (windowFrom <= fromSec) break
            windowTo = windowFrom
        }
        return byId.values.sortedByDescending { it.time }
    }

    /** Pages one window backwards from [to] while full pages come back. */
    private fun pageWindow(
        account: String,
        from: Long,
        to: Long,
        byId: MutableMap<String, StatementItem>,
    ) {
        var pageTo = to
        while (true) {
            val page = fetch(account, from, pageTo)
            page.forEach { byId.putIfAbsent(it.id, it) }
            if (page.size < PAGE_SIZE) return
            val oldest = page.minOf { it.time.epochSecond }
            // Same second again would return the same page: step past it.
            val next = if (oldest < pageTo) oldest else pageTo - 1
            if (next < from) return
            pageTo = next
        }
    }

    private fun fetch(
        account: String,
        from: Long,
        to: Long?,
    ): List<StatementItem> {
        val path =
            buildString {
                append(PersonalHttp.STATEMENT_PATH)
                append(PersonalHttp.encodeSegment(account))
                append('/').append(from)
                if (to != null) append('/').append(to)
            }
        return http.get(path, Array<StatementItem>::class.java).toList()
    }

    private fun checkRange(
        from: Long,
        to: Long,
    ) {
        require(to >= from) { "to must not be before from" }
        require(to - from <= MAX_RANGE_SECONDS) {
            "statement range must be at most $MAX_RANGE_SECONDS seconds (31 days + 1 hour); " +
                "use listAll for longer ranges"
        }
    }

    public companion object {
        /** Account id that means the client's default account. */
        public const val DEFAULT_ACCOUNT: String = "0"

        /** Longest range of one statement call: 31 days + 1 hour, in seconds. */
        public const val MAX_RANGE_SECONDS: Long = 2_682_000L

        /** Most transactions one statement call returns. */
        public const val PAGE_SIZE: Int = 500
    }
}
