package io.github.damienmiheev.monobank.personal.webhook

import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import java.nio.charset.StandardCharsets

/**
 * Parses personal webhook events (see `ClientApi.setWebhook`).
 *
 * How to receive them:
 * - Answer the **GET** monobank sends when the URL is set with exactly HTTP 200.
 * - Answer every **POST** with HTTP 200 within 5 seconds (store the event, process it later).
 *   Otherwise monobank retries after 60 and 600 seconds, then disables the webhook.
 * - Retries can deliver an event twice: deduplicate by [io.github.damienmiheev.monobank.personal.statement.StatementItem.id].
 * - The personal API does **not sign** its webhooks, so anyone who knows the URL can post fake
 *   events. Put a long random secret in the URL path, and treat an event as a hint: confirm
 *   anything important with `StatementApi.list`.
 * - No events are sent for managed ФОП accounts (`ClientInfo.managedClients`).
 */
public object WebhookParser {
    /**
     * Parses a webhook body. Events of a type this SDK does not know are returned with
     * [WebhookEventType.UNKNOWN] and `null` data instead of failing.
     *
     * @throws MonobankSerializationException when the body is not a valid event.
     */
    @JvmStatic
    public fun parse(body: ByteArray): WebhookEvent {
        val tree = MonobankJson.readTree(body, outcome = null)
        if (!tree.isObject) throw MonobankSerializationException("Webhook body is not a JSON object", null, null)
        val type = tree.path("type").asText("")
        if (type != WebhookEventType.STATEMENT_ITEM.value) return WebhookEvent(WebhookEventType.UNKNOWN, null)
        val event = MonobankJson.read(body, WebhookEvent::class.java, outcome = null)
        if (event.data == null) throw MonobankSerializationException("StatementItem webhook has no data", null, null)
        return event
    }

    /** Parses a webhook body given as a string. */
    @JvmStatic
    public fun parse(body: String): WebhookEvent = parse(body.toByteArray(StandardCharsets.UTF_8))
}
