package io.github.damienmiheev.monobank.personal

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Client-side rate limiter for the personal API, which allows `GET /personal/client-info` and
 * `GET /personal/statement/...` at most once per 60 seconds each.
 *
 * Off by default: enable it with [PersonalConfig.Builder.rateLimiter]. When enabled, every HTTP
 * attempt to a limited endpoint (including retries) blocks the calling thread until [interval]
 * has passed since the previous attempt to the same endpoint. `client-info` and `statement` are
 * limited separately; all statement calls (any account) share one slot. Other endpoints are not
 * limited.
 *
 * monobank counts the limit per token, so share one limiter between all clients of one token and
 * never between different tokens. Thread-safe: concurrent callers are queued one [interval] apart.
 *
 * @property interval Minimum time between two calls to the same endpoint.
 */
public class PersonalRateLimiter private constructor(
    public val interval: Duration,
    private val clock: Clock,
    private val sleeper: Sleeper,
) {
    private val lock = Any()
    private val nextAllowed = HashMap<String, Instant>()

    /** Waits; replaceable in tests. */
    public fun interface Sleeper {
        /** Blocks for [duration]. */
        @Throws(InterruptedException::class)
        public fun sleep(duration: Duration)

        public companion object {
            /** Uses [Thread.sleep]. */
            @JvmField
            public val SYSTEM: Sleeper = Sleeper { Thread.sleep(it.toMillis()) }
        }
    }

    /**
     * Reserves the next slot for [key] and blocks until it starts.
     *
     * @throws InterruptedException when the thread is interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    internal fun acquire(key: String) {
        val wait =
            synchronized(lock) {
                val now = clock.instant()
                val slot = nextAllowed[key]?.takeIf { it.isAfter(now) } ?: now
                nextAllowed[key] = slot.plus(interval)
                Duration.between(now, slot)
            }
        if (!wait.isZero && !wait.isNegative) sleeper.sleep(wait)
    }

    override fun toString(): String = "PersonalRateLimiter(interval=$interval)"

    public companion object {
        /** monobank's documented limit: one call per 60 seconds per endpoint. */
        @JvmField
        public val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(60)

        /** A limiter with monobank's 60-second interval. */
        @JvmStatic
        public fun create(): PersonalRateLimiter = create(DEFAULT_INTERVAL)

        /**
         * A limiter with a custom [interval] (for example 61 s to absorb clock differences), and
         * optionally a [clock] and [sleeper] for tests.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            interval: Duration,
            clock: Clock = Clock.systemUTC(),
            sleeper: Sleeper = Sleeper.SYSTEM,
        ): PersonalRateLimiter {
            require(!interval.isNegative) { "interval must not be negative" }
            return PersonalRateLimiter(interval, clock, sleeper)
        }
    }
}
