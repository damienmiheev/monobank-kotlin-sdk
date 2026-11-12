package io.github.damienmiheev.monobank.testkit.internal

import io.github.damienmiheev.monobank.testkit.CardSaving
import io.github.damienmiheev.monobank.testkit.CustomerPayment
import io.github.damienmiheev.monobank.testkit.RefundSettlement
import io.github.damienmiheev.monobank.testkit.TestClock
import io.github.damienmiheev.monobank.testkit.WalletPaymentOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Settings of the simulated merchant account. */
internal class FakeSettings(
    val clock: TestClock,
    val feePercent: BigDecimal,
    val sendProcessingWebhooks: Boolean,
    val validateBasketTotal: Boolean,
    @Volatile var refundSettlement: RefundSettlement,
)

/**
 * The fake's merchant account: invoices, refunds, wallets and the status machine. Every method
 * expects the caller to hold [lock] (use [locked]); webhooks are handed to [webhookSink] in the
 * order the changes happen.
 */
internal class FakeState(
    val settings: FakeSettings,
    private val webhookSink: (WebhookEvent) -> Unit,
) {
    private val lock = ReentrantLock()
    private val invoices = LinkedHashMap<String, InvoiceRecord>()
    private val wallets = LinkedHashMap<String, MutableList<CardRecord>>()
    private val walletOutcomes = ArrayDeque<WalletPaymentOutcome>()

    /** Runs [block] under the lock, after applying expirations that are due. */
    fun <T> locked(block: () -> T): T =
        lock.withLock {
            sweep(now())
            block()
        }

    /** The fake clock's time, in whole milliseconds: the precision of the dates it reports. */
    fun now(): Instant = settings.clock.instant().truncatedTo(ChronoUnit.MILLIS)

    fun reset() =
        lock.withLock {
            invoices.clear()
            wallets.clear()
            walletOutcomes.clear()
        }

    fun find(invoiceId: String): InvoiceRecord = invoices[invoiceId] ?: throw ApiError.notFound("invoice not found")

    /** For the control API: a missing invoice is a test bug, not an API error. */
    fun require(invoiceId: String): InvoiceRecord =
        requireNotNull(invoices[invoiceId]) { "the fake has no invoice $invoiceId" }

    fun add(invoice: InvoiceRecord) {
        invoices[invoice.invoiceId] = invoice
    }

    fun all(): List<InvoiceRecord> = invoices.values.toList()

    fun cards(walletId: String): List<CardRecord> = wallets[walletId].orEmpty().toList()

    fun card(cardToken: String): CardRecord? =
        wallets.values.asSequence().flatten().firstOrNull {
            it.cardToken ==
                cardToken
        }

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
    ): CardRecord {
        val cards = wallets.getOrPut(walletId) { mutableListOf() }
        cards.firstOrNull { it.maskedPan == maskedPan }?.let { return it }
        return CardRecord(Ids.cardToken(), walletId, maskedPan, country).also { cards.add(it) }
    }

    fun enqueueOutcome(outcome: WalletPaymentOutcome) {
        walletOutcomes.addLast(outcome)
    }

    fun nextOutcome(): WalletPaymentOutcome = walletOutcomes.removeFirstOrNull() ?: WalletPaymentOutcome.success()

    /** Expires unpaid invoices whose validity passed, and lapses holds older than 9 days. */
    private fun sweep(now: Instant) {
        for (invoice in invoices.values) {
            val validUntil = invoice.validUntil
            val holdUntil = invoice.holdUntil
            if (invoice.status == Status.CREATED && validUntil != null && !now.isBefore(validUntil)) {
                invoice.status = Status.EXPIRED
                invoice.touch(validUntil)
            } else if (invoice.status == Status.HOLD && holdUntil != null && !now.isBefore(holdUntil)) {
                invoice.status = Status.REVERSED
                invoice.finalAmount = 0
                invoice.holdUntil = null
                invoice.touch(holdUntil)
                emit(invoice)
            }
        }
    }

    /** Queues a webhook with the invoice's current state. monobank sends none for `expired`. */
    private fun emit(invoice: InvoiceRecord) {
        val url = invoice.webHookUrl ?: return
        if (invoice.status == Status.EXPIRED) return
        webhookSink(WebhookEvent(invoice.invoiceId, invoice.status, url, FakeJson.bytes(invoice.statusJson())))
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
                approvalCode = Ids.digits(6),
                rrn = Ids.digits(12),
                tranId = Ids.digits(8),
            )
        if (invoice.saveCard) {
            val walletId = checkNotNull(invoice.walletId)
            invoice.cardToken =
                wallets[walletId]?.firstOrNull { it.maskedPan == payment.maskedPan }?.cardToken ?: Ids.cardToken()
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
        check(invoice.status == Status.PROCESSING) {
            "invoice ${invoice.invoiceId} is ${invoice.status}, not processing"
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
                cards.add(
                    CardRecord(token, walletId, payment.maskedPan, payment.country),
                )
            }
            invoice.walletStatus = WALLET_CREATED
        } else {
            invoice.walletStatus = WALLET_FAILED
        }
        emit(invoice)
    }

    /** `created`/`processing` → `expired`, without a webhook. */
    fun expire(invoice: InvoiceRecord) {
        check(invoice.status == Status.CREATED || invoice.status == Status.PROCESSING) {
            "invoice ${invoice.invoiceId} is ${invoice.status}; only created or processing invoices expire"
        }
        invoice.status = Status.EXPIRED
        invoice.touch(now())
    }

    /** `POST invoice/finalize`: captures [amount] of a hold. */
    fun finalizeHold(
        invoice: InvoiceRecord,
        amount: Long,
    ) {
        if (invoice.status != Status.HOLD) throw ApiError.badRequest("invoice is not on hold")
        if (amount > invoice.amount) throw ApiError.badRequest("invalid 'amount': more than the hold")
        invoice.status = Status.SUCCESS
        invoice.finalAmount = amount
        invoice.fee = fee(amount, settings.feePercent)
        invoice.holdUntil = null
        invoice.touch(now())
        emit(invoice)
    }

    /** `POST invoice/cancel`: refunds a payment or releases a hold. Returns the new refund. */
    fun cancel(
        invoice: InvoiceRecord,
        extRef: String?,
        amount: Long?,
    ): CancelRecord {
        val refundAmount =
            when (invoice.status) {
                Status.HOLD -> {
                    if (amount != null && amount != invoice.amount) {
                        throw ApiError(400, "HOLD_INVOICE_NOT_FINALIZED", "hold must be finalized first")
                    }
                    invoice.amount
                }

                Status.SUCCESS -> {
                    val refundable = invoice.refundable()
                    val requested = amount ?: refundable
                    if (requested <= 0 || requested > refundable) throw wrongCancelAmount()
                    requested
                }

                Status.REVERSED -> {
                    throw wrongCancelAmount()
                }

                else -> {
                    throw ApiError(500, "CANCEL_NOT_AVAILABLE", "cancel is not available")
                }
            }
        val now = now()
        val cancel =
            CancelRecord(extRef, refundAmount, invoice.ccy, now, now, Status.PROCESSING, Ids.digits(6), Ids.digits(12))
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
        check(invoice.status == Status.SUCCESS) { "only a successful payment can be refunded" }
        check(amount in 1..invoice.refundable()) { "amount must be between 1 and ${invoice.refundable()}" }
        val now = now()
        invoice.cancels.add(
            CancelRecord(null, amount, invoice.ccy, now, now, Status.SUCCESS, Ids.digits(6), Ids.digits(12)),
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
            invoice.status = Status.REVERSED
            invoice.finalAmount = 0
            invoice.holdUntil = null
        } else {
            val remaining = (invoice.finalAmount ?: 0) - amount
            invoice.finalAmount = remaining
            if (remaining <= 0) invoice.status = Status.REVERSED
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
