package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.merchant.StatementCancel
import io.github.damienmiheev.monobank.acquiring.merchant.StatementItem

/**
 * Rebuilds an invoice state from a statement entry, for invoices whose `invoice/status` monobank
 * cannot serve (seen live: USD/EUR payments after a refund answer 500 INTERNAL_ERROR).
 *
 * Mirrors what `invoice/status` reports for refunded invoices (verified live): any successful
 * refund makes the status `reversed`, and `finalAmount` is the amount minus the refunds.
 */
internal object StatementFallback {
    fun toStatus(entry: StatementItem): InvoiceStatusResponse {
        val refunds = entry.cancelList.orEmpty()
        val refunded = refunds.sumOf { it.amount }
        val lastChange = (listOfNotNull(entry.date) + refunds.mapNotNull { it.date }).maxOrNull()
        return InvoiceStatusResponse(
            invoiceId = entry.invoiceId,
            statusRaw = if (refunds.isNotEmpty() && entry.statusRaw == "success") "reversed" else entry.statusRaw,
            amount = entry.amount,
            ccy = entry.ccy,
            finalAmount = if (entry.statusRaw == "success") (entry.amount - refunded).coerceAtLeast(0) else null,
            failureReason = null,
            errCode = null,
            createdDate = entry.date,
            modifiedDate = lastChange,
            reference = entry.reference,
            destination = entry.destination,
            cancelList = refunds.map(::refund).takeIf { it.isNotEmpty() },
            paymentInfo = paymentInfo(entry),
            walletData = null,
            tipsInfo = null,
            payMethod = null,
        ).also { it.reconstructedFlag = true }
    }

    private fun refund(cancel: StatementCancel): CancelListItem =
        CancelListItem(
            statusRaw = "success",
            amount = cancel.amount,
            ccy = cancel.ccy,
            createdDate = cancel.date,
            modifiedDate = cancel.date,
            approvalCode = cancel.approvalCode,
            rrn = cancel.rrn,
            extRef = null,
        )

    private fun paymentInfo(entry: StatementItem): PaymentInfo =
        PaymentInfo(
            maskedPan = entry.maskedPan,
            approvalCode = entry.approvalCode,
            rrn = entry.rrn,
            tranId = null,
            terminal = null,
            bank = null,
            paymentSystemRaw = null,
            paymentMethodRaw = null,
            fee = null,
            country = null,
            agentFee = entry.agentFee,
        )
}
