package io.github.damienmiheev.monobank.acquiring.monopay

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport.Companion.resource
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class MonopaySignerTest {
    private val sec1Pem = resource("/monopay/test-key.sec1.pem")
    private val pkcs8Pem = resource("/monopay/test-key.pkcs8.pem")
    private val publicPem = resource("/monopay/test-key.pub.pem")
    private val publicKey: PublicKey = WebhookVerifier.Companion.run { parsePublicKey(publicPem) }

    private fun verify(
        key: PublicKey,
        message: ByteArray,
        signatureBase64: String,
    ): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(Base64.getDecoder().decode(signatureBase64))
        }

    @Test
    fun `signs payload plus requestId with a SEC1 key from openssl`() {
        val json = """{"amount":4200,"ccy":980}"""

        val signed = MonopaySigner.fromPem(sec1Pem).sign(json, "req-1")

        assertThat(signed.requestId).isEqualTo("req-1")
        assertThat(String(Base64.getDecoder().decode(signed.payloadBase64))).isEqualTo(json)
        assertThat(verify(publicKey, (json + "req-1").toByteArray(), signed.signature)).isTrue()
        assertThat(verify(publicKey, json.toByteArray(), signed.signature)).isFalse()
    }

    @Test
    fun `PKCS8 and SEC1 PEMs are the same key, and base64 of a PEM is accepted`() {
        val json = """{"amount":1}"""
        val fromPkcs8 = MonopaySigner.fromPem(pkcs8Pem).sign(json, "r")
        assertThat(verify(publicKey, (json + "r").toByteArray(), fromPkcs8.signature)).isTrue()
        val fromBase64 =
            MonopaySigner
                .fromPem(
                    Base64.getEncoder().encodeToString(pkcs8Pem.toByteArray()),
                ).sign(json, "r")
        assertThat(verify(publicKey, (json + "r").toByteArray(), fromBase64.signature)).isTrue()
    }

    @Test
    fun `a signature made by openssl over the same bytes verifies with the same key`() {
        // openssl dgst -sha256 -sign test-key.sec1.pem over '{"amount":4200,"ccy":980}req-1'
        val opensslSignature = resource("/monopay/openssl-signature.txt").trim()
        assertThat(verify(publicKey, """{"amount":4200,"ccy":980}req-1""".toByteArray(), opensslSignature)).isTrue()
    }

    @Test
    fun `signs an invoice request as JSON and with a generated key pair`() {
        val pair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val signer = MonopaySigner.of(pair.private)

        val signed = signer.sign(CreateInvoiceRequest.builder(4200).ccy(980).build(), "req-2")

        val payload = Base64.getDecoder().decode(signed.payloadBase64)
        assertThat(String(payload)).contains("\"amount\":4200").contains("\"ccy\":980").doesNotContain("null")
        assertThat(verify(pair.public, payload + "req-2".toByteArray(), signed.signature)).isTrue()
        assertThat(signer.toString()).isEqualTo("MonopaySigner(privateKey=***)")
    }

    @Test
    fun `publicKeyValue is base64 of a PEM that monobank and the verifier can read`() {
        val value = MonopaySigner.publicKeyValue(publicKey)
        val pem = String(Base64.getDecoder().decode(value))
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("-----END PUBLIC KEY-----\n")
        assertThat(value).startsWith("LS0tLS1CRUdJTiBQVUJMSUMgS0VZ")
        assertThat(WebhookVerifier.Companion.run { parsePublicKey(value) }.encoded).isEqualTo(publicKey.encoded)
    }

    @Test
    fun `rejects keys that are not EC P-256`() {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThatThrownBy { MonopaySigner.of(rsa.private) }.isInstanceOf(IllegalArgumentException::class.java)
        val p384 =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp384r1")) }
                .generateKeyPair()
        assertThatThrownBy { MonopaySigner.of(p384.private) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonopaySigner.fromPem(publicPem) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonopaySigner.fromPem("garbage!") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonopaySigner.fromPem(pkcs8Pem).sign("{}", "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
