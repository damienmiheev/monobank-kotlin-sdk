package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.PUBLIC_PEM
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.TEST_KEY
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.TEST_KEY_ID
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.resource
import io.github.damienmiheev.monobank.corporate.internal.Secp256k1
import io.github.damienmiheev.monobank.corporate.internal.SignatureCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class CorporatePublicKeyTest {
    private val spkiDer: ByteArray =
        Base64.getMimeDecoder().decode(PUBLIC_PEM.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    @Test
    fun `loads the openssl public key from PEM, base64 PEM, SPKI DER and the point`() {
        val uncompressed = TEST_KEY.publicKey.uncompressed()
        val compressed = Secp256k1.decodePoint(uncompressed).getEncoded(true)
        val keys =
            listOf(
                CorporatePublicKey.fromPem(PUBLIC_PEM),
                CorporatePublicKey.fromPem(Base64.getEncoder().encodeToString(PUBLIC_PEM.toByteArray())),
                CorporatePublicKey.fromSpkiDer(spkiDer),
                CorporatePublicKey.fromPoint(uncompressed),
                CorporatePublicKey.fromPoint(compressed),
            )

        keys.forEach {
            assertThat(it.keyId).isEqualTo(TEST_KEY_ID)
            assertThat(it.publicKeyPem).isEqualTo(PUBLIC_PEM)
            assertThat(it.publicKeyPemBase64).isEqualTo(Base64.getEncoder().encodeToString(PUBLIC_PEM.toByteArray()))
            assertThat(it).isEqualTo(TEST_KEY.publicKey).hasSameHashCodeAs(TEST_KEY.publicKey)
        }
        assertThat(compressed).hasSize(33)
        assertThat(TEST_KEY.publicKey.toString()).isEqualTo("CorporatePublicKey(keyId=$TEST_KEY_ID)")
    }

    @Test
    fun `docs' bank sync point gives the documented key id`() {
        val key =
            CorporatePublicKey.fromPoint(
                Base64.getDecoder().decode(
                    "BNDZP+AGoRC+ER1plDSUCHOw2/aBNIocmD2gS/v34/b0iQ1HBo+oS3/f402e3OXA5uCxakSjuxGMP6X0XP9VIUk=",
                ),
            )
        assertThat(key.keyId).isEqualTo("2626ff34473bb66260b930af946fa9641a06bcd4")
    }

    @Test
    fun `verifies a KMS-style DER signature and a P1363 one, and rejects others`() {
        val data = "1554466347/personal/corp/settings".toByteArray()
        val raw = TEST_KEY.sign(data)
        val der = SignatureCodec.encode(raw, SignatureEncoding.DER)
        val key = CorporatePublicKey.fromPem(PUBLIC_PEM)

        assertThat(key.verify(data, raw)).isTrue()
        assertThat(key.verify(data, der)).isTrue()
        assertThat(key.verify(data + 1, raw)).isFalse()
        assertThat(key.verify(data, raw + 27)).isFalse()
        assertThat(key.verify(data, ByteArray(0))).isFalse()
    }

    @Test
    fun `rejects keys that are not secp256k1 public keys`() {
        fun rejects(block: () -> Unit) = assertThatThrownBy(block).isInstanceOf(IllegalArgumentException::class.java)

        rejects { CorporatePublicKey.fromPem(resource("/keys/p256-public.pem")) }
        rejects { CorporatePublicKey.fromPem(resource("/keys/test-sec1.pem")) }
        rejects { CorporatePublicKey.fromPem("garbage") }
        rejects { CorporatePublicKey.fromSpkiDer(byteArrayOf(1, 2, 3)) }
        rejects { CorporatePublicKey.fromPoint(ByteArray(65) { 7 }) }
        rejects { CorporatePublicKey.fromPoint(byteArrayOf(0)) }
    }
}
