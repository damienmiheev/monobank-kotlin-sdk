package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.corporate.company.RegistrationRequest
import io.github.damienmiheev.monobank.corporate.internal.CorporateAuthenticator
import io.github.damienmiheev.monobank.corporate.internal.Secp256k1
import io.github.damienmiheev.monobank.corporate.signature.CreateSignatureRequest
import io.github.damienmiheev.monobank.corporate.signature.SignDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections

class RequestSigningTest : CorporateTestSupport() {
    /** Records what it was asked to sign and delegates to the test key. */
    private class RecordingSigner(
        private val delegate: CorporateSigner = TEST_KEY,
    ) : CorporateSigner {
        val signed: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override val keyId: String get() = delegate.keyId

        override fun sign(data: ByteArray): ByteArray {
            signed += String(data, Charsets.UTF_8)
            return delegate.sign(data)
        }
    }

    @Test
    fun `string to sign is plain concatenation without separators`() {
        assertThat(CorporateAuthenticator.stringToSign("1554466347", null, "/personal/auth/request"))
            .isEqualTo("1554466347/personal/auth/request")
        assertThat(
            CorporateAuthenticator.stringToSign("1554466347", "uLkwh3NzFAfEkj7urj5C7AU_", "/personal/client-info"),
        ).isEqualTo("1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/client-info")
    }

    @Test
    fun `each endpoint signs exactly the documented string`() {
        val signer = RecordingSigner()
        val mono = client(signer = signer)
        val requestId = "uLkwh3NzFAfEkj7urj5C7AU_"
        repeat(11) { enqueueJson("{}") }
        enqueueJson("[]")

        listOf<() -> Any?>(
            { mono.company.register(registration()) },
            { mono.company.registrationStatus("cHVi") },
            { mono.company.settings() },
            { mono.company.settings(requestId) },
            { mono.company.setWebhook("https://example.com/hook") },
            { mono.access.request() },
            { mono.access.status(requestId) },
            { mono.client(requestId).info() },
            { mono.signatures.create(signatureRequest()) },
            { mono.signatures.status("sGtN4FnxYORZQU5Me1HbYhQ") },
            { mono.signatures.cancel("sGtN4FnxYORZQU5Me1HbYhQ") },
            {
                mono.client(requestId).statement(
                    "0",
                    Instant.ofEpochSecond(1546304461),
                    Instant.ofEpochSecond(1546306461),
                )
            },
            // Only signing matters here; "{}" does not parse into every answer type.
        ).forEach { call -> runCatching { call() } }

        assertThat(signer.signed).containsExactly(
            "1554466347/personal/auth/registration",
            "1554466347/personal/auth/registration/status",
            "1554466347/personal/corp/settings",
            // X-Request-Id passed by the caller is sent but, as the X-Sign docs say, not signed.
            "1554466347/personal/corp/settings",
            "1554466347/personal/corp/webhook",
            "1554466347/personal/auth/request",
            "1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/auth/request",
            "1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/client-info",
            "1554466347/personal/signature/create",
            "1554466347/personal/signature/status",
            "1554466347/personal/signature/cancel",
            "1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/statement/0/1546304461/1546306461",
        )
        // Registration carries no X-Key-Id; everything else does.
        val keyIds = (1..12).map { server.takeRequest().getHeader("X-Key-Id") }
        assertThat(keyIds.take(2)).containsOnlyNulls()
        assertThat(keyIds.drop(2)).containsOnly(TEST_KEY_ID)
    }

    @Test
    fun `the signed URL is the encoded path exactly as sent`() {
        val signer = RecordingSigner()
        enqueueJson("[]")

        client(signer = signer).client("req").statement("acc/1 x", Instant.ofEpochSecond(10), null)

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/personal/statement/acc%2F1%20x/10")
        assertThat(signer.signed).containsExactly("1554466347req/personal/statement/acc%2F1%20x/10")
    }

    @Test
    fun `query string is signed only when configured`() {
        val signer = RecordingSigner()
        enqueueJson("{}")
        enqueueJson("{}")

        client(signer = signer) { it.signQueryString(true) }.signatures.cancel("a b")
        client(signer = signer).signatures.cancel("a b")

        assertThat(server.takeRequest().path).isEqualTo("/personal/signature/cancel?requestId=a%20b")
        assertThat(signer.signed).containsExactly(
            "1554466347/personal/signature/cancel?requestId=a%20b",
            "1554466347/personal/signature/cancel",
        )
    }

    @Test
    fun `X-Sign is a verifiable 64-byte P1363 signature by default`() {
        enqueueJson("{}")

        mono.access.status("uLkwh3NzFAfEkj7urj5C7AU_")

        val request = takeSignedRequest(::timeRequestIdAndPath)
        assertThat(request.getHeader("X-Time")).isEqualTo("1554466347")
        assertThat(request.getHeader("X-Request-Id")).isEqualTo("uLkwh3NzFAfEkj7urj5C7AU_")
    }

    @Test
    fun `DER encoding can be configured`() {
        enqueueJson("{}")

        client { it.signatureEncoding(SignatureEncoding.DER) }.access.status("uLkwh3NzFAfEkj7urj5C7AU_")

        val request = server.takeRequest()
        val der = Base64.getDecoder().decode(request.getHeader("X-Sign"))
        val sequence = ASN1Sequence.getInstance(der)
        val r = ASN1Integer.getInstance(sequence.getObjectAt(0)).value
        val s = ASN1Integer.getInstance(sequence.getObjectAt(1)).value
        val raw = Secp256k1.encode(der, SignatureEncoding.P1363)
        assertThat(raw.copyOfRange(0, 32)).isEqualTo(
            org.bouncycastle.util.BigIntegers
                .asUnsignedByteArray(32, r),
        )
        assertThat(raw.copyOfRange(32, 64)).isEqualTo(
            org.bouncycastle.util.BigIntegers
                .asUnsignedByteArray(32, s),
        )
        val signed = "1554466347uLkwh3NzFAfEkj7urj5C7AU_/personal/auth/request".toByteArray()
        assertThat(verify(TEST_KEY.publicPoint(), signed, raw)).isTrue()
    }

    @Test
    fun `a custom signer may return DER, which is sent as P1363`() {
        val derSigner =
            object : CorporateSigner {
                override val keyId: String = "custom-key-id"

                override fun sign(data: ByteArray): ByteArray =
                    Secp256k1.encode(TEST_KEY.sign(data), SignatureEncoding.DER)
            }
        enqueueJson("{}")

        client(signer = derSigner).access.status("r1")

        takeSignedRequest(::timeRequestIdAndPath, keyId = "custom-key-id")
    }

    @Test
    fun `key id can be overridden`() {
        enqueueJson("{}")
        client { it.keyId("28a75537175a018645e6f8b14be7681791e701e0") }.company.settings()
        assertThat(server.takeRequest().getHeader("X-Key-Id")).isEqualTo("28a75537175a018645e6f8b14be7681791e701e0")
        assertThatThrownBy { CorporateConfig.builder(TEST_KEY).keyId("a\nb") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `every retry attempt gets a fresh time and signature`() {
        clock = FakeClock(Instant.ofEpochSecond(TIME), step = Duration.ofSeconds(7))
        val signer = RecordingSigner()
        val mono = client(signer = signer, retryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ZERO))
        enqueueJson("""{"errorDescription":"busy"}""", status = 503)
        enqueueJson("""{"errorDescription":"slow down"}""", status = 429)
        enqueueFixture("GET_personal_client_info.response.json")

        mono.client("req-1").info()

        val attempts = (1..3).map { takeSignedRequest(::timeRequestIdAndPath) }
        assertThat(attempts.map { it.getHeader("X-Time") }).containsExactly("1554466347", "1554466354", "1554466361")
        assertThat(attempts.map { it.getHeader("X-Sign") }.toSet()).hasSize(3)
        assertThat(signer.signed).containsExactly(
            "1554466347req-1/personal/client-info",
            "1554466354req-1/personal/client-info",
            "1554466361req-1/personal/client-info",
        )
    }

    @Test
    fun `a failing or broken signer surfaces as a not-sent serialization error`() {
        val failing =
            object : CorporateSigner {
                override val keyId: String = "k"

                override fun sign(data: ByteArray): ByteArray = throw IllegalStateException("KMS unavailable")
            }
        val garbage =
            object : CorporateSigner {
                override val keyId: String = "k"

                override fun sign(data: ByteArray): ByteArray = ByteArray(10)
            }

        listOf(failing, garbage).forEach { signer ->
            assertThatThrownBy { client(signer = signer, retryPolicy = RetryPolicy.NONE).company.settings() }
                .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                    assertThat(it.outcome).isEqualTo(RequestOutcome.NOT_SENT)
                    assertThat(it.message).startsWith("Could not sign")
                }
        }
        assertThat(server.requestCount).isZero()
    }

    private fun registration(): RegistrationRequest =
        RegistrationRequest
            .builder()
            .pubkey(TEST_KEY)
            .name("n")
            .description("d")
            .contactPerson("c")
            .phone("380671234567")
            .email("e@example.com")
            .logo(byteArrayOf(1, 2, 3))
            .build()

    private fun signatureRequest(): CreateSignatureRequest =
        CreateSignatureRequest.builder(listOf(SignDocument.builder("doc", "A421FD").build())).build()
}
