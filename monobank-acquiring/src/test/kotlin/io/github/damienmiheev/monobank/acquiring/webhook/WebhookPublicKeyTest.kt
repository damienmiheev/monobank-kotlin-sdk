package io.github.damienmiheev.monobank.acquiring.webhook

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebhookPublicKeyTest : AcquiringTestSupport() {
    private val docsKey = resource("/webhook/docs-example.pubkey.txt").trim()
    private val docsBody = resource("/webhook/docs-example.body.json").toByteArray()
    private val docsSign = resource("/webhook/docs-example.xsign.txt").trim()

    @Test
    fun `current public key is loaded once and returned as monobank sent it`() {
        enqueueJson("""{"key":"$docsKey"}""")

        assertThat(mono.webhooks.currentPublicKey()).isEqualTo(docsKey)
        assertThat(mono.webhooks.check(docsSign, docsBody)).isEqualTo(VerificationResult.VALID)
        assertThat(mono.webhooks.currentPublicKey()).isEqualTo(docsKey)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `current public key is null when it cannot be loaded`() {
        enqueueJson("""{"errCode":"FORBIDDEN","errText":"forbidden"}""", status = 403)
        assertThat(mono.webhooks.currentPublicKey()).isNull()
    }

    @Test
    fun `a fixed key is returned unchanged`() {
        val verifier = WebhookVerifier.ofPublicKey(docsKey)
        assertThat(verifier.currentPublicKey()).isEqualTo(docsKey)
        assertThat(verifier.check(docsSign, docsBody)).isEqualTo(VerificationResult.VALID)
    }
}
