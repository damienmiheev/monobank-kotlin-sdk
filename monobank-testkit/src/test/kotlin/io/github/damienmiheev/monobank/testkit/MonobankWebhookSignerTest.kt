package io.github.damienmiheev.monobank.testkit

import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class MonobankWebhookSignerTest {
    private val body = """{"invoiceId":"p2_9ZgpZVsl3","status":"success","amount":4200,"ccy":980}""".toByteArray()

    @Test
    fun `public key has the pubkey endpoint format - base64 of a PEM SubjectPublicKeyInfo`() {
        val signer = MonobankWebhookSigner.generate()

        val pem = String(Base64.getDecoder().decode(signer.publicKey), Charsets.US_ASCII)

        assertThat(pem).isEqualTo(signer.publicKeyPem)
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE")
        assertThat(pem).endsWith("\n-----END PUBLIC KEY-----\n")
        val lines = pem.lines().filter { it.isNotEmpty() && !it.startsWith("-----") }
        assertThat(lines).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(64) }
        val der = Base64.getDecoder().decode(lines.joinToString(""))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        assertThat(key.encoded).isEqualTo(signer.keyPair.public.encoded)
    }

    @Test
    fun `signature is base64 DER ECDSA-SHA256 over the raw bytes and the SDK verifier accepts it`() {
        val signer = MonobankWebhookSigner.generate()

        val xSign = signer.sign(body)

        val der = Base64.getDecoder().decode(xSign)
        assertThat(der[0]).isEqualTo(0x30.toByte()) // ASN.1 SEQUENCE
        val jdk =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(signer.keyPair.public)
                update(body)
                verify(der)
            }
        assertThat(jdk).isTrue()
        assertThat(WebhookVerifier.ofPublicKey(signer.publicKey).verify(xSign, body)).isTrue()
        assertThat(signer.verify(xSign, body)).isTrue()
        assertThat(signer.verify(xSign, body + ' '.code.toByte())).isFalse()
        assertThat(signer.verify("not base64!", body)).isFalse()
        assertThat(signer.verify(null, body)).isFalse()
        assertThat(signer.sign(String(body))).satisfies({ assertThat(signer.verify(it, body)).isTrue() })
    }

    @Test
    fun `rotation changes the key - old signatures no longer verify with the new key`() {
        val signer = MonobankWebhookSigner.generate()
        val oldKey = signer.publicKey
        val oldSignature = signer.sign(body)

        val newKey = signer.rotate()

        assertThat(newKey).isNotEqualTo(oldKey).isEqualTo(signer.publicKey)
        assertThat(WebhookVerifier.ofPublicKey(newKey).verify(oldSignature, body)).isFalse()
        assertThat(WebhookVerifier.ofPublicKey(newKey).verify(signer.sign(body), body)).isTrue()
        assertThat(WebhookVerifier.ofPublicKey(oldKey).verify(signer.sign(body), body)).isFalse()
    }

    @Test
    fun `takes a fixed key pair and rejects keys that are not P-256`() {
        val fixed = MonobankWebhookSigner.generateKeyPair()
        assertThat(MonobankWebhookSigner.of(fixed).publicKey).isEqualTo(MonobankWebhookSigner.of(fixed).publicKey)

        val p384 =
            KeyPairGenerator
                .getInstance(
                    "EC",
                ).apply { initialize(ECGenParameterSpec("secp384r1")) }
                .generateKeyPair()
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThatThrownBy { MonobankWebhookSigner.of(p384) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonobankWebhookSigner.of(rsa) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonobankWebhookSigner.generate().rotate(rsa) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `toString shows a fingerprint, never key material`() {
        val signer = MonobankWebhookSigner.generate()
        val private = Base64.getEncoder().encodeToString(signer.keyPair.private.encoded)

        assertThat(signer.toString()).matches("MonobankWebhookSigner\\(publicKeySha256=[0-9a-f]{16}\\)")
        assertThat(signer.toString()).doesNotContain(private).doesNotContain(signer.publicKey)
    }
}
