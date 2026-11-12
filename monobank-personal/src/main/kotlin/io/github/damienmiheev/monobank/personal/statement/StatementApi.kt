package io.github.damienmiheev.monobank.personal.statement

import io.github.damienmiheev.monobank.core.http.UrlEncoding
import io.github.damienmiheev.monobank.personal.PersonalRateLimiter
import io.github.damienmiheev.monobank.personal.internal.PersonalHttp
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.function.Consumer

/**
 * Account and jar statements. Needs a token.
 *
 * monobank limits statements to **one call per 60 seconds** (an earlier call fails with
 * `MonobankRateLimitException`), to **31 days + 1 hour** (2,682,000 seconds) per call, and to
 * **500 transactions** per call. [listAll] handles all three.
 */
public class StatementApi internal constructor(
    private val http: PersonalHttp,
) {
    private val logger = LoggerFactory.getLogger(StatementApi::class.java)

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
     * @param to End of the period, at most [MAX_RANGE_SECONDS] after [from]. `null` means now, as
     *   monobank's clock sees it; the range is then not checked here, and monobank answers HTTP 400
     *   (`MonobankBadRequestException`) when [from] is too far in the past.
     * @throws IllegalArgumentException for a blank account, `.` or `..`, `to` before `from`, or a
     *   longer range.
     * @throws IllegalStateException when the client has no token.
     */
    @JvmOverloads
    public fun list(
        account: String,
        from: Instant,
        to: Instant? = null,
    ): List<StatementItem> {
        http.requireToken("statement")
        val segment = accountSegment(account)
        if (to != null) checkRange(from.epochSecond, to.epochSecond)
        return fetch(segment, from.epochSecond, to?.epochSecond)
    }

    /** [listAll] from [from] until now. */
    public fun listAll(
        account: String,
        from: Instant,
    ): List<StatementItem> = listAll(account, from, Instant.now())

    /**
     * All transactions of one account or jar between [from] and [to], newest first, without
     * duplicates. See the overload with a page consumer for how it pages and how long it takes.
     *
     * If a request fails, everything fetched so far is lost; use the overload with a page
     * consumer to keep it.
     */
    public fun listAll(
        account: String,
        from: Instant,
        to: Instant,
    ): List<StatementItem> {
        val all = ArrayList<StatementItem>()
        listAll(account, from, to) { all.addAll(it) }
        return all.sortedByDescending { it.time }
    }

    /**
     * Fetches all transactions of one account or jar between [from] and [to] and hands each page
     * to [onPage] as soon as it arrives, so callers can store progress.
     *
     * How it works:
     * - Splits the range into windows of at most [MAX_RANGE_SECONDS] − 1 seconds, newest first.
     * - Pages each window backwards: while a call returns [PAGE_SIZE] transactions, it asks again
     *   with `to` set to the oldest transaction's time.
     * - Windows and pages overlap by one second so that nothing is lost; [onPage] only receives
     *   transactions it has not received before (by [StatementItem.id]), newest first within a
     *   page. A page can be empty.
     * - If more than [PAGE_SIZE] transactions share one second, the API cannot return the rest of
     *   that second; a warning is logged.
     *
     * **Slow and blocking:** requests are spaced by the client's [PersonalRateLimiter] or, if it has
     * none, by a private 61-second limiter for this call only. The private one cannot see statement
     * calls made before it: if one was made less than 60 s ago, the first request fails with
     * `MonobankRateLimitException` (`retryAfter` = 60 s). A 90-day range takes at least 2 minutes.
     *
     * If a request fails, the exception propagates and the pages already delivered stay with the
     * caller. To resume, call again with `to` set to the time of the oldest transaction received
     * (and skip ids already stored).
     *
     * @param account Account or jar id from `ClientApi.info`, or [DEFAULT_ACCOUNT].
     * @param from Start of the period.
     * @param to End of the period.
     * @param onPage Receives the new transactions of every response, in the order fetched.
     * @throws IllegalArgumentException for a blank account, `.` or `..`, or `to` before `from`.
     * @throws IllegalStateException when the client has no token.
     */
    public fun listAll(
        account: String,
        from: Instant,
        to: Instant,
        onPage: Consumer<List<StatementItem>>,
    ) {
        http.requireToken("statement")
        val segment = accountSegment(account)
        val fromSec = from.epochSecond
        require(to.epochSecond >= fromSec) { "to must not be before from" }
        val pager = Pager(segment, http.pacerIfUnlimited(), onPage)
        var windowTo = to.epochSecond
        while (true) {
            val windowFrom = maxOf(fromSec, windowTo - WINDOW_SECONDS)
            pager.pageWindow(windowFrom, windowTo)
            if (windowFrom <= fromSec) break
            windowTo = windowFrom
        }
    }

    /** State of one [listAll] call. */
    private inner class Pager(
        private val segment: String,
        private val pacer: PersonalRateLimiter?,
        private val onPage: Consumer<List<StatementItem>>,
    ) {
        private val seen = HashSet<String>()

        /** Pages one window backwards from [to] while full pages come back. */
        fun pageWindow(
            from: Long,
            to: Long,
        ) {
            var pageTo = to
            while (true) {
                pacer?.let { http.awaitSlot(it, PersonalHttp.STATEMENT_KEY) }
                val page = fetch(segment, from, pageTo)
                onPage.accept(page.filter { seen.add(it.id) })
                if (page.size < PAGE_SIZE) return
                val oldest = page.minOf { it.time.epochSecond }
                val next =
                    if (oldest < pageTo) {
                        oldest
                    } else {
                        logger.warn(
                            "monobank statement: {} or more transactions at one second ({}); the rest of it is skipped",
                            PAGE_SIZE,
                            pageTo,
                        )
                        pageTo - 1
                    }
                if (next < from) return
                pageTo = next
            }
        }
    }

    private fun accountSegment(account: String): String {
        require(account.isNotBlank()) { "account must not be blank (use \"$DEFAULT_ACCOUNT\" for the default account)" }
        return UrlEncoding.pathSegment(account)
    }

    private fun fetch(
        segment: String,
        from: Long,
        to: Long?,
    ): List<StatementItem> {
        val path =
            buildString {
                append(PersonalHttp.STATEMENT_PATH).append(segment)
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

        /** [listAll] windows stay one second below the limit, in case monobank checks it with `<`. */
        internal const val WINDOW_SECONDS: Long = MAX_RANGE_SECONDS - 1
    }
}
