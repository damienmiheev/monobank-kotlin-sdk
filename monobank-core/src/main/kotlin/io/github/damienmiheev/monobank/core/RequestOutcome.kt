package io.github.damienmiheev.monobank.core

/**
 * What is known about the effect of a failed request on monobank's side.
 *
 * Payment integrations need this to choose the right state: a [NOT_SENT] or [REJECTED] charge
 * can safely be marked failed and retried, an [UNKNOWN] one must be reconciled first (for
 * example by looking the payment up by its reference) before anything is retried.
 */
public enum class RequestOutcome {
    /** The request never reached monobank (DNS, connect or TLS failure). Nothing happened. */
    NOT_SENT,

    /** monobank received the request and definitely did not perform it (4xx, 429, or a known 5xx code). */
    REJECTED,

    /** monobank may or may not have performed the request (read timeout, connection reset, 5xx). */
    UNKNOWN,
}
