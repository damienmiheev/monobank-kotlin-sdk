package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.testkit.internal.FakeJson
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * A failure that [FakeMonobankAcquiring] injects into its answer to a request, registered with
 * [FakeMonobankAcquiring.injectFault].
 *
 * Faults come in two flavours, because a payment integration must tell them apart:
 * - **not processed**: the fake answers (or fails) without performing the request, so nothing
 *   changes ([timeoutWithoutProcessing], [dropConnectionWithoutProcessing], [serverError],
 *   [tooManyRequests], [response]);
 * - **processed**: the fake performs the request (the invoice is created, the card is charged,
 *   the refund is made) and then the answer is lost or replaced ([timeout], [dropConnection],
 *   [serverErrorAfterProcessing]). This is the "the charge reached monobank but we never heard back"
 *   case, where the client's outcome is unknown.
 *
 * [delay] performs the request normally, only later.
 */
public class Fault private constructor(
    internal val kind: Kind,
    internal val processed: Boolean,
    internal val delay: Duration = Duration.ZERO,
    internal val status: Int = 0,
    internal val body: ByteArray = ByteArray(0),
    internal val headers: Map<String, String> = emptyMap(),
) {
    internal enum class Kind { DELAY, HANG, DROP, RESPOND }

    override fun toString(): String {
        val details =
            when (kind) {
                Kind.DELAY -> "delay=$delay"
                Kind.RESPOND -> "status=$status"
                Kind.HANG, Kind.DROP -> ""
            }
        return "Fault($kind, processed=$processed${if (details.isEmpty()) "" else ", $details"})"
    }

    @Suppress("TooManyFunctions")
    public companion object {
        private const val INTERNAL_SERVER_ERROR = 500
        private const val TOO_MANY_REQUESTS = 429

        /** Performs the request normally but answers only after [delay]. */
        @JvmStatic
        public fun delay(delay: Duration): Fault {
            require(!delay.isNegative) { "delay must not be negative" }
            return Fault(Kind.DELAY, processed = true, delay = delay)
        }

        /**
         * Performs the request, then never answers: the connection stays open until the client
         * gives up (its request timeout), the fake is closed, or the fake's hang limit passes.
         * The client cannot know whether the request was performed (it was).
         */
        @JvmStatic
        public fun timeout(): Fault = Fault(Kind.HANG, processed = true)

        /** Never answers and does **not** perform the request (e.g. it was stuck before reaching monobank). */
        @JvmStatic
        public fun timeoutWithoutProcessing(): Fault = Fault(Kind.HANG, processed = false)

        /** Performs the request, then closes the connection without sending any response. */
        @JvmStatic
        public fun dropConnection(): Fault = Fault(Kind.DROP, processed = true)

        /** Reads the request, then closes the connection without performing it or answering. */
        @JvmStatic
        public fun dropConnectionWithoutProcessing(): Fault = Fault(Kind.DROP, processed = false)

        /** Answers HTTP 500 `{"errCode":"INTERNAL_ERROR",...}` without performing the request. */
        @JvmStatic
        public fun serverError(): Fault = serverError(INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal server error")

        /**
         * Answers HTTP [status] (a 5xx) with `{"errCode": errCode, "errText": errText}` without
         * performing the request.
         */
        @JvmStatic
        public fun serverError(
            status: Int,
            errCode: String,
            errText: String,
        ): Fault {
            require(status in 500..599) { "status must be a 5xx code" }
            return Fault(Kind.RESPOND, processed = false, status = status, body = errorBody(errCode, errText))
        }

        /**
         * Performs the request, then answers HTTP 500 `INTERNAL_ERROR` anyway, so the client cannot
         * know that it worked.
         */
        @JvmStatic
        public fun serverErrorAfterProcessing(): Fault =
            Fault(
                Kind.RESPOND,
                processed = true,
                status = INTERNAL_SERVER_ERROR,
                body = errorBody("INTERNAL_ERROR", "internal server error"),
            )

        /** Answers HTTP 429 `{"errCode":"TMR",...}` without a `Retry-After` header. Not performed. */
        @JvmStatic
        public fun tooManyRequests(): Fault =
            Fault(
                Kind.RESPOND,
                processed = false,
                status = TOO_MANY_REQUESTS,
                body = errorBody("TMR", "too many requests"),
            )

        /** Answers HTTP 429 `TMR` with `Retry-After` set to [retryAfter] in whole seconds. Not performed. */
        @JvmStatic
        public fun tooManyRequests(retryAfter: Duration): Fault {
            require(!retryAfter.isNegative) { "retryAfter must not be negative" }
            return Fault(
                Kind.RESPOND,
                processed = false,
                status = TOO_MANY_REQUESTS,
                body = errorBody("TMR", "too many requests"),
                headers = mapOf("Retry-After" to retryAfter.seconds.toString()),
            )
        }

        /** Answers with [status] and [body] (sent as `application/json`) without performing the request. */
        @JvmStatic
        public fun response(
            status: Int,
            body: String,
        ): Fault = response(status, body, emptyMap())

        /**
         * Answers with [status], [body] and extra [headers] without performing the request.
         * `Content-Type` defaults to `application/json` unless [headers] sets it.
         */
        @JvmStatic
        public fun response(
            status: Int,
            body: String,
            headers: Map<String, String>,
        ): Fault {
            require(status in 100..599) { "status must be an HTTP status code" }
            return Fault(
                Kind.RESPOND,
                processed = false,
                status = status,
                body = body.toByteArray(StandardCharsets.UTF_8),
                headers = headers.toMap(),
            )
        }

        private fun errorBody(
            errCode: String,
            errText: String,
        ): ByteArray = FakeJson.errorBody(errCode, errText)
    }
}
