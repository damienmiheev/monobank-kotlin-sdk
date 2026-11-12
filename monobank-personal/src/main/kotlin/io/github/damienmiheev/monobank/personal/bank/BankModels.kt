package io.github.damienmiheev.monobank.personal.bank

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant

/**
 * One currency pair from `GET /bank/currency`.
 *
 * Rates are prices of one unit of [currencyCodeA] in [currencyCodeB], in major units
 * (for example `41.25` hryvnias per US dollar), as decimals. Each pair has one or more of
 * [rateBuy], [rateSell] and [rateCross]: usually buy and sell for the main currencies and only
 * the cross rate for the others. A pair without [currencyCodeA] or [currencyCodeB] fails the
 * whole answer with `MonobankSerializationException`.
 *
 * @property currencyCodeA ISO 4217 numeric code of the currency being priced, e.g. 840 (USD).
 * @property currencyCodeB ISO 4217 numeric code of the currency it is priced in, e.g. 980 (UAH).
 * @property date When the rate was set (sent as Unix time in seconds).
 * @property rateSell Rate at which monobank sells [currencyCodeA], in units of [currencyCodeB] (decimal).
 * @property rateBuy Rate at which monobank buys [currencyCodeA], in units of [currencyCodeB] (decimal).
 * @property rateCross Cross rate, in units of [currencyCodeB] (decimal).
 */
@ConsistentCopyVisibility
public data class CurrencyRate
    @JsonCreator
    internal constructor(
        @param:JsonProperty("currencyCodeA") public val currencyCodeA: Int,
        @param:JsonProperty("currencyCodeB") public val currencyCodeB: Int,
        @param:JsonProperty("date") public val date: Instant?,
        @param:JsonProperty("rateSell") public val rateSell: BigDecimal?,
        @param:JsonProperty("rateBuy") public val rateBuy: BigDecimal?,
        @param:JsonProperty("rateCross") public val rateCross: BigDecimal?,
    )

/**
 * Answer of `GET /bank/sync`: the bank's public key and the server time.
 *
 * monobank does not document what the key is for. It is probably used with the corporate
 * (provider) API, and [serverTime] can probably help correct clock skew when signing requests
 * with `X-Time`; neither is confirmed.
 *
 * @property serverKeyId Key id, a hex string (observed: the SHA-1 of the decoded [serverPubKey]).
 * @property serverPubKey The bank's public key: Base64 of an uncompressed secp256k1 point
 *   (65 bytes, `0x04 || X || Y`). Not PEM.
 * @property serverTimeMsec Current server time as Unix time in **milliseconds** (unlike every
 *   other time field in this API, which is in seconds). See [serverTime].
 */
@ConsistentCopyVisibility
public data class SyncInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("serverKeyId") public val serverKeyId: String,
        @param:JsonProperty("serverPubKey") public val serverPubKey: String?,
        @param:JsonProperty("serverTimeMsec") public val serverTimeMsec: Long?,
    ) {
        /** [serverTimeMsec] as an [Instant], or `null` when it was not sent. */
        public val serverTime: Instant?
            get() = serverTimeMsec?.let { Instant.ofEpochMilli(it) }
    }
