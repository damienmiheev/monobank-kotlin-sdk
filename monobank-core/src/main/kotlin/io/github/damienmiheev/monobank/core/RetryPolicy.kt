package io.github.damienmiheev.monobank.core

import java.time.Duration

/**
 * How the SDK retries failed requests.
 *
 * A request is retried only when that is safe:
 * - it never reached monobank ([RequestOutcome.NOT_SENT]), or
 * - monobank answered 429 (the request was not performed), or
 * - the operation is idempotent (reads such as invoice status) and the outcome is
 *   [RequestOutcome.UNKNOWN] (timeout, connection reset, 5xx).
 *
 * Payments, refunds and other non-idempotent operations are never retried after an
 * [RequestOutcome.UNKNOWN] outcome: the SDK throws, and the caller reconciles first.
 *
 * @property maxRetries Retries after the first attempt. `0` disables retries.
 * @property initialBackoff Wait before the first retry; doubled on each later retry, with jitter.
 * @property maxBackoff Upper bound for one wait, including a server-requested `Retry-After`.
 */
public class RetryPolicy private constructor(
    public val maxRetries: Int,
    public val initialBackoff: Duration,
    public val maxBackoff: Duration,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(!initialBackoff.isNegative) { "initialBackoff must not be negative" }
        require(maxBackoff >= initialBackoff) { "maxBackoff must be >= initialBackoff" }
    }

    override fun equals(other: Any?): Boolean =
        other is RetryPolicy &&
            other.maxRetries == maxRetries &&
            other.initialBackoff == initialBackoff &&
            other.maxBackoff == maxBackoff

    override fun hashCode(): Int = (maxRetries * 31 + initialBackoff.hashCode()) * 31 + maxBackoff.hashCode()

    override fun toString(): String =
        "RetryPolicy(maxRetries=$maxRetries, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff)"

    public companion object {
        /** No retries at all. */
        @JvmField
        public val NONE: RetryPolicy = RetryPolicy(0, Duration.ZERO, Duration.ZERO)

        /** Two retries, starting at 500 ms, at most 10 s per wait. */
        @JvmField
        public val DEFAULT: RetryPolicy = RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(10))

        /** Creates a policy. */
        @JvmStatic
        public fun of(
            maxRetries: Int,
            initialBackoff: Duration,
            maxBackoff: Duration,
        ): RetryPolicy = RetryPolicy(maxRetries, initialBackoff, maxBackoff)
    }
}
