package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome

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
     * Current state of an invoice. Use it to recover from missed webhooks.
     *
     * `GET /api/merchant/invoice/status`. Safe to retry.
     */
    public fun status(invoiceId: String): InvoiceStatusResponse {
        requireId(invoiceId, "invoiceId")
        return http.get(INVOICE + "status", InvoiceStatusResponse::class.java, query = listOf("invoiceId" to invoiceId))
    }

    /**
     * Refunds a successful payment (fully, or partly with `amount`), or releases a hold.
     *
     * Merchants with fiscal receipts or split payments should send `items` (the refunded products,
     * with `splitReceiverId` for split payments) with a partial refund; see [finalize].
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
     * amount (e.g. capture 1.50 of a 1 × 2.00 line as `qty` 0.75, `sum` 200). An item that does not
     * match gets 400 `1001` "finalization basket contains item not found in hold: ..." (verified).
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
