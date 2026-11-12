package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.DOMAIN
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.PKCS8_PEM
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.PUBLIC_PEM
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.SEC1_PEM
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.TEST_KEY
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.TEST_KEY_ID
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.publicPoint
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.resource
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport.Companion.verify
import io.github.damienmiheev.monobank.corporate.internal.Secp256k1
import io.github.damienmiheev.monobank.corporate.internal.SignatureCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class CorporateKeyTest {
    @Test
    fun `loads the SEC1 key the docs' openssl command writes, with the EC PARAMETERS block`() {
        assertThat(SEC1_PEM).contains("BEGIN EC PARAMETERS").contains("BEGIN EC PRIVATE KEY")
        val key = CorporateKey.fromPem(SEC1_PEM)
        assertThat(key.keyId).isEqualTo(TEST_KEY_ID)
    }

    @Test
    fun `loads SEC1 without the parameters block, PKCS#8, and base64 of PEM to the same key`() {
        val sec1Only = SEC1_PEM.substring(SEC1_PEM.indexOf("-----BEGIN EC PRIVATE KEY"))
        val base64Pem = Base64.getEncoder().encodeToString(PKCS8_PEM.toByteArray())
        val crlf = SEC1_PEM.replace("\n", "\r\n")

        listOf(sec1Only, PKCS8_PEM, base64Pem, crlf, "  \n$PKCS8_PEM\n  ").forEach {
            assertThat(CorporateKey.fromPem(it).keyId).isEqualTo(TEST_KEY_ID)
        }
    }

    @Test
    fun `public key PEM is byte-for-byte what openssl ec -pubout wrote`() {
        assertThat(TEST_KEY.publicKey.publicKeyPem).isEqualTo(PUBLIC_PEM)
        assertThat(TEST_KEY.publicKey.publicKeyPemBase64)
            .isEqualTo(Base64.getEncoder().encodeToString(PUBLIC_PEM.toByteArray()))
            .startsWith("LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0K")
    }

    @Test
    fun `key id is the hex SHA-1 of the uncompressed point (docs' bank sync vector)`() {
        // GET /bank/sync example from the personal API docs: serverKeyId = sha1(serverPubKey).
        val serverPubKey = "BNDZP+AGoRC+ER1plDSUCHOw2/aBNIocmD2gS/v34/b0iQ1HBo+oS3/f402e3OXA5uCxakSjuxGMP6X0XP9VIUk="
        val point = Base64.getDecoder().decode(serverPubKey)
        assertThat(point).hasSize(65)
        // The vector is a real secp256k1 point.
        assertThat(DOMAIN.curve.decodePoint(point).isValid).isTrue()
        assertThat(Secp256k1.keyIdOf(point)).isEqualTo("2626ff34473bb66260b930af946fa9641a06bcd4")
    }

    @Test
    fun `key id of a generated key matches an independent computation from the public point`() {
        val (privateParams, publicParams) = generate()
        val key = CorporateKey.fromPem(sec1Pem(privateParams.d, withPublicKey = true))

        val uncompressed = publicParams.q.getEncoded(false)
        val expected = MessageDigest.getInstance("SHA-1").digest(uncompressed).joinToString("") { "%02x".format(it) }
        assertThat(key.keyId).isEqualTo(expected).hasSize(40).matches("[0-9a-f]{40}")
        assertThat(key.publicKey.uncompressed()).isEqualTo(uncompressed).hasSize(65)
        assertThat(key.publicKey.uncompressed()[0]).isEqualTo(4.toByte())
        assertThat(CorporateKey.fromPem(pkcs8Pem(privateParams.d)).keyId).isEqualTo(expected)
    }

    @Test
    fun `signatures of a generated key are 64 bytes and verify with BouncyCastle`() {
        repeat(20) {
            val (privateParams, publicParams) = generate()
            val key = CorporateKey.fromPem(sec1Pem(privateParams.d, withPublicKey = false))
            val data = "1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/statement/0/1546304461/1546306461".toByteArray()

            val signature = key.sign(data)

            assertThat(signature).hasSize(64)
            assertThat(verify(publicParams.q, data, signature)).isTrue()
            assertThat(verify(publicParams.q, data + 1, signature)).isFalse()
        }
    }

    @Test
    fun `signature matches openssl's deterministic RFC 6979 signature for the fixed key`() {
        // printf '%s' '1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/client-info' |
        //   openssl pkeyutl -sign -inkey test-sec1.pem -rawin -digest sha256 -pkeyopt nonce-type:1
        val data = "1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/client-info".toByteArray()
        val opensslDer =
            "MEUCIQDql21b7gzpCdN/gIMIkF2EZm8Oeh6Hr5dRIZa0VtbddwIgfK7yBWiq6Lnw1+ZYGD3SxRTEen8UjzbK0bqTStjVgkE="

        val signature = TEST_KEY.sign(data)

        assertThat(Base64.getEncoder().encodeToString(signature))
            .isEqualTo("6pdtW+4M6QnTf4CDCJBdhGZvDnoeh6+XUSGWtFbW3Xd8rvIFaKroufDX5lgYPdLFFMR6fxSPNsrRupNK2NWCQQ==")
        assertThat(
            SignatureCodec.encode(signature, SignatureEncoding.DER),
        ).isEqualTo(Base64.getDecoder().decode(opensslDer))
        assertThat(SignatureCodec.encode(Base64.getDecoder().decode(opensslDer), SignatureEncoding.P1363))
            .isEqualTo(signature)
        assertThat(verify(TEST_KEY.publicPoint(), data, signature)).isTrue()
    }

    @Test
    fun `more openssl RFC 6979 vectors, including a short s and r and s without a 0x00 prefix`() {
        // Same openssl command as above; P1363 values converted from openssl's DER with asn1parse.
        val vectors =
            listOf(
                // s is 31 bytes: P1363 pads it with a leading zero byte.
                Triple(
                    "1554466347/personal/signature/status?requestId=v234",
                    "MEQCIQD1WCNvxjv0LDCxdRgQHnC7HYN9dXc6mDOm1FCbxYQ8PQIfMy6Iq4CHBZbcCm1qNMgUJCRyY1JVsh1hcQbYJHIxUg==",
                    "9Vgjb8Y79CwwsXUYEB5wux2DfXV3OpgzptRQm8WEPD0AMy6Iq4CHBZbcCm1qNMgUJCRyY1JVsh1hcQbYJHIxUg==",
                ),
                // r and s are both below 2^255: DER has no 0x00 sign bytes.
                Triple(
                    "1554466347/personal/signature/status?requestId=v1",
                    "MEQCIAdjBCymiQWqvvV4+ZqjstK5k/SdltJhgVFXJj4KASsrAiAijJOTsxrz/MrlVLt9F47GtMgapTS0pPacpN1xC9DWSA==",
                    "B2MELKaJBaq+9Xj5mqOy0rmT9J2W0mGBUVcmPgoBKysijJOTsxrz/MrlVLt9F47GtMgapTS0pPacpN1xC9DWSA==",
                ),
            )
        vectors.forEach { (message, opensslDer, p1363) ->
            val signature = TEST_KEY.sign(message.toByteArray())
            assertThat(Base64.getEncoder().encodeToString(signature)).isEqualTo(p1363)
            assertThat(Base64.getEncoder().encodeToString(SignatureCodec.encode(signature, SignatureEncoding.DER)))
                .isEqualTo(opensslDer)
            assertThat(
                TEST_KEY.publicKey.verify(message.toByteArray(), Base64.getDecoder().decode(opensslDer)),
            ).isTrue()
        }
    }

    @Test
    fun `a 64-byte DER signature is read as DER, not as r and s`() {
        // r and s of 29 bytes each: SEQUENCE(2 + 31 + 31) = 64 bytes.
        val r = BigInteger(1, ByteArray(29) { 0x11 })
        val s = BigInteger(1, ByteArray(29) { 0x22 })
        val der = derOf(r, s)
        assertThat(der).hasSize(64)

        val raw = SignatureCodec.encode(der, SignatureEncoding.P1363)

        assertThat(BigInteger(1, raw.copyOfRange(0, 32))).isEqualTo(r)
        assertThat(BigInteger(1, raw.copyOfRange(32, 64))).isEqualTo(s)
        // A 64-byte r||s that happens to start like DER but is not canonical DER stays r||s.
        val rawLookingLikeDer =
            ByteArray(64) { 1 }.also {
                it[0] = 0x30
                it[1] = 62
            }
        assertThat(SignatureCodec.encode(rawLookingLikeDer, SignatureEncoding.P1363)).isEqualTo(rawLookingLikeDer)
    }

    @Test
    fun `short r and s are left-padded to 32 bytes`() {
        val raw = SignatureCodec.encode(derOf(BigInteger.ONE, BigInteger.TWO), SignatureEncoding.P1363)
        assertThat(raw).hasSize(64)
        assertThat(raw[31]).isEqualTo(1.toByte())
        assertThat(raw[63]).isEqualTo(2.toByte())
        assertThat(raw.copyOfRange(0, 31).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `rejects keys that are not usable secp256k1 private keys`() {
        fun rejects(
            pem: String,
            message: String,
        ) = assertThatThrownBy { CorporateKey.fromPem(pem) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(message)

        rejects(resource("/keys/p256-sec1.pem"), "secp256k1")
        rejects(resource("/keys/p256-pkcs8.pem"), "secp256k1")
        rejects(resource("/keys/test-explicit-params.pem"), "explicit EC parameters")
        rejects(resource("/keys/test-encrypted-pkcs8.pem"), "encrypted")
        rejects(resource("/keys/test-encrypted-sec1.pem"), "encrypted")
        rejects(PUBLIC_PEM, "no 'EC PRIVATE KEY'")
        rejects("not a key", "not PEM")
        rejects("", "not PEM")
        rejects("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n", "malformed")
        rejects(sec1Pem(BigInteger.ZERO, withPublicKey = false), "out of range")
        rejects(sec1Pem(DOMAIN.n, withPublicKey = false), "out of range")
        // An EC PARAMETERS block for P-256 next to a key that names no curve.
        rejects(P256_PARAMETERS + pem("EC PRIVATE KEY", ECPrivateKey(256, BigInteger.TEN).encoded), "secp256k1")
        // Embedded public key of a different private key.
        rejects(sec1Pem(BigInteger.TEN, withPublicKey = true, publicOf = BigInteger.TWO), "does not match")
    }

    @Test
    fun `a key that names no curve is taken as secp256k1, also next to a matching parameters block`() {
        val bare = pem("EC PRIVATE KEY", ECPrivateKey(256, BigInteger.TEN).encoded)
        val expected = CorporatePublicKey.fromPoint(DOMAIN.g.multiply(BigInteger.TEN).getEncoded(false)).keyId
        assertThat(CorporateKey.fromPem(bare).keyId).isEqualTo(expected)
        assertThat(CorporateKey.fromPem(SECP256K1_PARAMETERS + bare).keyId).isEqualTo(expected)
    }

    @Test
    fun `toString never shows the private key`() {
        val privateHex = "db7f65b970cb964df64be4c5526dd28a8376c9cc32f6a74c7b9b21b260dd0162"
        assertThat(TEST_KEY.toString()).isEqualTo("CorporateKey(keyId=$TEST_KEY_ID, privateKey=***)")
        val config = CorporateConfig.builder(SEC1_PEM).build()
        assertThat(config.toString()).doesNotContain(privateHex).doesNotContain("PRIVATE").contains(TEST_KEY_ID)
        assertThat(MonobankCorporate(config).toString()).doesNotContain(privateHex).doesNotContain("MHQCAQEE")
    }

    private companion object {
        /** `openssl ecparam -name prime256v1`. */
        const val P256_PARAMETERS = "-----BEGIN EC PARAMETERS-----\nBggqhkjOPQMBBw==\n-----END EC PARAMETERS-----\n"

        /** `openssl ecparam -name secp256k1`. */
        const val SECP256K1_PARAMETERS = "-----BEGIN EC PARAMETERS-----\nBgUrgQQACg==\n-----END EC PARAMETERS-----\n"
    }

    private fun generate(): Pair<ECPrivateKeyParameters, ECPublicKeyParameters> {
        val generator = ECKeyPairGenerator()
        generator.init(ECKeyGenerationParameters(DOMAIN, SecureRandom()))
        val pair = generator.generateKeyPair()
        return pair.private as ECPrivateKeyParameters to pair.public as ECPublicKeyParameters
    }

    private fun sec1Pem(
        d: BigInteger,
        withPublicKey: Boolean,
        publicOf: BigInteger = d,
    ): String {
        val sec1 =
            if (withPublicKey) {
                val point = DOMAIN.g.multiply(publicOf).normalize()
                ECPrivateKey(256, d, DERBitString(point.getEncoded(false)), SECObjectIdentifiers.secp256k1)
            } else {
                ECPrivateKey(256, d, SECObjectIdentifiers.secp256k1)
            }
        return pem("EC PRIVATE KEY", sec1.encoded)
    }

    private fun pkcs8Pem(d: BigInteger): String {
        val info =
            PrivateKeyInfo(
                AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, SECObjectIdentifiers.secp256k1),
                ECPrivateKey(256, d, null),
            )
        return pem("PRIVATE KEY", info.encoded)
    }

    private fun pem(
        type: String,
        der: ByteArray,
    ): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $type-----\n"

    private fun derOf(
        r: BigInteger,
        s: BigInteger,
    ): ByteArray =
        org.bouncycastle.asn1
            .DERSequence(arrayOf(org.bouncycastle.asn1.ASN1Integer(r), org.bouncycastle.asn1.ASN1Integer(s)))
            .encoded
}
