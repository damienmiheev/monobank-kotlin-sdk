package io.github.damienmiheev.monobank.corporate.client

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.internal.CorporateAuthenticator
import io.github.damienmiheev.monobank.corporate.internal.CorporateHttp
import io.github.damienmiheev.monobank.corporate.internal.SigningRules
import java.time.Instant

/**
 * Data of one client who approved an access request, identified by that request's id
 * (sent as `X-Request-Id`). Get one with `MonobankCorporate.client(requestId)`; it is cheap,
 * create one per call if convenient.
 *
 * Both calls are signed `X-Time | X-Request-Id | URL`. HTTP 401 (thrown as
 * [io.github.damienmiheev.monobank.core.MonobankAuthException]) means the client has not
 * approved the request, or has since revoked access in the monobank app; HTTP 403 means the
 * company key or signature was rejected.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 *
 * @property requestId The approved access request id
 *   ([io.github.damienmiheev.monobank.corporate.access.AccessRequest.tokenRequestId]).
 */
@ExperimentalMonobankApi
public class ClientApi internal constructor(
    private val http: CorporateHttp,
    public val requestId: String,
) {
    init {
        CorporateHttp.requireHeaderValue(requestId, "requestId")
    }

    private val headers = mapOf(CorporateAuthenticator.REQUEST_ID_HEADER to requestId)

    /**
     * The client's accounts and, with the `p` permission, name and client id.
     *
     * `GET /personal/client-info`. Safe to retry.
     */
    public fun info(): ClientInfo = http.get(SigningRules.CLIENT_INFO, ClientInfo::class.java, headers = headers)

    /**
     * Transactions of one account between [from] and [to], newest first.
     *
     * `GET /personal/statement/{account}/{from}/{to}`, times as Unix seconds (sub-second parts are
     * dropped). Safe to retry. monobank rate-limits statements (community reports: about one
     * call per minute per client); a range is at most [MAX_RANGE_SECONDS] long.
     *
     * @param account Account id from [info], or [DEFAULT_ACCOUNT] for the default UAH account.
     * @param from Start of the period.
     * @param to End of the period; `null` means now (as monobank's clock sees it).
     * @throws IllegalArgumentException for a blank account, [to] before [from], or a longer range.
     */
    @JvmOverloads
    public fun statement(
        account: String,
        from: Instant,
        to: Instant? = null,
    ): List<StatementItem> {
        require(account.isNotBlank()) { "account must not be blank (use \"$DEFAULT_ACCOUNT\" for the default account)" }
        if (to != null) {
            require(!to.isBefore(from)) { "to must not be before from" }
            require(to.epochSecond - from.epochSecond <= MAX_RANGE_SECONDS) {
                "statement range must be at most $MAX_RANGE_SECONDS seconds (31 days + 1 hour)"
            }
        }
        val path =
            buildString {
                append(SigningRules.STATEMENT_PREFIX)
                append(CorporateHttp.encodeSegment(account))
                append('/').append(from.epochSecond)
                if (to != null) append('/').append(to.epochSecond)
            }
        return http.get(path, Array<StatementItem>::class.java, headers = headers).toList()
    }

    override fun toString(): String = "ClientApi(requestId=$requestId)"

    public companion object {
        /** Account id that means the client's default (UAH) account. */
        public const val DEFAULT_ACCOUNT: String = "0"

        /** Longest range of one statement call: 31 days + 1 hour, in seconds. */
        public const val MAX_RANGE_SECONDS: Long = 2_682_000L
    }
}
