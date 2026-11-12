package io.github.damienmiheev.monobank.corporate.signature

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.internal.CorporateHttp
import io.github.damienmiheev.monobank.corporate.internal.SigningRules

/**
 * monoKEP: ask monobank clients to sign documents with their qualified electronic signature.
 * A signing request lives 3 days.
 *
 * All calls are signed `X-Time | URL`. By default the URL signed is the path without the query
 * string; see `CorporateConfig.Builder.signQueryString`.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 */
@ExperimentalMonobankApi
public class SignatureApi internal constructor(
    private val http: CorporateHttp,
) {
    /**
     * Creates a signing request; send the signer [CreateSignatureResponse.deeplink].
     *
     * `POST /personal/signature/create`. Not retried after an unknown outcome (it creates a
     * request); if it fails that way, create a new one.
     */
    public fun create(request: CreateSignatureRequest): CreateSignatureResponse =
        http.post(SigningRules.SIGNATURE_CREATE, request, CreateSignatureResponse::class.java, idempotent = false)

    /**
     * Signing state of [requestId], with the signatures made so far.
     *
     * `GET /personal/signature/status?requestId=...`. Safe to retry.
     */
    public fun status(requestId: String): SignatureStatus {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        return http.get(
            SigningRules.SIGNATURE_STATUS,
            SignatureStatus::class.java,
            query = listOf("requestId" to requestId),
        )
    }

    /**
     * Cancels signing request [requestId].
     *
     * `DELETE /personal/signature/cancel?requestId=...`. Safe to retry.
     */
    public fun cancel(requestId: String) {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        http.delete(SigningRules.SIGNATURE_CANCEL, listOf("requestId" to requestId))
    }
}
