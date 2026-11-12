package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.common.AcquiringErrorCodes
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.acquiring.merchant.MerchantApi
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import java.time.Instant

/**
 * Invoices: create, check, refund, hold/finalize, direct and synchronous payments, receipts.
 *
 * Retries: reads (status, receipt without email, fiscal checks) are retried after a server error
 * or timeout. Everything that changes state is retried only when the request provably never
 * reached monobank or was rate-limited; after an unknown outcome the SDK throws, and you
 * reconcile first (see `MerchantApi.findByReference`).
 */
public class InvoiceApi internal constructor(
    private val http: AcquiringHttp,
) {
    private val merchant = MerchantApi(http)

    /**
     * Creates an invoice and returns the payment page URL.
     *
     * `POST /api/merchant/invoice/create`. Not retried after an unknown outcome: if this throws
     * with `RequestOutcome.UNKNOWN`, look the payment up by `merchantPaymInfo.reference` (see
     * `MerchantApi.findByReference`) before creating another invoice.
     *
     * To save a card without charging it, use `CreateInvoiceRequest.verification(walletId)`.
     */
    public fun create(request: CreateInvoiceRequest): CreateInvoiceResponse =
        http.post(INVOICE + "create", request, CreateInvoiceResponse::class.java, idempotent = false)

    /**
     * Current state of an invoice.
     *
     * USD and EUR invoices report the converted UAH amount (`ccy` 980) once paid. Verified on the
     * test merchant: after a refund of such a converted payment, this endpoint answers 500
     * `INTERNAL_ERROR` "unexpected end of JSON input" for that invoice every time; reconcile it
     * through `merchant.statementEntries` / `findByReference`, which keep working.
     *
     * Use it to recover from missed webhooks.
     *
     * `GET /api/merchant/invoice/status`. Safe to retry.
     */
    public fun status(invoiceId: String): InvoiceStatusResponse {
        requireId(invoiceId, "invoiceId")
        return http.get(INVOICE + "status", InvoiceStatusResponse::class.java, query = listOf("invoiceId" to invoiceId))
    }

    /**
     * Like [status], but when monobank's `invoice/status` fails with 500 `INTERNAL_ERROR` (seen
     * live, permanently, for USD/EUR payments after a refund), rebuilds the state from the merchant
     * statement entry of this invoice, searched from [statementFrom] (the payment date or a little
     * earlier). The result then has [InvoiceStatusResponse.isReconstructedFromStatement] = `true`:
     * status, amounts (in UAH for converted payments), dates and refunds are there; refunds carry
     * no `extRef` (statement data has none), so match them by amount.
     *
     * If the statement has no entry for the invoice either, the original error is thrown. Safe to retry.
     */
    public fun status(
        invoiceId: String,
        statementFrom: Instant,
    ): InvoiceStatusResponse =
        try {
            status(invoiceId)
        } catch (e: MonobankServerException) {
            if (e.errorCode != AcquiringErrorCodes.INTERNAL_ERROR) throw e
            val entry =
                merchant.statementEntries(statementFrom).items.firstOrNull { it.invoiceId == invoiceId } ?: throw e
            StatementFallback.toStatus(entry)
        }

    /**
     * Refunds a successful payment (fully, or partly with `amount`), or releases a hold.
     *
     * Merchants with fiscal receipts or split payments should send `items` with a partial refund,
     * built like [finalize]'s: the paid basket lines with a smaller `qty` (verified: 0.50 of a
     * 2.00 line as `qty` 0.25, `sum` 200).
     *
     * USD and EUR payments are converted to UAH when paid (verified): refund them in UAH, without
     * `items` (a partial UAH refund without items and a full refund without amount and items were
     * accepted; items with the original-currency `sum` are refused with 400 `1001` "whole discount
     * is expected to be cancelled with the last item").
     *
     * Verified live: a refund while the invoice is still `processing` after a finalize is refused
     * with 404 `1004` "order not finished" (nothing was refunded; retry once the status is
     * `success`). After ANY successful refund, partial included, the invoice status becomes
     * `reversed`; `finalAmount` holds what is still paid.
     *
     * `POST /api/merchant/invoice/cancel`. Not retried after an unknown outcome. The refund is
     * usually [io.github.damienmiheev.monobank.acquiring.common.OperationStatus.PROCESSING]: follow
     * it with `status(invoiceId).cancelFor(extRef)` or webhooks. A refund that is impossible fails
     * with HTTP 500 `CANCEL_NOT_AVAILABLE`, whose outcome is `RequestOutcome.REJECTED`.
     */
    public fun cancel(request: CancelInvoiceRequest): CancelInvoiceResponse =
        http.post(INVOICE + "cancel", request, CancelInvoiceResponse::class.java, idempotent = false)

    /**
     * Invalidates an invoice that has not been paid yet, so it can no longer be paid.
     *
     * `POST /api/merchant/invoice/remove`. Not retried after an unknown outcome (a repeated call
     * may fail although the first one worked).
     *
     * monobank does not document which error it returns for an invoice that is already paid or
     * being paid: it surfaces as a `MonobankApiException` (usually `MonobankBadRequestException`)
     * with the raw `errorCode`. After **any** error, call [status] before concluding anything:
     * the customer may have paid a moment before.
     */
    public fun remove(invoiceId: String) {
        requireId(invoiceId, "invoiceId")
        http.postNoContent(INVOICE + "remove", InvoiceIdRequest(invoiceId), idempotent = false)
    }

    /**
     * Captures (finalizes) a hold, fully or partly.
     *
     * Merchants with fiscal receipts or split payments must send `items` for a partial capture
     * (amount below the held amount); without them monobank answers 400 `1001` "wrong amount for
     * finalization without basket" (verified on monobank's test merchant). The same is likely for
     * a partial refund ([cancel]) without `items`.
     *
     * Each item must repeat a line of the hold's basket exactly — same `name`, `code`, unit price
     * `sum` and `splitReceiverId` — with only `qty` changed so that the items add up to the captured
     * amount (e.g. capture 1.50 of a 1 × 2.00 line as `qty` 0.75, `sum` 200; fractional `qty` is
     * accepted). An item that does not match gets 400 `1001` "finalization basket contains item not
     * found in hold: ..." (verified).
     *
     * The answer `success` means monobank accepted the capture; the invoice then stays `processing`
     * (with `finalAmount` already set) for a while — over 30 s on the test merchant — before it turns
     * `success`. Refunds are refused until then (see [cancel]).
     *
     * `POST /api/merchant/invoice/finalize`. Not retried after an unknown outcome (including a
     * 5xx `EXTERNAL_ERROR`: the capture may have happened); check
     * `status(invoiceId)` before finalizing again.
     */
    public fun finalize(request: FinalizeInvoiceRequest): FinalizeInvoiceResponse =
        http.post(INVOICE + "finalize", request, FinalizeInvoiceResponse::class.java, idempotent = false)

    /**
     * Creates and pays an invoice with raw card data (PCI DSS certified merchants only). Handle
     * [PaymentResponse.tdsUrl] for 3-D Secure.
     *
     * `POST /api/merchant/invoice/payment-direct`. A payment: never retried after an unknown outcome.
     */
    public fun paymentDirect(request: PaymentDirectRequest): PaymentResponse =
        http.post(INVOICE + "payment-direct", request, PaymentResponse::class.java, idempotent = false)

    /**
     * Pays synchronously with card data or decrypted Apple Pay / Google Pay data (access via
     * monobank support). Returns the invoice state.
     *
     * `POST /api/merchant/invoice/sync-payment`. This is a payment, **not** a status lookup:
     * calling it again charges again. Never retried after an unknown outcome.
     */
    public fun syncPayment(request: SyncPaymentRequest): InvoiceStatusResponse =
        http.post(INVOICE + "sync-payment", request, InvoiceStatusResponse::class.java, idempotent = false)

    /**
     * The payment receipt as PDF bytes.
     *
     * `GET /api/merchant/invoice/receipt`. Safe to retry.
     *
     * @throws MonobankSerializationException when monobank returned no file or a broken one.
     */
    public fun receipt(invoiceId: String): ByteArray {
        requireId(invoiceId, "invoiceId")
        val wire = http.get(INVOICE + "receipt", ReceiptWire::class.java, query = listOf("invoiceId" to invoiceId))
        val file =
            wire.file ?: throw MonobankSerializationException(
                "monobank returned a receipt without a file",
                null,
                RequestOutcome.UNKNOWN,
            )
        return Base64Pdf.decode(file)
    }

    /**
     * Emails the payment receipt to [email] and returns the PDF bytes when monobank includes them.
     *
     * `GET /api/merchant/invoice/receipt?email=`. It sends an email, so it is **not** retried after
     * an unknown outcome (a retry could send the receipt twice).
     */
    public fun sendReceipt(
        invoiceId: String,
        email: String,
    ): ByteArray? {
        requireId(invoiceId, "invoiceId")
        require(email.isNotBlank()) { "email must not be blank" }
        val wire =
            http.get(
                INVOICE + "receipt",
                ReceiptWire::class.java,
                query = listOf("invoiceId" to invoiceId, "email" to email),
                idempotent = false,
            )
        return wire.file?.let { Base64Pdf.decode(it) }
    }

    /**
     * Fiscal checks (receipts registered with the tax service) of an invoice.
     *
     * `GET /api/merchant/invoice/fiscal-checks`. Safe to retry.
     */
    public fun fiscalChecks(invoiceId: String): List<FiscalCheck> {
        requireId(invoiceId, "invoiceId")
        return http
            .get(INVOICE + "fiscal-checks", FiscalChecksWire::class.java, query = listOf("invoiceId" to invoiceId))
            .checks
            .orEmpty()
    }

    private companion object {
        private const val INVOICE = "/api/merchant/invoice/"

        fun requireId(
            value: String,
            name: String,
        ) = require(value.isNotBlank()) { "$name must not be blank" }
    }
}
