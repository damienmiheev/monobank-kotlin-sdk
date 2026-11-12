package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.testkit.internal.Bank
import io.github.damienmiheev.monobank.testkit.internal.FakeJson
import io.github.damienmiheev.monobank.testkit.internal.MerchantState
import io.github.damienmiheev.monobank.testkit.internal.Status
import io.github.damienmiheev.monobank.testkit.internal.WebhookDispatcher
import io.github.damienmiheev.monobank.testkit.internal.WebhookEvent
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * One more merchant served by a [FakeMonobankAcquiring], added with
 * [FakeMonobankAcquiring.Builder.addMerchant]. Each merchant has its own tokens, webhook key,
 * invoices and wallets: a token of merchant A cannot read or cancel merchant B's invoices (404), and
 * merchant B's webhooks are signed with B's key. This is what a multi-tenant integration (one base
 * URL, one token per tenant) needs.
 *
 * ```kotlin
 * val fake = FakeMonobankAcquiring.builder()
 *     .tokens("tenant-a")
 *     .addMerchant(MerchantSetup.builder("tenant-b").build())
 *     .build().start()
 * fake.merchant("tenant-b").rotateKey() // only tenant B's key changes
 * ```
 *
 * @property tokens Accepted `X-Token` values (aliases of this merchant); never printed by `toString`.
 * @property signer Webhook signer; its key is served by `GET /api/merchant/pubkey` for these tokens.
 * @property merchantId What `GET /api/merchant/details` returns as `merchantId` (must be unique per fake).
 * @property merchantName `merchantName` in `GET /api/merchant/details`.
 * @property edrpou `edrpou` in `GET /api/merchant/details`.
 * @property fiscalization Fiscal receipts are enabled (see [Builder.fiscalization]).
 * @property splitPayments Split payments are enabled (see [Builder.splitPayments]).
 * @property splitReceivers What `GET /api/merchant/split-receiver/list` returns.
 */
public class MerchantSetup private constructor(
    builder: Builder,
) {
    public val tokens: List<String> = builder.tokens
    public val signer: MonobankWebhookSigner = builder.signer ?: MonobankWebhookSigner.generate()
    public val merchantId: String = builder.merchantId ?: randomMerchantId()
    public val merchantName: String = builder.merchantName
    public val edrpou: String = builder.edrpou
    public val fiscalization: Boolean = builder.fiscalization
    public val splitPayments: Boolean = builder.splitPayments
    public val splitReceivers: List<SplitReceiver> =
        builder.splitReceivers ?: if (builder.splitPayments) listOf(SplitReceiver.DEFAULT) else emptyList()

    override fun toString(): String = "MerchantSetup(merchantId=$merchantId, merchantName=$merchantName)"

    /** Builder for [MerchantSetup]. */
    public class Builder internal constructor(
        internal val tokens: List<String>,
    ) {
        internal var signer: MonobankWebhookSigner? = null
        internal var merchantId: String? = null
        internal var merchantName: String = "Fake Merchant"
        internal var edrpou: String = "4242424242"
        internal var fiscalization: Boolean = false
        internal var splitPayments: Boolean = false
        internal var splitReceivers: List<SplitReceiver>? = null

        /**
         * Fiscal receipts (a PRRO link) are enabled (default off). Then, as monobank answers:
         * - `invoice/create` and `wallet/payment` without a non-empty `merchantPaymInfo.basketOrder` → 400
         *   `INVALID_MERCHANT_PAYM_INFO` (verified live for `invoice/create`; inferred for `wallet/payment`,
         *   and the fake exempts verification invoices, which charge nothing);
         * - a partial `invoice/finalize` without `items` → 400 `1001` "wrong amount for finalization without
         *   basket" (verified live); a partial `invoice/cancel` without `items` fails the same way (inferred).
         */
        public fun fiscalization(enabled: Boolean): Builder = apply { fiscalization = enabled }

        /**
         * Split payments are enabled (default off): the [fiscalization] rules apply too, every basket item
         * needs a `splitReceiverId` (400 `INVALID_MERCHANT_PAYM_INFO` "'splitReceiverId' is required",
         * verified live for `invoice/create`, inferred for `wallet/payment`), and the receiver list
         * contains [SplitReceiver.DEFAULT] unless [splitReceivers] says otherwise. The fake does not check
         * that a `splitReceiverId` is one of the listed receivers.
         */
        public fun splitPayments(enabled: Boolean): Builder = apply { splitPayments = enabled }

        /**
         * What `GET /api/merchant/split-receiver/list` returns (default: [SplitReceiver.DEFAULT] when split
         * payments are on, otherwise none).
         */
        public fun splitReceivers(receivers: List<SplitReceiver>): Builder =
            apply { splitReceivers = receivers.toList() }

        /** The webhook signer (default: a newly generated key). */
        public fun signer(signer: MonobankWebhookSigner): Builder = apply { this.signer = signer }

        /** What `GET /api/merchant/details` returns (default: a random id, "Fake Merchant", `4242424242`). */
        public fun details(
            merchantId: String,
            merchantName: String,
            edrpou: String,
        ): Builder =
            apply {
                require(merchantId.isNotBlank()) { "merchantId must not be blank" }
                this.merchantId = merchantId
                this.merchantName = merchantName
                this.edrpou = edrpou
            }

        /** Builds the setup. */
        public fun build(): MerchantSetup = MerchantSetup(this)
    }

    public companion object {
        /** Starts a merchant answering to [token] and any further [aliases]. */
        @JvmStatic
        public fun builder(
            token: String,
            vararg aliases: String,
        ): Builder {
            val all = listOf(token) + aliases
            require(all.all { it.isNotBlank() }) { "tokens must not be blank" }
            return Builder(all)
        }

        private fun randomMerchantId(): String {
            val random = SecureRandom()
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return buildString { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
    }
}

/**
 * The control surface of one merchant of a [FakeMonobankAcquiring] (from
 * [FakeMonobankAcquiring.merchant]): play its customers, seed its cards, rotate its key, inspect its
 * invoices. The same calls on [FakeMonobankAcquiring] act on the merchant that owns the invoice (or on
 * the default merchant for calls without an invoice). Thread-safe.
 */
public class FakeMerchant internal constructor(
    internal val state: MerchantState,
    private val bank: Bank,
    private val webhooks: WebhookDispatcher,
) {
    /** What `GET /api/merchant/details` returns as `merchantId`. */
    public val merchantId: String get() = state.info.merchantId

    /** The first accepted `X-Token` of this merchant; give it to the client under test. */
    public val token: String = state.tokens.first()

    /** Signs this merchant's webhooks; its key is served by `GET /api/merchant/pubkey` for its tokens. */
    public val signer: MonobankWebhookSigner get() = state.signer

    /** Rotates this merchant's webhook key (see [MonobankWebhookSigner.rotate]); returns the new public key. */
    public fun rotateKey(): String = state.signer.rotate()

    /** The customer pays [invoiceId] successfully with the default card. See [FakeMonobankAcquiring.payInvoice]. */
    public fun payInvoice(invoiceId: String): FakeInvoice = payInvoice(invoiceId, CustomerPayment.approved())

    /** The customer pays [invoiceId] as described by [payment]. See [FakeMonobankAcquiring.payInvoice]. */
    public fun payInvoice(
        invoiceId: String,
        payment: CustomerPayment,
    ): FakeInvoice =
        bank.locked {
            val invoice = state.require(invoiceId)
            if (invoice.status == Status.CREATED) state.beginPayment(invoice, payment)
            state.completePayment(invoice, payment)
            invoice.snapshot(merchantId)
        }

    /** The customer's payment of [invoiceId] is declined (code `59`). */
    public fun failInvoice(invoiceId: String): FakeInvoice = payInvoice(invoiceId, CustomerPayment.declined())

    /** `created` → `processing` only. See [FakeMonobankAcquiring.startPayment]. */
    @JvmOverloads
    public fun startPayment(
        invoiceId: String,
        payment: CustomerPayment = CustomerPayment.approved(),
    ): FakeInvoice =
        bank.locked {
            val invoice = state.require(invoiceId)
            state.beginPayment(invoice, payment)
            invoice.snapshot(merchantId)
        }

    /** Expires [invoiceId] now, without a webhook. See [FakeMonobankAcquiring.expireInvoice]. */
    public fun expireInvoice(invoiceId: String): FakeInvoice =
        bank.locked {
            val invoice = state.require(invoiceId)
            state.expire(invoice)
            invoice.snapshot(merchantId)
        }

    /** Completes a pending refund. See [FakeMonobankAcquiring.settleRefund]. */
    public fun settleRefund(
        invoiceId: String,
        extRef: String?,
        success: Boolean,
    ): FakeInvoice =
        bank.locked {
            val invoice = state.require(invoiceId)
            val refund =
                checkNotNull(
                    invoice.cancels.firstOrNull {
                        it.status == Status.PROCESSING && (extRef == null || it.extRef == extRef)
                    },
                ) { "invoice $invoiceId has no pending refund${extRef?.let { " with extRef $it" } ?: ""}" }
            state.settle(invoice, refund, success)
            invoice.snapshot(merchantId)
        }

    /** A refund made outside the API. See [FakeMonobankAcquiring.refundExternally]. */
    public fun refundExternally(
        invoiceId: String,
        amount: Long,
    ): FakeInvoice =
        bank.locked {
            val invoice = state.require(invoiceId)
            state.refundExternally(invoice, amount)
            invoice.snapshot(merchantId)
        }

    /** Queues the outcome of this merchant's next `wallet/payment`; without one, a charge succeeds. */
    public fun enqueueWalletPaymentOutcome(outcome: WalletPaymentOutcome) {
        bank.locked { state.enqueueOutcome(outcome) }
    }

    /**
     * Saves a Visa card into [walletId] directly and returns its token. Saving the same [maskedPan]
     * into the same wallet again returns the same token.
     */
    @JvmOverloads
    public fun addCard(
        walletId: String,
        maskedPan: String = CustomerPayment.DEFAULT_MASKED_PAN,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return bank.locked { state.addCard(walletId, maskedPan, "804", "visa").cardToken }
    }

    /** A snapshot of this merchant's [invoiceId], or `null`. */
    public fun invoice(invoiceId: String): FakeInvoice? =
        bank.locked { state.all().firstOrNull { it.invoiceId == invoiceId }?.snapshot(merchantId) }

    /** Snapshots of this merchant's invoices, oldest first. */
    public fun invoices(): List<FakeInvoice> = bank.locked { state.all().map { it.snapshot(merchantId) } }

    /**
     * POSTs [body] to [url] now, on the calling thread, signed with this merchant's current key, like
     * a real webhook (with retries). For hand-built cases: an unknown reference, a mismatched
     * `invoiceId`, an amount that does not match, an unknown status.
     */
    public fun sendSignedWebhook(
        url: String,
        body: String,
    ): RecordedWebhook {
        val event = customEvent(url, body)
        return webhooks.sendRaw(event, signer.sign(event.body))
    }

    internal fun customEvent(
        url: String,
        body: String,
    ): WebhookEvent {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val json = runCatching { FakeJson.mapper.readTree(bytes) }.getOrNull()
        return WebhookEvent(
            invoiceId = json?.path("invoiceId")?.asText("").orEmpty(),
            status = json?.path("status")?.asText("").orEmpty(),
            url = url,
            body = bytes,
            kind = WebhookKind.CUSTOM,
            walletStatus =
                json
                    ?.path("walletData")
                    ?.path("status")
                    ?.takeIf { it.isTextual }
                    ?.asText(),
            merchantId = merchantId,
            signer = state.signer,
        )
    }

    override fun toString(): String = "FakeMerchant(merchantId=$merchantId)"
}
