package io.github.damienmiheev.monobank.acquiring.merchant

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import java.time.Instant

/**
 * Merchant data: details, public key, statement, submerchants. All calls are reads and are
 * retried after a server error or timeout.
 */
public class MerchantApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * The merchant's id, name and EDRPOU. A cheap way to check that a token works.
     *
     * `GET /api/merchant/details`. Safe to retry.
     */
    public fun details(): MerchantDetails = http.get("/api/merchant/details", MerchantDetails::class.java)

    /**
     * The public key that signs this merchant's webhooks.
     *
     * `GET /api/merchant/pubkey`. Safe to retry. Cache it; `WebhookVerifier` does this for you.
     */
    public fun publicKey(): PublicKeyResponse = http.get("/api/merchant/pubkey", PublicKeyResponse::class.java)

    /**
     * Payments between [from] and [to], newest first.
     *
     * `GET /api/merchant/statement`. Safe to retry. The API has no paging and no server-side
     * filter: keep the window short. Times are sent as unix seconds (UTC).
     *
     * @param to End of the window; `null` means now.
     * @param code Submerchant terminal code, for merchants with submerchants.
     */
    @JvmOverloads
    public fun statement(
        from: Instant,
        to: Instant? = null,
        code: String? = null,
    ): List<StatementItem> {
        if (to != null) require(!to.isBefore(from)) { "to must not be before from" }
        val query =
            listOf(
                "from" to from.epochSecond.toString(),
                "to" to to?.epochSecond?.toString(),
                "code" to code,
            )
        return http.get("/api/merchant/statement", StatementWire::class.java, query = query).list.orEmpty()
    }

    /**
     * Finds payments whose `merchantPaymInfo.reference` equals [reference], by scanning the
     * statement between [from] and [to] on the client.
     *
     * This is the recovery path when `invoices.create`, `wallet.payment` or another payment call
     * failed with `RequestOutcome.UNKNOWN` (e.g. a timeout) and you never got the `invoiceId`:
     * look the payment up before trying again, so the customer is not charged twice. It only
     * works when every attempt uses a unique, deterministic reference (e.g. your transaction id).
     *
     * Limits: the statement has no server-side filter (the whole window is downloaded) and lists
     * only payments that reached `hold`, `processing`, `success` or `failure`, not invoices that
     * are still `created`. An empty result therefore does not prove that no invoice exists.
     *
     * `GET /api/merchant/statement`. Safe to retry.
     */
    @JvmOverloads
    public fun findByReference(
        reference: String,
        from: Instant,
        to: Instant? = null,
        code: String? = null,
    ): List<StatementItem> {
        require(reference.isNotEmpty()) { "reference must not be empty" }
        return statement(from, to, code).filter { it.reference == reference }
    }

    /**
     * Submerchant terminals; their `code` goes into `CreateInvoiceRequest.code`.
     *
     * `GET /api/merchant/submerchant/list`. Safe to retry.
     */
    public fun submerchants(): List<Submerchant> =
        http.get("/api/merchant/submerchant/list", SubmerchantWire::class.java).list.orEmpty()
}
