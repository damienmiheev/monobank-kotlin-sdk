package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.testkit.internal.AcquiringRoutes
import io.github.damienmiheev.monobank.testkit.internal.Bank
import io.github.damienmiheev.monobank.testkit.internal.FakeServer
import io.github.damienmiheev.monobank.testkit.internal.FakeSettings
import io.github.damienmiheev.monobank.testkit.internal.HostedPages
import io.github.damienmiheev.monobank.testkit.internal.Ids
import io.github.damienmiheev.monobank.testkit.internal.MerchantInfo
import io.github.damienmiheev.monobank.testkit.internal.MerchantState
import io.github.damienmiheev.monobank.testkit.internal.NamedThreadFactory
import io.github.damienmiheev.monobank.testkit.internal.RuleAction
import io.github.damienmiheev.monobank.testkit.internal.WebhookDispatcher
import io.github.damienmiheev.monobank.testkit.internal.WebhookSettings
import java.io.UncheckedIOException
import java.math.BigDecimal
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.time.Duration
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate

/**
 * An in-memory fake of monobank's acquiring API on a local HTTP port, for testing code that
 * takes payments through monobank without calling monobank.
 *
 * **A test double, not a guarantee.** It approximates monobank's *documented* behaviour (routes,
 * JSON shapes, status codes, `{"errCode","errText"}` error bodies, the invoice status machine,
 * signed webhooks). Where the documentation is silent, it makes a choice, listed below. Passing
 * against the fake does not prove your integration works against monobank: also run it against
 * monobank's test token before going live.
 *
 * ### Routes
 * Served, with `X-Token` checked (missing → 400 `BAD_REQUEST`, as documented for `pubkey`; unknown →
 * 403 `FORBIDDEN`): `GET pubkey`, `GET details`, `POST invoice/create`, `GET invoice/status`,
 * `POST invoice/remove`, `POST invoice/finalize`, `POST invoice/cancel`, `POST wallet/payment`,
 * `GET wallet`, `DELETE wallet/card`, `GET statement`, `GET split-receiver/list` (all under `/api/merchant/`,
 * see [MonobankPaths]).
 * **Not served** (they answer 404 `NOT_FOUND`, which looks like "not found", beware): `invoice/receipt`,
 * `invoice/fiscal-checks`, `invoice/payment-direct`, `invoice/sync-payment`, QR, subscriptions,
 * `split-receiver/get-by-id`, submerchants, monopay keys, Tap-to-Phone, POS refunds. Apple Pay / Google Pay
 * tokens in `wallet/payment` answer 400.
 *
 * `pageUrl` and `tdsUrl` lead to a tiny page on the fake with "Pay" and "Decline" buttons (no token
 * needed, like the real page) that plays the customer and then redirects to `redirectUrl`.
 *
 * ### Behaviour (documented by monobank)
 * - Your test plays the customer with [payInvoice], [failInvoice], [startPayment], [expireInvoice].
 * - Status changes send a webhook to the invoice's `webHookUrl`, signed like monobank (`X-Sign`,
 *   ECDSA P-256 over the raw body, see [MonobankWebhookSigner]); the body is identical to
 *   `invoice/status`. Nothing is sent for `expired`. A card-saving invoice also gets a card webhook
 *   when `walletData.status` changes.
 * - `modifiedDate` never decreases. Amounts are minor units (kopiyky). Unpaid invoices expire after
 *   `validity` (default 24 h, clamped to 30 days). Holds work only in UAH and lapse after 9 days.
 *   `finalAmount` drops with refunds. Only payments that reached `processing` appear in the statement.
 *
 * ### Verified against monobank's test merchant (fiscal receipts and split payments on)
 * - Any successful refund, partial or full, makes the invoice `reversed`; `finalAmount` keeps what is
 *   left (e.g. 100 after refunding 50 of 150), `cancelList` shows the refund, and a webhook is sent.
 *   A `reversed` invoice with money left can be refunded further.
 * - A partial `invoice/finalize` answers `success`, but the invoice stays `processing` (with
 *   `finalAmount` already the captured amount) before it becomes `success` (more than 30 s live; here
 *   [Builder.finalizeSettlementDelay], 2 s by default, or [settleFinalize]); webhooks: `processing`,
 *   then `success`. Meanwhile `invoice/cancel` answers 404 `1004` "order not finished". A full finalize
 *   becomes `success` at once (not observed live).
 * - Fractional item `qty` is accepted in finalize and refund `items` (0.75 × 200 = 150, 0.25 × 200 = 50).
 * - A saved-card hold (`wallet/payment` with `paymentType` hold, no 3-D Secure) answers `processing`
 *   without `tdsUrl`, while `invoice/status` already says `hold` (the fake moves it at once, with
 *   `processing` and `hold` webhooks). Its status has `paymentInfo.paymentMethod` `wallet` and a
 *   top-level `payMethod` `wallet`, which the spec does not list.
 * - Date precision: payment answers (`wallet/payment`, and by inference `invoice/cancel`) carry
 *   microseconds in Kyiv time (`2026-11-12T19:04:33.525289+02:00`); `invoice/status`, and by inference
 *   webhooks and the statement, send whole seconds in UTC (`2026-11-12T17:04:33Z`). So the answer's
 *   `modifiedDate` can look later than the newer `hold` status: compare dates to the second (the SDK's
 *   `isOlderThan` / `isNewerThan` do). `modifiedDate` never goes backwards, but two changes within one
 *   second carry the same date, as at monobank.
 *
 * ### Where the fake invents behaviour (monobank does not document it)
 * - `wallet/payment` holds are accepted in any currency (monobank documents no limit there);
 *   `invoice/create` holds outside UAH get 400 `1001` "hold payments are allowed only for UAH",
 *   as verified on monobank's test merchant.
 * - A saved-card debit without 3-D Secure answers `success` at once (not observed live; holds were).
 * - A paying customer produces a `processing` webhook before the final one
 *   ([Builder.sendProcessingWebhooks] turns it off).
 * - The card webhook (`walletData` `new` → `created`/`failed`) is separate and keeps the invoice's
 *   status and `modifiedDate`. The token is already in `walletData` while `new`, but can be charged
 *   only once `created` (before that: 400 `TOKEN_NOT_FOUND`).
 * - A `walletId` is generated when `saveCardData.walletId` is absent. Verification invoices end
 *   `success` with `finalAmount` 0 and are listed in the statement.
 * - `invoice/remove` turns a `created` invoice `expired`. Removing an already `expired` invoice answers
 *   200 again (idempotent: the goal is reached); removing a paid or paying invoice answers 400 `BAD_REQUEST`.
 * - `invoice/cancel` answers `processing` and completes at once ([RefundSettlement] chooses manual
 *   completion, or an immediate `success`/`failure` answer). Errors: more than is left, or already
 *   refunded → 400 `WRONG_CANCEL_AMOUNT`; a partial release of an unfinalized hold → 400
 *   `HOLD_INVOICE_NOT_FINALIZED`; a second release while one is pending → 400 `WRONG_CANCEL_AMOUNT`;
 *   an unpaid invoice → 500 `CANCEL_NOT_AVAILABLE`. A hold release makes the invoice `reversed`, `finalAmount` 0.
 * - `invoice/finalize` on anything but an unfinalized hold (also a lapsed one, or one being released),
 *   or for more than the hold → 400 `BAD_REQUEST`.
 * - A hold lapses at exactly 9 days: `reversed`, `finalAmount` 0, with a webhook. Expirations and
 *   lapses apply on the next request or control call, or [advanceClock] / [applyDueChanges].
 * - Statement: a `reversed` payment shows its last status before the reversal (`success` for a
 *   refunded payment, `hold` for a released or lapsed hold, which never shows as `success`) with its
 *   refunds in `cancelList`; `profitAmount` = amount − fee for `success` only. The fee is 1.3 %
 *   ([Builder.feePercent]).
 * - `merchantPaymInfo.basketOrder` must add up to `amount` (400 `INVALID_MERCHANT_PAYM_INFO`;
 *   [Builder.validateBasketTotal] turns it off).
 * - Webhooks are tried 3 times, 200 ms apart, until the receiver answers 200 ([Builder.webhookRetries]).
 *   They go out one at a time, in order, on one thread: a slow receiver delays later webhooks.
 * - Merchants with fiscal receipts or split payments ([Builder.fiscalization], [Builder.splitPayments]):
 *   the basket rules of `invoice/create` and the partial-finalize rule are verified against monobank's
 *   test merchant; the same rules on `wallet/payment`, the partial-`invoice/cancel` rule (400 `1001` without
 *   `items`) and the exemption of verification invoices are inferred.
 * - On those merchants, `items` sent with `invoice/finalize` must each match a line of the hold's basket
 *   by name, code, `sum` (unit price) and `splitReceiverId`; only `qty` may differ. A mismatch answers 400
 *   `1001` "finalization basket contains item not found in hold: <name>_<code>_<sum>_<splitReceiverId>"
 *   for the first unmatched item (verified live). Inferred: an item's `qty` may not exceed the hold line's
 *   ("... item exceeds the hold: ..."), and qty × sum over the items must equal the amount ("wrong amount
 *   for finalization"). A partial `invoice/cancel` with `items` is checked the same way against the paid
 *   (or finalized) basket ("refund basket contains item not found in invoice: ...", "wrong amount for
 *   refund"; all inferred). Fractional `qty` is accepted (verified, see above).
 * - The settling window after a partial finalize is applied to every merchant, not only fiscal or split ones.
 *
 * ### Faults and webhook control
 * [injectFault] (delay before or after processing, answer after webhooks, timeout, dropped
 * connection, 5xx, 429 with or without `Retry-After`, any canned answer, each with or without the
 * request being performed); [pause] / [resume] for "connection refused" (the client's
 * `RequestOutcome.NOT_SENT`). Webhooks: manual delivery, delay, selective drop / duplicate / corrupt
 * ([dropNextWebhooks], [dropPendingWebhooks], [deliverPendingWebhooks]), [redeliver], hand-built
 * [sendSignedWebhook] / [sendWebhook].
 *
 * ### Merchants
 * By default one merchant answers to [Builder.tokens] (all aliases of that merchant). Add more with
 * [Builder.addMerchant]: each has its own tokens, key, invoices and wallets ([merchant]).
 *
 * ### Sharing one fake
 * Predicates passed to `awaitWebhooks`, `injectFault`, `dropNextWebhooks` and similar run while the
 * fake holds an internal lock: they must only look at the webhook or request they are given and
 * must not call the fake (for example `fake.invoice(id)`), or they can deadlock with a request in flight.
 * Faults are matched per path (and optionally per request, see [injectFault] with a predicate),
 * counts and recorded webhooks are global, and [reset] clears everything. Use one fake per test
 * class, or don't use global faults, global waits or [reset] from tests that run in parallel: match
 * faults by request and wait with [awaitWebhooks] for an invoice id. [requests] and [sentWebhooks]
 * grow until [reset].
 *
 * ### Containers (Testcontainers)
 * The fake listens on 127.0.0.1 by default, so it is not reachable from other machines. For an
 * application in a container, call `Testcontainers.exposeHostPorts(fake.port)` before starting it and
 * give it `http://host.testcontainers.internal:<port>`; set [Builder.advertisedHost] to
 * `host.testcontainers.internal` so `pageUrl`/`tdsUrl` match. The SDK client inside the container
 * must opt in to plain http on that host: `MonobankClientOptions.builder().baseUrl(fake.baseUrl)
 * .allowPlainHttp(true)` (or [containerClientOptionsBuilder]). Docker networks that reach the host
 * through its bridge IP (`host.docker.internal` with `host-gateway`, docker-compose) need
 * [Builder.bindAddress], which exposes a fake that accepts payments: test machines only.
 *
 * Kotlin:
 * ```kotlin
 * FakeMonobankAcquiring().start().use { fake ->
 *     val mono = MonobankAcquiring(AcquiringConfig.builder(fake.token).options(fake.clientOptions()).build())
 *     val receiver = startMyWebhookEndpoint() // e.g. a JDK HttpServer answering 200
 *     val invoice = mono.invoices.create(CreateInvoiceRequest.builder(4200).webHookUrl(receiver.url).build())
 *     fake.payInvoice(invoice.invoiceId)
 *     val webhooks = fake.awaitWebhooks(invoice.invoiceId, 2, Duration.ofSeconds(5)) // processing, success
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
 * Thread-safe: it serves concurrent requests, and the control methods can be called from any
 * thread. No method throws a checked exception. [close] stops the server and every thread it started.
 */
@Suppress("TooManyFunctions", "LargeClass")
public class FakeMonobankAcquiring private constructor(
    builder: Builder,
) : AutoCloseable {
    /** A fake with default settings: token [DEFAULT_TOKEN], a new key, real time, automatic webhooks. */
    public constructor() : this(Builder())

    /** The fake's clock: invoice validity, holds and every reported date follow it. */
    public val clock: TestClock = builder.clock

    /** Prefix of every thread this fake starts, e.g. `fake-monobank-3-http-1` (unique per fake). */
    internal val threadPrefix: String = "fake-monobank-${INSTANCES.incrementAndGet()}"
    private val stopped = CountDownLatch(1)
    private val timer = ScheduledThreadPoolExecutor(1, NamedThreadFactory("$threadPrefix-timer"))
    private val webhooks =
        WebhookDispatcher(
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
            threadPrefix = threadPrefix,
        )
    private val bank =
        Bank(
            FakeSettings(
                clock = clock,
                feePercent = builder.feePercent,
                sendProcessingWebhooks = builder.sendProcessingWebhooks,
                validateBasketTotal = builder.validateBasketTotal,
                refundSettlement = builder.refundSettlement,
                finalizeSettlementDelay = builder.finalizeSettlementDelay,
            ),
            Ids(builder.seed?.let { Random(it) } ?: SecureRandom()),
            webhooks::enqueue,
            ::wakeUp,
        )
    private val merchantList: List<FakeMerchant>
    private val bindAddress: InetAddress = builder.bindAddress
    private val advertisedHost: String? = builder.advertisedHost

    init {
        val setups =
            listOf(
                MerchantSetup
                    .builder(builder.tokens.first(), *builder.tokens.drop(1).toTypedArray())
                    .details(builder.merchantId, builder.merchantName, builder.edrpou)
                    .fiscalization(builder.fiscalization)
                    .splitPayments(builder.splitPayments)
                    .apply { builder.signer?.let { signer(it) } }
                    .apply { builder.splitReceivers?.let { splitReceivers(it) } }
                    .build(),
            ) + builder.extraMerchants
        val allTokens = setups.flatMap { it.tokens }
        require(allTokens.toSet().size == allTokens.size) { "every token must belong to one merchant only" }
        require(setups.map { it.merchantId }.toSet().size == setups.size) { "merchant ids must be unique" }
        merchantList =
            setups.map { setup ->
                val state =
                    MerchantState(
                        bank,
                        MerchantInfo(
                            setup.merchantId,
                            setup.merchantName,
                            setup.edrpou,
                            setup.fiscalization,
                            setup.splitPayments,
                            setup.splitReceivers,
                        ),
                        setup.tokens.toSet(),
                        setup.signer,
                    )
                bank.merchants.add(state)
                FakeMerchant(state, bank, webhooks)
            }
    }

    /** The default merchant: the one configured by [Builder.tokens], [Builder.signer] and [Builder.merchant]. */
    public val merchant: FakeMerchant = merchantList.first()

    /** Signs the default merchant's webhooks; its key is served by `GET /api/merchant/pubkey`. */
    public val signer: MonobankWebhookSigner get() = merchant.signer

    /** The default merchant's first `X-Token`; give it to the client under test. */
    public val token: String get() = merchant.token

    private val server =
        FakeServer(
            routes = AcquiringRoutes(bank) { baseUrl },
            pages = HostedPages(bank),
            dispatcher = webhooks,
            merchantsByToken = merchantList.flatMap { m -> m.state.tokens.map { it to m.state } }.toMap(),
            clock = clock,
            hangLimit = builder.hangLimit,
            bindAddress = bindAddress,
            threadPrefix = threadPrefix,
        )
    private val requestedPort = builder.port
    private val lifecycle = Any()

    @Volatile
    private var boundPort: Int = -1

    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    private fun wakeUp(delay: Duration) {
        if (closed) return
        try {
            // A little after the delay, so the fake clock (real time + offset) has passed it.
            timer.schedule({ applyDueChanges() }, delay.toMillis() + WAKE_UP_MARGIN_MS, TimeUnit.MILLISECONDS)
        } catch (
            @Suppress("SwallowedException") e: RejectedExecutionException,
        ) {
            // closing
        }
    }

    /** The merchant that accepts [token]. @throws IllegalArgumentException when no merchant does. */
    public fun merchant(token: String): FakeMerchant =
        requireNotNull(merchantList.firstOrNull { token in it.state.tokens }) { "no merchant accepts this token" }

    /** Every merchant, the default one first. */
    public fun merchants(): List<FakeMerchant> = merchantList

    // ---- Lifecycle ----

    /**
     * Starts serving (on 127.0.0.1 and a random free port unless [Builder.bindAddress] /
     * [Builder.port] say otherwise).
     *
     * @return this fake, for chaining.
     * @throws IllegalStateException when it was already started or closed.
     * @throws UncheckedIOException when the port cannot be bound (e.g. a fixed port in use), or, with a
     *   wildcard [Builder.bindAddress], when 127.0.0.1 on that port reaches another program (random ports are
     *   retried a few times).
     */
    public fun start(): FakeMonobankAcquiring =
        apply {
            synchronized(lifecycle) {
                check(!closed) { "the fake is closed" }
                check(boundPort < 0) { "the fake is already started" }
                boundPort = server.start(requestedPort)
            }
        }

    /**
     * Stops listening, keeping every invoice, card and setting: clients now get "connection refused"
     * (the SDK reports `RequestOutcome.NOT_SENT`). Requests in progress are cut off. [resume] listens
     * again on the same port. Does nothing when already paused.
     *
     * @throws IllegalStateException when not started, or closed.
     */
    public fun pause() {
        synchronized(lifecycle) {
            check(!closed && boundPort >= 0) { "the fake is not running" }
            if (paused) return
            server.pause()
            paused = true
        }
    }

    /**
     * Listens again on the same port after [pause]. Does nothing when the fake is not paused, so it
     * is safe in `@AfterEach` cleanup.
     *
     * @throws IllegalStateException when the fake is closed.
     * @throws UncheckedIOException when the port was taken meanwhile.
     */
    public fun resume() {
        synchronized(lifecycle) {
            check(!closed) { "the fake is closed" }
            if (!paused) return
            server.start(boundPort)
            paused = false
        }
    }

    /** The port it listens on. @throws IllegalStateException before [start]. */
    public val port: Int
        get() = boundPort.also { check(it >= 0) { "the fake is not started" } }

    /**
     * Base URL the fake advertises (in `pageUrl`, `tdsUrl`), e.g. `http://127.0.0.1:54321`, or with
     * [Builder.advertisedHost] e.g. `http://host.testcontainers.internal:54321`.
     *
     * @throws IllegalStateException before [start].
     */
    public val baseUrl: String
        get() = advertisedHost?.let { "http://$it:$port" } ?: localBaseUrl

    /** URL for clients on this machine, e.g. `http://127.0.0.1:54321`. @throws IllegalStateException before [start]. */
    public val localBaseUrl: String
        get() {
            val host = if (bindAddress.isAnyLocalAddress) "127.0.0.1" else literal(bindAddress)
            return "http://$host:$port"
        }

    /** Client options pointing at this fake ([localBaseUrl]; the SDK's defaults otherwise). */
    public fun clientOptions(): MonobankClientOptions = clientOptionsBuilder().build()

    /** A client options builder pointing at this fake ([localBaseUrl]), to adjust timeouts or retries. */
    public fun clientOptionsBuilder(): MonobankClientOptions.Builder =
        MonobankClientOptions.builder().baseUrl(localBaseUrl)

    /**
     * A client options builder for an application in a container: [baseUrl] (the advertised host,
     * e.g. `host.testcontainers.internal`) with plain http allowed. Pass its values to the
     * application's configuration.
     */
    public fun containerClientOptionsBuilder(): MonobankClientOptions.Builder =
        MonobankClientOptions.builder().baseUrl(baseUrl).allowPlainHttp(true)

    /**
     * Stops the server and the webhook sender, ends hanging requests, and waits (up to a few seconds)
     * for every thread the fake started to finish; webhook deliveries in progress are cut off.
     * Pending webhooks are discarded. Idempotent; never throws a checked exception (an interrupt is
     * kept on the thread).
     */
    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
        }
        stopped.countDown()
        timer.shutdownNow()
        server.stop()
        webhooks.close()
        try {
            timer.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Forgets every invoice, card, refund, recorded request and webhook, queued and scheduled
     * webhook, fault, webhook rule and queued wallet payment outcome of every merchant. Keeps the
     * merchants, keys, tokens, settings and the clock.
     */
    public fun reset() {
        bank.reset()
        server.reset()
        webhooks.reset()
    }

    // ---- The customer and monobank's back office (on the merchant that owns the invoice) ----

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
    ): FakeInvoice = owner(invoiceId).payInvoice(invoiceId, payment)

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
    ): FakeInvoice = owner(invoiceId).startPayment(invoiceId, payment)

    /**
     * Expires [invoiceId] now (`created` or `processing` → `expired`), as when its validity runs
     * out or the customer abandons 3-D Secure. Like monobank, no webhook is sent.
     *
     * @throws IllegalStateException when the invoice is in another status.
     */
    public fun expireInvoice(invoiceId: String): FakeInvoice = owner(invoiceId).expireInvoice(invoiceId)

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
    ): FakeInvoice = owner(invoiceId).settleRefund(invoiceId, extRef, success)

    /**
     * A refund of [amount] (minor units) made outside the API, e.g. in monobank's web portal or a
     * chargeback: it appears in `cancelList` without an `extRef`, completed, with a webhook.
     *
     * @throws IllegalStateException when the invoice is not `success` or [amount] is more than is left.
     */
    public fun refundExternally(
        invoiceId: String,
        amount: Long,
    ): FakeInvoice = owner(invoiceId).refundExternally(invoiceId, amount)

    /**
     * Settles a partial capture now: the invoice goes from `processing` to `success`, with a webhook
     * (see [Builder.finalizeSettlementDelay]).
     *
     * @throws IllegalStateException when the invoice is not settling a partial capture.
     */
    public fun settleFinalize(invoiceId: String): FakeInvoice = owner(invoiceId).settleFinalize(invoiceId)

    /** Queues the outcome of the default merchant's next `wallet/payment`; without one, a charge succeeds. */
    public fun enqueueWalletPaymentOutcome(outcome: WalletPaymentOutcome) {
        merchant.enqueueWalletPaymentOutcome(outcome)
    }

    /**
     * Saves a Visa card into the default merchant's [walletId] directly and returns its token. Saving
     * the same [maskedPan] into the same wallet again returns the same token.
     */
    @JvmOverloads
    public fun addCard(
        walletId: String,
        maskedPan: String = CustomerPayment.DEFAULT_MASKED_PAN,
    ): String = merchant.addCard(walletId, maskedPan)

    /** Sets how later refunds complete, for every merchant. */
    public fun setRefundSettlement(settlement: RefundSettlement) {
        bank.settings.refundSettlement = settlement
    }

    /**
     * Moves [clock] forward and applies what became due: unpaid invoices expire, holds lapse (with a
     * webhook). The application under test keeps its own clock, so a statement window it computes from
     * real time may no longer cover the fake's dates.
     */
    public fun advanceClock(duration: Duration) {
        clock.advance(duration)
        applyDueChanges()
    }

    /** Applies expirations and hold lapses that are due by [clock], e.g. after moving a shared [TestClock]. */
    public fun applyDueChanges() {
        bank.locked { }
    }

    /** Rotates the default merchant's webhook key; returns the new public key. */
    public fun rotateKey(): String = merchant.rotateKey()

    /** A snapshot of [invoiceId] (of any merchant), or `null` when the fake has no such invoice. */
    public fun invoice(invoiceId: String): FakeInvoice? = merchantList.firstNotNullOfOrNull { it.invoice(invoiceId) }

    /** Snapshots of every invoice of every merchant, oldest first per merchant. */
    public fun invoices(): List<FakeInvoice> = merchantList.flatMap { it.invoices() }

    private fun owner(invoiceId: String): FakeMerchant {
        val state = bank.locked { bank.owner(invoiceId) }
        return merchantList.first { it.state === state }
    }

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
     * [MonobankPaths.ANY] for every path). Faults queued for one path apply in order; path-specific
     * faults take precedence over [MonobankPaths.ANY].
     *
     * @throws IllegalArgumentException for a path that is not a served route (or contains a query).
     */
    public fun injectFault(
        path: String,
        fault: Fault,
        times: Int,
    ) {
        injectFault(path, fault, times) { true }
    }

    /**
     * Applies [fault] to the next [times] requests on [path] that match [request], e.g. the one
     * whose body carries your reference: `{ it.body.contains("\"order-42\"") }`. Lets tests that
     * share a fake inject faults without catching each other's requests.
     */
    public fun injectFault(
        path: String,
        fault: Fault,
        times: Int,
        request: Predicate<FakeRequest>,
    ) {
        require(times > 0) { "times must be positive" }
        require(path == MonobankPaths.ANY || path in MonobankPaths.ROUTES) {
            "path must be one of MonobankPaths (without a query), or MonobankPaths.ANY"
        }
        server.faults.add(path, fault, times, request)
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

    /**
     * Switches between automatic and manual webhook delivery. Switching to manual also holds back
     * webhooks already waiting for their delay; switching to automatic sends what is queued.
     */
    public fun setWebhookDelivery(delivery: WebhookDelivery) {
        webhooks.setMode(delivery)
    }

    /** Waits [delay] before each later automatic webhook delivery. */
    public fun setWebhookDelay(delay: Duration) {
        require(!delay.isNegative) { "delay must not be negative" }
        webhooks.setDelay(delay)
    }

    /** Sends every queued webhook now, in order, on the calling thread; returns what was recorded. */
    public fun deliverWebhooksNow(): List<RecordedWebhook> = webhooks.deliverPending(false) { true }

    /** Sends every queued webhook now, newest first (out of order, as monobank may); returns what was recorded. */
    public fun deliverWebhooksInReverseOrder(): List<RecordedWebhook> = webhooks.deliverPending(true) { true }

    /** Sends the queued webhooks matching [filter] now, in order, on the calling thread; the rest stay queued. */
    public fun deliverPendingWebhooks(filter: Predicate<PendingWebhook>): List<RecordedWebhook> =
        webhooks.deliverPending(false, filter)

    /**
     * Drops the queued webhooks matching [filter] (recorded as [WebhookOutcome.DROPPED], never sent),
     * e.g. the card webhook: `{ it.kind == WebhookKind.CARD }`.
     */
    public fun dropPendingWebhooks(filter: Predicate<PendingWebhook>): List<RecordedWebhook> =
        webhooks.dropPending(filter)

    /** Webhooks waiting to be sent (manual delivery or a delay), oldest first. */
    public fun pendingWebhooks(): List<PendingWebhook> = webhooks.pending()

    /** How many webhooks wait to be sent. */
    public fun pendingWebhookCount(): Int = webhooks.pending().size

    /** The next [count] webhooks are lost: recorded as [WebhookOutcome.DROPPED] and never sent. */
    public fun dropNextWebhooks(count: Int) {
        dropNextWebhooks(count) { true }
    }

    /**
     * The next [count] webhooks matching [filter] are lost, e.g. a lost card webhook:
     * `dropNextWebhooks(1) { it.kind == WebhookKind.CARD }`. Works with automatic delivery.
     */
    public fun dropNextWebhooks(
        count: Int,
        filter: Predicate<PendingWebhook>,
    ) {
        addRule(RuleAction.DROP, count, filter)
    }

    /** The next [count] webhooks are each sent twice with the same body. */
    public fun duplicateNextWebhooks(count: Int) {
        duplicateNextWebhooks(count) { true }
    }

    /** The next [count] webhooks matching [filter] are each sent twice with the same body. */
    public fun duplicateNextWebhooks(
        count: Int,
        filter: Predicate<PendingWebhook>,
    ) {
        addRule(RuleAction.DUPLICATE, count, filter)
    }

    /** The next [count] webhooks carry an `X-Sign` made with a different key, so verification fails. */
    public fun corruptNextWebhookSignatures(count: Int) {
        corruptNextWebhookSignatures(count) { true }
    }

    /** The next [count] webhooks matching [filter] carry an `X-Sign` made with a different key. */
    public fun corruptNextWebhookSignatures(
        count: Int,
        filter: Predicate<PendingWebhook>,
    ) {
        addRule(RuleAction.CORRUPT, count, filter)
    }

    private fun addRule(
        action: RuleAction,
        count: Int,
        filter: Predicate<PendingWebhook>,
    ) {
        require(count > 0) { "count must be positive" }
        webhooks.addRule(action, count, filter)
    }

    /**
     * Sends a recorded webhook again now, on the calling thread: the same body bytes, signed with its
     * merchant's current key (so a valid signature even when the original was corrupted), with
     * retries. For "the receiver was down, then the webhook came again".
     */
    public fun redeliver(webhook: RecordedWebhook): RecordedWebhook = webhooks.redeliver(webhook.event)

    /**
     * POSTs [body] to [url] now, on the calling thread, signed with the default merchant's key (use
     * [FakeMerchant.sendSignedWebhook] for another merchant). For hand-built cases: an unknown
     * reference, a mismatched `invoiceId`, an amount that does not match, an unknown status.
     */
    public fun sendSignedWebhook(
        url: String,
        body: String,
    ): RecordedWebhook = merchant.sendSignedWebhook(url, body)

    /**
     * POSTs [body] to [url] now with [xSign] exactly as given (`null`: no `X-Sign` header), e.g. a
     * missing header or a signature that is not base64.
     */
    public fun sendWebhook(
        url: String,
        body: String,
        xSign: String?,
    ): RecordedWebhook = webhooks.sendRaw(merchant.customEvent(url, body), xSign)

    /** Every webhook sent, failed or dropped so far, in the order they finished. */
    public fun sentWebhooks(): List<RecordedWebhook> = webhooks.recorded()

    /**
     * Waits until at least [count] webhooks are recorded (sent, failed or dropped) and returns all of them.
     *
     * @throws IllegalStateException when fewer arrive within [timeout], or the thread is interrupted
     *   (the interrupt flag is kept).
     */
    public fun awaitWebhooks(
        count: Int,
        timeout: Duration,
    ): List<RecordedWebhook> = webhooks.await(count, timeout) { true }

    /**
     * Waits until at least [count] webhooks about [invoiceId] are recorded and returns them. Safe in a
     * fake shared between tests.
     *
     * @throws IllegalStateException when fewer arrive within [timeout], or the thread is interrupted.
     */
    public fun awaitWebhooks(
        invoiceId: String,
        count: Int,
        timeout: Duration,
    ): List<RecordedWebhook> = webhooks.await(count, timeout) { it.invoiceId == invoiceId }

    /**
     * Waits until at least [count] recorded webhooks match [filter] and returns them.
     *
     * @throws IllegalStateException when fewer arrive within [timeout], or the thread is interrupted.
     */
    public fun awaitWebhooks(
        count: Int,
        timeout: Duration,
        filter: Predicate<RecordedWebhook>,
    ): List<RecordedWebhook> = webhooks.await(count, timeout, filter)

    override fun toString(): String =
        "FakeMonobankAcquiring(baseUrl=${if (boundPort >= 0) baseUrl else "not started"}, " +
            "merchants=${merchantList.size}, paused=$paused, closed=$closed)"

    /** Settings for [FakeMonobankAcquiring]. */
    public class Builder internal constructor() {
        internal var tokens: List<String> = listOf(DEFAULT_TOKEN)
        internal var signer: MonobankWebhookSigner? = null
        internal var clock: TestClock = TestClock.system()
        internal var port: Int = 0
        internal var bindAddress: InetAddress = InetAddress.getByName("127.0.0.1")
        internal var advertisedHost: String? = null
        internal var seed: Long? = null
        internal var merchantId: String = "12o4Vv7EWy"
        internal var merchantName: String = "Fake Merchant"
        internal var edrpou: String = "4242424242"
        internal val extraMerchants: MutableList<MerchantSetup> = mutableListOf()
        internal var fiscalization: Boolean = false
        internal var splitPayments: Boolean = false
        internal var splitReceivers: List<SplitReceiver>? = null
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
        internal var finalizeSettlementDelay: Duration? = Duration.ofSeconds(2)

        /**
         * How long a partially finalized invoice stays `processing` before it becomes `success` (default
         * 2 s, measured on the fake clock; monobank took more than 30 s live). `null` keeps it `processing`
         * until [FakeMonobankAcquiring.settleFinalize]. While it settles, `invoice/cancel` answers
         * 404 `1004` "order not finished", as monobank does.
         */
        public fun finalizeSettlementDelay(delay: Duration?): Builder =
            apply {
                require(delay == null || !delay.isNegative) { "delay must not be negative" }
                finalizeSettlementDelay = delay
            }

        /**
         * Accepted `X-Token` values of the default merchant (default [DEFAULT_TOKEN]); they are aliases
         * of one merchant, and the first is [FakeMonobankAcquiring.token]. For separate merchants, use [addMerchant].
         */
        public fun tokens(vararg tokens: String): Builder =
            apply {
                require(tokens.isNotEmpty() && tokens.all { it.isNotBlank() }) { "tokens must not be empty or blank" }
                this.tokens = tokens.toList()
            }

        /** The default merchant's webhook signer, e.g. one with a fixed key (default: a newly generated key). */
        public fun signer(signer: MonobankWebhookSigner): Builder = apply { this.signer = signer }

        /** What `GET /api/merchant/details` returns for the default merchant. */
        public fun merchant(
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

        /** Fiscal receipts for the default merchant (default off); see [MerchantSetup.Builder.fiscalization]. */
        public fun fiscalization(enabled: Boolean): Builder = apply { fiscalization = enabled }

        /** Split payments for the default merchant (default off); see [MerchantSetup.Builder.splitPayments]. */
        public fun splitPayments(enabled: Boolean): Builder = apply { splitPayments = enabled }

        /** The default merchant's split receivers; see [MerchantSetup.Builder.splitReceivers]. */
        public fun splitReceivers(vararg receivers: SplitReceiver): Builder =
            apply {
                splitReceivers =
                    receivers.toList()
            }

        /** Adds another merchant with its own tokens, key, invoices and wallets. */
        public fun addMerchant(setup: MerchantSetup): Builder = apply { extraMerchants.add(setup) }

        /** The clock (default [TestClock.system]). */
        public fun clock(clock: TestClock): Builder = apply { this.clock = clock }

        /** The port to listen on (default 0: a random free port). */
        public fun port(port: Int): Builder =
            apply {
                require(port in 0..65_535) { "port must be 0..65535" }
                this.port = port
            }

        /**
         * The address to listen on (default 127.0.0.1). Anything but a loopback address makes the fake,
         * which accepts payments with a known token, reachable from other machines: use it only on test
         * machines, e.g. for docker-compose networks.
         */
        public fun bindAddress(address: InetAddress): Builder = apply { bindAddress = address }

        /**
         * The host the fake puts into the URLs it returns ([baseUrl], `pageUrl`, `tdsUrl`), e.g.
         * `host.testcontainers.internal`. Default: the bind address.
         */
        public fun advertisedHost(host: String?): Builder =
            apply {
                require(host == null || (host.isNotBlank() && host.none { it == '/' || it.isWhitespace() })) {
                    "advertisedHost must be a host name"
                }
                advertisedHost = host
            }

        /** Seeds the generator of invoice ids, card tokens and codes, for reproducible ids (default: random). */
        public fun seed(seed: Long): Builder = apply { this.seed = seed }

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
         * 200) and the pause between them (default 200 ms; monobank's spacing is not documented).
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

        /** How refunds complete and what `invoice/cancel` answers (default [RefundSettlement.AUTOMATIC]). */
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

        /** How long a [Fault.timeout] (or [Fault.answerAfterWebhooks]) holds a request at most (default 2 minutes). */
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

        private const val WAKE_UP_MARGIN_MS = 20L
        private val INSTANCES = AtomicInteger()
        private const val SHUTDOWN_SECONDS = 5L

        /** Starts configuring a fake. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        private fun literal(address: InetAddress): String =
            if (address is Inet6Address) "[${address.hostAddress.substringBefore('%')}]" else address.hostAddress
    }
}
