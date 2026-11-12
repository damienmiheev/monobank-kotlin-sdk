package io.github.damienmiheev.monobank.testkit

import com.sun.net.httpserver.HttpServer
import io.github.damienmiheev.monobank.acquiring.AcquiringConfig
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring
import io.github.damienmiheev.monobank.core.RetryPolicy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The SDK's real client pointed at [fake], with fast retries. */
fun clientFor(
    fake: FakeMonobankAcquiring,
    timeout: Duration = Duration.ofSeconds(30),
    retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ZERO),
    token: String = fake.token,
    keyRefreshInterval: Duration = Duration.ofMinutes(1),
): MonobankAcquiring =
    MonobankAcquiring(
        AcquiringConfig
            .builder(token)
            .webhookKeyRefreshInterval(keyRefreshInterval)
            .options(
                fake
                    .clientOptionsBuilder()
                    .requestTimeout(timeout)
                    .retryPolicy(retryPolicy)
                    .build(),
            ).build(),
    )

/** A webhook as the receiver saw it. */
class ReceivedWebhook(
    val xSign: String?,
    val contentType: String?,
    val body: ByteArray,
) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/**
 * A webhook endpoint on the JDK HTTP server, like the one a Kill Bill plugin exposes. Answers 200
 * unless statuses are queued with [answerNext].
 */
class WebhookReceiver : AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val answers = LinkedBlockingQueue<Int>()
    val received: MutableList<ReceivedWebhook> = CopyOnWriteArrayList()

    init {
        server.createContext("/notification") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }
            received.add(
                ReceivedWebhook(
                    exchange.requestHeaders.getFirst("X-Sign"),
                    exchange.requestHeaders.getFirst("Content-Type"),
                    body,
                ),
            )
            val status = answers.poll() ?: 200
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.executor = executor
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/notification"

    fun answerNext(vararg statuses: Int) {
        statuses.forEach { answers.add(it) }
    }

    fun await(
        count: Int,
        timeout: Duration = Duration.ofSeconds(30),
    ): List<ReceivedWebhook> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (received.size < count && System.nanoTime() < deadline) Thread.sleep(10)
        check(received.size >= count) { "expected $count webhooks, got ${received.size}" }
        return received.toList()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/** A raw HTTP call, for what the SDK client never sends (missing token, wrong method, bad JSON). */
fun rawCall(
    fake: FakeMonobankAcquiring,
    method: String,
    pathAndQuery: String,
    body: String? = null,
    token: String? = fake.token,
): HttpResponse<String> {
    val builder =
        HttpRequest
            .newBuilder(URI.create(fake.baseUrl + pathAndQuery))
            .timeout(Duration.ofSeconds(30))
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
            )
    token?.let { builder.header("X-Token", it) }
    body?.let { builder.header("Content-Type", "application/json") }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
}
