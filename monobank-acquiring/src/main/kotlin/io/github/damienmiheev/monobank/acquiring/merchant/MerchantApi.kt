package io.github.damienmiheev.monobank.acquiring.merchant

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.core.json.MonobankJson
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
    ): List<StatementItem> =
        http
            .get(
                "/api/merchant/statement",
                StatementWire::class.java,
                query = statementQuery(from, to, code),
            ).list
            .orEmpty()

    /**
     * Like [statement], but reads the answer entry by entry: entries the SDK cannot read (for
     * example after monobank changes a field's type) are returned in [StatementEntries.unreadable]
     * with their raw JSON instead of failing the whole call. Use it for reconciliation and reports.
     *
     * `GET /api/merchant/statement`. Safe to retry.
     */
    @JvmOverloads
    public fun statementEntries(
        from: Instant,
        to: Instant? = null,
        code: String? = null,
    ): StatementEntries {
        val response =
            http.execute(
                ApiRequest(
                    HttpMethod.GET,
                    "/api/merchant/statement",
                    statementQuery(from, to, code),
                    idempotent = true,
                ),
            )
        val list = MonobankJson.readTree(response.body).path("list")
        if (list.isMissingNode || list.isNull) return StatementEntries(emptyList(), emptyList())
        if (!list.isArray) {
            throw MonobankSerializationException(
                "monobank statement: 'list' is not an array",
                null,
                RequestOutcome.UNKNOWN,
            )
        }
        val items = mutableListOf<StatementItem>()
        val unreadable = mutableListOf<UnreadableStatementEntry>()
        for (node in list) {
            try {
                items += MonobankJson.read(MonobankJson.mapper.writeValueAsBytes(node), StatementItem::class.java)
            } catch (e: MonobankSerializationException) {
                unreadable +=
                    UnreadableStatementEntry(
                        rawJson = node.toString(),
                        invoiceId = node.path("invoiceId").takeIf { it.isTextual }?.asText(),
                        reference = node.path("reference").takeIf { it.isTextual }?.asText(),
                        error = e.message.orEmpty(),
                    )
            }
        }
        return StatementEntries(items, unreadable)
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
     * Choosing the window: entries are filtered by the payment's operation `date`, which can be
     * minutes after your call (3-D Secure, customer on the payment page) and monobank's clock may
     * differ from yours. Use `from = attemptedAt - 5 minutes` and `to = null` (now), and when
     * nothing is found, call again later (e.g. after the invoice lifetime) before treating the
     * attempt as failed. Refunds cannot be reconciled here (statement refunds carry no
     * `extRef`); use `invoices.status(invoiceId).cancelFor(extRef)`.
     *
     * Entries are read one by one, so an entry the SDK cannot read does not hide the others. If
     * such an entry has this [reference], the payment exists but can't be read: the call throws
     * `MonobankSerializationException` (outcome UNKNOWN) rather than reporting "not found", and
     * the entry's `invoiceId` is in the message so you can call `invoices.status` with it.
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
        val entries = statementEntries(from, to, code)
        val unreadableMatch = entries.unreadable.firstOrNull { it.reference == reference }
        if (unreadableMatch != null) {
            throw MonobankSerializationException(
                "A statement entry with this reference could not be read (invoiceId=${unreadableMatch.invoiceId}): " +
                    unreadableMatch.error,
                null,
                RequestOutcome.UNKNOWN,
            )
        }
        return entries.items.filter { it.reference == reference }
    }

    private fun statementQuery(
        from: Instant,
        to: Instant?,
        code: String?,
    ): List<Pair<String, String?>> {
        if (to != null) require(!to.isBefore(from)) { "to must not be before from" }
        return listOf(
            "from" to from.epochSecond.toString(),
            "to" to to?.epochSecond?.toString(),
            "code" to code,
        )
    }

    /**
     * Submerchant terminals; their `code` goes into `CreateInvoiceRequest.code`.
     *
     * `GET /api/merchant/submerchant/list`. Safe to retry.
     */
    public fun submerchants(): List<Submerchant> =
        http.get("/api/merchant/submerchant/list", SubmerchantWire::class.java).list.orEmpty()
}
