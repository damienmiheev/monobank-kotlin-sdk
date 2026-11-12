package io.github.damienmiheev.monobank.testkit.internal

import io.github.damienmiheev.monobank.testkit.CustomerPayment
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A minimal stand-in for monobank's payment page (`pageUrl`) and 3-D Secure page (`tdsUrl`):
 * `GET` shows the invoice with "Pay" and "Decline" buttons, `POST action=pay|decline` plays the
 * customer, then redirects to the invoice's `redirectUrl` (303) when it has one. No token needed,
 * as on the real page. Lets browser-driven end-to-end tests walk the hosted flow.
 */
internal class HostedPages(
    private val bank: Bank,
) {
    fun handles(path: String): Boolean = path.startsWith(PAY) || path.startsWith(TDS)

    fun handle(
        method: String,
        path: String,
        body: ByteArray,
    ): HttpResult {
        val invoiceId = path.substringAfter(if (path.startsWith(PAY)) PAY else TDS).trimEnd('/')
        return bank.locked {
            val merchant =
                bank.merchants.firstOrNull { it.has(invoiceId) } ?: return@locked html(404, "Invoice not found")
            val invoice = merchant.require(invoiceId)
            when (method) {
                "GET" -> page(invoice, path)
                "POST" -> act(merchant, invoice, form(body)["action"])
                else -> html(405, "Method not allowed")
            }
        }
    }

    private fun page(
        invoice: InvoiceRecord,
        path: String,
    ): HttpResult {
        val payable = invoice.status == Status.CREATED || invoice.status == Status.PROCESSING
        val buttons =
            if (payable) {
                """<form method="post" action="$path"><button name="action" value="pay">Pay</button> """ +
                    """<button name="action" value="decline">Decline</button></form>"""
            } else {
                ""
            }
        return html(
            200,
            "Fake monobank payment page: invoice ${invoice.invoiceId}, amount ${invoice.amount} " +
                "(minor units), currency ${invoice.ccy}, status ${invoice.status}.$buttons",
        )
    }

    private fun act(
        merchant: MerchantState,
        invoice: InvoiceRecord,
        action: String?,
    ): HttpResult {
        val payment =
            when (action) {
                "pay" -> CustomerPayment.approved()
                "decline" -> CustomerPayment.declined()
                else -> return html(400, "Unknown action")
            }
        if (invoice.status != Status.CREATED && invoice.status != Status.PROCESSING) {
            return html(409, "Invoice is ${invoice.status}")
        }
        if (invoice.status == Status.CREATED) merchant.beginPayment(invoice, payment)
        merchant.completePayment(invoice, payment)
        val redirect = invoice.redirectUrl ?: return html(200, "Invoice is ${invoice.status}")
        return HttpResult(303, ByteArray(0), mapOf("Location" to redirect, "Content-Type" to "text/html"))
    }

    private fun form(body: ByteArray): Map<String, String> =
        String(body, StandardCharsets.UTF_8)
            .split('&')
            .filter { '=' in it }
            .associate {
                URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
            }

    private fun html(
        status: Int,
        text: String,
    ): HttpResult =
        HttpResult(
            status,
            "<!doctype html><html><body><p>$text</p></body></html>".toByteArray(StandardCharsets.UTF_8),
            mapOf("Content-Type" to "text/html; charset=utf-8"),
        )

    companion object {
        const val PAY = "/pay/"
        const val TDS = "/tds/"
    }
}
