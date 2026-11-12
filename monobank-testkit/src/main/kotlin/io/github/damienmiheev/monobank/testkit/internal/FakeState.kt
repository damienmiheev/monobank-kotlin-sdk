package io.github.damienmiheev.monobank.testkit.internal

import io.github.damienmiheev.monobank.testkit.CardSaving
import io.github.damienmiheev.monobank.testkit.CustomerPayment
import io.github.damienmiheev.monobank.testkit.MonobankWebhookSigner
import io.github.damienmiheev.monobank.testkit.RefundSettlement
import io.github.damienmiheev.monobank.testkit.SplitReceiver
import io.github.damienmiheev.monobank.testkit.TestClock
import io.github.damienmiheev.monobank.testkit.WalletPaymentOutcome
import io.github.damienmiheev.monobank.testkit.WebhookKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Settings shared by every merchant of a fake. */
internal class FakeSettings(
    val clock: TestClock,
    val feePercent: BigDecimal,
    val sendProcessingWebhooks: Boolean,
    val validateBasketTotal: Boolean,
    @Volatile var refundSettlement: RefundSettlement,
    /** How long a partially finalized invoice stays `processing`; `null`: until settled by hand. */
    val finalizeSettlementDelay: Duration?,
)

/** The merchant behind a set of tokens. */
internal class MerchantInfo(
    val merchantId: String,
    val merchantName: String,
    val edrpou: String,
    val fiscalization: Boolean,
    val splitPayments: Boolean,
    val splitReceivers: List<SplitReceiver>,
) {
    /** Fiscal receipts or split payments need the basket, and its items for partial amounts. */
    val needsBasket: Boolean get() = fiscalization || splitPayments
}

/**
 * All merchants of one fake, under one lock. Every state change goes through [locked], which first
 * applies the expirations and hold lapses that are due.
 */
internal class Bank(
    val settings: FakeSettings,
    val ids: Ids,
    val webhookSink: (WebhookEvent) -> Unit,
    /** Asks for [locked] to run again after a real-time delay, so due changes happen without a request. */
    val wakeUp: (Duration) -> Unit,
) {
    private val lock = ReentrantLock()
    val merchants: MutableList<MerchantState> = ArrayList()

    fun <T> locked(block: () -> T): T =
        lock.withLock {
            val now = now()
            merchants.forEach { it.sweep(now) }
            block()
        }

    /** The fake clock's time, in microseconds: the finest precision of the dates it reports. */
    fun now(): Instant = settings.clock.instant().truncatedTo(ChronoUnit.MICROS)

    fun reset() = lock.withLock { merchants.forEach { it.reset() } }

    /** The merchant owning [invoiceId], for the control API. */
    fun owner(invoiceId: String): MerchantState =
        requireNotNull(merchants.firstOrNull { it.has(invoiceId) }) { "the fake has no invoice $invoiceId" }
}

/**
 * One merchant account: invoices, refunds, wallets and the status machine. Every method expects the
 * caller to hold the bank's lock ([Bank.locked]); webhooks are queued in the order the changes happen.
 */
internal class MerchantState(
    private val bank: Bank,
    val info: MerchantInfo,
    val tokens: Set<String>,
    val signer: MonobankWebhookSigner,
) {
    private val invoices = LinkedHashMap<String, InvoiceRecord>()
    private val wallets = LinkedHashMap<String, MutableList<CardRecord>>()
    private val walletOutcomes = ArrayDeque<WalletPaymentOutcome>()

    val settings: FakeSettings get() = bank.settings

    val basketRules: BasketRules
        get() = BasketRules(settings.validateBasketTotal, info.needsBasket, info.splitPayments)
    val ids: Ids get() = bank.ids

    fun now(): Instant = bank.now()

    fun reset() {
        invoices.clear()
        wallets.clear()
        walletOutcomes.clear()
    }

    fun has(invoiceId: String): Boolean = invoiceId in invoices

    fun find(invoiceId: String): InvoiceRecord = invoices[invoiceId] ?: throw ApiError.notFound("invoice not found")

    /** For the control API: a missing invoice is a test bug, not an API error. */
    fun require(invoiceId: String): InvoiceRecord =
        requireNotNull(invoices[invoiceId]) { "merchant ${info.merchantId} has no invoice $invoiceId" }

    fun add(invoice: InvoiceRecord) {
        invoices[invoice.invoiceId] = invoice
    }

    fun all(): List<InvoiceRecord> = invoices.values.toList()

    fun cards(walletId: String): List<CardRecord> = wallets[walletId].orEmpty().toList()

    fun card(cardToken: String): CardRecord? = wallets.values.flatten().firstOrNull { it.cardToken == cardToken }

    fun removeCard(cardToken: String): Boolean =
        wallets.values.any { cards ->
            cards.removeIf {
                it.cardToken ==
                    cardToken
            }
        }

    fun addCard(
        walletId: String,
        maskedPan: String,
        country: String?,
        paymentSystem: String,
    ): CardRecord {
        val cards = wallets.getOrPut(walletId) { mutableListOf() }
        cards.firstOrNull { it.maskedPan == maskedPan }?.let { return it }
        return CardRecord(ids.cardToken(), walletId, maskedPan, country, paymentSystem).also { cards.add(it) }
    }

    fun enqueueOutcome(outcome: WalletPaymentOutcome) {
        walletOutcomes.addLast(outcome)
    }

    fun nextOutcome(): WalletPaymentOutcome = walletOutcomes.removeFirstOrNull() ?: WalletPaymentOutcome.success()

    /** Expires unpaid invoices whose validity passed, and lapses holds older than 9 days. */
    fun sweep(now: Instant) {
        for (invoice in invoices.values) {
            val validUntil = invoice.validUntil
            val holdUntil = invoice.holdUntil
            if (invoice.status == Status.CREATED && validUntil != null && !now.isBefore(validUntil)) {
                invoice.status = Status.EXPIRED
                invoice.touch(validUntil)
            } else if (invoice.status == Status.HOLD && holdUntil != null && !now.isBefore(holdUntil)) {
                reverse(invoice)
                invoice.touch(holdUntil)
                emit(invoice)
            } else {
                val settleAt = invoice.settleAt
                if (invoice.status == Status.PROCESSING && settleAt != null && !now.isBefore(settleAt)) {
                    settleFinalize(invoice)
                }
            }
        }
    }

    /** Queues a webhook with the invoice's current state. monobank sends none for `expired`. */
    private fun emit(
        invoice: InvoiceRecord,
        kind: WebhookKind = WebhookKind.PAYMENT,
    ) {
        val url = invoice.webHookUrl ?: return
        if (invoice.status == Status.EXPIRED) return
        val body = FakeJson.bytes(invoice.statusJson())
        val walletStatus = if (invoice.saveCard) invoice.walletStatus else null
        bank.webhookSink(
            WebhookEvent(invoice.invoiceId, invoice.status, url, body, kind, walletStatus, info.merchantId, signer),
        )
    }

    /** `created` → `processing`: the customer (or a saved-card charge) started paying. */
    fun beginPayment(
        invoice: InvoiceRecord,
        payment: CustomerPayment,
        paymentMethod: String = payment.paymentMethod,
    ) {
        check(invoice.status == Status.CREATED) { "invoice ${invoice.invoiceId} is ${invoice.status}, not created" }
        val now = now()
        invoice.status = Status.PROCESSING
        invoice.paidAt = now
        invoice.card =
            CardInfo(
                maskedPan = payment.maskedPan,
                paymentSystem = payment.paymentSystem,
                paymentMethod = paymentMethod,
                bank = payment.bank,
                country = payment.country,
                approvalCode = ids.digits(6),
                rrn = ids.digits(12),
                tranId = ids.digits(8),
            )
        if (invoice.saveCard) {
            val walletId = checkNotNull(invoice.walletId)
            invoice.cardToken =
                wallets[walletId]?.firstOrNull { it.maskedPan == payment.maskedPan }?.cardToken ?: ids.cardToken()
            invoice.walletStatus = WALLET_NEW
        }
        invoice.touch(now)
        if (settings.sendProcessingWebhooks) emit(invoice)
    }

    /** `processing` → `success`/`hold` or `failure`, then the card saving result. */
    fun completePayment(
        invoice: InvoiceRecord,
        payment: CustomerPayment,
    ) {
        check(invoice.status == Status.PROCESSING && !invoice.finalizing) {
            "invoice ${invoice.invoiceId} is ${invoice.status}, not a payment in progress"
        }
        val now = now()
        if (!payment.approved) {
            invoice.status = Status.FAILURE
            invoice.errCode = payment.errCode
            invoice.failureReason = payment.failureReason
            invoice.finalAmount = 0
            if (invoice.saveCard) invoice.walletStatus = WALLET_FAILED
            invoice.touch(now)
            emit(invoice)
            return
        }
        invoice.status = if (invoice.paymentType == PAYMENT_HOLD) Status.HOLD else Status.SUCCESS
        invoice.finalAmount = invoice.amount
        invoice.fee = fee(invoice.amount, settings.feePercent)
        if (invoice.status == Status.HOLD) invoice.holdUntil = now.plus(HOLD_PERIOD)
        invoice.touch(now)
        emit(invoice)
        if (invoice.saveCard) saveCard(invoice, payment)
    }

    /**
     * The separate card webhook: `walletData.status` becomes `created` (or `failed`). The invoice
     * itself did not change, so it keeps its status and `modifiedDate`.
     */
    private fun saveCard(
        invoice: InvoiceRecord,
        payment: CustomerPayment,
    ) {
        if (payment.cardSaving == CardSaving.SAVED) {
            val walletId = checkNotNull(invoice.walletId)
            val cards = wallets.getOrPut(walletId) { mutableListOf() }
            val token = checkNotNull(invoice.cardToken)
            if (cards.none { it.cardToken == token }) {
                cards.add(CardRecord(token, walletId, payment.maskedPan, payment.country, payment.paymentSystem))
            }
            invoice.walletStatus = WALLET_CREATED
        } else {
            invoice.walletStatus = WALLET_FAILED
        }
        emit(invoice, WebhookKind.CARD)
    }

    /** `created`/`processing` → `expired`, without a webhook. */
    fun expire(invoice: InvoiceRecord) {
        check(invoice.status == Status.CREATED || (invoice.status == Status.PROCESSING && !invoice.finalizing)) {
            "invoice ${invoice.invoiceId} is ${invoice.status}; only created or processing invoices expire"
        }
        invoice.status = Status.EXPIRED
        invoice.touch(now())
    }

    /** `POST invoice/finalize`: captures [amount] of a hold. */
    fun finalizeHold(
        invoice: InvoiceRecord,
        amount: Long,
        items: List<BasketLine>,
    ) {
        if (invoice.status != Status.HOLD) throw ApiError.badRequest("invoice is not on hold")
        if (invoice.cancels.any { it.status == Status.PROCESSING }) {
            throw ApiError.badRequest("the hold is being released")
        }
        if (amount > invoice.amount) throw ApiError.badRequest("invalid 'amount': more than the hold")
        // Verified live on a merchant with fiscal receipts and split payments.
        if (info.needsBasket && amount < invoice.amount && items.isEmpty()) throw noBasketForPartialAmount()
        if (info.needsBasket && items.isNotEmpty()) {
            matchItems(invoice.basket, items, amount, ItemTexts.FINALIZE)
            invoice.basket = items
        }
        invoice.finalAmount = amount
        invoice.fee = fee(amount, settings.feePercent)
        invoice.holdUntil = null
        val now = now()
        if (amount < invoice.amount) {
            // Verified live: a partial capture stays `processing` (finalAmount already set) before `success`.
            invoice.status = Status.PROCESSING
            invoice.finalizing = true
            val delay = settings.finalizeSettlementDelay
            if (delay != null) {
                invoice.settleAt = now.plus(delay)
                bank.wakeUp(delay)
            }
        } else {
            invoice.status = Status.SUCCESS
        }
        invoice.touch(now)
        emit(invoice)
    }

    /** Completes a partial capture: `processing` → `success`, with a webhook. */
    fun settleFinalize(invoice: InvoiceRecord) {
        check(invoice.status == Status.PROCESSING && invoice.finalizing) {
            "invoice ${invoice.invoiceId} is not waiting for a partial capture to settle"
        }
        invoice.status = Status.SUCCESS
        invoice.finalizing = false
        invoice.settleAt = null
        invoice.touch(now())
        emit(invoice)
    }

    /** `POST invoice/cancel`: refunds a payment or releases a hold. Returns the new refund. */
    fun cancel(
        invoice: InvoiceRecord,
        extRef: String?,
        amount: Long?,
        items: List<BasketLine>,
    ): CancelRecord {
        val refundAmount =
            when (invoice.status) {
                Status.HOLD -> holdReleaseAmount(invoice, amount)

                Status.SUCCESS, Status.REVERSED -> paymentRefundAmount(invoice, amount, items, info.needsBasket)

                // Verified live: a partial capture that is still settling cannot be refunded yet.
                Status.PROCESSING -> throw if (invoice.finalizing) orderNotFinished() else cancelNotAvailable()

                else -> throw cancelNotAvailable()
            }
        val now = now()
        val cancel =
            CancelRecord(extRef, refundAmount, invoice.ccy, now, now, Status.PROCESSING, ids.digits(6), ids.digits(12))
        invoice.cancels.add(cancel)
        return cancel
    }

    /** Completes a refund: `success` lowers `finalAmount` (to 0 → `reversed`), `failure` changes nothing else. */
    fun settle(
        invoice: InvoiceRecord,
        cancel: CancelRecord,
        success: Boolean,
    ) {
        check(cancel.status == Status.PROCESSING) { "the refund is already ${cancel.status}" }
        val now = now()
        cancel.status = if (success) Status.SUCCESS else Status.FAILURE
        cancel.modifiedAt = now
        if (success) applyRefund(invoice, cancel.amount)
        invoice.touch(now)
        emit(invoice)
    }

    /** A refund made outside the API (portal, chargeback): no `extRef`, completed at once. */
    fun refundExternally(
        invoice: InvoiceRecord,
        amount: Long,
    ) {
        check(
            invoice.status == Status.SUCCESS ||
                (invoice.status == Status.REVERSED && invoice.reversedFrom == Status.SUCCESS),
        ) {
            "only a successful payment can be refunded"
        }
        check(amount in 1..invoice.refundable()) { "amount must be between 1 and ${invoice.refundable()}" }
        val now = now()
        invoice.cancels.add(
            CancelRecord(null, amount, invoice.ccy, now, now, Status.SUCCESS, ids.digits(6), ids.digits(12)),
        )
        applyRefund(invoice, amount)
        invoice.touch(now)
        emit(invoice)
    }

    private fun applyRefund(
        invoice: InvoiceRecord,
        amount: Long,
    ) {
        if (invoice.status == Status.HOLD) {
            reverse(invoice)
        } else {
            // Verified live: any successful refund, even a partial one, makes the invoice `reversed`;
            // finalAmount keeps what is left.
            val remaining = maxOf(0, (invoice.finalAmount ?: 0) - amount)
            if (invoice.status == Status.SUCCESS) invoice.reversedFrom = Status.SUCCESS
            invoice.status = Status.REVERSED
            invoice.finalAmount = remaining
        }
    }

    companion object {
        const val PAYMENT_HOLD = "hold"
        const val WALLET_NEW = "new"
        const val WALLET_CREATED = "created"
        const val WALLET_FAILED = "failed"

        /** monobank cancels a hold that is not finalized within 9 days. */
        val HOLD_PERIOD: Duration = Duration.ofDays(9)
    }
}

private fun wrongCancelAmount() = ApiError(400, "WRONG_CANCEL_AMOUNT", "wrong cancel amount")

/** The acquiring fee in minor units, rounded half up. */
private fun fee(
    amount: Long,
    percent: BigDecimal,
): Long =
    BigDecimal
        .valueOf(amount)
        .multiply(percent)
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .toLong()

/** A hold is released whole; a partial release or a second release while one is pending is refused. */
private fun holdReleaseAmount(
    invoice: InvoiceRecord,
    amount: Long?,
): Long {
    if (amount != null && amount != invoice.amount) {
        throw ApiError(400, "HOLD_INVOICE_NOT_FINALIZED", "hold must be finalized first")
    }
    if (invoice.cancels.any { it.status == Status.PROCESSING }) throw wrongCancelAmount()
    return invoice.amount
}

/** monobank's answer (verified live for finalize) to a partial amount without `items` on a fiscal or split merchant. */
private fun noBasketForPartialAmount() = ApiError(400, "1001", "wrong amount for finalization without basket")

/** Error texts of the item checks: the finalize "not found" text is verified live, the rest inferred. */
internal enum class ItemTexts(
    val notFound: String,
    val tooMany: String,
    val wrongTotal: String,
) {
    FINALIZE(
        "finalization basket contains item not found in hold: ",
        "finalization basket item exceeds the hold: ",
        "wrong amount for finalization",
    ),
    REFUND(
        "refund basket contains item not found in invoice: ",
        "refund basket item exceeds the invoice: ",
        "wrong amount for refund",
    ),
}

/**
 * Matches each item to a line of [basket] by (name, code, sum = unit price, splitReceiverId): only
 * qty may differ, and not beyond the line's qty. The items must add up to [amount].
 */
private fun matchItems(
    basket: List<BasketLine>,
    items: List<BasketLine>,
    amount: Long,
    texts: ItemTexts,
) {
    for (item in items) {
        val line = basket.firstOrNull { it.sameItem(item) } ?: throw ApiError(400, "1001", texts.notFound + item.key)
        if (item.qty > line.qty) throw ApiError(400, "1001", texts.tooMany + item.key)
    }
    if (items.sumOf { it.total() } != amount) throw ApiError(400, "1001", texts.wrongTotal)
}

private fun orderNotFinished() = ApiError(404, "1004", "order not finished")

private fun cancelNotAvailable() = ApiError(500, "CANCEL_NOT_AVAILABLE", "cancel is not available")

/**
 * The amount of a refund of a paid invoice. A partially refunded payment is `reversed` but can be
 * refunded further; a released or lapsed hold cannot.
 */
private fun paymentRefundAmount(
    invoice: InvoiceRecord,
    amount: Long?,
    items: List<BasketLine>,
    needsBasket: Boolean,
): Long {
    if (invoice.status == Status.REVERSED && invoice.reversedFrom != Status.SUCCESS) throw wrongCancelAmount()
    val refundable = invoice.refundable()
    val requested = amount ?: refundable
    if (requested <= 0 || requested > refundable) throw wrongCancelAmount()
    // Inferred from the finalize rule: a partial refund needs the refunded items.
    val partial = requested < (invoice.finalAmount ?: 0)
    if (needsBasket && partial && items.isEmpty()) throw noBasketForPartialAmount()
    if (needsBasket && partial) matchItems(invoice.basket, items, requested, ItemTexts.REFUND)
    return requested
}

/** The invoice becomes `reversed`, remembering what it was. */
private fun reverse(invoice: InvoiceRecord) {
    invoice.reversedFrom = invoice.status
    invoice.status = Status.REVERSED
    invoice.finalAmount = 0
    invoice.holdUntil = null
}
