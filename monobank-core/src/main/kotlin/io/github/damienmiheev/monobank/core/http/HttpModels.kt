package io.github.damienmiheev.monobank.core.http

import io.github.damienmiheev.monobank.core.InternalMonobankApi
import io.github.damienmiheev.monobank.core.RequestOutcome

/** HTTP method. */
@InternalMonobankApi
public enum class HttpMethod { GET, POST, PUT, DELETE }

/**
 * One API call, before authentication headers are added.
 *
 * @property path Path starting with `/`, without the query string.
 * @property query Query parameters in order; `null` values are skipped.
 * @property body Exact bytes to send (signers sign these same bytes).
 * @property idempotent Whether repeating the call after an unknown outcome is safe (see RetryPolicy).
 * @property acceptedStatuses Non-2xx statuses that are a normal answer for this call and are
 *   returned instead of thrown (e.g. 404 "QR not scanned yet").
 */
@InternalMonobankApi
public class ApiRequest(
    public val method: HttpMethod,
    public val path: String,
    public val query: List<Pair<String, String?>> = emptyList(),
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
    public val contentType: String? = if (body != null) "application/json" else null,
    public val idempotent: Boolean,
    public val acceptedStatuses: Set<Int> = emptySet(),
) {
    init {
        require(path.startsWith("/")) { "path must start with /" }
        require(path.none { it == '?' || it == '#' || it.isWhitespace() || it.code < SPACE }) {
            "path must not contain a query, fragment or whitespace; encode ids with UrlEncoding.pathSegment"
        }
    }

    /** Path plus encoded query string, as sent on the wire. */
    public val pathAndQuery: String by lazy {
        val encoded =
            query
                .filter { it.second != null }
                .joinToString("&") { (k, v) -> "${UrlEncoding.encode(k)}=${UrlEncoding.encode(v!!)}" }
        if (encoded.isEmpty()) path else "$path?$encoded"
    }
}

/** A raw HTTP answer. */
private const val SPACE = 0x20

@InternalMonobankApi
public class ApiResponse(
    public val statusCode: Int,
    public val headers: Map<String, List<String>>,
    public val body: ByteArray,
) {
    /** First value of a header, case-insensitive. */
    public fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

/**
 * Adds authentication headers to a request. Called once per attempt, so time-based signatures
 * are fresh on retries.
 */
@InternalMonobankApi
public fun interface RequestAuthenticator {
    /** Returns the headers to add for [request]. */
    public fun headers(request: ApiRequest): Map<String, String>
}

/**
 * Parsed error body of one monobank API.
 */
@InternalMonobankApi
public class ApiError(
    public val code: String?,
    public val text: String?,
)

/**
 * Turns a non-2xx body into an [ApiError] and decides the outcome of 5xx answers.
 */
@InternalMonobankApi
public interface ErrorDecoder {
    /** Parses [body]; returns empty fields when it is not in the expected shape. */
    public fun decode(body: ByteArray): ApiError

    /** Outcome of a 5xx answer with the given error code. */
    public fun serverErrorOutcome(errorCode: String?): RequestOutcome = RequestOutcome.UNKNOWN
}

@InternalMonobankApi
public object UrlEncoding {
    /** Encodes a query name or value (spaces as `%20`). */
    public fun encode(value: String): String =
        java.net.URLEncoder
            .encode(value, Charsets.UTF_8)
            .replace("+", "%20")

    /**
     * Encodes one path segment, e.g. an account or order id placed into a URL path, so it can't
     * add segments, a query or a fragment.
     */
    public fun pathSegment(value: String): String {
        require(value.isNotEmpty()) { "path segment must not be empty" }
        require(value != "." && value != "..") { "path segment must not be . or .." }
        return encode(value)
    }
}
