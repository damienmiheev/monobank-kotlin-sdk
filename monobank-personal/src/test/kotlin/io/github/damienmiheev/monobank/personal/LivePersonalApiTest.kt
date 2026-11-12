package io.github.damienmiheev.monobank.personal

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.personal.statement.StatementApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Read-only calls against the real personal API. Skipped unless `MONOBANK_PERSONAL_TOKEN` holds a
 * personal token from https://api.monobank.ua/.
 *
 * Run by hand: `MONOBANK_PERSONAL_TOKEN=... ./gradlew :monobank-personal:liveTest`.
 * It calls `client-info` and `statement` once each (monobank allows each once per 60 s, so wait a
 * minute between runs) and `/bank/currency` and `/bank/sync` without the token (also only when the
 * variable is set, so that a plain `liveTest` run makes no network calls). Nothing is changed:
 * the webhook URL is never set.
 */
@Tag("live")
class LivePersonalApiTest {
    private val token: String? = System.getenv("MONOBANK_PERSONAL_TOKEN")?.takeIf { it.isNotBlank() }

    @Test
    fun `public endpoints`() {
        assumeTrue(token != null, "MONOBANK_PERSONAL_TOKEN is not set")
        val mono = MonobankPersonal.publicOnly()

        val rates = mono.bank.currency()
        assertThat(rates).isNotEmpty()
        assertThat(rates).anySatisfy { assertThat(it.currencyCodeA).isEqualTo(840) }

        val sync = mono.bank.sync()
        assertThat(sync.serverKeyId).isNotBlank()
        assertThat(Duration.between(sync.serverTime, Instant.now()).abs()).isLessThan(Duration.ofMinutes(5))
    }

    @Test
    fun `client info and the last day of the default account statement`() {
        assumeTrue(token != null, "MONOBANK_PERSONAL_TOKEN is not set")
        val mono =
            MonobankPersonal(
                PersonalConfig
                    .builder(token!!)
                    .rateLimiter(PersonalRateLimiter.create())
                    .options(
                        MonobankClientOptions
                            .builder()
                            .retryPolicy(RetryPolicy.NONE)
                            .build(),
                    ).build(),
            )

        val info = mono.client.info()
        assertThat(info.clientId).isNotBlank()
        assertThat(info.accounts).isNotNull()

        val items = mono.statements.list(StatementApi.DEFAULT_ACCOUNT, Instant.now().minus(Duration.ofDays(1)))
        assertThat(items.map { it.id }).doesNotHaveDuplicates()
    }
}
