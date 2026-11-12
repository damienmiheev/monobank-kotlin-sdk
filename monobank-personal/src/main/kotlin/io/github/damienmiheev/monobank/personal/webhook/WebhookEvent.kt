package io.github.damienmiheev.monobank.personal.webhook

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.personal.statement.StatementItem

/**
 * Type of a personal webhook event.
 *
 * @property value Wire value.
 */
public enum class WebhookEventType(
    @get:JsonValue public val value: String,
) {
    /** A new transaction on one of the client's accounts or jars. */
    STATEMENT_ITEM("StatementItem"),

    /** An event type this SDK version does not know; [WebhookEvent.data] is then `null`. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * A personal webhook event, the body monobank POSTs to the URL set with `ClientApi.setWebhook`:
 * `{"type":"StatementItem","data":{"account":"...","statementItem":{...}}}`.
 *
 * Events are **not signed**; see [WebhookParser] for how to receive them safely.
 *
 * @property type Event type.
 * @property data Event data; set for [WebhookEventType.STATEMENT_ITEM], `null` for unknown types.
 */
@ConsistentCopyVisibility
public data class WebhookEvent
    @JsonCreator
    internal constructor(
        @param:JsonProperty("type") public val type: WebhookEventType,
        @param:JsonProperty("data") public val data: StatementItemEventData?,
    )

/**
 * Data of a [WebhookEventType.STATEMENT_ITEM] event.
 *
 * @property account Id of the account or jar the transaction belongs to.
 * @property statementItem The transaction; the same shape as a statement entry.
 */
@ConsistentCopyVisibility
public data class StatementItemEventData
    @JsonCreator
    internal constructor(
        @param:JsonProperty("account") public val account: String,
        @param:JsonProperty("statementItem") public val statementItem: StatementItem,
    )
