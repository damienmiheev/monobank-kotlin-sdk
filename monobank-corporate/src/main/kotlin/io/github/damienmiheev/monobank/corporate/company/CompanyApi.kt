package io.github.damienmiheev.monobank.corporate.company

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.CorporateKey
import io.github.damienmiheev.monobank.corporate.internal.CorporateAuthenticator
import io.github.damienmiheev.monobank.corporate.internal.CorporateHttp
import io.github.damienmiheev.monobank.corporate.internal.SigningRules

/**
 * The company itself: registration, settings and the company-wide webhook.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 */
@ExperimentalMonobankApi
public class CompanyApi internal constructor(
    private val http: CorporateHttp,
) {
    /**
     * Applies for access to the corporate API with the company's key. monobank reviews the
     * application by hand; poll [registrationStatus] until it is approved or declined.
     *
     * `POST /personal/auth/registration`, signed `X-Time | URL` with the key being registered
     * and without `X-Key-Id`. Not retried after an unknown outcome (it files an application):
     * check [registrationStatus] before sending it again.
     */
    public fun register(request: RegistrationRequest): RegistrationResponse =
        http.post(
            SigningRules.REGISTRATION,
            request,
            RegistrationResponse::class.java,
            idempotent = false,
        )

    /**
     * State of the application filed with [pubkey].
     *
     * `POST /personal/auth/registration/status`, signed `X-Time | URL` without `X-Key-Id`. A read:
     * safe to retry.
     *
     * @param pubkey Base64 of the PEM public key, exactly as sent to [register]
     *   (see [CorporateKey.publicKeyPemBase64]).
     */
    public fun registrationStatus(pubkey: String): RegistrationStatusResponse {
        require(pubkey.isNotBlank()) { "pubkey must not be blank" }
        return http.post(
            SigningRules.REGISTRATION_STATUS,
            RegistrationStatusRequest(pubkey),
            RegistrationStatusResponse::class.java,
            idempotent = true,
        )
    }

    /** State of the application filed with [key]'s public key ([CorporateKey.publicKeyPemBase64]). */
    public fun registrationStatus(key: CorporateKey): RegistrationStatusResponse =
        registrationStatus(key.publicKeyPemBase64)

    /**
     * The company's data as monobank has it: name, granted permissions, webhook.
     *
     * `GET /personal/corp/settings`. Safe to retry.
     *
     * The docs list `X-Request-Id` as a required header here but sign the call as
     * `X-Time | URL` and do not say what the id is; community clients send none. By default no
     * `X-Request-Id` is sent. Pass [requestId] (one of your clients' access request ids) only if
     * monobank rejects the call without it; it is then sent but, as documented, not signed.
     */
    @JvmOverloads
    public fun settings(requestId: String? = null): CompanySettings =
        http.get(SigningRules.CORP_SETTINGS, CompanySettings::class.java, headers = requestIdHeader(requestId))

    /**
     * Sets the company-wide webhook: monobank POSTs client transaction events there for every
     * client that granted access. While handling this call monobank sends a test POST to the URL,
     * and the call fails unless that test gets HTTP 200.
     *
     * The event format and any signature on it are not documented, so this SDK does not parse
     * or verify them.
     *
     * `POST /personal/corp/webhook`. Safe to retry (it sets a value). See [settings] for
     * [requestId].
     *
     * @param webHookUrl The URL. An empty string presumably removes the webhook (not documented).
     */
    @JvmOverloads
    public fun setWebhook(
        webHookUrl: String,
        requestId: String? = null,
    ) {
        require(webHookUrl.none { it == '\r' || it == '\n' }) { "webHookUrl must be one line" }
        http.postNoContent(
            SigningRules.CORP_WEBHOOK,
            SetWebhookRequest(webHookUrl),
            idempotent = true,
            headers = requestIdHeader(requestId),
        )
    }

    private fun requestIdHeader(requestId: String?): Map<String, String> {
        if (requestId == null) return emptyMap()
        CorporateHttp.requireHeaderValue(requestId, "requestId")
        return mapOf(CorporateAuthenticator.REQUEST_ID_HEADER to requestId)
    }
}
