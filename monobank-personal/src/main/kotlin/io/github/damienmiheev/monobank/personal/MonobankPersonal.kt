package io.github.damienmiheev.monobank.personal

import io.github.damienmiheev.monobank.personal.bank.BankApi
import io.github.damienmiheev.monobank.personal.client.ClientApi
import io.github.damienmiheev.monobank.personal.internal.PersonalHttp
import io.github.damienmiheev.monobank.personal.statement.StatementApi

/**
 * Client for the monobank personal API (https://api.monobank.ua/docs/): currency rates, the
 * client's accounts and jars, statements and the webhook URL.
 *
 * Get a personal token at https://api.monobank.ua/. All amounts are in minor units (kopiyky,
 * cents) and all times are Unix seconds, read as [java.time.Instant]. Errors are thrown as
 * `MonobankException`s; the error text monobank sends (`errorDescription`) is in
 * `MonobankApiException.errorText` (there is no error code).
 *
 * Thread-safe; create one per token and reuse it.
 *
 * ```kotlin
 * val mono = MonobankPersonal.create(token)
 * val accounts = mono.client.info().accounts
 * val today = mono.statements.list(StatementApi.DEFAULT_ACCOUNT, Instant.now().minus(Duration.ofDays(1)))
 * val rates = MonobankPersonal.publicOnly().bank.currency()
 * ```
 */
public class MonobankPersonal internal constructor(
    public val config: PersonalConfig,
    http: PersonalHttp,
) {
    /** Creates a client from [config]. */
    public constructor(config: PersonalConfig) : this(config, PersonalHttp(config))

    /** Public endpoints (no token needed): currency rates and the bank's public key. */
    public val bank: BankApi = BankApi(http)

    /** Client info (accounts, jars) and the webhook URL. Needs a token. */
    public val client: ClientApi = ClientApi(http)

    /** Account and jar statements. Needs a token. */
    public val statements: StatementApi = StatementApi(http)

    override fun toString(): String = "MonobankPersonal($config)"

    public companion object {
        /** Creates a client with default settings for a personal [token]. */
        @JvmStatic
        public fun create(token: String): MonobankPersonal = MonobankPersonal(PersonalConfig.builder(token).build())

        /**
         * Creates a client without a token, for the public endpoints ([bank]) only. Calls that
         * need a token throw [IllegalStateException].
         */
        @JvmStatic
        public fun publicOnly(): MonobankPersonal = MonobankPersonal(PersonalConfig.publicBuilder().build())
    }
}
