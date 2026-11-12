package io.github.damienmiheev.monobank.corporate.client

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import java.time.Instant

/**
 * Answer of `GET /personal/client-info` for one client. Without the `p` (personal data)
 * permission only [accounts] is returned.
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 *
 * @property clientId Client id (the same id as for send.monobank.ua).
 * @property name Client name.
 * @property webHookUrl URL for balance change events.
 * @property permissions Granted permissions, one letter each, e.g. `psf`.
 * @property accounts The client's accounts available to the company.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class ClientInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("clientId") public val clientId: String?,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("webHookUrl") public val webHookUrl: String?,
        @param:JsonProperty("permissions") public val permissions: String?,
        @param:JsonProperty("accounts") public val accounts: List<Account>?,
    )

/**
 * One account of the client.
 *
 * **Experimental:** see [ClientInfo].
 *
 * @property id Account id; pass it to [ClientApi.statement].
 * @property sendId Id for https://send.monobank.ua/{sendId}.
 * @property balance Balance in minor units of [currencyCode] (kopiyky, cents).
 * @property creditLimit Credit limit in minor units of [currencyCode] (kopiyky, cents).
 * @property type Card / account type.
 * @property currencyCode ISO 4217 numeric currency code of the account.
 * @property cashbackType Kind of cashback the account earns.
 * @property maskedPan Masked card numbers (premium cards can have several).
 * @property iban Account IBAN.
 */
@ExperimentalMonobankApi
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
 * Card / account type. Unknown future values are read as [UNKNOWN].
 *
 * **Experimental:** see [ClientInfo].
 *
 * @property value Wire value.
 */
@ExperimentalMonobankApi
public enum class AccountType(
    @get:JsonValue public val value: String,
) {
    /** Black card. */
    BLACK("black"),

    /** White card. */
    WHITE("white"),

    /** Platinum card. */
    PLATINUM("platinum"),

    /** Iron card. */
    IRON("iron"),

    /** Sole proprietor (FOP) account. */
    FOP("fop"),

    /** Yellow (children's) card. */
    YELLOW("yellow"),

    /** eAid (єПідтримка) card. */
    E_AID("eAid"),

    /** A type this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Kind of cashback an account earns. Unknown future values are read as [UNKNOWN].
 *
 * **Experimental:** see [ClientInfo].
 *
 * @property value Wire value.
 */
@ExperimentalMonobankApi
public enum class CashbackType(
    @get:JsonValue public val value: String,
) {
    /** No cashback. */
    NONE("None"),

    /** Cashback in hryvnias. */
    UAH("UAH"),

    /** Cashback in miles. */
    MILES("Miles"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * One transaction of a statement.
 *
 * **Experimental:** see [ClientInfo].
 *
 * @property id Unique transaction id.
 * @property time Transaction time (Unix seconds on the wire).
 * @property description Transaction description.
 * @property mcc Merchant Category Code (ISO 18245).
 * @property originalMcc Original Merchant Category Code (ISO 18245).
 * @property hold Whether the amount is still on hold (authorized, not settled).
 * @property amount Amount in minor units of the account currency (kopiyky, cents); negative for debits.
 * @property operationAmount Amount in minor units of the transaction currency (kopiyky, cents).
 * @property currencyCode ISO 4217 numeric currency code. The docs call it the account currency;
 *   community reports say it is the currency of [operationAmount].
 * @property commissionRate Commission in minor units (kopiyky, cents).
 * @property cashbackAmount Cashback in minor units (kopiyky, cents).
 * @property balance Account balance after the transaction, in minor units (kopiyky, cents).
 * @property comment Comment the payer entered; absent when there is none.
 * @property receiptId Receipt number for check.gov.ua; may be absent.
 * @property counterEdrpou Counterparty's EDRPOU code; only on sole-proprietor (FOP) accounts.
 * @property counterIban Counterparty's IBAN; only on sole-proprietor (FOP) accounts.
 * @property counterName Counterparty's name.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class StatementItem
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String,
        @param:JsonProperty("time") public val time: Instant,
        @param:JsonProperty("description") public val description: String?,
        @param:JsonProperty("mcc") public val mcc: Int?,
        @param:JsonProperty("originalMcc") public val originalMcc: Int?,
        @param:JsonProperty("hold") public val hold: Boolean?,
        @param:JsonProperty("amount") public val amount: Long,
        @param:JsonProperty("operationAmount") public val operationAmount: Long?,
        @param:JsonProperty("currencyCode") public val currencyCode: Int,
        @param:JsonProperty("commissionRate") public val commissionRate: Long?,
        @param:JsonProperty("cashbackAmount") public val cashbackAmount: Long?,
        @param:JsonProperty("balance") public val balance: Long?,
        @param:JsonProperty("comment") public val comment: String?,
        @param:JsonProperty("receiptId") public val receiptId: String?,
        @param:JsonProperty("counterEdrpou") public val counterEdrpou: String?,
        @param:JsonProperty("counterIban") public val counterIban: String?,
        @param:JsonProperty("counterName") public val counterName: String?,
    )
