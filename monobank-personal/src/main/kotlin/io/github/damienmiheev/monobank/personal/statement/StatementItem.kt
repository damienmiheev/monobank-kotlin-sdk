package io.github.damienmiheev.monobank.personal.statement

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * One transaction, from `GET /personal/statement/...` or a webhook event.
 *
 * Amounts are signed: negative for money leaving the account, positive for money coming in.
 * [id], [time], [amount] and [currencyCode] are required: a response or webhook without them (or
 * with `null`) fails with `MonobankSerializationException` instead of reading as 0. The other
 * fields are `null` when monobank does not send them.
 *
 * @property id Unique transaction id. Use it to deduplicate (webhook retries, overlapping statement pages).
 * @property time When the transaction happened (sent as Unix time in seconds).
 * @property description Transaction description.
 * @property mcc Merchant Category Code (ISO 18245).
 * @property originalMcc Original Merchant Category Code (ISO 18245).
 * @property hold `true` while the amount is only held (authorization hold), not yet settled.
 * @property amount Amount in minor units (kopiyky, cents) of the **account** currency, signed.
 * @property operationAmount Amount in minor units of the **transaction** currency, signed.
 * @property currencyCode ISO 4217 numeric currency code. The docs describe it as "Код валюти
 *   рахунку" (account currency); in practice it has been observed to be the currency of the
 *   transaction (of [operationAmount]). For the account currency use `Account.currencyCode`.
 * @property commissionRate Commission in minor units (kopiyky, cents).
 * @property cashbackAmount Cashback in minor units (kopiyky, cents).
 * @property balance Account balance after the transaction, in minor units of the account currency.
 * @property comment Comment the sender typed for a transfer; absent when there is none.
 * @property receiptId Receipt number for check.gov.ua; may be absent.
 * @property invoiceId ФОП receipt number; sent for incoming payments to ФОП accounts.
 * @property counterEdrpou Counterparty's ЄДРПОУ code; ФОП account statements only.
 * @property counterIban Counterparty's IBAN; ФОП account statements only.
 * @property counterName Counterparty's name.
 */
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
        @param:JsonProperty("invoiceId") public val invoiceId: String?,
        @param:JsonProperty("counterEdrpou") public val counterEdrpou: String?,
        @param:JsonProperty("counterIban") public val counterIban: String?,
        @param:JsonProperty("counterName") public val counterName: String?,
    )
