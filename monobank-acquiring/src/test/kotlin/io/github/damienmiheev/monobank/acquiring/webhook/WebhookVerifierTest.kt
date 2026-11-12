package io.github.damienmiheev.monobank.acquiring.webhook

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankSignatureException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebhookVerifierTest : AcquiringTestSupport() {
    private val docsBody = resource("/webhook/docs-example.body.json").toByteArray()
    private val docsKey = resource("/webhook/docs-example.pubkey.txt").trim()
    private val docsSign = resource("/webhook/docs-example.xsign.txt").trim()

    private fun keyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

    /** The key the way monobank's /pubkey returns it: base64 of a PEM. */
    private fun pubkeyResponseValue(pair: KeyPair): String {
        val pem =
            "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
                "\n-----END PUBLIC KEY-----\n"
        return Base64.getEncoder().encodeToString(pem.toByteArray())
    }

    private fun sign(
        pair: KeyPair,
        body: ByteArray,
    ): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(body)
                sign()
            },
        )

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    @Test
    fun `verifies the example from monobank's docs with the key from the pubkey endpoint`() {
        enqueueJson("""{"key":"$docsKey"}""")

        assertThat(mono.webhooks.check(docsSign, docsBody)).isEqualTo(VerificationResult.VALID)
        assertThat(takeRequest().path).isEqualTo("/api/merchant/pubkey")

        val parsed = mono.webhooks.verifyAndParse(docsSign, docsBody)
        assertThat(parsed.invoiceId).isEqualTo("p2_9ZgpZVsl3")
        assertThat(parsed.status).isEqualTo(InvoiceStatus.CREATED)
        assertThat(server.requestCount).isEqualTo(1) // key cached
    }

    @Test
    fun `a fixed key accepts PEM, base64 PEM and base64 DER`() {
        val pair = keyPair()
        val body = """{"invoiceId":"i","status":"success","amount":1,"ccy":980}""".toByteArray()
        val signature = sign(pair, body)
        val base64Pem = pubkeyResponseValue(pair)
        val pem = String(Base64.getDecoder().decode(base64Pem))
        val der = Base64.getEncoder().encodeToString(pair.public.encoded)
        listOf(base64Pem, pem, der).forEach {
            assertThat(WebhookVerifier.ofPublicKey(it).verify(signature, body)).isTrue()
        }
        assertThatThrownBy {
            WebhookVerifier.ofPublicKey(
                "not a key",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tampered body, wrong key, missing or garbage signature are invalid`() {
        val pair = keyPair()
        val verifier = WebhookVerifier.ofPublicKey(pubkeyResponseValue(pair))
        val body = """{"invoiceId":"i","status":"success","amount":100,"ccy":980}""".toByteArray()
        val signature = sign(pair, body)

        assertThat(verifier.check(signature, body)).isEqualTo(VerificationResult.VALID)
        assertThat(verifier.check(signature, body.copyOf().also { it[it.size - 3] = '9'.code.toByte() }))
            .isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.check(sign(keyPair(), body), body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.check(null, body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.check("", body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.check("%%%not-base64%%%", body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThat(verifier.check("AAAA", body)).isEqualTo(VerificationResult.INVALID_SIGNATURE)
        assertThatThrownBy { verifier.verifyOrThrow("AAAA", body) }
            .isInstanceOfSatisfying(MonobankSignatureException::class.java) {
                assertThat(it.reason).isEqualTo(MonobankSignatureException.Reason.INVALID_SIGNATURE)
                assertThat(it.outcome).isNull()
            }
    }

    @Test
    fun `key fetch failure is KEY_UNAVAILABLE, not invalid`() {
        val verifier = WebhookVerifier(WebhookKeyProvider { throw IllegalStateException("monobank down") })
        assertThat(verifier.check(docsSign, docsBody)).isEqualTo(VerificationResult.KEY_UNAVAILABLE)
        assertThatThrownBy { verifier.verifyOrThrow(docsSign, docsBody) }
            .isInstanceOfSatisfying(MonobankSignatureException::class.java) {
                assertThat(it.reason).isEqualTo(MonobankSignatureException.Reason.KEY_UNAVAILABLE)
            }

        enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500)
        enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500)
        enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500)
        assertThat(mono.webhooks.check(docsSign, docsBody)).isEqualTo(VerificationResult.KEY_UNAVAILABLE)
    }

    @Test
    fun `rotated key is re-fetched once and the previous key stays valid`() {
        val oldPair = keyPair()
        val newPair = keyPair()
        var served = oldPair
        val fetches = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier(
                WebhookKeyProvider {
                    fetches.incrementAndGet()
                    pubkeyResponseValue(served)
                },
                Duration.ofMinutes(1),
                clock,
            )
        val body = """{"invoiceId":"i","status":"success","amount":1,"ccy":980}""".toByteArray()

        assertThat(verifier.verify(sign(oldPair, body), body)).isTrue()
        served = newPair
        clock.now = clock.now.plusSeconds(120)
        // Signed with the new key: mismatch -> refetch -> valid.
        assertThat(verifier.verify(sign(newPair, body), body)).isTrue()
        // A late webhook signed with the old key still verifies.
        assertThat(verifier.verify(sign(oldPair, body), body)).isTrue()
        assertThat(fetches.get()).isEqualTo(2)
    }

    @Test
    fun `bad signatures cannot make the verifier hammer the key endpoint`() {
        val pair = keyPair()
        val fetches = AtomicInteger()
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier(
                WebhookKeyProvider {
                    fetches.incrementAndGet()
                    pubkeyResponseValue(pair)
                },
                Duration.ofMinutes(1),
                clock,
            )
        val body = "{}".toByteArray()
        val forged = sign(keyPair(), body)

        repeat(50) { assertThat(verifier.check(forged, body)).isEqualTo(VerificationResult.INVALID_SIGNATURE) }
        assertThat(fetches.get()).isEqualTo(1) // the first load counts as fresh
        clock.now = clock.now.plusSeconds(61)
        repeat(50) { verifier.check(forged, body) }
        assertThat(fetches.get()).isEqualTo(2) // one refresh per interval
    }

    @Test
    fun `refresh failure after a mismatch asks monobank to retry`() {
        val pair = keyPair()
        var fail = false
        val clock = MutableClock(Instant.parse("2025-01-01T00:00:00Z"))
        val verifier =
            WebhookVerifier(
                WebhookKeyProvider {
                    check(!fail) { "down" }
                    pubkeyResponseValue(pair)
                },
                Duration.ofMinutes(1),
                clock,
            )
        val body = "{}".toByteArray()
        assertThat(verifier.verify(sign(pair, body), body)).isTrue()
        fail = true
        clock.now = clock.now.plusSeconds(120)
        assertThat(verifier.check(sign(keyPair(), body), body)).isEqualTo(VerificationResult.KEY_UNAVAILABLE)
        // The cached key still works while the endpoint is down.
        assertThat(verifier.check(sign(pair, body), body)).isEqualTo(VerificationResult.VALID)
    }

    @Test
    fun `concurrent first use fetches the key once`() {
        val pair = keyPair()
        val fetches = AtomicInteger()
        val verifier =
            WebhookVerifier(
                WebhookKeyProvider {
                    fetches.incrementAndGet()
                    Thread.sleep(50)
                    pubkeyResponseValue(pair)
                },
            )
        val body = "{}".toByteArray()
        val signature = sign(pair, body)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results =
            (1..16).map {
                pool.submit<Boolean> {
                    start.await()
                    verifier.verify(signature, body)
                }
            }
        start.countDown()
        assertThat(results.map { it.get(5, TimeUnit.SECONDS) }).containsOnly(true)
        pool.shutdown()
        assertThat(fetches.get()).isEqualTo(1)
    }

    @Test
    fun `verifyAndParse rejects bodies that are not invoices only after a valid signature`() {
        val pair = keyPair()
        val verifier = WebhookVerifier.ofPublicKey(pubkeyResponseValue(pair))
        val body = """{"hello":"world"}""".toByteArray()
        assertThatThrownBy { verifier.verifyAndParse(sign(pair, body), body) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) { assertThat(it.outcome).isNull() }
        assertThatThrownBy {
            verifier.verifyAndParse(
                "AAAA",
                body,
            )
        }.isInstanceOf(MonobankSignatureException::class.java)
    }

    @Test
    fun `parser accepts strings`() {
        assertThat(WebhookParser.parse(String(docsBody)).invoiceId).isEqualTo("p2_9ZgpZVsl3")
    }
}
