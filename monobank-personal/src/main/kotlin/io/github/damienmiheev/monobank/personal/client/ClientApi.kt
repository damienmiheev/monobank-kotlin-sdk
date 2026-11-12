package io.github.damienmiheev.monobank.personal.client

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.personal.internal.PersonalHttp
import java.net.URI

/**
 * The client's own data: profile, accounts and jars, and the webhook URL. Needs a token.
 */
public class ClientApi internal constructor(
    private val http: PersonalHttp,
) {
    /**
     * The client, their accounts, jars and managed ФОП clients.
     *
     * `GET /personal/client-info`. monobank allows it **at most once per 60 seconds**; an earlier
     * call is answered with HTTP 429 (`MonobankRateLimitException`), unless a
     * [io.github.damienmiheev.monobank.personal.PersonalRateLimiter] is configured, which then
     * waits instead. Safe to retry.
     *
     * @throws IllegalStateException when the client has no token.
     */
    public fun info(): ClientInfo {
        http.requireToken("client-info")
        return http.get(PersonalHttp.CLIENT_INFO_PATH, ClientInfo::class.java)
    }

    /**
     * Sets the URL that receives an event for every new transaction on the client's accounts and
     * jars (parse it with [io.github.damienmiheev.monobank.personal.webhook.WebhookParser]).
     *
     * `POST /personal/webhook`. Before accepting the URL, monobank sends it a **GET** request,
     * which must be answered with exactly HTTP 200. Events then arrive as POST requests; answer
     * each with HTTP 200 within 5 seconds, or monobank retries after 60 and 600 seconds and
     * **disables the webhook** after the third failure (set it again to re-enable). Events are not
     * signed: put a hard-to-guess secret in the URL path. No events are sent for managed ФОП accounts.
     *
     * Retried after an unknown outcome: setting the same URL again is harmless (it only repeats
     * the GET check).
     *
     * @param webHookUrl Absolute `http(s)` URL.
     * @throws IllegalStateException when the client has no token.
     */
    public fun setWebhook(webHookUrl: String) {
        http.requireToken("webhook")
        require(isHttpUrl(webHookUrl)) { "webHookUrl must be an absolute http(s) URL" }
        http.postNoContent("/personal/webhook", SetWebhookBody(webHookUrl), idempotent = true)
    }

    private fun isHttpUrl(url: String): Boolean {
        if (url.isBlank() || url.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrEmpty()
    }

    /** Body of `POST /personal/webhook`. */
    internal class SetWebhookBody(
        @get:JsonProperty("webHookUrl") val webHookUrl: String,
    )
}
