package io.github.damienmiheev.monobank.core

import java.time.Duration

/**
 * Base class of every exception thrown by the SDK. The hierarchy is sealed, so a Kotlin `when`
 * over it can be exhaustive.
 *
 * @property outcome What is known about the request's effect, or `null` when the error is not
 *   about an outgoing request (for example an invalid webhook signature).
 */
public sealed class MonobankException(
    message: String,
    cause: Throwable?,
    public val outcome: RequestOutcome?,
) : RuntimeException(message, cause)

/**
 * monobank answered with an HTTP error status.
 *
 * @property statusCode HTTP status code.
 * @property errorCode monobank's machine-readable error code (`errCode` in the acquiring API), if any.
 * @property errorText monobank's human-readable error text, if any.
 * @property responseBody Raw response body. It never contains card data, but may contain
 *   personal data from your own request; do not log it blindly.
 * @property traceId monobank's request trace id from the response headers (`trace-id`,
 *   `X-Request-Id`), when sent. Quote it to monobank support.
 */
public sealed class MonobankApiException(
    public val statusCode: Int,
    public val errorCode: String?,
    public val errorText: String?,
    public val responseBody: String?,
    outcome: RequestOutcome,
) : MonobankException(
        message = buildMessage(statusCode, errorCode, errorText),
        cause = null,
        outcome = outcome,
    ) {
    public var traceId: String? = null
        internal set

    private companion object {
        fun buildMessage(
            statusCode: Int,
            errorCode: String?,
            errorText: String?,
        ): String {
            val details = listOfNotNull(errorCode, errorText).joinToString(": ")
            val base = "monobank API error: HTTP $statusCode"
            return if (details.isEmpty()) base else "$base ($details)"
        }
    }
}

/** HTTP 4xx other than 401, 403, 404 and 429: the request was invalid and was not performed. */
public class MonobankBadRequestException
    @InternalMonobankApi
    constructor(
        statusCode: Int,
        errorCode: String?,
        errorText: String?,
        responseBody: String?,
    ) : MonobankApiException(statusCode, errorCode, errorText, responseBody, RequestOutcome.REJECTED)

/** HTTP 401 or 403: the token, key or signature was rejected. */
public class MonobankAuthException
    @InternalMonobankApi
    constructor(
        statusCode: Int,
        errorCode: String?,
        errorText: String?,
        responseBody: String?,
    ) : MonobankApiException(statusCode, errorCode, errorText, responseBody, RequestOutcome.REJECTED)

/** HTTP 404: the invoice, order or other object does not exist. */
public class MonobankNotFoundException
    @InternalMonobankApi
    constructor(
        errorCode: String?,
        errorText: String?,
        responseBody: String?,
    ) : MonobankApiException(404, errorCode, errorText, responseBody, RequestOutcome.REJECTED)

/**
 * HTTP 429: too many requests. The request was not performed.
 *
 * @property retryAfter How long monobank asked to wait, when it said so.
 */
public class MonobankRateLimitException
    @InternalMonobankApi
    constructor(
        errorCode: String?,
        errorText: String?,
        responseBody: String?,
        public val retryAfter: Duration?,
    ) : MonobankApiException(429, errorCode, errorText, responseBody, RequestOutcome.REJECTED)

/**
 * HTTP 5xx. Usually the outcome is [RequestOutcome.UNKNOWN]; for error codes monobank
 * documents as definite failures (such as `CANCEL_NOT_AVAILABLE`) it is [RequestOutcome.REJECTED].
 */
public class MonobankServerException
    @InternalMonobankApi
    constructor(
        statusCode: Int,
        errorCode: String?,
        errorText: String?,
        responseBody: String?,
        outcome: RequestOutcome,
    ) : MonobankApiException(statusCode, errorCode, errorText, responseBody, outcome)

/**
 * The request failed below HTTP: DNS, connect, TLS, timeout or connection reset.
 * [outcome] is [RequestOutcome.NOT_SENT] when the request provably never left, otherwise
 * [RequestOutcome.UNKNOWN].
 */
public class MonobankNetworkException
    @InternalMonobankApi
    constructor(
        message: String,
        cause: Throwable?,
        outcome: RequestOutcome,
    ) : MonobankException(message, cause, outcome)

/**
 * A request could not be encoded, or a response or webhook could not be decoded.
 * For a response that arrived with a 2xx status the outcome is [RequestOutcome.UNKNOWN]:
 * monobank performed the request but the SDK could not read the answer.
 */
public class MonobankSerializationException
    @InternalMonobankApi
    constructor(
        message: String,
        cause: Throwable?,
        outcome: RequestOutcome?,
    ) : MonobankException(message, cause, outcome)

/**
 * A webhook or callback signature could not be verified.
 *
 * @property reason Why verification failed. Answer [Reason.INVALID_SIGNATURE] with a 4xx; answer
 *   [Reason.KEY_UNAVAILABLE] with a 5xx so monobank delivers the webhook again later.
 */
public class MonobankSignatureException
    @InternalMonobankApi
    constructor(
        public val reason: Reason,
        message: String,
        cause: Throwable? = null,
    ) : MonobankException(message, cause, null) {
        /** Why signature verification failed. */
        public enum class Reason {
            /** The signature header is missing, malformed, or does not match the body. */
            INVALID_SIGNATURE,

            /** The key needed to verify could not be loaded (for example monobank was unreachable). */
            KEY_UNAVAILABLE,
        }
    }
