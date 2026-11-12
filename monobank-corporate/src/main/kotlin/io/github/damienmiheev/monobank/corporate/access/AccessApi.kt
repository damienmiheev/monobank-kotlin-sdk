package io.github.damienmiheev.monobank.corporate.access

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.http.ApiRequest
import io.github.damienmiheev.monobank.core.http.ApiResponse
import io.github.damienmiheev.monobank.core.http.HttpMethod
import io.github.damienmiheev.monobank.corporate.internal.CorporateAuthenticator
import io.github.damienmiheev.monobank.corporate.internal.CorporateErrorDecoder
import io.github.damienmiheev.monobank.corporate.internal.CorporateHttp
import io.github.damienmiheev.monobank.corporate.internal.SigningRules
import java.nio.charset.StandardCharsets

/**
 * Answer of `POST /personal/auth/request`: a new request for access to one client's data.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 *
 * @property tokenRequestId The request id. Once the client approves, it is the `X-Request-Id`
 *   for that client's data: store it with the client and pass it to
 *   [io.github.damienmiheev.monobank.corporate.MonobankCorporate.client].
 * @property acceptUrl Link for the client to approve in the monobank app, e.g.
 *   `https://mbnk.app/auth/...`: show it as a QR code on a desktop, redirect to it on a phone.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class AccessRequest
    @JsonCreator
    internal constructor(
        @param:JsonProperty("tokenRequestId") public val tokenRequestId: String,
        @param:JsonProperty("acceptUrl") public val acceptUrl: String,
    )

/**
 * State of an access request, from [AccessApi.status].
 *
 * **Experimental:** see [AccessRequest].
 */
@ExperimentalMonobankApi
public enum class AccessRequestStatus {
    /** The client approved: their data can be read with the request id (HTTP 200). */
    APPROVED,

    /** The client has not approved yet (HTTP 401 "Request is unauthorized yet"). Ask again later. */
    PENDING,

    /** monobank does not know the request (HTTP 404): it expired (after 24 hours) or never existed. */
    NOT_FOUND,

    /**
     * Not returned by this SDK version. Reserved so that a `when` over this enum keeps compiling
     * when later versions report more states (for example a declined request); treat it like
     * [PENDING].
     */
    UNKNOWN,
}

/**
 * Requests for access to a client's data, and their state.
 *
 * Flow: [request] → show [AccessRequest.acceptUrl] to the client → poll [status] (or wait for
 * the `X-Callback` call) until [AccessRequestStatus.APPROVED] → read data with
 * `MonobankCorporate.client(tokenRequestId)`. The data the company may read is fixed per company
 * at registration ([io.github.damienmiheev.monobank.corporate.company.CompanySettings.permission]).
 *
 * **Experimental:** see [AccessRequest].
 */
@ExperimentalMonobankApi
public class AccessApi internal constructor(
    private val http: CorporateHttp,
) {
    /**
     * Creates a request for access to a client's data. The client can approve it for 24 hours.
     *
     * `POST /personal/auth/request`, signed `X-Time | URL`. Not retried after an unknown outcome
     * (each call creates a new request); just call it again.
     *
     * @param callbackUrl Optional `X-Callback`: monobank calls it with GET and header
     *   `X-Request-Id` once the client approves. That call is not signed: treat it only as a hint
     *   and confirm with [status]. Use https and an unguessable path.
     */
    @JvmOverloads
    public fun request(callbackUrl: String? = null): AccessRequest {
        val headers =
            if (callbackUrl == null) {
                emptyMap()
            } else {
                CorporateHttp.requireHeaderValue(callbackUrl, "callbackUrl")
                mapOf(CALLBACK_HEADER to callbackUrl)
            }
        return http.post(SigningRules.AUTH_REQUEST, null, AccessRequest::class.java, idempotent = false, headers)
    }

    /**
     * Whether the client approved access request [requestId]. Pending and unknown requests are
     * answers, not errors.
     *
     * `GET /personal/auth/request` with `X-Request-Id`, signed `X-Time | X-Request-Id | URL`.
     * Safe to retry.
     *
     * HTTP 401 means [AccessRequestStatus.PENDING] only when monobank's `errorDescription` says the
     * request is "unauthorized yet", as documented; any other 401 (for example a rejected
     * signature) is thrown, so a signing problem never looks like a client who has not answered.
     *
     * @throws MonobankAuthException on HTTP 403, or on a 401 that is not "unauthorized yet" (the
     *   key is wrong or not approved, or the signature was rejected).
     */
    public fun status(requestId: String): AccessRequestStatus {
        CorporateHttp.requireHeaderValue(requestId, "requestId")
        val response =
            http.execute(
                ApiRequest(
                    HttpMethod.GET,
                    SigningRules.AUTH_REQUEST,
                    headers = mapOf(CorporateAuthenticator.REQUEST_ID_HEADER to requestId),
                    idempotent = true,
                    acceptedStatuses = setOf(HTTP_UNAUTHORIZED, HTTP_NOT_FOUND),
                ),
            )
        return when (response.statusCode) {
            HTTP_UNAUTHORIZED -> pendingOrThrow(response)
            HTTP_NOT_FOUND -> AccessRequestStatus.NOT_FOUND
            else -> AccessRequestStatus.APPROVED
        }
    }

    private fun pendingOrThrow(response: ApiResponse): AccessRequestStatus {
        val text = runCatching { CorporateErrorDecoder.decode(response.body).text }.getOrNull()
        if (text != null && text.contains(PENDING_TEXT, ignoreCase = true)) return AccessRequestStatus.PENDING
        throw MonobankAuthException(
            HTTP_UNAUTHORIZED,
            null,
            text,
            response.body.toString(StandardCharsets.UTF_8),
        )
    }

    private companion object {
        const val CALLBACK_HEADER = "X-Callback"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404

        /** Documented 401 text while the client has not answered: "Request is unauthorized yet". */
        const val PENDING_TEXT = "unauthorized yet"
    }
}
