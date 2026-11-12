package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp

/**
 * Invoices: create, check, refund, hold/finalize.
 */
public class InvoiceApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Creates an invoice and returns the payment page URL.
     *
     * `POST /api/merchant/invoice/create`. Not retried after an unknown outcome: if this throws
     * with `RequestOutcome.UNKNOWN`, look the payment up by `merchantPaymInfo.reference` before
     * creating another invoice.
     */
    public fun create(request: CreateInvoiceRequest): CreateInvoiceResponse =
        http.post("/api/merchant/invoice/create", request, CreateInvoiceResponse::class.java, idempotent = false)

    /**
     * Current state of an invoice. Use it to recover from missed webhooks.
     *
     * `GET /api/merchant/invoice/status`. Safe to retry.
     */
    public fun status(invoiceId: String): InvoiceStatusResponse {
        require(invoiceId.isNotBlank()) { "invoiceId must not be blank" }
        return http.get(
            "/api/merchant/invoice/status",
            InvoiceStatusResponse::class.java,
            query = listOf("invoiceId" to invoiceId),
        )
    }
}
