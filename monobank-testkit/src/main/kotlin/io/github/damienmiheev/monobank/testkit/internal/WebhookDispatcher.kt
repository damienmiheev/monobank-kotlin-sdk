package io.github.damienmiheev.monobank.testkit.internal

import io.github.damienmiheev.monobank.testkit.MonobankWebhookSigner
import io.github.damienmiheev.monobank.testkit.PendingWebhook
import io.github.damienmiheev.monobank.testkit.RecordedWebhook
import io.github.damienmiheev.monobank.testkit.WebhookDelivery
import io.github.damienmiheev.monobank.testkit.WebhookKind
import io.github.damienmiheev.monobank.testkit.WebhookOutcome
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.KeyPair
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Predicate
import kotlin.concurrent.withLock

/** A webhook to send: the body is fixed when the invoice changes, the signature when it is sent. */
internal class WebhookEvent(
    val invoiceId: String,
    val status: String,
    val url: String,
    val body: ByteArray,
    val kind: WebhookKind,
    val walletStatus: String?,
    val merchantId: String,
    val signer: MonobankWebhookSigner,
) {
    /** Set once the event was sent, failed, dropped or discarded. Guarded by the dispatcher's lock. */
    var done: Boolean = false
}

/** Fixed webhook transport settings. */
internal class WebhookSettings(
    val maxAttempts: Int,
    val retryInterval: Duration,
    val timeout: Duration,
)

/** Daemon threads with a recognisable name, so a leak would be easy to spot. */
internal class NamedThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
}

/** What a webhook rule does to the webhooks it matches. */
internal enum class RuleAction { DROP, DUPLICATE, CORRUPT }

/**
 * Sends signed webhooks like monobank: POST, `Content-Type: application/json`, `X-Sign`, up to
 * [WebhookSettings.maxAttempts] tries until the receiver answers 200. Adds the test controls:
 * manual delivery, delay, selective drop / duplicate / corrupt, replay. Thread-safe.
 */
internal class WebhookDispatcher(
    private val clock: Clock,
    private val settings: WebhookSettings,
    mode: WebhookDelivery,
    delay: Duration,
    private val stopped: CountDownLatch,
) : AutoCloseable {
    private class Rule(
        val action: RuleAction,
        var remaining: Int,
        val predicate: Predicate<PendingWebhook>,
    )

    private val logger = LoggerFactory.getLogger(WebhookDispatcher::class.java)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val pending = ArrayList<WebhookEvent>()
    private val recorded = ArrayList<RecordedWebhook>()
    private val rules = ArrayList<Rule>()
    private val inFlight: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())
    private val executor = ScheduledThreadPoolExecutor(1, NamedThreadFactory("fake-monobank-webhooks"))
    private val captured = ThreadLocal<MutableList<WebhookEvent>?>()
    private var mode: WebhookDelivery = mode
    private var delay: Duration = delay
    private var closed = false
    private val wrongKey: KeyPair by lazy { MonobankWebhookSigner.generateKeyPair() }

    fun enqueue(event: WebhookEvent) {
        captured.get()?.add(event)
        lock.withLock {
            if (closed) {
                event.done = true
                return
            }
            pending.add(event)
            if (mode == WebhookDelivery.AUTOMATIC) schedule(event)
        }
    }

    /** Runs [block] and returns it with the webhooks it queued on this thread. */
    fun <T> capture(block: () -> T): Pair<T, List<WebhookEvent>> {
        val events = ArrayList<WebhookEvent>()
        captured.set(events)
        try {
            return block() to events
        } finally {
            captured.remove()
        }
    }

    /** Waits until every one of [events] is sent, failed, dropped or discarded, or [timeout] passes. */
    fun awaitDone(
        events: List<WebhookEvent>,
        timeout: Duration,
    ) {
        lock.withLock {
            var nanos = timeout.toNanos()
            while (events.any { !it.done } && nanos > 0 && !closed) {
                nanos =
                    try {
                        changed.awaitNanos(nanos)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
            }
        }
    }

    fun setMode(newMode: WebhookDelivery) =
        lock.withLock {
            val wasManual = mode == WebhookDelivery.MANUAL
            mode = newMode
            if (wasManual && newMode == WebhookDelivery.AUTOMATIC) pending.forEach { schedule(it) }
        }

    fun setDelay(newDelay: Duration) = lock.withLock { delay = newDelay }

    fun addRule(
        action: RuleAction,
        count: Int,
        predicate: Predicate<PendingWebhook>,
    ) = lock.withLock { rules.add(Rule(action, count, predicate)) }

    fun pending(): List<PendingWebhook> = lock.withLock { pending.map { PendingWebhook(it) } }

    fun recorded(): List<RecordedWebhook> = lock.withLock { recorded.toList() }

    /** Sends the queued webhooks matching [predicate] now, on the calling thread, in queue order (or reversed). */
    fun deliverPending(
        reversed: Boolean,
        predicate: Predicate<PendingWebhook>,
    ): List<RecordedWebhook> {
        val batch = take(predicate)
        return (if (reversed) batch.asReversed() else batch).flatMap { deliver(it) }
    }

    /** Drops the queued webhooks matching [predicate]: recorded as dropped, never sent. */
    fun dropPending(predicate: Predicate<PendingWebhook>): List<RecordedWebhook> =
        take(predicate).map { record(it, null, true, WebhookOutcome.DROPPED, 0, null, false) }

    private fun take(predicate: Predicate<PendingWebhook>): List<WebhookEvent> =
        lock.withLock {
            val selected = pending.filter { predicate.test(PendingWebhook(it)) }
            pending.removeAll(selected)
            selected
        }

    /** Sends [event] again, with a fresh signature by its merchant's current key. */
    fun redeliver(event: WebhookEvent): RecordedWebhook = send(event, corrupt = false, duplicate = true)

    /** Sends a hand-made webhook with [xSign] as given (or none). */
    fun sendRaw(
        event: WebhookEvent,
        xSign: String?,
    ): RecordedWebhook = transmit(event, xSign, signatureValid = false, duplicate = false)

    /** Waits until at least [count] recorded webhooks match [predicate], or [timeout] passes. */
    fun await(
        count: Int,
        timeout: Duration,
        predicate: Predicate<RecordedWebhook>,
    ): List<RecordedWebhook> =
        lock.withLock {
            var nanos = timeout.toNanos()
            var matching = recorded.filter { predicate.test(it) }
            while (matching.size < count && nanos > 0) {
                nanos =
                    try {
                        changed.awaitNanos(nanos)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("interrupted while waiting for webhooks", e)
                    }
                matching = recorded.filter { predicate.test(it) }
            }
            check(matching.size >= count) {
                "expected at least $count webhooks within $timeout, got ${matching.size}: $matching"
            }
            matching
        }

    /** Forgets queued and recorded webhooks and rules; already scheduled deliveries find nothing to send. */
    fun reset() =
        lock.withLock {
            pending.forEach { it.done = true }
            pending.clear()
            recorded.clear()
            rules.clear()
            changed.signalAll()
        }

    override fun close() {
        lock.withLock {
            closed = true
            pending.forEach { it.done = true }
            pending.clear()
            changed.signalAll()
        }
        executor.shutdownNow()
        // HttpURLConnection ignores interrupts: close the sockets of deliveries in progress.
        synchronized(inFlight) { inFlight.toList() }.forEach { it.disconnect() }
        try {
            executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun schedule(event: WebhookEvent) {
        executor.schedule({ deliverScheduled(event) }, delay.toNanos(), TimeUnit.NANOSECONDS)
    }

    /** A scheduled delivery sends its event only if it is still queued and delivery is still automatic. */
    private fun deliverScheduled(event: WebhookEvent) {
        val ready = lock.withLock { mode == WebhookDelivery.AUTOMATIC && pending.remove(event) }
        if (ready) deliver(event)
    }

    private fun matchRule(
        action: RuleAction,
        view: PendingWebhook,
    ): Boolean {
        val rule = rules.firstOrNull { it.action == action && it.predicate.test(view) } ?: return false
        if (--rule.remaining <= 0) rules.remove(rule)
        return true
    }

    private fun deliver(event: WebhookEvent): List<RecordedWebhook> {
        val view = PendingWebhook(event)
        val actions =
            lock.withLock {
                if (matchRule(RuleAction.DROP, view)) {
                    setOf(RuleAction.DROP)
                } else {
                    setOfNotNull(
                        RuleAction.DUPLICATE.takeIf { matchRule(it, view) },
                        RuleAction.CORRUPT.takeIf { matchRule(it, view) },
                    )
                }
            }
        if (RuleAction.DROP in actions) {
            return listOf(record(event, null, true, WebhookOutcome.DROPPED, 0, null, false))
        }
        val corrupt = RuleAction.CORRUPT in actions
        val first = send(event, corrupt, duplicate = false)
        val second = if (RuleAction.DUPLICATE in actions) send(event, corrupt, duplicate = true) else null
        return listOfNotNull(first, second)
    }

    private fun send(
        event: WebhookEvent,
        corrupt: Boolean,
        duplicate: Boolean,
    ): RecordedWebhook {
        val xSign =
            if (corrupt) MonobankWebhookSigner.signWith(wrongKey, event.body) else event.signer.sign(event.body)
        return transmit(event, xSign, !corrupt, duplicate)
    }

    private fun transmit(
        event: WebhookEvent,
        xSign: String?,
        signatureValid: Boolean,
        duplicate: Boolean,
    ): RecordedWebhook {
        var attempts = 1
        var status = post(event, xSign)
        while (status != HTTP_OK && attempts < settings.maxAttempts && !waitBeforeRetry()) {
            attempts++
            status = post(event, xSign)
        }
        val outcome = if (status == HTTP_OK) WebhookOutcome.DELIVERED else WebhookOutcome.FAILED
        logger.debug("fake monobank webhook {} {} -> {}", event.invoiceId, event.status, status)
        return record(event, xSign, signatureValid, outcome, attempts, status, duplicate)
    }

    /** Pauses between attempts; `true` when the fake is closing and retries should stop. */
    private fun waitBeforeRetry(): Boolean =
        try {
            stopped.await(settings.retryInterval.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }

    private fun post(
        event: WebhookEvent,
        xSign: String?,
    ): Int? {
        // Never through a proxy: the receiver is whatever the test configured.
        val connection = URI.create(event.url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        inFlight.add(connection)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = settings.timeout.toMillis().toInt()
            connection.readTimeout = settings.timeout.toMillis().toInt()
            connection.setRequestProperty("Content-Type", "application/json")
            xSign?.let { connection.setRequestProperty("X-Sign", it) }
            // No keep-alive: the JDK would otherwise start a cache thread that outlives the fake.
            connection.setRequestProperty("Connection", "close")
            connection.setFixedLengthStreamingMode(event.body.size)
            connection.outputStream.use { it.write(event.body) }
            val code = connection.responseCode
            (connection.errorStream ?: runCatching { connection.inputStream }.getOrNull())?.use { it.readBytes() }
            code
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            null
        } finally {
            inFlight.remove(connection)
            connection.disconnect()
        }
    }

    private fun record(
        event: WebhookEvent,
        xSign: String?,
        signatureValid: Boolean,
        outcome: WebhookOutcome,
        attempts: Int,
        status: Int?,
        duplicate: Boolean,
    ): RecordedWebhook {
        val webhook =
            RecordedWebhook(
                event = event,
                xSign = xSign,
                signatureValid = signatureValid,
                outcome = outcome,
                attempts = attempts,
                responseStatus = status,
                duplicate = duplicate,
                sentAt = if (outcome == WebhookOutcome.DROPPED) null else clock.instant(),
            )
        lock.withLock {
            recorded.add(webhook)
            event.done = true
            changed.signalAll()
        }
        return webhook
    }

    private companion object {
        private const val HTTP_OK = 200
        private const val SHUTDOWN_SECONDS = 5L
    }
}
