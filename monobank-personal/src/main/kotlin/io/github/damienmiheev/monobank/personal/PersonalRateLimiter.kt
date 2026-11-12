package io.github.damienmiheev.monobank.personal

import java.time.Duration

/**
 * Client-side rate limiter for the personal API, which allows `GET /personal/client-info` and
 * `GET /personal/statement/...` at most once per 60 seconds each.
 *
 * Off by default: enable it with [PersonalConfig.Builder.rateLimiter]. When enabled, every HTTP
 * attempt to a limited endpoint (including retries) blocks the calling thread until [interval]
 * has passed since the previous attempt to the same endpoint. `client-info` and `statement` are
 * limited separately; all statement calls (any account) share one slot. Other endpoints, including
 * `/bank/currency`, are not limited.
 *
 * The default interval is 61 seconds, one more than monobank's limit, because the gap is measured
 * when a request is about to be sent; network delays can bring two requests closer together at
 * monobank's side. Time is measured with [System.nanoTime], so wall-clock changes do not matter.
 *
 * monobank counts the limit per token, so share one limiter between all clients of one token and
 * never between different tokens. Thread-safe: concurrent callers are queued one [interval] apart,
 * with no upper bound on the wait (the 3rd queued caller waits about 2 intervals, and
 * `requestTimeout` does not cover this wait). A caller interrupted while waiting gives its slot
 * back when nobody queued behind it.
 *
 * @property interval Minimum time between two calls to the same endpoint.
 */
public class PersonalRateLimiter private constructor(
    public val interval: Duration,
    private val nanoTime: () -> Long,
    private val sleeper: Sleeper,
) {
    private val lock = Any()
    private val intervalNanos = interval.toNanos()

    /** Next free slot per endpoint, in [nanoTime] units. */
    private val nextAllowed = HashMap<String, Long>()

    /** Waits for a number of nanoseconds; replaceable in tests. */
    internal fun interface Sleeper {
        @Throws(InterruptedException::class)
        fun sleepNanos(nanos: Long)

        companion object {
            /** Uses [Thread.sleep], rounded up to whole milliseconds so it never wakes early. */
            val SYSTEM: Sleeper = Sleeper { Thread.sleep(toSleepMillis(it)) }
        }
    }

    /**
     * Reserves the next slot for [key] and blocks until it starts.
     *
     * @throws InterruptedException when the thread is interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    internal fun acquire(key: String) {
        var slot = 0L
        val wait =
            synchronized(lock) {
                val now = nanoTime()
                val reserved = nextAllowed[key]
                slot = if (reserved != null && reserved - now > 0) reserved else now
                nextAllowed[key] = slot + intervalNanos
                slot - now
            }
        if (wait <= 0) return
        try {
            sleeper.sleepNanos(wait)
        } catch (e: InterruptedException) {
            synchronized(lock) {
                // Give the slot back unless someone has queued behind it.
                if (nextAllowed[key] == slot + intervalNanos) nextAllowed[key] = slot
            }
            throw e
        }
    }

    override fun toString(): String = "PersonalRateLimiter(interval=$interval)"

    public companion object {
        /** Default interval: monobank's 60-second limit plus one second of margin. */
        @JvmField
        public val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(61)

        /** A limiter with the [DEFAULT_INTERVAL] of 61 seconds. */
        @JvmStatic
        public fun create(): PersonalRateLimiter = create(DEFAULT_INTERVAL)

        /** A limiter with a custom [interval]; values below 60 seconds will get HTTP 429 answers. */
        @JvmStatic
        public fun create(interval: Duration): PersonalRateLimiter = create(interval, System::nanoTime, Sleeper.SYSTEM)

        /** Test hook: a limiter with its own time source and sleeper. */
        internal fun create(
            interval: Duration,
            nanoTime: () -> Long,
            sleeper: Sleeper,
        ): PersonalRateLimiter {
            require(!interval.isNegative) { "interval must not be negative" }
            return PersonalRateLimiter(interval, nanoTime, sleeper)
        }

        /** Nanoseconds to milliseconds, rounded up. */
        internal fun toSleepMillis(nanos: Long): Long = (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
