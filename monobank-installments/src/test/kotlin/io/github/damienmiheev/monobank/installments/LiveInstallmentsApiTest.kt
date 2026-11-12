package io.github.damienmiheev.monobank.installments

import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.installments.order.OrderStateResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Read-only calls against the installments **sandbox** (`https://u2-demo-ext.mono.st4g3.com`). Skipped unless
 * `MONOBANK_CHAST_STORE_ID` and `MONOBANK_CHAST_STORE_SECRET` hold the sandbox store id and secret (published in
 * monobank's docs at https://monobank.ua/api-docs/chast).
 *
 * Run by hand: `MONOBANK_CHAST_STORE_ID=... MONOBANK_CHAST_STORE_SECRET=... ./gradlew :monobank-installments:liveTest`.
 * Nothing is created: it validates a phone, asks for the state of a random order id and reads a daily report.
 * A wrong signature would fail every call with `MonobankAuthException`, so this also proves the request signing.
 */
@Tag("live")
class LiveInstallmentsApiTest {
    private val storeId: String? = System.getenv("MONOBANK_CHAST_STORE_ID")?.takeIf { it.isNotBlank() }
    private val storeSecret: String? = System.getenv("MONOBANK_CHAST_STORE_SECRET")?.takeIf { it.isNotEmpty() }

    private fun sandbox(): MonobankInstallments {
        assumeTrue(storeId != null && storeSecret != null, "MONOBANK_CHAST_STORE_ID / _SECRET are not set")
        return MonobankInstallments(
            InstallmentsConfig.builder(storeId!!, storeSecret!!).environment(InstallmentsEnvironment.SANDBOX).build(),
        )
    }

    @Test
    fun `signed client validation is accepted`() {
        val chast = sandbox()
        val response = chast.clients.validate("+380501234561")
        assertThat(response).isNotNull()
    }

    @Test
    fun `state of an unknown order is not-found, not an error`() {
        val chast = sandbox()
        val result =
            try {
                chast.orders.state(UUID.randomUUID().toString())
            } catch (e: MonobankApiException) {
                // The sandbox may answer 400 for orders it never saw; only a signature problem is a failure here.
                assertThat(e).isNotInstanceOf(MonobankAuthException::class.java)
                return
            }
        assertThat(result).isInstanceOf(OrderStateResult.NotFound::class.java)
    }

    @Test
    fun `daily report is readable`() {
        val chast = sandbox()
        val orders = chast.store.report(LocalDate.now().minusDays(1))
        assertThat(orders).isNotNull()
    }
}
