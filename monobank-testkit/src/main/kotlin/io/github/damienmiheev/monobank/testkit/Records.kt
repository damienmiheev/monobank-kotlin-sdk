package io.github.damienmiheev.monobank.testkit

import java.nio.charset.StandardCharsets
import java.time.Instant

/** What became of a webhook [FakeMonobankAcquiring] tried to send. */
public enum class WebhookOutcome {
    /** The receiver answered HTTP 200. */
    DELIVERED,

    /** Every attempt failed (no answer, or an answer other than 200). */
    FAILED,

    /** Deliberately not sent ([FakeMonobankAcquiring.dropNextWebhooks]): a lost webhook. */
    DROPPED,
}

/**
 * A webhook [FakeMonobankAcquiring] sent (or deliberately dropped), for assertions.
 * [toString] leaves out the body, which may contain a card token.
 *
 * @property invoiceId Invoice the webhook is about.
 * @property status Invoice status in the body (`processing`, `success`, ...).
 * @property url Where it was sent (the invoice's `webHookUrl`).
 * @property body Body exactly as sent (UTF-8 JSON, the same shape as `GET /api/merchant/invoice/status`).
 * @property xSign The `X-Sign` header sent; `null` when dropped.
 * @property signatureValid `false` when the signature was deliberately corrupted.
 * @property outcome Delivered, failed or dropped.
 * @property attempts HTTP attempts made (0 when dropped; monobank tries at most 3 times).
 * @property responseStatus HTTP status of the last attempt, or `null` when there was no answer.
 * @property duplicate `true` for the second copy of a duplicated webhook.
 * @property sentAt When the last attempt was made (fake clock), or `null` when dropped.
 */
public class RecordedWebhook internal constructor(
    public val invoiceId: String,
    public val status: String,
    public val url: String,
    private val bodyBytes: ByteArray,
    public val xSign: String?,
    public val signatureValid: Boolean,
    public val outcome: WebhookOutcome,
    public val attempts: Int,
    public val responseStatus: Int?,
    public val duplicate: Boolean,
    public val sentAt: Instant?,
) {
    public val body: String
        get() = String(bodyBytes, StandardCharsets.UTF_8)

    /** The raw body bytes, exactly as signed and sent (a copy). */
    public fun bodyBytes(): ByteArray = bodyBytes.copyOf()

    override fun toString(): String =
        "RecordedWebhook(invoiceId=$invoiceId, status=$status, outcome=$outcome, attempts=$attempts, " +
            "responseStatus=$responseStatus, duplicate=$duplicate, signatureValid=$signatureValid)"
}

/**
 * A request [FakeMonobankAcquiring] received. The `X-Token` header is never recorded.
 * [toString] shows only the method and path (the query and body can contain a card token).
 *
 * @property method HTTP method.
 * @property path Path without the query, e.g. `/api/merchant/invoice/create`.
 * @property query Decoded query parameters.
 * @property body Request body as UTF-8 text (empty for GET).
 * @property receivedAt When it arrived (fake clock).
 */
public class FakeRequest internal constructor(
    public val method: String,
    public val path: String,
    public val query: Map<String, String>,
    public val body: String,
    public val receivedAt: Instant,
) {
    override fun toString(): String = "FakeRequest($method $path)"
}

/**
 * A refund or hold release on a fake invoice.
 *
 * @property extRef The refund reference from the cancel request; `null` when none was given or the
 *   refund was made outside the API ([FakeMonobankAcquiring.refundExternally]).
 * @property amount Amount in minor units (kopiyky).
 * @property status `processing`, `success` or `failure`.
 */
public class FakeRefund internal constructor(
    public val extRef: String?,
    public val amount: Long,
    public val status: String,
) {
    override fun toString(): String = "FakeRefund(extRef=$extRef, amount=$amount, status=$status)"
}

/**
 * A snapshot of an invoice held by [FakeMonobankAcquiring], for assertions.
 *
 * @property invoiceId Invoice id.
 * @property status Current status (`created`, `processing`, `hold`, `success`, `failure`, `reversed`, `expired`).
 * @property amount Amount in minor units (kopiyky).
 * @property ccy ISO 4217 numeric currency.
 * @property finalAmount Amount after payment and refunds, in minor units (kopiyky); `null` before payment.
 * @property paymentType `debit`, `hold` or `verification`.
 * @property viaWallet `true` when it was created by `wallet/payment` (a saved-card charge).
 * @property reference `merchantPaymInfo.reference`.
 * @property webHookUrl Webhook URL.
 * @property walletId Wallet the card is saved into, for card-saving invoices.
 * @property cardToken Saved card token (hidden in [toString]), once assigned.
 * @property refunds Refunds and hold releases, oldest first.
 * @property createdDate Creation time.
 * @property modifiedDate Last change.
 * @property validUntil When an unpaid invoice expires.
 * @property holdUntil When an unfinalized hold lapses (9 days after the hold).
 * @property statusJson The body `GET /api/merchant/invoice/status` returns for it now.
 */
public class FakeInvoice internal constructor(
    public val invoiceId: String,
    public val status: String,
    public val amount: Long,
    public val ccy: Int,
    public val finalAmount: Long?,
    public val paymentType: String,
    public val viaWallet: Boolean,
    public val reference: String?,
    public val webHookUrl: String?,
    public val walletId: String?,
    public val cardToken: String?,
    public val refunds: List<FakeRefund>,
    public val createdDate: Instant,
    public val modifiedDate: Instant,
    public val validUntil: Instant?,
    public val holdUntil: Instant?,
    public val statusJson: String,
) {
    override fun toString(): String =
        "FakeInvoice(invoiceId=$invoiceId, status=$status, amount=$amount, ccy=$ccy, finalAmount=$finalAmount, " +
            "paymentType=$paymentType, viaWallet=$viaWallet, reference=$reference, refunds=$refunds)"
}
