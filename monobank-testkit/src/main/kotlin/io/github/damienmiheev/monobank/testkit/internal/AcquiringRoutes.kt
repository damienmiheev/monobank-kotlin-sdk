package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.damienmiheev.monobank.testkit.CustomerPayment
import io.github.damienmiheev.monobank.testkit.MonobankPaths
import io.github.damienmiheev.monobank.testkit.RefundSettlement
import io.github.damienmiheev.monobank.testkit.WalletPaymentOutcome
import java.time.Instant

/** An HTTP answer of the fake. */
internal class HttpResult(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(node: ObjectNode): HttpResult = HttpResult(200, FakeJson.bytes(node))

        fun error(error: ApiError): HttpResult = HttpResult(error.status, FakeJson.errorBody(error.code, error.text))
    }
}

/** A parsed request: decoded query parameters and the raw body. */
internal class ApiCall(
    val query: Map<String, String>,
    val body: ByteArray,
)

/** monobank acquiring's routes over one merchant's state: validation, status codes and JSON shapes. */
internal class AcquiringRoutes(
    private val bank: Bank,
    private val baseUrl: () -> String,
) {
    private class Route(
        val method: String,
        val handler: (MerchantState, ApiCall) -> HttpResult,
    )

    private val routes: Map<String, Route> =
        mapOf(
            MonobankPaths.PUBKEY to Route("GET") { m, _ -> pubkey(m) },
            MonobankPaths.DETAILS to Route("GET") { m, _ -> details(m) },
            MonobankPaths.INVOICE_CREATE to Route("POST", ::create),
            MonobankPaths.INVOICE_STATUS to Route("GET", ::status),
            MonobankPaths.INVOICE_REMOVE to Route("POST", ::remove),
            MonobankPaths.INVOICE_FINALIZE to Route("POST", ::finalizeInvoice),
            MonobankPaths.INVOICE_CANCEL to Route("POST", ::cancel),
            MonobankPaths.WALLET_PAYMENT to Route("POST", ::walletPayment),
            MonobankPaths.WALLET to Route("GET", ::wallet),
            MonobankPaths.WALLET_CARD to Route("DELETE", ::deleteCard),
            MonobankPaths.STATEMENT to Route("GET", ::statement),
            MonobankPaths.SPLIT_RECEIVERS to Route("GET") { m, _ -> splitReceivers(m) },
        )

    /** The route's allowed method, or `null` for an unknown path. */
    fun methodOf(path: String): String? = routes[path]?.method

    fun handle(
        path: String,
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val route = routes[path] ?: return HttpResult.error(ApiError.notFound("not found"))
        return try {
            route.handler(merchant, call)
        } catch (e: ApiError) {
            HttpResult.error(e)
        }
    }

    private fun pubkey(merchant: MerchantState): HttpResult =
        HttpResult.json(FakeJson.obj().put("key", merchant.signer.publicKey))

    private fun details(merchant: MerchantState): HttpResult =
        HttpResult.json(
            FakeJson
                .obj()
                .put("merchantId", merchant.info.merchantId)
                .put("merchantName", merchant.info.merchantName)
                .put("edrpou", merchant.info.edrpou),
        )

    private fun create(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val params = RequestParsing.create(FakeJson.parseObject(call.body), merchant.basketRules)
        return bank.locked {
            val now = merchant.now()
            val invoice =
                InvoiceRecord(
                    invoiceId = merchant.ids.invoiceId(now),
                    amount = params.amount,
                    ccy = params.ccy,
                    paymentType = params.paymentType,
                    viaWallet = false,
                    order = params.order,
                    webHookUrl = params.webHookUrl,
                    redirectUrl = params.redirectUrl,
                    createdAt = now,
                    validUntil = now.plus(params.validity),
                    saveCard = params.saveCard,
                    walletId = if (params.saveCard) params.walletId ?: merchant.ids.walletId() else null,
                )
            merchant.add(invoice)
            val answer =
                FakeJson
                    .obj()
                    .put("invoiceId", invoice.invoiceId)
                    .put("pageUrl", "${baseUrl()}${HostedPages.PAY}${invoice.invoiceId}")
            if (params.withAppUrl && params.paymentType != "verification") {
                answer.put("appUrl", "monobank://pay/${invoice.invoiceId}")
            }
            HttpResult.json(answer)
        }
    }

    private fun status(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val invoiceId = requiredQuery(call, "invoiceId")
        return bank.locked { HttpResult.json(merchant.find(invoiceId).statusJson()) }
    }

    /**
     * Invalidates an unpaid invoice. Removing an invoice that is already `expired` (removed before,
     * or timed out) answers 200 again: the goal is reached and nothing changes. Any other status is 400.
     */
    private fun remove(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val invoiceId = Fields.requiredString(FakeJson.parseObject(call.body), "invoiceId")
        return bank.locked {
            val invoice = merchant.find(invoiceId)
            when (invoice.status) {
                Status.CREATED -> merchant.expire(invoice)
                Status.EXPIRED -> Unit
                else -> throw ApiError.badRequest("invoice is ${invoice.status} and cannot be removed")
            }
            HttpResult.json(FakeJson.obj())
        }
    }

    private fun finalizeInvoice(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val json = FakeJson.parseObject(call.body)
        val invoiceId = Fields.requiredString(json, "invoiceId")
        val amount = Fields.requiredLong(json, "amount")
        if (amount <= 0) throw ApiError.badRequest("invalid 'amount'")
        val items = RequestParsing.items(json)
        return bank.locked {
            merchant.finalizeHold(merchant.find(invoiceId), amount, items)
            HttpResult.json(FakeJson.obj().put("status", Status.SUCCESS))
        }
    }

    private fun cancel(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val json = FakeJson.parseObject(call.body)
        val invoiceId = Fields.requiredString(json, "invoiceId")
        val extRef = Fields.string(json, "extRef")
        val amount = Fields.long(json, "amount")
        if (amount != null && amount <= 0) throw ApiError.badRequest("invalid 'amount'")
        val items = RequestParsing.items(json)
        return bank.locked {
            val invoice = merchant.find(invoiceId)
            val refund = merchant.cancel(invoice, extRef, amount, items)
            val settlement = merchant.settings.refundSettlement
            when (settlement) {
                RefundSettlement.IMMEDIATE_SUCCESS -> merchant.settle(invoice, refund, true)
                RefundSettlement.IMMEDIATE_FAILURE -> merchant.settle(invoice, refund, false)
                RefundSettlement.AUTOMATIC, RefundSettlement.MANUAL -> Unit
            }
            val answer =
                FakeJson
                    .obj()
                    .put("status", refund.status)
                    .put("createdDate", FakeJson.date(refund.createdAt))
                    .put("modifiedDate", FakeJson.date(refund.modifiedAt))
            if (settlement == RefundSettlement.AUTOMATIC) merchant.settle(invoice, refund, true)
            HttpResult.json(answer)
        }
    }

    private fun walletPayment(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val params =
            RequestParsing.walletPayment(FakeJson.parseObject(call.body), merchant.basketRules)
        return bank.locked {
            val card =
                merchant.card(params.cardToken) ?: throw ApiError(400, "TOKEN_NOT_FOUND", "card token not found")
            val now = merchant.now()
            val invoice =
                InvoiceRecord(
                    invoiceId = merchant.ids.invoiceId(now),
                    amount = params.amount,
                    ccy = params.ccy,
                    paymentType = params.paymentType,
                    viaWallet = true,
                    order = params.order,
                    webHookUrl = params.webHookUrl,
                    redirectUrl = params.redirectUrl,
                    createdAt = now,
                    validUntil = null,
                    saveCard = false,
                    walletId = null,
                )
            merchant.add(invoice)
            val payment =
                CustomerPayment
                    .builder()
                    .maskedPan(card.maskedPan)
                    .country(card.country)
                    .paymentSystem(card.paymentSystem)
                    .build()
            merchant.beginPayment(invoice, payment, paymentMethod = "wallet")
            val outcome = merchant.nextOutcome()
            when (outcome.kind) {
                WalletPaymentOutcome.Kind.SUCCESS -> merchant.completePayment(invoice, payment)
                WalletPaymentOutcome.Kind.DECLINED -> merchant.completePayment(invoice, declined(outcome))
                WalletPaymentOutcome.Kind.PROCESSING, WalletPaymentOutcome.Kind.THREE_DS -> Unit
            }
            HttpResult.json(paymentAnswer(invoice, outcome.kind == WalletPaymentOutcome.Kind.THREE_DS))
        }
    }

    private fun declined(outcome: WalletPaymentOutcome): CustomerPayment =
        CustomerPayment.declined(
            outcome.errCode ?: CustomerPayment.DEFAULT_DECLINE_CODE,
            outcome.failureReason ?: "",
        )

    /** `wallet/payment` answers `processing`, `success` or `failure`; a hold is reported as `success`. */
    private fun paymentAnswer(
        invoice: InvoiceRecord,
        threeDs: Boolean,
    ): ObjectNode {
        val answer = FakeJson.obj().put("invoiceId", invoice.invoiceId)
        if (threeDs) answer.put("tdsUrl", "${baseUrl()}${HostedPages.TDS}${invoice.invoiceId}")
        val status =
            when (invoice.status) {
                Status.SUCCESS, Status.HOLD -> Status.SUCCESS
                Status.FAILURE -> Status.FAILURE
                else -> Status.PROCESSING
            }
        answer.put("status", status)
        invoice.failureReason?.let { answer.put("failureReason", it) }
        return answer
            .put("amount", invoice.amount)
            .put("ccy", invoice.ccy)
            .put("createdDate", FakeJson.date(invoice.createdAt))
            .put("modifiedDate", FakeJson.date(invoice.modifiedAt))
    }

    private fun wallet(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val walletId = requiredQuery(call, "walletId")
        return bank.locked {
            val answer = FakeJson.obj()
            val list = answer.putArray("wallet")
            merchant.cards(walletId).forEach { card ->
                val item = list.addObject().put("cardToken", card.cardToken).put("maskedPan", card.maskedPan)
                card.country?.let { item.put("country", it) }
            }
            HttpResult.json(answer)
        }
    }

    private fun deleteCard(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val cardToken = requiredQuery(call, "cardToken")
        return bank.locked {
            if (!merchant.removeCard(cardToken)) throw ApiError(400, "TOKEN_NOT_FOUND", "card token not found")
            HttpResult.json(FakeJson.obj())
        }
    }

    private fun statement(
        merchant: MerchantState,
        call: ApiCall,
    ): HttpResult {
        val from = epochQuery(call, "from") ?: throw ApiError.badRequest("empty 'from'")
        val to = epochQuery(call, "to")
        return bank.locked {
            // `to` is in whole seconds: include the whole last second.
            val until = to?.plusMillis(999) ?: merchant.now()
            val answer = FakeJson.obj()
            val list = answer.putArray("list")
            merchant
                .all()
                .filter { invoice -> invoice.paidAt?.let { !it.isBefore(from) && !it.isAfter(until) } ?: false }
                .sortedByDescending { it.paidAt }
                .forEach { invoice -> invoice.statementJson()?.let { list.add(it) } }
            HttpResult.json(answer)
        }
    }

    private fun splitReceivers(merchant: MerchantState): HttpResult {
        val answer = FakeJson.obj()
        val list = answer.putArray("list")
        merchant.info.splitReceivers.forEach {
            list
                .addObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("edrpou", it.edrpou)
        }
        return HttpResult.json(answer)
    }

    private fun requiredQuery(
        call: ApiCall,
        name: String,
    ): String = call.query[name]?.takeIf { it.isNotBlank() } ?: throw ApiError.badRequest("empty '$name'")

    private fun epochQuery(
        call: ApiCall,
        name: String,
    ): Instant? {
        val raw = call.query[name]?.takeIf { it.isNotBlank() } ?: return null
        val seconds = raw.toLongOrNull() ?: throw ApiError.badRequest("invalid '$name'")
        return Instant.ofEpochSecond(seconds)
    }
}
