package io.github.damienmiheev.monobank.testkit.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.damienmiheev.monobank.testkit.FakeRequest
import io.github.damienmiheev.monobank.testkit.Fault
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate

/** Faults registered per path (optionally per request), consumed one request at a time. Thread-safe. */
internal class FaultRegistry {
    private class Rule(
        val fault: Fault,
        var remaining: Int,
        val predicate: Predicate<FakeRequest>,
    )

    private val rules = LinkedHashMap<String, MutableList<Rule>>()

    @Synchronized
    fun add(
        path: String,
        fault: Fault,
        times: Int,
        predicate: Predicate<FakeRequest>,
    ) {
        rules.getOrPut(path) { ArrayList() }.add(Rule(fault, times, predicate))
    }

    /** The fault for this request: the first matching rule for its path, then for every path. */
    @Synchronized
    fun take(request: FakeRequest): Fault? {
        val (key, rule) =
            listOf(request.path, ANY_PATH).firstNotNullOfOrNull { key ->
                rules[key]?.firstOrNull { it.predicate.test(request) }?.let { key to it }
            } ?: return null
        if (--rule.remaining <= 0) rules[key]?.remove(rule)
        if (rules[key].isNullOrEmpty()) rules.remove(key)
        return rule.fault
    }

    @Synchronized
    fun clear() = rules.clear()

    companion object {
        const val ANY_PATH = "*"
    }
}

/**
 * The JDK HTTP server in front of [AcquiringRoutes] and [HostedPages], with token checks, fault
 * injection and request logs. It can be paused (no listener: connection refused) and resumed on the same port.
 */
internal class FakeServer(
    private val routes: AcquiringRoutes,
    private val pages: HostedPages,
    private val dispatcher: WebhookDispatcher,
    private val merchantsByToken: Map<String, MerchantState>,
    private val clock: Clock,
    private val hangLimit: Duration,
    private val bindAddress: InetAddress,
    threadPrefix: String,
) {
    private val logger = LoggerFactory.getLogger(FakeServer::class.java)
    private val executor: ExecutorService = Executors.newCachedThreadPool(NamedThreadFactory("$threadPrefix-http"))
    private val requestLog: MutableList<FakeRequest> = Collections.synchronizedList(ArrayList())
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val faults = FaultRegistry()
    private var server: HttpServer? = null
    private val identity: String = UUID.randomUUID().toString()

    /** Released when the listener stops (pause or close): ends hanging and delayed requests. */
    @Volatile
    private var stopping = CountDownLatch(1)

    /**
     * Starts listening on [port] (0: a free one) and returns the port.
     *
     * @throws UncheckedIOException when the port cannot be bound.
     */
    @Synchronized
    fun start(port: Int): Int {
        repeat(BIND_ATTEMPTS) {
            val http = bind(port)
            // On a wildcard bind, another program may hold 127.0.0.1 on the same port (macOS allows it),
            // and local clients would reach that program instead. Check that loopback reaches this fake.
            if (!bindAddress.isAnyLocalAddress || loopbackReachesSelf(http.address.port)) {
                server = http
                return http.address.port
            }
            http.stop(0)
            if (port != 0) throw UncheckedIOException(IOException("127.0.0.1:$port is served by another program"))
        }
        throw UncheckedIOException(IOException("no port whose 127.0.0.1 address is free, after $BIND_ATTEMPTS tries"))
    }

    private fun bind(port: Int): HttpServer {
        val http =
            try {
                HttpServer.create(InetSocketAddress(bindAddress, port), 0)
            } catch (e: IOException) {
                throw UncheckedIOException("cannot listen on ${bindAddress.hostAddress}:$port: ${e.message}", e)
            }
        http.createContext("/") { exchange -> handle(exchange) }
        http.executor = executor
        stopping = CountDownLatch(1)
        http.start()
        return http
    }

    /** Asks `127.0.0.1:[port]` for this fake's identity: `true` only when this fake answers. */
    private fun loopbackReachesSelf(port: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), PROBE_TIMEOUT_MS)
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.getOutputStream().write(
                    "GET $IDENTITY_PATH HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                String(socket.getInputStream().readBytes(), StandardCharsets.UTF_8).endsWith(identity)
            }
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            false
        }

    /** Stops listening (new connections are refused) and ends requests in progress; keeps all state. */
    @Synchronized
    fun pause() {
        stopping.countDown()
        server?.stop(0)
        server = null
    }

    fun stop() {
        pause()
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun requests(): List<FakeRequest> = synchronized(requestLog) { requestLog.toList() }

    fun count(path: String): Int = counts[path]?.get() ?: 0

    fun reset() {
        requestLog.clear()
        counts.clear()
        faults.clear()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            serve(exchange)
        } catch (e: IOException) {
            logger.debug("fake monobank: client went away: {}", e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            logger.warn("fake monobank: unexpected failure on {}", exchange.requestURI.path, e)
        } catch (
            @Suppress("SwallowedException") e: InterruptedException,
        ) {
            Thread.currentThread().interrupt() // shutting down
        } finally {
            // Without response headers this closes the connection: the client sees no answer.
            exchange.close()
        }
    }

    private fun record(
        exchange: HttpExchange,
        body: ByteArray,
        query: Map<String, String>?,
    ): FakeRequest {
        val request =
            FakeRequest(
                method = exchange.requestMethod.uppercase(),
                path = exchange.requestURI.path,
                query = query ?: emptyMap(),
                headers = headers(exchange),
                body = FakeJson.utf8(body),
                receivedAt = clock.instant(),
            )
        requestLog.add(request)
        counts.computeIfAbsent(request.path) { AtomicInteger() }.incrementAndGet()
        return request
    }

    private fun serve(exchange: HttpExchange) {
        if (exchange.requestURI.path == IDENTITY_PATH) {
            send(exchange, HttpResult(200, identity.toByteArray(StandardCharsets.UTF_8)), IDENTITY_PATH)
            return
        }
        val interrupted = stopping
        val body = exchange.requestBody.use { it.readBytes() }
        val query = parseQuery(exchange.requestURI.rawQuery)
        val request = record(exchange, body, query)
        val fault = faults.take(request)
        if (fault != null) logger.debug("fake monobank: injecting {} on {}", fault, request.path)
        if (fault != null && !fault.processed) {
            answerWith(exchange, fault, interrupted, request.path)
            return
        }
        if (fault?.kind == Fault.Kind.DELAY && interrupted.await(fault.delay.toNanos(), TimeUnit.NANOSECONDS)) return
        val token = exchange.requestHeaders.getFirst(TOKEN_HEADER)
        val (result, webhooks) =
            dispatcher.capture {
                if (query == null) {
                    HttpResult.error(ApiError.badRequest("invalid query string"))
                } else {
                    process(request.method, request.path, token, ApiCall(query, body))
                }
            }
        if (afterProcessing(exchange, fault, webhooks, interrupted, request.path)) send(exchange, result, request.path)
    }

    /** Applies a fault that acts after the request was performed; `true` when the real answer should go out. */
    private fun afterProcessing(
        exchange: HttpExchange,
        fault: Fault?,
        webhooks: List<WebhookEvent>,
        interrupted: CountDownLatch,
        path: String,
    ): Boolean =
        when (fault?.kind) {
            Fault.Kind.DELAY_AFTER -> {
                !interrupted.await(fault.delay.toNanos(), TimeUnit.NANOSECONDS)
            }

            Fault.Kind.AFTER_WEBHOOKS -> {
                dispatcher.awaitDone(webhooks, hangLimit)
                true
            }

            Fault.Kind.HANG, Fault.Kind.DROP, Fault.Kind.RESPOND -> {
                answerWith(exchange, fault, interrupted, path)
                false
            }

            Fault.Kind.DELAY, null -> {
                true
            }
        }

    /** Applies a fault that replaces the answer: hang, drop the connection, or a canned answer. */
    private fun answerWith(
        exchange: HttpExchange,
        fault: Fault,
        interrupted: CountDownLatch,
        path: String,
    ) {
        when (fault.kind) {
            Fault.Kind.HANG -> interrupted.await(hangLimit.toNanos(), TimeUnit.NANOSECONDS)
            Fault.Kind.RESPOND -> send(exchange, HttpResult(fault.status, fault.body, fault.headers), path)
            else -> Unit // DROP: close without an answer
        }
    }

    private fun process(
        method: String,
        path: String,
        token: String?,
        call: ApiCall,
    ): HttpResult =
        try {
            if (pages.handles(path)) {
                pages.handle(method, path, call.body)
            } else {
                processApi(method, path, token, call)
            }
        } catch (e: RuntimeException) {
            logger.warn("fake monobank: internal error on {}", path, e)
            HttpResult.error(ApiError(500, "INTERNAL_ERROR", "internal server error"))
        }

    private fun processApi(
        method: String,
        path: String,
        token: String?,
        call: ApiCall,
    ): HttpResult {
        val allowed = routes.methodOf(path)
        val merchant = token?.let { merchantsByToken[it] }
        val rejection =
            when {
                allowed == null -> ApiError.notFound("not found")
                method != allowed -> ApiError(405, "METHOD_NOT_ALLOWED", "Method not allowed")
                token.isNullOrBlank() -> ApiError.badRequest("empty 'X-Token'")
                merchant == null -> ApiError(403, "FORBIDDEN", "forbidden")
                else -> null
            }
        if (rejection != null || merchant == null) return HttpResult.error(rejection ?: ApiError.notFound("not found"))
        return routes.handle(path, merchant, call)
    }

    private fun send(
        exchange: HttpExchange,
        result: HttpResult,
        path: String,
    ) {
        val headers = exchange.responseHeaders
        if (result.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            headers.set("Content-Type", "application/json")
        }
        result.headers.forEach { (name, value) -> headers.set(name, value) }
        exchange.sendResponseHeaders(result.status, if (result.body.isEmpty()) -1 else result.body.size.toLong())
        if (result.body.isNotEmpty()) exchange.responseBody.use { it.write(result.body) }
        logger.debug("fake monobank {} {} -> {}", exchange.requestMethod, path, result.status)
    }

    private fun headers(exchange: HttpExchange): Map<String, String> =
        exchange.requestHeaders
            .filterKeys { !it.equals(TOKEN_HEADER, ignoreCase = true) }
            .mapNotNull { (name, values) -> values.firstOrNull()?.let { name.lowercase() to it } }
            .toMap()

    /** Decoded query parameters, or `null` when the query is not valid percent-encoding. */
    private fun parseQuery(raw: String?): Map<String, String>? {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            raw
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val name = pair.substringBefore('=')
                    val value = if ('=' in pair) pair.substringAfter('=') else ""
                    decode(name) to decode(value)
                }
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            null
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        private const val SHUTDOWN_SECONDS = 5L
        private const val TOKEN_HEADER = "X-Token"
        private const val BIND_ATTEMPTS = 5
        private const val PROBE_TIMEOUT_MS = 5_000

        /** Internal route that answers this fake's random identity; never recorded or counted. */
        private const val IDENTITY_PATH = "/__fake-monobank/identity"
    }
}
