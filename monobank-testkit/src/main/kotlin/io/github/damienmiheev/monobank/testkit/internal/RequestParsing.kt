package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Duration

/** A validated `invoice/create` request. */
internal class CreateParams(
    val amount: Long,
    val ccy: Int,
    val paymentType: String,
    val order: OrderInfo,
    val webHookUrl: String?,
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
)

/** Validates request bodies the way monobank documents them, answering 400 like it does. */
internal object RequestParsing {
    private const val UAH = 980
    private const val TEXT_MAX = 280
    private val DEFAULT_VALIDITY: Duration = Duration.ofHours(24)
    private val MAX_VALIDITY: Duration = Duration.ofDays(30)
    private val INVOICE_TYPES = setOf("debit", "hold", "verification")
    private val WALLET_TYPES = setOf("debit", "hold")
    private val INITIATION_KINDS = setOf("merchant", "client")

    fun create(
        json: ObjectNode,
        validateBasketTotal: Boolean,
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
            order = order(json, amount, validateBasketTotal),
            webHookUrl = webHookUrl(json),
            validity = validity(json),
            saveCard = saveCard == true,
            walletId = saveCardData?.let { Fields.string(it, "walletId") }?.takeIf { it.isNotBlank() },
            withAppUrl = Fields.boolean(json, "withAppUrl") ?: false,
        )
    }

    fun walletPayment(
        json: ObjectNode,
        validateBasketTotal: Boolean,
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
            paymentType = paymentType(json, WALLET_TYPES, ccy),
            order = order(json, amount, validateBasketTotal),
            webHookUrl = webHookUrl(json),
        )
    }

    private fun paymentType(
        json: JsonNode,
        allowed: Set<String>,
        ccy: Int,
    ): String {
        val paymentType = Fields.string(json, "paymentType") ?: "debit"
        badRequestUnless(paymentType in allowed) { "invalid 'paymentType'" }
        badRequestUnless(paymentType != "hold" || ccy == UAH) { "hold is available only for ccy 980" }
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
        if (ccy !in 1..999) throw ApiError.badRequest("invalid 'ccy'")
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
        validateBasketTotal: Boolean,
    ): OrderInfo {
        val info = Fields.obj(json, "merchantPaymInfo") ?: return OrderInfo(null, null, null)
        val reference = paymInfoString(info, "reference")
        val destination = paymInfoString(info, "destination")
        val comment = paymInfoString(info, "comment")
        if ((destination?.length ?: 0) > TEXT_MAX || (comment?.length ?: 0) > TEXT_MAX) {
            throw invalidPaymInfo("destination and comment are limited to $TEXT_MAX characters")
        }
        val basket = info.get("basketOrder")
        if (basket != null && !basket.isNull) {
            if (!basket.isArray) throw invalidPaymInfo("invalid 'basketOrder'")
            val total = basket.sumOf { basketItemTotal(it) }
            val hasDiscounts = info.hasNonNull("discounts") || basket.any { it.hasNonNull("discounts") }
            if (validateBasketTotal && !hasDiscounts && total != amount) {
                throw invalidPaymInfo("basketOrder total $total does not match amount $amount")
            }
        }
        val metadata = info.get("metadata")?.takeIf { it.isObject }
        return OrderInfo(reference, destination, metadata)
    }

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

    private fun basketItemTotal(item: JsonNode): Long {
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
        return total ?: BigDecimal
            .valueOf(sum)
            .multiply(qty.decimalValue())
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
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
