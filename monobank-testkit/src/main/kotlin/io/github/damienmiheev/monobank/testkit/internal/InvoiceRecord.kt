package io.github.damienmiheev.monobank.testkit.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.damienmiheev.monobank.testkit.FakeInvoice
import io.github.damienmiheev.monobank.testkit.FakeRefund
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
)

/** A saved card in a wallet. */
internal class CardRecord(
    val cardToken: String,
    val walletId: String,
    val maskedPan: String,
    val country: String?,
)

/** Order details taken from `merchantPaymInfo`. */
internal class OrderInfo(
    val reference: String?,
    val destination: String?,
    val metadata: JsonNode?,
)

/** Everything the fake knows about one invoice. Guarded by the state's lock. */
internal class InvoiceRecord(
    val invoiceId: String,
    val amount: Long,
    val ccy: Int,
    val paymentType: String,
    val viaWallet: Boolean,
    val order: OrderInfo,
    val webHookUrl: String?,
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
    val cancels: MutableList<CancelRecord> = mutableListOf()

    /** Moves [modifiedAt] to [now], always strictly forward (monobank's `modifiedDate` only increases). */
    fun touch(now: Instant) {
        modifiedAt = if (now.isAfter(modifiedAt)) now else modifiedAt.plusMillis(1)
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
        val shown = STATEMENT_STATUS[status] ?: return null
        val node =
            FakeJson
                .obj()
                .put("invoiceId", invoiceId)
                .put("status", shown)
                .put("maskedPan", card?.maskedPan ?: "")
                .put("date", FakeJson.date(paid))
                .put("paymentScheme", "full")
                .put("amount", amount)
        if (status == Status.SUCCESS || status == Status.REVERSED) node.put("profitAmount", amount - (fee ?: 0))
        node.put("ccy", ccy)
        card?.takeIf { status != Status.FAILURE }?.let { node.put("approvalCode", it.approvalCode).put("rrn", it.rrn) }
        order.reference?.let { node.put("reference", it) }
        order.destination?.let { node.put("destination", it) }
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
        order.metadata?.let { node.set<JsonNode>("metadata", it.deepCopy()) }
        return node
    }

    fun snapshot(): FakeInvoice =
        FakeInvoice(
            invoiceId = invoiceId,
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

        /** Statement statuses: monobank lists only paid or attempted payments. */
        private val STATEMENT_STATUS =
            mapOf(
                Status.PROCESSING to Status.PROCESSING,
                Status.HOLD to Status.HOLD,
                Status.SUCCESS to Status.SUCCESS,
                Status.FAILURE to Status.FAILURE,
                Status.REVERSED to Status.SUCCESS,
            )
    }
}

/** Random identifiers in monobank's formats. */
internal object Ids {
    private val random = SecureRandom()
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val HEX = "0123456789abcdef"
    private val DAY = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC)

    private fun pick(
        alphabet: String,
        length: Int,
    ): String = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    /** Like `2210012MPLYwJjVUzchj`: date, then random characters. */
    fun invoiceId(now: Instant): String = DAY.format(now) + pick(ALNUM, 14)

    /** Like `67XZtXdR4NpKU3`. */
    fun cardToken(): String = pick(ALNUM, 14)

    /** Like `c1376a611e17b059aeaf96b73258da9c`. */
    fun walletId(): String = pick(HEX, 32)

    fun digits(length: Int): String = pick("0123456789", length)
}
