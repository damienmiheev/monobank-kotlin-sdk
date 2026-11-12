package io.github.damienmiheev.monobank.personal.client

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.core.Redaction
import java.net.URI

/**
 * Answer of `GET /personal/client-info`: the client, their accounts and jars, and the
 * entrepreneurs (ФОП) who gave this client accountant access.
 *
 * Ids, balances and currency codes are required: an answer without them (or with `null`) fails
 * with `MonobankSerializationException` instead of reading as 0. Other fields are `null` when absent.
 *
 * @property clientId Client id (the same id as on send.monobank.ua).
 * @property name Client's name.
 * @property webHookUrl Webhook URL currently set with `ClientApi.setWebhook`, if any. It should
 *   contain a secret, so [toString] shows only its scheme and host.
 * @property permissions Permissions granted to the token, one letter per permission (e.g. `psfj`).
 * @property accounts The client's accounts (cards and ФОП accounts).
 * @property jars The client's jars (банки).
 * @property managedClients Entrepreneurs who gave this client accountant access to their ФОП accounts.
 */
@ConsistentCopyVisibility
public data class ClientInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("clientId") public val clientId: String,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("webHookUrl") public val webHookUrl: String?,
        @param:JsonProperty("permissions") public val permissions: String?,
        @param:JsonProperty("accounts") public val accounts: List<Account>?,
        @param:JsonProperty("jars") public val jars: List<Jar>?,
        @param:JsonProperty("managedClients") public val managedClients: List<ManagedClient>?,
    ) {
        override fun toString(): String =
            "ClientInfo(clientId=$clientId, name=$name, webHookUrl=${redactUrl(webHookUrl)}, " +
                "permissions=$permissions, accounts=$accounts, jars=$jars, managedClients=$managedClients)"

        private companion object {
            /** Keeps `scheme://host[:port]` and hides the path, query and user info. */
            fun redactUrl(url: String?): String? {
                if (url.isNullOrEmpty()) return url
                val uri = runCatching { URI(url) }.getOrNull()
                val host = uri?.host
                if (uri?.scheme == null || host == null) return Redaction.HIDDEN
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                return "${uri.scheme}://$host$port/${Redaction.HIDDEN}"
            }
        }
    }

/**
 * One of the client's accounts.
 *
 * @property id Account id; pass it to `StatementApi.list`.
 * @property sendId Id for `https://send.monobank.ua/{sendId}`.
 * @property balance Balance in minor units (kopiyky, cents) of [currencyCode].
 * @property creditLimit Credit limit in minor units of [currencyCode].
 * @property type Account (card) type.
 * @property currencyCode ISO 4217 numeric currency code.
 * @property cashbackType Kind of cashback credited to this account.
 * @property maskedPan Masked card numbers (premium accounts can have several cards).
 * @property iban Account IBAN.
 */
@ConsistentCopyVisibility
public data class Account
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("sendId") public val sendId: String?,
        @param:JsonProperty("balance") public val balance: Long,
        @param:JsonProperty("creditLimit") public val creditLimit: Long?,
        @param:JsonProperty("type") public val type: AccountType?,
        @param:JsonProperty("currencyCode") public val currencyCode: Int,
        @param:JsonProperty("cashbackType") public val cashbackType: CashbackType?,
        @param:JsonProperty("maskedPan") public val maskedPan: List<String>?,
        @param:JsonProperty("iban") public val iban: String?,
    )

/**
 * One of the client's jars (банки).
 *
 * @property id Jar id; pass it to `StatementApi.list` to get the jar's statement.
 * @property sendId Id for `https://send.monobank.ua/{sendId}`.
 * @property title Jar title.
 * @property description Jar description.
 * @property currencyCode ISO 4217 numeric currency code.
 * @property balance Balance in minor units (kopiyky, cents) of [currencyCode].
 * @property goal Savings goal in minor units (kopiyky, cents) of [currencyCode].
 */
@ConsistentCopyVisibility
public data class Jar
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("sendId") public val sendId: String?,
        @param:JsonProperty("title") public val title: String?,
        @param:JsonProperty("description") public val description: String?,
        @param:JsonProperty("currencyCode") public val currencyCode: Int,
        @param:JsonProperty("balance") public val balance: Long,
        @param:JsonProperty("goal") public val goal: Long?,
    )

/**
 * An entrepreneur (ФОП) who gave this client accountant access to their accounts.
 *
 * monobank sends **no webhook events** for these accounts; poll their statements instead.
 *
 * @property clientId The entrepreneur's client id.
 * @property tin The entrepreneur's tax number (РНОКПП). The docs' example is a JSON number; it is
 *   read as text either way.
 * @property name The entrepreneur's name.
 * @property accounts The ФОП accounts this client can see.
 */
@ConsistentCopyVisibility
public data class ManagedClient
    @JsonCreator
    internal constructor(
        @param:JsonProperty("clientId") public val clientId: String,
        @param:JsonProperty("tin") public val tin: String?,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("accounts") public val accounts: List<ManagedAccount>?,
    )

/**
 * A ФОП account of a [ManagedClient].
 *
 * @property id Account id; pass it to `StatementApi.list`.
 * @property balance Balance in minor units (kopiyky, cents) of [currencyCode].
 * @property creditLimit Credit limit in minor units of [currencyCode].
 * @property type Account type; [AccountType.FOP] per the docs.
 * @property currencyCode ISO 4217 numeric currency code.
 * @property iban Account IBAN.
 */
@ConsistentCopyVisibility
public data class ManagedAccount
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("balance") public val balance: Long,
        @param:JsonProperty("creditLimit") public val creditLimit: Long?,
        @param:JsonProperty("type") public val type: AccountType?,
        @param:JsonProperty("currencyCode") public val currencyCode: Int,
        @param:JsonProperty("iban") public val iban: String?,
    )
