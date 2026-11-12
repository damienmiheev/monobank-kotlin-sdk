package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.MonobankApiException
import io.github.damienmiheev.monobank.corporate.access.AccessRequestStatus
import io.github.damienmiheev.monobank.corporate.client.ClientApi
import io.github.damienmiheev.monobank.corporate.company.RegistrationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Read-only calls against the real corporate API; they confirm the request signing.
 * Run with `./gradlew :monobank-corporate:liveTest`.
 *
 * Environment variables (never commit their values):
 * - `MONOBANK_CORP_PRIVATE_KEY_PEM`: the company's secp256k1 private key, PEM text or base64 of it.
 *   Without it every test is skipped.
 * - `MONOBANK_CORP_REQUEST_ID`: optional, an access request id a client has approved; enables the
 *   client-info and statement tests.
 * - `MONOBANK_CORP_SIGNATURE_ENCODING`: optional, `P1363` (default) or `DER`.
 * - `MONOBANK_CORP_SIGN_QUERY`: optional, `true` to sign the query string.
 *
 * The registration status call works with any registered key; the others need an approved one.
 */
@Tag("live")
class CorporateLiveTest {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun mono(): MonobankCorporate {
        val pem = env("MONOBANK_CORP_PRIVATE_KEY_PEM")
        assumeTrue(pem != null, "MONOBANK_CORP_PRIVATE_KEY_PEM is not set")
        val config =
            CorporateConfig
                .builder(pem!!)
                .signatureEncoding(
                    env("MONOBANK_CORP_SIGNATURE_ENCODING")?.let { SignatureEncoding.valueOf(it) }
                        ?: SignatureEncoding.P1363,
                ).signQueryString(env("MONOBANK_CORP_SIGN_QUERY").toBoolean())
                .build()
        return MonobankCorporate(config)
    }

    private fun requestId(): String {
        val id = env("MONOBANK_CORP_REQUEST_ID")
        assumeTrue(id != null, "MONOBANK_CORP_REQUEST_ID is not set")
        return id!!
    }

    @Test
    fun `registration status answers for the key and returns its key id`() {
        val mono = mono()
        val status = mono.company.registrationStatus((mono.config.signer as CorporateKey).publicKey)
        assertThat(status.status).isNotEqualTo(RegistrationStatus.UNKNOWN)
        if (status.status == RegistrationStatus.APPROVED) assertThat(status.keyId).isEqualTo(mono.config.keyId)
    }

    @Test
    fun `settings are readable with the signed request`() {
        val settings = mono().company.settings()
        assertThat(settings.permission).isNotBlank()
    }

    @Test
    fun `approved access request is reported as approved`() {
        val mono = mono()
        assertThat(mono.access.status(requestId())).isEqualTo(AccessRequestStatus.APPROVED)
    }

    @Test
    fun `client info and a one-day statement can be read`() {
        val client = mono().client(requestId())
        val info = client.info()
        assertThat(info.accounts).isNotNull()
        val to = Instant.now()
        assertThat(client.statement(ClientApi.DEFAULT_ACCOUNT, to.minus(Duration.ofDays(1)), to)).isNotNull()
    }

    /**
     * Settles which URL `signature/status` signs: sends a status request for an unknown id with
     * and without the query string signed. 400/404 means monobank accepted the signature;
     * 401/403 means it did not. Exactly one setting should be accepted; the default is the one
     * without the query string.
     */
    @Test
    fun `signature status shows which query-string setting monobank accepts`() {
        val pem = env("MONOBANK_CORP_PRIVATE_KEY_PEM")
        assumeTrue(pem != null, "MONOBANK_CORP_PRIVATE_KEY_PEM is not set")
        val accepted =
            listOf(false, true).associateWith { signQuery ->
                val mono = MonobankCorporate(CorporateConfig.builder(pem!!).signQueryString(signQuery).build())
                val error = runCatching { mono.signatures.status("sdk-live-test-unknown-request") }.exceptionOrNull()
                val status = (error as? MonobankApiException)?.statusCode
                println("signQueryString=$signQuery -> ${status ?: error ?: "200"}")
                status != 401 && status != 403
            }
        assertThat(accepted[CorporateConfig.builder(pem!!).build().signQueryString])
            .`as`("default signQueryString accepted; results: %s", accepted)
            .isTrue()
    }

    /**
     * Checks the company endpoints with an `X-Request-Id` (sent, not signed). A 401/403 here while
     * `settings are readable with the signed request` passes means monobank signs any
     * `X-Request-Id` it receives; then never pass `requestId` to `settings`/`setWebhook`.
     */
    @Test
    fun `settings with an unsigned request id`() {
        val mono = mono()
        assertThat(mono.company.settings(requestId()).permission).isNotBlank()
    }
}
