package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.testkit.internal.AcquiringRoutes
import io.github.damienmiheev.monobank.testkit.internal.FakeServer
import io.github.damienmiheev.monobank.testkit.internal.FakeSettings
import io.github.damienmiheev.monobank.testkit.internal.FakeState
import io.github.damienmiheev.monobank.testkit.internal.MerchantInfo
import io.github.damienmiheev.monobank.testkit.internal.Status
import io.github.damienmiheev.monobank.testkit.internal.WebhookDispatcher
import io.github.damienmiheev.monobank.testkit.internal.WebhookSettings
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * An in-memory fake of monobank's acquiring API on a local HTTP port, for testing code that
 * takes payments through monobank without calling monobank.
 *
 * **A test double, not a guarantee.** It approximates monobank's *documented* behaviour (routes,
 * JSON shapes, status codes, `{"errCode","errText"}` error bodies, the invoice status machine,
 * signed webhooks). Where the documentation is silent, it makes a reasonable, documented choice.
 * Passing against the fake does not prove your integration works against monobank: also run it
 * against monobank's test token before going live.
 *
 * What it serves (with `X-Token` checked against the configured tokens: missing → 400, wrong → 403):
 * `GET /api/merchant/pubkey`, `GET /api/merchant/details`, `POST /api/merchant/invoice/create`,
 * `GET /api/merchant/invoice/status`, `POST /api/merchant/invoice/remove`, `POST /api/merchant/invoice/finalize`,
 * `POST /api/merchant/invoice/cancel`, `POST /api/merchant/wallet/payment`, `GET /api/merchant/wallet`,
 * `DELETE /api/merchant/wallet/card`, `GET /api/merchant/statement` (paths in [MonobankPaths]).
 *
 * How it behaves:
 * - Nobody pays the payment page: your test plays the customer with [payInvoice], [failInvoice],
 *   [startPayment] and [expireInvoice].
 * - Status changes send a webhook to the invoice's `webHookUrl`, signed by [signer] like monobank
 *   (`X-Sign`, ECDSA P-256 over the raw body), body identical to `invoice/status`; nothing is sent
 *   for `expired`. A paying customer produces `processing` then `success` (or `hold`, `failure`).
 *   A card-saving invoice then gets a separate card webhook with `walletData.status` `created`
 *   (or `failed`); that webhook keeps the invoice's status and `modifiedDate`.
 * - `modifiedDate` only increases. Amounts are minor units (kopiyky). Unpaid invoices expire after
 *   `validity` (default 24 h, at most 30 days). A hold lapses (`reversed`, with a webhook) 9 days
 *   after it was placed, and finalizing it afterwards fails. Refunds lower `finalAmount`, and a
 *   fully refunded payment becomes `reversed`. Only payments that reached `processing` or later
 *   appear in the statement. Time comes from [clock], which tests can move with [advanceClock].
 * - Faults: [injectFault] (delay, timeout, dropped connection, 5xx, 429 with or without
 *   `Retry-After`, any canned answer), with or without the request being performed.
 * - Webhook faults: [dropNextWebhooks], [duplicateNextWebhooks], [corruptNextWebhookSignatures],
 *   [setWebhookDelay], manual delivery with [deliverWebhooksNow] / [deliverWebhooksInReverseOrder].
 * - Assertions: [requestCount], [requests], [sentWebhooks], [awaitWebhooks], [invoice], [invoices].
 *
 * Kotlin:
 * ```kotlin
 * FakeMonobankAcquiring().start().use { fake ->
 *     val mono = MonobankAcquiring(AcquiringConfig.builder(fake.token).options(fake.clientOptions()).build())
 *     val invoice = mono.invoices.create(
 *         CreateInvoiceRequest.builder(4200).webHookUrl("http://127.0.0.1:8080/notification").build(),
 *     )
 *     fake.payInvoice(invoice.invoiceId)
 *     val webhooks = fake.awaitWebhooks(2, Duration.ofSeconds(5)) // processing, success
 *     check(mono.webhooks.verify(webhooks.last().xSign, webhooks.last().bodyBytes()))
 *     check(mono.invoices.status(invoice.invoiceId).status == InvoiceStatus.SUCCESS)
 * }
 * ```
 *
 * Java:
 * ```java
 * try (FakeMonobankAcquiring fake = new FakeMonobankAcquiring().start()) {
 *     MonobankAcquiring mono = new MonobankAcquiring(
 *             AcquiringConfig.builder(fake.getToken()).options(fake.clientOptions()).build());
 *     String invoiceId = mono.getInvoices().create(CreateInvoiceRequest.builder(4200).build()).getInvoiceId();
 *     fake.injectFault(MonobankPaths.INVOICE_STATUS, Fault.serverError());
 *     fake.payInvoice(invoiceId);
 *     // the SDK retries the 500 on a read
 *     assert mono.getInvoices().status(invoiceId).getStatus() == InvoiceStatus.SUCCESS;
 * }
 * ```
 *
 * It models one merchant: every configured token sees the same invoices and the same key; start
 * two fakes for two merchants. Thread-safe: it serves concurrent requests, and the control methods
 * can be called from any thread. [close] stops the server and every thread it started.
 */
@Suppress("TooManyFunctions")
public class FakeMonobankAcquiring private constructor(
    builder: Builder,
) : AutoCloseable {
    /** A fake with default settings: token [DEFAULT_TOKEN], a new key, real time, automatic webhooks. */
    public constructor() : this(Builder())

    /** Signs webhooks; its key is served by `GET /api/merchant/pubkey`. */
    public val signer: MonobankWebhookSigner = builder.signer ?: MonobankWebhookSigner.generate()

    /** The fake's clock: invoice validity, holds and every reported date follow it. */
    public val clock: TestClock = builder.clock

    /** The first accepted `X-Token`; give it to the client under test. */
    public val token: String = builder.tokens.first()

    private val tokens: Set<String> = builder.tokens.toSet()
    private val stopped = CountDownLatch(1)
    private val webhooks =
        WebhookDispatcher(
            signer = signer,
            clock = clock,
            settings =
                WebhookSettings(
                    builder.webhookMaxAttempts,
                    builder.webhookRetryInterval,
                    builder.webhookTimeout,
                ),
            mode = builder.webhookDelivery,
            delay = builder.webhookDelay,
            stopped = stopped,
        )
    private val state =
        FakeState(
            FakeSettings(
                clock = clock,
                feePercent = builder.feePercent,
                sendProcessingWebhooks = builder.sendProcessingWebhooks,
                validateBasketTotal = builder.validateBasketTotal,
                refundSettlement = builder.refundSettlement,
            ),
            webhooks::enqueue,
        )
    private val server =
        FakeServer(
            routes =
                AcquiringRoutes(
                    state,
                    signer,
                    MerchantInfo(builder.merchantId, builder.merchantName, builder.edrpou),
                ) { baseUrl },
            validTokens = tokens,
            clock = clock,
            hangLimit = builder.hangLimit,
            stopped = stopped,
        )
    private val requestedPort = builder.port
    private val lifecycle = Any()

    @Volatile
    private var boundPort: Int = -1

    @Volatile
    private var closed = false

    /**
     * Starts serving on `127.0.0.1` (a random free port unless [Builder.port] set one).
     *
     * @return this fake, for chaining.
     * @throws IllegalStateException when it was already started or closed.
     */
    public fun start(): FakeMonobankAcquiring =
        apply {
            synchronized(lifecycle) {
                check(!closed) { "the fake is closed" }
                check(boundPort < 0) { "the fake is already started" }
                boundPort = server.start(requestedPort)
            }
        }

    /** The port it listens on. @throws IllegalStateException before [start]. */
    public val port: Int
        get() = boundPort.also { check(it >= 0) { "the fake is not started" } }

    /** Base URL to give the client, e.g. `http://127.0.0.1:54321`. @throws IllegalStateException before [start]. */
    public val baseUrl: String
        get() = "http://127.0.0.1:$port"

    /** Client options pointing at this fake (the SDK's defaults otherwise). */
    public fun clientOptions(): MonobankClientOptions = clientOptionsBuilder().build()

    /** A client options builder pointing at this fake, to adjust timeouts or retries. */
    public fun clientOptionsBuilder(): MonobankClientOptions.Builder = MonobankClientOptions.builder().baseUrl(baseUrl)

    /**
     * Stops the server and the webhook sender, ends hanging requests, and waits for every thread
     * the fake started to finish. Pending webhooks are discarded. Idempotent.
     */
    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
        }
        stopped.countDown()
        server.stop()
        webhooks.close()
    }

    /**
     * Forgets every invoice, card, refund, recorded request and webhook, queued fault and queued
     * wallet payment outcome. Keeps the key, tokens, settings and the clock.
     */
    public fun reset() {
        state.reset()
        server.reset()
        webhooks.reset()
    }

    // ---- The customer and monobank's back office ----

    /** The customer pays [invoiceId] successfully with the default card. See [payInvoice]. */
    public fun payInvoice(invoiceId: String): FakeInvoice = payInvoice(invoiceId, CustomerPayment.approved())

    /**
     * The customer pays [invoiceId] as described by [payment]: `created` → `processing` → `success`
     * (`hold` for a hold invoice, `failure` when declined), with a webhook for each change and,
     * for a card-saving invoice, the card webhook. Also completes a `processing` invoice, e.g. a
     * saved-card charge waiting for 3-D Secure.
     *
     * @return the invoice afterwards.
     * @throws IllegalStateException when the invoice is neither `created` nor `processing`.
     * @throws IllegalArgumentException when the fake has no such invoice.
     */
    public fun payInvoice(
        invoiceId: String,
        payment: CustomerPayment,
    ): FakeInvoice =
        state.locked {
            val invoice = state.require(invoiceId)
            if (invoice.status == Status.CREATED) state.beginPayment(invoice, payment)
            state.completePayment(invoice, payment)
            invoice.snapshot()
        }

    /** The customer's payment of [invoiceId] is declined (code `59`). See [payInvoice]. */
    public fun failInvoice(invoiceId: String): FakeInvoice = payInvoice(invoiceId, CustomerPayment.declined())

    /**
     * The customer submits the payment but it is not finished: `created` → `processing` (with a
     * webhook). Finish it with [payInvoice]. While `processing`, `invoice/remove` is refused.
     *
     * @throws IllegalStateException when the invoice is not `created`.
     */
    @JvmOverloads
    public fun startPayment(
        invoiceId: String,
        payment: CustomerPayment = CustomerPayment.approved(),
    ): FakeInvoice =
        state.locked {
            val invoice = state.require(invoiceId)
            state.beginPayment(invoice, payment)
            invoice.snapshot()
        }

    /**
     * Expires [invoiceId] now (`created` or `processing` → `expired`), as when its validity runs
     * out or the customer abandons 3-D Secure. Like monobank, no webhook is sent.
     *
     * @throws IllegalStateException when the invoice is in another status.
     */
    public fun expireInvoice(invoiceId: String): FakeInvoice =
        state.locked {
            val invoice = state.require(invoiceId)
            state.expire(invoice)
            invoice.snapshot()
        }

    /**
     * Completes a `processing` refund of [invoiceId] (with [RefundSettlement.MANUAL]): the one
     * with [extRef], or the oldest pending one when [extRef] is `null`. Success lowers
     * `finalAmount` (a full refund makes the invoice `reversed`); either way a webhook is sent.
     *
     * @throws IllegalStateException when there is no such pending refund.
     */
    public fun settleRefund(
        invoiceId: String,
        extRef: String?,
        success: Boolean,
    ): FakeInvoice =
        state.locked {
            val invoice = state.require(invoiceId)
            val refund =
                checkNotNull(
                    invoice.cancels.firstOrNull {
                        it.status == Status.PROCESSING && (extRef == null || it.extRef == extRef)
                    },
                ) { "invoice $invoiceId has no pending refund${extRef?.let { " with extRef $it" } ?: ""}" }
            state.settle(invoice, refund, success)
            invoice.snapshot()
        }

    /**
     * A refund of [amount] (minor units) made outside the API, e.g. in monobank's web portal or a
     * chargeback: it appears in `cancelList` without an `extRef`, completed, with a webhook.
     *
     * @throws IllegalStateException when the invoice is not `success` or [amount] is more than is left.
     */
    public fun refundExternally(
        invoiceId: String,
        amount: Long,
    ): FakeInvoice =
        state.locked {
            val invoice = state.require(invoiceId)
            state.refundExternally(invoice, amount)
            invoice.snapshot()
        }

    /**
     * Queues the outcome of the next `POST /api/merchant/wallet/payment`. Outcomes are used in
     * order; without one, a charge succeeds.
     */
    public fun enqueueWalletPaymentOutcome(outcome: WalletPaymentOutcome) {
        state.locked { state.enqueueOutcome(outcome) }
    }

    /**
     * Saves a card into [walletId] directly, as if a card-saving payment had happened earlier, and
     * returns its token. Saving the same [maskedPan] into the same wallet again returns the same token.
     */
    @JvmOverloads
    public fun addCard(
        walletId: String,
        maskedPan: String = CustomerPayment.DEFAULT_MASKED_PAN,
    ): String {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return state.locked { state.addCard(walletId, maskedPan, "804").cardToken }
    }

    /** Sets how later refunds complete. */
    public fun setRefundSettlement(settlement: RefundSettlement) {
        state.settings.refundSettlement = settlement
    }

    /** Moves [clock] forward; holds and invoices that are due expire on the next request or control call. */
    public fun advanceClock(duration: Duration) {
        clock.advance(duration)
    }

    /** Rotates the webhook key (see [MonobankWebhookSigner.rotate]); returns the new public key. */
    public fun rotateKey(): String = signer.rotate()

    /** A snapshot of [invoiceId], or `null` when the fake has no such invoice. */
    public fun invoice(invoiceId: String): FakeInvoice? =
        state.locked { state.all().firstOrNull { it.invoiceId == invoiceId }?.snapshot() }

    /** Snapshots of every invoice, oldest first (including those created by `wallet/payment`). */
    public fun invoices(): List<FakeInvoice> = state.locked { state.all().map { it.snapshot() } }

    // ---- Faults ----

    /** Applies [fault] to the next request on [path] (a [MonobankPaths] constant, or [MonobankPaths.ANY]). */
    public fun injectFault(
        path: String,
        fault: Fault,
    ) {
        injectFault(path, fault, 1)
    }

    /**
     * Applies [fault] to the next [times] requests on [path] (a [MonobankPaths] constant, or
     * [MonobankPaths.ANY] for every path). Faults queued for one path apply in order.
     */
    public fun injectFault(
        path: String,
        fault: Fault,
        times: Int,
    ) {
        require(times > 0) { "times must be positive" }
        require(path == MonobankPaths.ANY || path.startsWith("/")) { "path must start with '/' or be '*'" }
        server.faults.add(path, fault, times)
    }

    /** Answers the next request on [path] with [status] and the JSON [body], without performing it. */
    public fun nextResponse(
        path: String,
        status: Int,
        body: String,
    ) {
        injectFault(path, Fault.response(status, body))
    }

    /** Removes every queued fault. */
    public fun clearFaults() {
        server.faults.clear()
    }

    // ---- Requests ----

    /** How many requests arrived on [path] (whatever the answer, faults included). */
    public fun requestCount(path: String): Int = server.count(path)

    /** Every request received, oldest first. */
    public fun requests(): List<FakeRequest> = server.requests()

    // ---- Webhooks ----

    /** Switches between automatic and manual webhook delivery. Switching to automatic sends what is queued. */
    public fun setWebhookDelivery(delivery: WebhookDelivery) {
        webhooks.setMode(delivery)
    }

    /** Waits [delay] before each automatic webhook delivery. */
    public fun setWebhookDelay(delay: Duration) {
        require(!delay.isNegative) { "delay must not be negative" }
        webhooks.setDelay(delay)
    }

    /** Sends every queued webhook now, in order, on the calling thread; returns what was recorded. */
    public fun deliverWebhooksNow(): List<RecordedWebhook> = webhooks.deliverAll(reversed = false)

    /** Sends every queued webhook now, newest first (out of order, as monobank may); returns what was recorded. */
    public fun deliverWebhooksInReverseOrder(): List<RecordedWebhook> = webhooks.deliverAll(reversed = true)

    /** Webhooks waiting to be sent (manual delivery or a delay). */
    public fun pendingWebhookCount(): Int = webhooks.pendingCount()

    /** The next [count] webhooks are lost: recorded as [WebhookOutcome.DROPPED] and never sent. */
    public fun dropNextWebhooks(count: Int) {
        require(count > 0) { "count must be positive" }
        webhooks.dropNext(count)
    }

    /** The next [count] webhooks are each sent twice with the same body. */
    public fun duplicateNextWebhooks(count: Int) {
        require(count > 0) { "count must be positive" }
        webhooks.duplicateNext(count)
    }

    /** The next [count] webhooks carry an `X-Sign` made with a different key, so verification fails. */
    public fun corruptNextWebhookSignatures(count: Int) {
        require(count > 0) { "count must be positive" }
        webhooks.corruptNext(count)
    }

    /** Every webhook sent, failed or dropped so far, in the order they finished. */
    public fun sentWebhooks(): List<RecordedWebhook> = webhooks.recorded()

    /**
     * Waits until at least [count] webhooks are recorded (sent, failed or dropped) and returns all of them.
     *
     * @throws IllegalStateException when fewer arrive within [timeout].
     */
    public fun awaitWebhooks(
        count: Int,
        timeout: Duration,
    ): List<RecordedWebhook> = webhooks.await(count, timeout)

    override fun toString(): String =
        "FakeMonobankAcquiring(baseUrl=${if (boundPort >= 0) baseUrl else "not started"}, closed=$closed)"

    /** Settings for [FakeMonobankAcquiring]. */
    public class Builder internal constructor() {
        internal var tokens: List<String> = listOf(DEFAULT_TOKEN)
        internal var signer: MonobankWebhookSigner? = null
        internal var clock: TestClock = TestClock.system()
        internal var port: Int = 0
        internal var merchantId: String = "12o4Vv7EWy"
        internal var merchantName: String = "Fake Merchant"
        internal var edrpou: String = "4242424242"
        internal var webhookDelivery: WebhookDelivery = WebhookDelivery.AUTOMATIC
        internal var webhookDelay: Duration = Duration.ZERO
        internal var webhookMaxAttempts: Int = 3
        internal var webhookRetryInterval: Duration = Duration.ofMillis(200)
        internal var webhookTimeout: Duration = Duration.ofSeconds(5)
        internal var refundSettlement: RefundSettlement = RefundSettlement.AUTOMATIC
        internal var sendProcessingWebhooks: Boolean = true
        internal var validateBasketTotal: Boolean = true
        internal var feePercent: BigDecimal = BigDecimal("1.3")
        internal var hangLimit: Duration = Duration.ofMinutes(2)

        /** Accepted `X-Token` values (default [DEFAULT_TOKEN]); the first is [FakeMonobankAcquiring.token]. */
        public fun tokens(vararg tokens: String): Builder =
            apply {
                require(tokens.isNotEmpty() && tokens.all { it.isNotBlank() }) { "tokens must not be empty or blank" }
                this.tokens = tokens.toList()
            }

        /** The webhook signer, e.g. one with a fixed key (default: a newly generated key). */
        public fun signer(signer: MonobankWebhookSigner): Builder = apply { this.signer = signer }

        /** The clock (default [TestClock.system]). */
        public fun clock(clock: TestClock): Builder = apply { this.clock = clock }

        /** The port to listen on (default 0: a random free port). */
        public fun port(port: Int): Builder =
            apply {
                require(port in 0..65_535) { "port must be 0..65535" }
                this.port = port
            }

        /** What `GET /api/merchant/details` returns. */
        public fun merchant(
            merchantId: String,
            merchantName: String,
            edrpou: String,
        ): Builder =
            apply {
                this.merchantId = merchantId
                this.merchantName = merchantName
                this.edrpou = edrpou
            }

        /** Automatic (default) or manual webhook delivery. */
        public fun webhookDelivery(delivery: WebhookDelivery): Builder = apply { webhookDelivery = delivery }

        /** Delay before each automatic webhook (default none). */
        public fun webhookDelay(delay: Duration): Builder =
            apply {
                require(!delay.isNegative) { "delay must not be negative" }
                webhookDelay = delay
            }

        /**
         * Webhook attempts (default 3, as monobank documents: it retries until the receiver answers
         * 200) and the pause between them (default 200 ms).
         */
        public fun webhookRetries(
            maxAttempts: Int,
            interval: Duration,
        ): Builder =
            apply {
                require(maxAttempts > 0) { "maxAttempts must be positive" }
                require(!interval.isNegative) { "interval must not be negative" }
                webhookMaxAttempts = maxAttempts
                webhookRetryInterval = interval
            }

        /** Connect and read timeout of each webhook attempt (default 5 s). */
        public fun webhookTimeout(timeout: Duration): Builder =
            apply {
                require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
                webhookTimeout = timeout
            }

        /** How refunds complete (default [RefundSettlement.AUTOMATIC]). */
        public fun refundSettlement(settlement: RefundSettlement): Builder = apply { refundSettlement = settlement }

        /** Whether a `processing` webhook precedes the final one (default `true`). */
        public fun sendProcessingWebhooks(send: Boolean): Builder = apply { sendProcessingWebhooks = send }

        /**
         * Whether `merchantPaymInfo.basketOrder` must add up to `amount` (default `true`; skipped
         * when discounts are present). A mismatch answers 400 `INVALID_MERCHANT_PAYM_INFO`.
         */
        public fun validateBasketTotal(validate: Boolean): Builder = apply { validateBasketTotal = validate }

        /** Acquiring fee in percent, reported as `paymentInfo.fee` and in `profitAmount` (default 1.3). */
        public fun feePercent(percent: BigDecimal): Builder =
            apply {
                require(percent.signum() >= 0) { "feePercent must not be negative" }
                feePercent = percent
            }

        /** How long a [Fault.timeout] holds a request at most before closing it (default 2 minutes). */
        public fun hangLimit(limit: Duration): Builder =
            apply {
                require(!limit.isNegative) { "hangLimit must not be negative" }
                hangLimit = limit
            }

        /** Builds the fake (not started yet: call [FakeMonobankAcquiring.start]). */
        public fun build(): FakeMonobankAcquiring = FakeMonobankAcquiring(this)
    }

    public companion object {
        /** The token a default fake accepts. */
        public const val DEFAULT_TOKEN: String = "fake-monobank-token"

        /** Starts configuring a fake. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
