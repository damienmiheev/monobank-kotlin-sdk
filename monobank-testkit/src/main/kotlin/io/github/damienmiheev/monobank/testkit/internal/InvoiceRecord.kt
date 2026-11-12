package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.damienmiheev.monobank.testkit.FakeInvoice
import io.github.damienmiheev.monobank.testkit.FakeRefund
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Random

/** Invoice statuses as monobank sends them. */
internal object Status {
    const val CREATED = "created"
    const val PROCESSING = "processing"
    const val HOLD = "hold"
    const val SUCCESS = "success"
    const val FAILURE = "failure"
    const val REVERSED = "reversed"
    const val EXPIRED = "expired"
}

/** Card data of a payment (`paymentInfo`). */
internal class CardInfo(
    val maskedPan: String,
    val paymentSystem: String,
    val paymentMethod: String,
    val bank: String?,
    val country: String?,
    val approvalCode: String,
    val rrn: String,
    val tranId: String,
)

/** A refund or hold release (`cancelList` entry). */
internal class CancelRecord(
    val extRef: String?,
    val amount: Long,
    val ccy: Int,
    val createdAt: Instant,
    var modifiedAt: Instant,
    var status: String,
    val approvalCode: String,
    val rrn: String,
    /** Basket qty this refund cancels, by index of the invoice's [InvoiceRecord.basket] line. */
    val lineQty: Map<Int, BigDecimal> = emptyMap(),
)

/** A saved card in a wallet. */
internal class CardRecord(
    val cardToken: String,
    val walletId: String,
    val maskedPan: String,
    val country: String?,
    val paymentSystem: String,
)

/** Order details taken from `merchantPaymInfo`. */
internal class OrderInfo(
    val reference: String?,
    val destination: String?,
    val metadata: JsonNode?,
    val basket: List<BasketLine> = emptyList(),
)

/** A basket line (`basketOrder[]`) or a finalize/refund item (`items[]`); [sum] is the unit price. */
internal class BasketLine(
    val name: String,
    val code: String,
    val sum: Long,
    val qty: BigDecimal,
    val splitReceiverId: String?,
) {
    /** monobank's item key in error texts (verified live): `<name>_<code>_<sum>_<splitReceiverId>`. */
    val key: String get() = listOfNotNull(name, code, sum.toString(), splitReceiverId).joinToString("_")

    fun sameItem(other: BasketLine): Boolean = sameGoods(other) && splitReceiverId == other.splitReceiverId

    /** Same name, code and unit price; the receiver may differ. */
    fun sameGoods(other: BasketLine): Boolean = name == other.name && code == other.code && sum == other.sum

    /** qty × sum in minor units, rounded half up. */
    fun total(): Long = exactTotal().setScale(0, RoundingMode.HALF_UP).toLong()

    /** qty × sum in minor units, not rounded. */
    fun exactTotal(): BigDecimal = qty.multiply(BigDecimal.valueOf(sum))
}

/** Everything the fake knows about one invoice. Guarded by the state's lock. */
internal class InvoiceRecord(
    val invoiceId: String,
    var amount: Long,
    var ccy: Int,
    val paymentType: String,
    val viaWallet: Boolean,
    val order: OrderInfo,
    val webHookUrl: String?,
    val redirectUrl: String?,
    val createdAt: Instant,
    val validUntil: Instant?,
    val saveCard: Boolean,
    val walletId: String?,
) {
    var status: String = Status.CREATED
    var modifiedAt: Instant = createdAt
    var paidAt: Instant? = null
    var finalAmount: Long? = null
    var fee: Long? = null
    var errCode: String? = null
    var failureReason: String? = null
    var card: CardInfo? = null
    var cardToken: String? = null
    var walletStatus: String? = null
    var holdUntil: Instant? = null

    /** The status before the invoice became `reversed` (`hold` for a released or lapsed hold). */
    var reversedFrom: String? = null

    /** The amount and currency of the request; [amount] and [ccy] become UAH when a USD/EUR payment is made. */
    val originalAmount: Long = amount
    val originalCcy: Int = ccy

    /** `true` once a USD/EUR payment was converted to UAH. */
    val converted: Boolean get() = ccy != originalCcy

    /** monobank's bug (seen live): `invoice/status` of this invoice answers 500 from now on. */
    var statusBroken: Boolean = false

    /** A partial capture is settling (`processing` after `invoice/finalize`). */
    var finalizing: Boolean = false

    /** When an automatic settlement of [finalizing] is due. */
    var settleAt: Instant? = null

    /** The basket the money stands for: the created one, replaced by the items of a finalize. */
    var basket: List<BasketLine> = order.basket
    val cancels: MutableList<CancelRecord> = mutableListOf()

    /** Moves [modifiedAt] to [now], never backwards (equal dates happen, also at monobank). */
    fun touch(now: Instant) {
        modifiedAt = maxOf(now, modifiedAt)
    }

    /** Amount that can still be refunded: paid amount minus completed and pending refunds. */
    fun refundable(): Long = (finalAmount ?: 0) - cancels.filter { it.status == Status.PROCESSING }.sumOf { it.amount }

    /** The body of `GET /api/merchant/invoice/status` and of webhooks. */
    fun statusJson(): ObjectNode {
        val node = FakeJson.obj().put("invoiceId", invoiceId).put("status", status)
        failureReason?.let { node.put("failureReason", it) }
        errCode?.let { node.put("errCode", it) }
        node.put("amount", amount).put("ccy", ccy)
        finalAmount?.let { node.put("finalAmount", it) }
        node.put("createdDate", FakeJson.date(createdAt)).put("modifiedDate", FakeJson.date(modifiedAt))
        order.reference?.let { node.put("reference", it) }
        order.destination?.let { node.put("destination", it) }
        if (cancels.isNotEmpty()) {
            val list = node.putArray("cancelList")
            cancels.forEach { list.add(cancelJson(it)) }
        }
        card?.let { node.set<JsonNode>("paymentInfo", paymentInfoJson(it)) }
        // Not in the spec, but sent live for a saved-card payment.
        if (card?.paymentMethod == "wallet") node.put("payMethod", "wallet")
        walletDataJson()?.let { node.set<JsonNode>("walletData", it) }
        return node
    }

    /** `walletData` of a card-saving invoice, once the customer has entered a card. */
    private fun walletDataJson(): ObjectNode? {
        val token = cardToken ?: return null
        val tokenStatus = walletStatus ?: return null
        if (!saveCard) return null
        return FakeJson
            .obj()
            .put("cardToken", token)
            .put("walletId", walletId)
            .put("status", tokenStatus)
    }

    private fun cancelJson(cancel: CancelRecord): ObjectNode {
        val item =
            FakeJson
                .obj()
                .put("status", cancel.status)
                .put("amount", cancel.amount)
                .put("ccy", cancel.ccy)
                .put("createdDate", FakeJson.date(cancel.createdAt))
                .put("modifiedDate", FakeJson.date(cancel.modifiedAt))
                .put("approvalCode", cancel.approvalCode)
                .put("rrn", cancel.rrn)
        cancel.extRef?.let { item.put("extRef", it) }
        return item
    }

    private fun paymentInfoJson(card: CardInfo): ObjectNode {
        val info = FakeJson.obj().put("maskedPan", card.maskedPan)
        if (status != Status.FAILURE) info.put("approvalCode", card.approvalCode).put("rrn", card.rrn)
        info.put("tranId", card.tranId).put("terminal", TERMINAL)
        card.bank?.let { info.put("bank", it) }
        info.put("paymentSystem", card.paymentSystem).put("paymentMethod", card.paymentMethod)
        fee?.let { info.put("fee", it) }
        card.country?.let { info.put("country", it) }
        return info
    }

    /** The entry of `GET /api/merchant/statement`, or `null` when monobank would not list it. */
    fun statementJson(): ObjectNode? {
        val paid = paidAt ?: return null
        // monobank's statement has no `reversed`: a reversed payment keeps its last status before reversal.
        val shown = if (status == Status.REVERSED) reversedFrom ?: Status.SUCCESS else status
        if (shown !in STATEMENT_STATUSES) return null
        val node =
            FakeJson
                .obj()
                .put("invoiceId", invoiceId)
                .put("status", shown)
                .put("maskedPan", card?.maskedPan ?: "")
                .put("date", FakeJson.date(paid))
                .put("paymentScheme", "full")
                .put("amount", amount)
        if (shown == Status.SUCCESS) node.put("profitAmount", amount - (fee ?: 0))
        node.put("ccy", ccy)
        card?.takeIf { status != Status.FAILURE }?.let { node.put("approvalCode", it.approvalCode).put("rrn", it.rrn) }
        order.reference?.let { node.put("reference", it) }
        order.destination?.let { node.put("destination", it) }
        statementRefunds(node)
        order.metadata?.let { node.set<JsonNode>("metadata", it.deepCopy()) }
        return node
    }

    private fun statementRefunds(node: ObjectNode) {
        val refunds = cancels.filter { it.status == Status.SUCCESS }
        if (refunds.isNotEmpty()) {
            val list = node.putArray("cancelList")
            refunds.forEach {
                list
                    .addObject()
                    .put("amount", it.amount)
                    .put("ccy", it.ccy)
                    .put("date", FakeJson.date(it.modifiedAt))
                    .put("approvalCode", it.approvalCode)
                    .put("rrn", it.rrn)
                    .put("maskedPan", card?.maskedPan ?: "")
            }
        }
    }

    fun snapshot(merchantId: String): FakeInvoice =
        FakeInvoice(
            invoiceId = invoiceId,
            merchantId = merchantId,
            status = status,
            amount = amount,
            ccy = ccy,
            finalAmount = finalAmount,
            paymentType = paymentType,
            viaWallet = viaWallet,
            reference = order.reference,
            webHookUrl = webHookUrl,
            walletId = walletId,
            cardToken = cardToken,
            refunds = cancels.map { FakeRefund(it.extRef, it.amount, it.status) },
            createdDate = createdAt,
            modifiedDate = modifiedAt,
            validUntil = validUntil,
            holdUntil = holdUntil,
            statusJson = statusJson().toString(),
        )

    companion object {
        const val TERMINAL = "MI001088"

        /** Statement statuses (monobank's enum): only paid or attempted payments are listed. */
        private val STATEMENT_STATUSES = setOf(Status.PROCESSING, Status.HOLD, Status.SUCCESS, Status.FAILURE)
    }
}

/** Random identifiers in monobank's formats, from [random] (seedable for reproducible ids). */
internal class Ids(
    private val random: Random,
) {
    private fun pick(
        alphabet: String,
        length: Int,
    ): String =
        synchronized(random) { buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } } }

    /** Like `2210012MPLYwJjVUzchj`: date, then random characters. */
    fun invoiceId(now: Instant): String = DAY.format(now) + pick(ALNUM, 14)

    /** Like `67XZtXdR4NpKU3`. */
    fun cardToken(): String = pick(ALNUM, 14)

    /** Like `c1376a611e17b059aeaf96b73258da9c`. */
    fun walletId(): String = pick(HEX, 32)

    fun digits(length: Int): String = pick("0123456789", length)

    private companion object {
        private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val HEX = "0123456789abcdef"
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC)
    }
}
