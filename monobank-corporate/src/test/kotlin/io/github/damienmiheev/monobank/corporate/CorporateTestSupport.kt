package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.MonobankClientOptions
import io.github.damienmiheev.monobank.core.RetryPolicy
import io.github.damienmiheev.monobank.corporate.internal.CorporateHttp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.ECPoint
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

/**
 * Base class: a MockWebServer, a client signing with the fixed test key, and a fake clock.
 *
 * The keys under `src/test/resources/keys/` were generated for these tests with the docs'
 * openssl commands and are not used anywhere else.
 */
abstract class CorporateTestSupport {
    protected lateinit var server: MockWebServer
    protected lateinit var clock: FakeClock
    protected lateinit var mono: MonobankCorporate

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
        clock = FakeClock(Instant.ofEpochSecond(TIME))
        mono = client()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    protected fun client(
        retryPolicy: RetryPolicy = RetryPolicy.of(2, Duration.ZERO, Duration.ZERO),
        signer: CorporateSigner = TEST_KEY,
        customize: (CorporateConfig.Builder) -> CorporateConfig.Builder = { it },
    ): MonobankCorporate {
        val config =
            customize(
                CorporateConfig
                    .builder(signer)
                    .clock(clock)
                    .options(
                        MonobankClientOptions
                            .builder()
                            .baseUrl(server.url("/").toString())
                            .retryPolicy(retryPolicy)
                            .build(),
                    ),
            ).build()
        return MonobankCorporate(config, CorporateHttp(config) { })
    }

    protected fun enqueueJson(
        body: String,
        status: Int = 200,
    ) {
        server.enqueue(
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body),
        )
    }

    protected fun enqueueFixture(name: String) = enqueueJson(fixture(name))

    /**
     * Takes the next request and checks the signing headers: `X-Time` from the fake clock,
     * `X-Key-Id` (unless [keyId] is `null`), and an `X-Sign` that verifies over [expectedSigned]
     * with the test key's public point.
     */
    protected fun takeSignedRequest(
        expectedSigned: (RecordedRequest) -> String,
        keyId: String? = TEST_KEY_ID,
    ): RecordedRequest {
        val request = server.takeRequest()
        val time = request.getHeader("X-Time")
        assertThat(time).matches("\\d{10}")
        assertThat(request.getHeader("X-Key-Id")).isEqualTo(keyId)
        val signature = Base64.getDecoder().decode(request.getHeader("X-Sign"))
        assertThat(signature).hasSize(64)
        assertThat(verify(TEST_KEY.publicPoint(), expectedSigned(request).toByteArray(), signature))
            .`as`("X-Sign verifies over '%s'", expectedSigned(request))
            .isTrue()
        return request
    }

    /** `X-Time | URL`, URL = the request path without the query string. */
    protected fun timeAndPath(request: RecordedRequest): String =
        request.getHeader("X-Time") + request.requestUrl!!.encodedPath

    /** `X-Time | X-Request-Id | URL`. */
    protected fun timeRequestIdAndPath(request: RecordedRequest): String =
        request.getHeader("X-Time") + request.getHeader("X-Request-Id") + request.requestUrl!!.encodedPath

    /** A clock whose time tests can move. */
    class FakeClock(
        @Volatile var now: Instant,
        private val step: Duration = Duration.ZERO,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant {
            val current = now
            now = now.plus(step)
            return current
        }
    }

    companion object {
        /** `X-Time` the fake clock starts at (the docs' example time). */
        const val TIME = 1_554_466_347L

        /** `X-Key-Id` of the fixed test key, computed with openssl: `sha1(uncompressed point)`. */
        const val TEST_KEY_ID = "9cfcc2eee16244c5d22082930e9a00e9d2ce441e"

        val SEC1_PEM: String = resource("/keys/test-sec1.pem")
        val PKCS8_PEM: String = resource("/keys/test-pkcs8.pem")
        val PUBLIC_PEM: String = resource("/keys/test-public.pem")
        val TEST_KEY: CorporateKey = CorporateKey.fromPem(SEC1_PEM)

        val DOMAIN: ECDomainParameters =
            CustomNamedCurves.getByName("secp256k1").let { ECDomainParameters(it.curve, it.g, it.n, it.h) }

        fun fixture(name: String): String = resource("/fixtures/$name")

        fun resource(name: String): String =
            requireNotNull(CorporateTestSupport::class.java.getResource(name)) { "missing resource $name" }.readText()

        fun CorporateKey.publicPoint(): ECPoint = DOMAIN.curve.decodePoint(publicKeyUncompressed())

        /** Verifies a 64-byte `r‖s` ECDSA-SHA256 signature with BouncyCastle's own verifier. */
        fun verify(
            publicPoint: ECPoint,
            data: ByteArray,
            rawSignature: ByteArray,
        ): Boolean {
            require(rawSignature.size == 64)
            val digest = SHA256Digest()
            digest.update(data, 0, data.size)
            val hash = ByteArray(32)
            digest.doFinal(hash, 0)
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(publicPoint, DOMAIN))
            return verifier.verifySignature(
                hash,
                BigInteger(1, rawSignature.copyOfRange(0, 32)),
                BigInteger(1, rawSignature.copyOfRange(32, 64)),
            )
        }
    }
}
