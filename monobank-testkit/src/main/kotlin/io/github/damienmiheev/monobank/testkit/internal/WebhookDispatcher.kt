package io.github.damienmiheev.monobank.testkit.internal

import io.github.damienmiheev.monobank.testkit.MonobankWebhookSigner
import io.github.damienmiheev.monobank.testkit.RecordedWebhook
import io.github.damienmiheev.monobank.testkit.WebhookDelivery
import io.github.damienmiheev.monobank.testkit.WebhookOutcome
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPair
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A webhook waiting to be sent: the body is fixed when the invoice changes. */
internal class WebhookEvent(
    val invoiceId: String,
    val status: String,
    val url: String,
    val body: ByteArray,
)

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

/**
 * Sends signed webhooks like monobank: POST, `Content-Type: application/json`, `X-Sign`, up to
 * [WebhookSettings.maxAttempts] tries until the receiver answers 200. Adds the test controls:
 * manual delivery, delay, drop, duplicate, corrupt signature. Thread-safe.
 */
internal class WebhookDispatcher(
    private val signer: MonobankWebhookSigner,
    private val clock: Clock,
    private val settings: WebhookSettings,
    mode: WebhookDelivery,
    delay: Duration,
    private val stopped: CountDownLatch,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(WebhookDispatcher::class.java)
    private val lock = ReentrantLock()
    private val recordedChanged = lock.newCondition()
    private val pending = ArrayDeque<WebhookEvent>()
    private val recorded = ArrayList<RecordedWebhook>()
    private val executor = ScheduledThreadPoolExecutor(1, NamedThreadFactory("fake-monobank-webhooks"))
    private var mode: WebhookDelivery = mode
    private var delay: Duration = delay
    private var toDrop = 0
    private var toDuplicate = 0
    private var toCorrupt = 0
    private var closed = false
    private val wrongKey: KeyPair by lazy { MonobankWebhookSigner.generateKeyPair() }

    fun enqueue(event: WebhookEvent) {
        lock.withLock {
            if (closed) return
            pending.addLast(event)
            if (mode == WebhookDelivery.AUTOMATIC) scheduleOne()
        }
    }

    fun setMode(newMode: WebhookDelivery) =
        lock.withLock {
            val wasManual = mode == WebhookDelivery.MANUAL
            mode = newMode
            if (wasManual && newMode == WebhookDelivery.AUTOMATIC) repeat(pending.size) { scheduleOne() }
        }

    fun setDelay(newDelay: Duration) = lock.withLock { delay = newDelay }

    fun dropNext(count: Int) = lock.withLock { toDrop += count }

    fun duplicateNext(count: Int) = lock.withLock { toDuplicate += count }

    fun corruptNext(count: Int) = lock.withLock { toCorrupt += count }

    fun pendingCount(): Int = lock.withLock { pending.size }

    fun recorded(): List<RecordedWebhook> = lock.withLock { recorded.toList() }

    /** Sends every queued webhook now, on the calling thread; returns what was recorded. */
    fun deliverAll(reversed: Boolean): List<RecordedWebhook> {
        val batch =
            lock.withLock {
                val all = pending.toList()
                pending.clear()
                all
            }
        return (if (reversed) batch.asReversed() else batch).flatMap { deliver(it) }
    }

    /** Waits until at least [count] webhooks are recorded, or [timeout] passes. */
    fun await(
        count: Int,
        timeout: Duration,
    ): List<RecordedWebhook> =
        lock.withLock {
            var nanos = timeout.toNanos()
            while (recorded.size < count && nanos > 0) {
                nanos = recordedChanged.awaitNanos(nanos)
            }
            check(recorded.size >= count) {
                "expected at least $count webhooks within $timeout, got ${recorded.size}: $recorded"
            }
            recorded.toList()
        }

    fun reset() =
        lock.withLock {
            pending.clear()
            recorded.clear()
            toDrop = 0
            toDuplicate = 0
            toCorrupt = 0
        }

    override fun close() {
        lock.withLock {
            closed = true
            pending.clear()
        }
        executor.shutdownNow()
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    private fun scheduleOne() {
        executor.schedule({ deliverNextQueued() }, delay.toNanos(), TimeUnit.NANOSECONDS)
    }

    private fun deliverNextQueued() {
        val event = lock.withLock { pending.removeFirstOrNull() } ?: return
        deliver(event)
    }

    private class Plan(
        val drop: Boolean,
        val duplicate: Boolean,
        val corrupt: Boolean,
    )

    private fun deliver(event: WebhookEvent): List<RecordedWebhook> {
        val plan =
            lock.withLock {
                val drop = toDrop > 0
                if (drop) toDrop--
                val duplicate = !drop && toDuplicate > 0
                if (duplicate) toDuplicate--
                val corrupt = !drop && toCorrupt > 0
                if (corrupt) toCorrupt--
                Plan(drop, duplicate, corrupt)
            }
        if (plan.drop) {
            return listOf(record(event, null, true, WebhookOutcome.DROPPED, 0, null, false))
        }
        val first = send(event, plan.corrupt, duplicate = false)
        val second = if (plan.duplicate) send(event, plan.corrupt, duplicate = true) else null
        return listOfNotNull(first, second)
    }

    private fun send(
        event: WebhookEvent,
        corrupt: Boolean,
        duplicate: Boolean,
    ): RecordedWebhook {
        val xSign =
            if (corrupt) MonobankWebhookSigner.signWith(wrongKey, event.body) else signer.sign(event.body)
        var attempts = 1
        var status = post(event, xSign)
        while (status != HTTP_OK && attempts < settings.maxAttempts && !waitBeforeRetry()) {
            attempts++
            status = post(event, xSign)
        }
        val outcome = if (status == HTTP_OK) WebhookOutcome.DELIVERED else WebhookOutcome.FAILED
        logger.debug("fake monobank webhook {} {} -> {}", event.invoiceId, event.status, status)
        return record(event, xSign, !corrupt, outcome, attempts, status, duplicate)
    }

    /** Pauses between attempts; `true` when the fake is closing and retries should stop. */
    private fun waitBeforeRetry(): Boolean = stopped.await(settings.retryInterval.toNanos(), TimeUnit.NANOSECONDS)

    private fun post(
        event: WebhookEvent,
        xSign: String,
    ): Int? {
        val connection = URI.create(event.url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = settings.timeout.toMillis().toInt()
            connection.readTimeout = settings.timeout.toMillis().toInt()
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Sign", xSign)
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
                invoiceId = event.invoiceId,
                status = event.status,
                url = event.url,
                bodyBytes = event.body,
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
            recordedChanged.signalAll()
        }
        return webhook
    }

    private companion object {
        const val HTTP_OK = 200
        const val SHUTDOWN_SECONDS = 5L
    }
}
