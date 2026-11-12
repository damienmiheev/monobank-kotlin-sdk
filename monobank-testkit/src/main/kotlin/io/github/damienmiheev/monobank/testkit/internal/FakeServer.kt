package io.github.damienmiheev.monobank.testkit.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.damienmiheev.monobank.testkit.FakeRequest
import io.github.damienmiheev.monobank.testkit.Fault
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Faults registered per path, consumed one request at a time. Thread-safe. */
internal class FaultRegistry {
    private class Rule(
        val fault: Fault,
        var remaining: Int,
    )

    private val rules = LinkedHashMap<String, ArrayDeque<Rule>>()

    @Synchronized
    fun add(
        path: String,
        fault: Fault,
        times: Int,
    ) {
        rules.getOrPut(path) { ArrayDeque() }.addLast(Rule(fault, times))
    }

    /** The fault for this request: rules for [path] first, then rules for every path. */
    @Synchronized
    fun take(path: String): Fault? {
        val key = if (rules[path].isNullOrEmpty()) ANY_PATH else path
        val queue = rules[key] ?: return null
        val rule = queue.firstOrNull() ?: return null
        rule.remaining--
        if (rule.remaining <= 0) queue.removeFirst()
        if (queue.isEmpty()) rules.remove(key)
        return rule.fault
    }

    @Synchronized
    fun clear() = rules.clear()

    companion object {
        const val ANY_PATH = "*"
    }
}

/** The JDK HTTP server in front of [AcquiringRoutes], with token checks, fault injection and request logs. */
internal class FakeServer(
    private val routes: AcquiringRoutes,
    private val validTokens: Set<String>,
    private val clock: Clock,
    private val hangLimit: Duration,
    private val stopped: CountDownLatch,
) {
    private val logger = LoggerFactory.getLogger(FakeServer::class.java)
    private val executor: ExecutorService = Executors.newCachedThreadPool(NamedThreadFactory("fake-monobank-http"))
    private val requestLog: MutableList<FakeRequest> = Collections.synchronizedList(ArrayList())
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val faults = FaultRegistry()
    private var server: HttpServer? = null

    fun start(port: Int): Int {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 0)
        http.createContext("/") { exchange -> handle(exchange) }
        http.executor = executor
        http.start()
        server = http
        return http.address.port
    }

    fun stop() {
        server?.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
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
        } finally {
            // Without response headers this closes the connection: the client sees no answer.
            exchange.close()
        }
    }

    private fun serve(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path
        val query = parseQuery(exchange.requestURI.rawQuery)
        val body = exchange.requestBody.use { it.readBytes() }
        requestLog.add(FakeRequest(method, path, query, FakeJson.utf8(body), clock.instant()))
        counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        val fault = faults.take(path)
        if (fault != null && !fault.processed) {
            applyFault(exchange, fault, path)
            return
        }
        if (fault?.kind == Fault.Kind.DELAY && stopped.await(fault.delay.toNanos(), TimeUnit.NANOSECONDS)) return
        val result = process(method, path, exchange.requestHeaders.getFirst("X-Token"), ApiCall(query, body))
        if (fault != null && fault.kind != Fault.Kind.DELAY) {
            applyFault(exchange, fault, path)
            return
        }
        send(exchange, result, path)
    }

    private fun applyFault(
        exchange: HttpExchange,
        fault: Fault,
        path: String,
    ) {
        logger.debug("fake monobank: injecting {} on {}", fault, path)
        when (fault.kind) {
            Fault.Kind.HANG -> stopped.await(hangLimit.toNanos(), TimeUnit.NANOSECONDS)
            Fault.Kind.DROP -> Unit
            Fault.Kind.RESPOND -> send(exchange, HttpResult(fault.status, fault.body, fault.headers), path)
            Fault.Kind.DELAY -> Unit
        }
    }

    private fun process(
        method: String,
        path: String,
        token: String?,
        call: ApiCall,
    ): HttpResult {
        val allowed = routes.methodOf(path)
        val rejection =
            when {
                allowed == null -> ApiError.notFound("not found")
                method != allowed -> ApiError(405, "METHOD_NOT_ALLOWED", "Method not allowed")
                token.isNullOrBlank() -> ApiError.badRequest("empty 'X-Token'")
                token !in validTokens -> ApiError(403, "FORBIDDEN", "forbidden")
                else -> null
            }
        if (rejection != null) return HttpResult.error(rejection)
        return try {
            routes.handle(path, call)
        } catch (e: RuntimeException) {
            logger.warn("fake monobank: internal error on {}", path, e)
            HttpResult.error(ApiError(500, "INTERNAL_ERROR", "internal server error"))
        }
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

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = pair.substringBefore('=')
                val value = if ('=' in pair) pair.substringAfter('=') else ""
                decode(name) to decode(value)
            }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val SHUTDOWN_SECONDS = 5L
    }
}
