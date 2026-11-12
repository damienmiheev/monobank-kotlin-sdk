package io.github.damienmiheev.monobank.personal.bank

import io.github.damienmiheev.monobank.personal.internal.PersonalHttp

/**
 * Public endpoints that need no token: currency rates and the bank's public key.
 *
 * The token is not sent to these endpoints even when the client has one.
 */
public class BankApi internal constructor(
    private val http: PersonalHttp,
) {
    /**
     * monobank's currency rates.
     *
     * `GET /bank/currency`. No token needed. monobank caches the rates and refreshes them at most
     * once every 5 minutes, and answers frequent calls with HTTP 429: cache the result on your side
     * for at least 5 minutes. Safe to retry.
     */
    public fun currency(): List<CurrencyRate> = http.get("/bank/currency", Array<CurrencyRate>::class.java).toList()

    /**
     * The bank's public key, its id and the server time.
     *
     * `GET /bank/sync`. No token needed. Safe to retry.
     */
    public fun sync(): SyncInfo = http.get("/bank/sync", SyncInfo::class.java)
}
