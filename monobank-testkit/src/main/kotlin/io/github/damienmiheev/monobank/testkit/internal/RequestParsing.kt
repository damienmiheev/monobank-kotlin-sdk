package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Duration

/**
 * The merchant's rules for `merchantPaymInfo.basketOrder`.
 *
 * @property validateTotal The basket must add up to the amount (the fake's own rule).
 * @property basketRequired Fiscalization or split payments: a non-empty basket is mandatory.
 * @property splitRequired Split payments: every basket item needs a `splitReceiverId`.
 */
internal class BasketRules(
    val validateTotal: Boolean,
    val basketRequired: Boolean,
    val splitRequired: Boolean,
) {
    fun withoutRequirements(): BasketRules = BasketRules(validateTotal, basketRequired = false, splitRequired = false)
}

/** A validated `invoice/create` request. */
internal class CreateParams(
    val amount: Long,
    val ccy: Int,
    val paymentType: String,
    val order: OrderInfo,
    val webHookUrl: String?,
    val redirectUrl: String?,
    val validity: Duration,
    val saveCard: Boolean,
    val walletId: String?,
    val withAppUrl: Boolean,
)

/** A validated `wallet/payment` request. */
internal class WalletPaymentParams(
    val cardToken: String,
    val amount: Long,
    val ccy: Int,
    val paymentType: String,
    val order: OrderInfo,
    val webHookUrl: String?,
    val redirectUrl: String?,
)

/** Validates request bodies the way monobank documents them, answering 400 like it does. */
internal object RequestParsing {
    private const val UAH = 980
    private val ACQUIRING_CURRENCIES = setOf(980L, 840L, 978L)
    private const val TEXT_MAX = 280
    private val DEFAULT_VALIDITY: Duration = Duration.ofHours(24)
    private val MAX_VALIDITY: Duration = Duration.ofDays(30)
    private val INVOICE_TYPES = setOf("debit", "hold", "verification")
    private val WALLET_TYPES = setOf("debit", "hold")
    private val INITIATION_KINDS = setOf("merchant", "client")

    fun create(
        json: ObjectNode,
        rules: BasketRules,
    ): CreateParams {
        val amount = Fields.requiredLong(json, "amount")
        val ccy = currency(json, required = false)
        val paymentType = paymentType(json, INVOICE_TYPES, ccy)
        val saveCardData = Fields.obj(json, "saveCardData")
        val saveCard =
            saveCardData?.let {
                Fields.boolean(it, "saveCard")
                    ?: throw ApiError.badRequest("empty 'saveCard'")
            }
        if (paymentType == "verification") {
            badRequestUnless(amount == 0L) { "invalid 'amount': must be 0 for verification" }
            badRequestUnless(saveCard == true) { "verification requires saveCardData.saveCard = true" }
        } else {
            badRequestUnless(amount > 0) { "invalid 'amount'" }
        }
        return CreateParams(
            amount = amount,
            ccy = ccy,
            paymentType = paymentType,
            // A verification invoice charges nothing, so the fake does not require a basket for it.
            order = order(json, amount, if (paymentType == "verification") rules.withoutRequirements() else rules),
            webHookUrl = webHookUrl(json),
            redirectUrl = Fields.string(json, "redirectUrl")?.takeIf { it.isNotBlank() },
            validity = validity(json),
            saveCard = saveCard == true,
            walletId = saveCardData?.let { Fields.string(it, "walletId") }?.takeIf { it.isNotBlank() },
            withAppUrl = Fields.boolean(json, "withAppUrl") ?: false,
        )
    }

    fun walletPayment(
        json: ObjectNode,
        rules: BasketRules,
    ): WalletPaymentParams {
        badRequestUnless(!json.hasNonNull("aToken") && !json.hasNonNull("gToken")) {
            "Apple Pay and Google Pay tokens are not supported by the fake"
        }
        val cardToken = Fields.requiredString(json, "cardToken")
        val amount = Fields.requiredLong(json, "amount")
        badRequestUnless(amount > 0) { "invalid 'amount'" }
        val ccy = currency(json, required = true)
        val initiationKind = Fields.requiredString(json, "initiationKind")
        badRequestUnless(initiationKind in INITIATION_KINDS) { "invalid 'initiationKind'" }
        return WalletPaymentParams(
            cardToken = cardToken,
            amount = amount,
            ccy = ccy,
            // monobank documents no currency limit for wallet holds; the fake accepts them (unverified).
            paymentType = paymentType(json, WALLET_TYPES, ccy, uahOnlyHold = false),
            order = order(json, amount, rules),
            webHookUrl = webHookUrl(json),
            redirectUrl = Fields.string(json, "redirectUrl")?.takeIf { it.isNotBlank() },
        )
    }

    private fun paymentType(
        json: JsonNode,
        allowed: Set<String>,
        ccy: Int,
        uahOnlyHold: Boolean = true,
    ): String {
        val paymentType = Fields.string(json, "paymentType") ?: "debit"
        badRequestUnless(paymentType in allowed) { "invalid 'paymentType'" }
        // Verified on monobank's test merchant for invoice/create (EUR hold).
        if (uahOnlyHold && paymentType == "hold" && ccy != UAH) {
            throw ApiError(400, "1001", "hold payments are allowed only for UAH")
        }
        return paymentType
    }

    /** Invoice lifetime: 24 h by default, silently clamped to 30 days. */
    private fun validity(json: JsonNode): Duration {
        val seconds = Fields.long(json, "validity") ?: return DEFAULT_VALIDITY
        badRequestUnless(seconds > 0) { "invalid 'validity'" }
        return minOf(Duration.ofSeconds(seconds), MAX_VALIDITY)
    }

    private fun currency(
        json: JsonNode,
        required: Boolean,
    ): Int {
        val ccy = Fields.long(json, "ccy") ?: if (required) throw ApiError.badRequest("empty 'ccy'") else UAH.toLong()
        // Acquiring accepts only UAH, USD and EUR (verified on monobank's test merchant: PLN, GBP,
        // CHF, CZK, HUF and RON get 400 1001 "invalid 'ccy'").
        if (ccy !in ACQUIRING_CURRENCIES) throw ApiError(400, "1001", "invalid 'ccy'")
        return ccy.toInt()
    }

    private fun webHookUrl(json: JsonNode): String? {
        val url = Fields.string(json, "webHookUrl")?.takeIf { it.isNotBlank() } ?: return null
        val uri =
            try {
                URI(url)
            } catch (
                @Suppress("SwallowedException") e: java.net.URISyntaxException,
            ) {
                throw ApiError.badRequest("invalid 'webHookUrl'")
            }
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host == null) {
            throw ApiError.badRequest("invalid 'webHookUrl'")
        }
        return url
    }

    /** `merchantPaymInfo`: text limits, required basket fields and (optionally) basket total = amount. */
    private fun order(
        json: JsonNode,
        amount: Long,
        rules: BasketRules,
    ): OrderInfo {
        val info = Fields.obj(json, "merchantPaymInfo")
        val basket = info?.get("basketOrder")?.takeIf { !it.isNull }
        paymInfoUnless(!rules.basketRequired || (basket != null && !(basket.isArray && basket.isEmpty))) {
            BASKET_REQUIRED
        }
        if (info == null) return OrderInfo(null, null, null)
        val reference = paymInfoString(info, "reference")
        val destination = paymInfoString(info, "destination")
        val comment = paymInfoString(info, "comment")
        paymInfoUnless((destination?.length ?: 0) <= TEXT_MAX && (comment?.length ?: 0) <= TEXT_MAX) {
            "destination and comment are limited to $TEXT_MAX characters"
        }
        val lines = if (basket != null) basket(info, basket, amount, rules) else emptyList()
        val metadata = info.get("metadata")?.takeIf { it.isObject }
        return OrderInfo(reference, destination, metadata, lines)
    }

    private fun basket(
        info: JsonNode,
        basket: JsonNode,
        amount: Long,
        rules: BasketRules,
    ): List<BasketLine> {
        paymInfoUnless(basket.isArray) { "invalid 'basketOrder'" }
        val totals = basket.map { basketItemTotal(it) }
        paymInfoUnless(!rules.splitRequired || basket.all { isText(it.get("splitReceiverId")) }) {
            "'splitReceiverId' is required"
        }
        val hasDiscounts = info.hasNonNull("discounts") || basket.any { it.hasNonNull("discounts") }
        if (rules.validateTotal && !hasDiscounts) {
            if (rules.basketRequired) {
                // Verified live for wallet/payment on a fiscal and split merchant (a EUR payment with a UAH
                // basket): no rounding, the basket must add up to the amount exactly.
                val exact = totals.fold(BigDecimal.ZERO, BigDecimal::add)
                paymInfoUnless(exact.compareTo(BigDecimal.valueOf(amount)) == 0) { DISCOUNT_MISMATCH }
            } else {
                val total = totals.sumOf { it.setScale(0, RoundingMode.HALF_UP).toLong() }
                paymInfoUnless(total == amount) { "basketOrder total $total does not match amount $amount" }
            }
        }
        return basket.map { line(it) }
    }

    /** `items` of finalize and cancel; malformed items answer 400 `BAD_REQUEST`. */
    fun items(json: JsonNode): List<BasketLine> {
        val items = json.get("items")?.takeIf { !it.isNull } ?: return emptyList()
        badRequestUnless(items.isArray) { "invalid 'items'" }
        return items.map { item ->
            badRequestUnless(
                item.isObject && isText(item.get("name")) && isText(item.get("code")),
            ) { "invalid 'items'" }
            val qty = item.get("qty")
            badRequestUnless(qty != null && qty.isNumber && qty.decimalValue().signum() > 0) { "invalid 'items' qty" }
            val sum = Fields.long(item, "sum")
            badRequestUnless(sum != null && sum >= 0) { "invalid 'items' sum" }
            line(item)
        }
    }

    /**
     * monobank's answer (verified live on a fiscal and split merchant) when qty × sum of a `wallet/payment`
     * basket or of refund `items` is not exactly the amount.
     */
    const val DISCOUNT_MISMATCH =
        "total amount of ('sum' * 'qty')-calculated 'discount' does not equal 'amount' field from request"

    /** monobank's answer (verified live) when a fiscalization or split merchant sends no basket. */
    const val BASKET_REQUIRED =
        "'basketOrder' cannot be empty, it is mandatory for fiscalization and split payments"

    private fun paymInfoString(
        info: JsonNode,
        name: String,
    ): String? =
        try {
            Fields.string(info, name)
        } catch (
            @Suppress("SwallowedException") e: ApiError,
        ) {
            throw invalidPaymInfo("invalid '$name'")
        }

    /** qty × sum of a basket line, not rounded (or its `total` when given). */
    private fun basketItemTotal(item: JsonNode): BigDecimal {
        paymInfoUnless(item.isObject) { "invalid 'basketOrder' item" }
        paymInfoUnless(isText(item.get("name"))) { "empty basketOrder 'name'" }
        paymInfoUnless(isText(item.get("code"))) { "empty basketOrder 'code'" }
        val qty = item.get("qty")
        paymInfoUnless(qty != null && qty.isNumber && qty.decimalValue().signum() > 0) { "invalid basketOrder 'qty'" }
        val sum =
            try {
                Fields.requiredLong(item, "sum")
            } catch (
                @Suppress("SwallowedException") e: ApiError,
            ) {
                throw invalidPaymInfo("invalid basketOrder 'sum'")
            }
        paymInfoUnless(sum >= 0) { "invalid basketOrder 'sum'" }
        val total = item.get("total")?.takeIf { it.isNumber }?.asLong()
        return total?.let { BigDecimal.valueOf(it) } ?: BigDecimal.valueOf(sum).multiply(qty.decimalValue())
    }
}

private fun badRequestUnless(
    condition: Boolean,
    text: () -> String,
) {
    if (!condition) throw ApiError.badRequest(text())
}

private fun paymInfoUnless(
    condition: Boolean,
    text: () -> String,
) {
    if (!condition) throw invalidPaymInfo(text())
}

private fun isText(node: JsonNode?): Boolean = node != null && node.isTextual && node.asText().isNotBlank()

private fun invalidPaymInfo(text: String) = ApiError(400, "INVALID_MERCHANT_PAYM_INFO", text)

private fun line(item: JsonNode): BasketLine =
    BasketLine(
        name = item.path("name").asText(),
        code = item.path("code").asText(),
        sum = item.path("sum").asLong(),
        qty = item.path("qty").decimalValue(),
        splitReceiverId = item.get("splitReceiverId")?.takeIf { it.isTextual }?.asText(),
    )
