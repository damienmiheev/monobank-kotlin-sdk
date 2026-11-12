package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.damienmiheev.monobank.testkit.CustomerPayment
import io.github.damienmiheev.monobank.testkit.MonobankWebhookSigner
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

/** The merchant behind the fake's tokens. */
internal class MerchantInfo(
    val merchantId: String,
    val merchantName: String,
    val edrpou: String,
)

/** monobank acquiring's routes over [FakeState]: validation, status codes and JSON shapes. */
internal class AcquiringRoutes(
    private val state: FakeState,
    private val signer: MonobankWebhookSigner,
    private val merchant: MerchantInfo,
    private val baseUrl: () -> String,
) {
    private class Route(
        val method: String,
        val handler: (ApiCall) -> HttpResult,
    )

    private val routes: Map<String, Route> =
        mapOf(
            "/api/merchant/pubkey" to Route("GET") { pubkey() },
            "/api/merchant/details" to Route("GET") { details() },
            "/api/merchant/invoice/create" to Route("POST", ::create),
            "/api/merchant/invoice/status" to Route("GET", ::status),
            "/api/merchant/invoice/remove" to Route("POST", ::remove),
            "/api/merchant/invoice/finalize" to Route("POST", ::finalizeInvoice),
            "/api/merchant/invoice/cancel" to Route("POST", ::cancel),
            "/api/merchant/wallet/payment" to Route("POST", ::walletPayment),
            "/api/merchant/wallet" to Route("GET", ::wallet),
            "/api/merchant/wallet/card" to Route("DELETE", ::deleteCard),
            "/api/merchant/statement" to Route("GET", ::statement),
        )

    /**
     * The route's allowed method, or `null` for an unknown path.
     */
    fun methodOf(path: String): String? = routes[path]?.method

    fun handle(
        path: String,
        call: ApiCall,
    ): HttpResult {
        val route = routes[path] ?: return HttpResult.error(ApiError.notFound("not found"))
        return try {
            route.handler(call)
        } catch (e: ApiError) {
            HttpResult.error(e)
        }
    }

    private fun pubkey(): HttpResult = HttpResult.json(FakeJson.obj().put("key", signer.publicKey))

    private fun details(): HttpResult =
        HttpResult.json(
            FakeJson
                .obj()
                .put("merchantId", merchant.merchantId)
                .put("merchantName", merchant.merchantName)
                .put("edrpou", merchant.edrpou),
        )

    private fun create(call: ApiCall): HttpResult {
        val params = RequestParsing.create(FakeJson.parseObject(call.body), state.settings.validateBasketTotal)
        return state.locked {
            val now = state.now()
            val invoice =
                InvoiceRecord(
                    invoiceId = Ids.invoiceId(now),
                    amount = params.amount,
                    ccy = params.ccy,
                    paymentType = params.paymentType,
                    viaWallet = false,
                    order = params.order,
                    webHookUrl = params.webHookUrl,
                    createdAt = now,
                    validUntil = now.plus(params.validity),
                    saveCard = params.saveCard,
                    walletId = if (params.saveCard) params.walletId ?: Ids.walletId() else null,
                )
            state.add(invoice)
            val answer =
                FakeJson
                    .obj()
                    .put("invoiceId", invoice.invoiceId)
                    .put("pageUrl", "${baseUrl()}/pay/${invoice.invoiceId}")
            if (params.withAppUrl && params.paymentType != "verification") {
                answer.put("appUrl", "monobank://pay/${invoice.invoiceId}")
            }
            HttpResult.json(answer)
        }
    }

    private fun status(call: ApiCall): HttpResult {
        val invoiceId = requiredQuery(call, "invoiceId")
        return state.locked { HttpResult.json(state.find(invoiceId).statusJson()) }
    }

    private fun remove(call: ApiCall): HttpResult {
        val invoiceId = Fields.requiredString(FakeJson.parseObject(call.body), "invoiceId")
        return state.locked {
            val invoice = state.find(invoiceId)
            if (invoice.status != Status.CREATED) {
                throw ApiError.badRequest("invoice is ${invoice.status} and cannot be removed")
            }
            state.expire(invoice)
            HttpResult.json(FakeJson.obj())
        }
    }

    private fun finalizeInvoice(call: ApiCall): HttpResult {
        val json = FakeJson.parseObject(call.body)
        val invoiceId = Fields.requiredString(json, "invoiceId")
        val amount = Fields.requiredLong(json, "amount")
        if (amount <= 0) throw ApiError.badRequest("invalid 'amount'")
        return state.locked {
            state.finalizeHold(state.find(invoiceId), amount)
            HttpResult.json(FakeJson.obj().put("status", Status.SUCCESS))
        }
    }

    private fun cancel(call: ApiCall): HttpResult {
        val json = FakeJson.parseObject(call.body)
        val invoiceId = Fields.requiredString(json, "invoiceId")
        val extRef = Fields.string(json, "extRef")
        val amount = Fields.long(json, "amount")
        if (amount != null && amount <= 0) throw ApiError.badRequest("invalid 'amount'")
        return state.locked {
            val invoice = state.find(invoiceId)
            val refund = state.cancel(invoice, extRef, amount)
            val answer =
                FakeJson
                    .obj()
                    .put("status", refund.status)
                    .put("createdDate", FakeJson.date(refund.createdAt))
                    .put("modifiedDate", FakeJson.date(refund.modifiedAt))
            if (state.settings.refundSettlement == RefundSettlement.AUTOMATIC) state.settle(invoice, refund, true)
            HttpResult.json(answer)
        }
    }

    private fun walletPayment(call: ApiCall): HttpResult {
        val params = RequestParsing.walletPayment(FakeJson.parseObject(call.body), state.settings.validateBasketTotal)
        return state.locked {
            val card =
                state.card(params.cardToken) ?: throw ApiError(400, "TOKEN_NOT_FOUND", "card token not found")
            val now = state.now()
            val invoice =
                InvoiceRecord(
                    invoiceId = Ids.invoiceId(now),
                    amount = params.amount,
                    ccy = params.ccy,
                    paymentType = params.paymentType,
                    viaWallet = true,
                    order = params.order,
                    webHookUrl = params.webHookUrl,
                    createdAt = now,
                    validUntil = null,
                    saveCard = false,
                    walletId = null,
                )
            state.add(invoice)
            val payment =
                CustomerPayment
                    .builder()
                    .maskedPan(card.maskedPan)
                    .country(card.country)
                    .build()
            state.beginPayment(invoice, payment, paymentMethod = "wallet")
            val outcome = state.nextOutcome()
            when (outcome.kind) {
                WalletPaymentOutcome.Kind.SUCCESS -> state.completePayment(invoice, payment)
                WalletPaymentOutcome.Kind.DECLINED -> state.completePayment(invoice, declined(outcome))
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
        if (threeDs) answer.put("tdsUrl", "${baseUrl()}/tds/${invoice.invoiceId}")
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

    private fun wallet(call: ApiCall): HttpResult {
        val walletId = requiredQuery(call, "walletId")
        return state.locked {
            val answer = FakeJson.obj()
            val list = answer.putArray("wallet")
            state.cards(walletId).forEach { card ->
                val item = list.addObject().put("cardToken", card.cardToken).put("maskedPan", card.maskedPan)
                card.country?.let { item.put("country", it) }
            }
            HttpResult.json(answer)
        }
    }

    private fun deleteCard(call: ApiCall): HttpResult {
        val cardToken = requiredQuery(call, "cardToken")
        return state.locked {
            if (!state.removeCard(cardToken)) throw ApiError(400, "TOKEN_NOT_FOUND", "card token not found")
            HttpResult.json(FakeJson.obj())
        }
    }

    private fun statement(call: ApiCall): HttpResult {
        val from = epochQuery(call, "from") ?: throw ApiError.badRequest("empty 'from'")
        val to = epochQuery(call, "to")
        return state.locked {
            // `to` is in whole seconds: include the whole last second.
            val until = to?.plusMillis(999) ?: state.now()
            val answer = FakeJson.obj()
            val list = answer.putArray("list")
            state
                .all()
                .filter { invoice -> invoice.paidAt?.let { !it.isBefore(from) && !it.isAfter(until) } ?: false }
                .sortedByDescending { it.paidAt }
                .forEach { invoice -> invoice.statementJson()?.let { list.add(it) } }
            HttpResult.json(answer)
        }
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
