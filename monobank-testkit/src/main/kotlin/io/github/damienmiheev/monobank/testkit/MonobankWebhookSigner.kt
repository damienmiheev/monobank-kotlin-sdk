package io.github.damienmiheev.monobank.testkit

import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64

/**
 * Signs webhook bodies the way monobank acquiring does, for tests.
 *
 * monobank signs the **raw bytes** of every webhook body with ECDSA over SHA-256 on the P-256
 * (`prime256v1`) curve and sends the DER signature, base64-encoded, in the `X-Sign` header. The
 * matching public key is served by `GET /api/merchant/pubkey` as `{"key": "<base64 of a PEM>"}`;
 * [publicKey] returns exactly that `key` value.
 *
 * ```kotlin
 * val signer = MonobankWebhookSigner.generate()
 * val body = """{"invoiceId":"p2_9ZgpZVsl3","status":"success","amount":4200,"ccy":980}""".toByteArray()
 * val xSign = signer.sign(body)
 * val verifier = WebhookVerifier.ofPublicKey(signer.publicKey) // from monobank-acquiring
 * check(verifier.verify(xSign, body))
 * ```
 *
 * The key can be replaced with [rotate], as monobank may rotate its key without notice. Thread-safe.
 *
 * This is a test helper that follows monobank's documented signing scheme; it is not monobank's key
 * and its signatures are not accepted anywhere but in your tests.
 */
public class MonobankWebhookSigner private constructor(
    initial: KeyPair,
) {
    @Volatile
    private var current: KeyPair = initial

    /** The key pair in use now (it changes on [rotate]). Contains the private key: test use only. */
    public val keyPair: KeyPair
        get() = current

    /**
     * The public key exactly as `GET /api/merchant/pubkey` returns it in its `key` field: base64 of
     * a PEM `PUBLIC KEY` (X.509 SubjectPublicKeyInfo).
     */
    public val publicKey: String
        get() = encodePublicKey(current)

    /** The public key as PEM text (`-----BEGIN PUBLIC KEY----- ...`). */
    public val publicKeyPem: String
        get() = pem(current)

    /**
     * Signs [body] like monobank: ECDSA with SHA-256 over the raw bytes, DER-encoded, then base64.
     * The result is the `X-Sign` header value.
     */
    public fun sign(body: ByteArray): String = signWith(current, body)

    /** Signs the UTF-8 bytes of [body]. See [sign]. */
    public fun sign(body: String): String = sign(body.toByteArray(StandardCharsets.UTF_8))

    /**
     * `true` when [xSign] is a valid signature of [body] by the current key. The argument order is
     * the same as `WebhookVerifier.verify`.
     */
    public fun verify(
        xSign: String?,
        body: ByteArray,
    ): Boolean {
        if (xSign.isNullOrBlank()) return false
        return try {
            val signature = Base64.getDecoder().decode(xSign.trim())
            Signature.getInstance(ALGORITHM).run {
                initVerify(current.public)
                update(body)
                verify(signature)
            }
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            false
        } catch (
            @Suppress("SwallowedException") e: GeneralSecurityException,
        ) {
            false
        }
    }

    /**
     * Replaces the key with a newly generated P-256 key pair, as when monobank rotates its key.
     * Later signatures use the new key and [publicKey] returns the new key.
     *
     * @return The new [publicKey].
     */
    public fun rotate(): String = rotate(generateKeyPair())

    /**
     * Replaces the key with [newKeyPair] (a P-256 EC key pair).
     *
     * @return The new [publicKey].
     * @throws IllegalArgumentException when [newKeyPair] is not a P-256 EC key pair.
     */
    public fun rotate(newKeyPair: KeyPair): String {
        requireP256(newKeyPair)
        current = newKeyPair
        return publicKey
    }

    /** Shows a short fingerprint of the public key, never the private key. */
    override fun toString(): String = "MonobankWebhookSigner(publicKeySha256=${fingerprint(current)})"

    public companion object {
        private const val ALGORITHM = "SHA256withECDSA"
        private const val CURVE = "secp256r1"
        private const val PEM_LINE = 64
        private const val FINGERPRINT_BYTES = 8

        /** A signer with a newly generated P-256 key pair. */
        @JvmStatic
        public fun generate(): MonobankWebhookSigner = MonobankWebhookSigner(generateKeyPair())

        /**
         * A signer with your own key pair, e.g. one fixed for reproducible tests.
         *
         * @throws IllegalArgumentException when [keyPair] is not a P-256 EC key pair.
         */
        @JvmStatic
        public fun of(keyPair: KeyPair): MonobankWebhookSigner {
            requireP256(keyPair)
            return MonobankWebhookSigner(keyPair)
        }

        /** Generates a P-256 (`prime256v1`) EC key pair, the kind monobank signs webhooks with. */
        @JvmStatic
        public fun generateKeyPair(): KeyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec(CURVE)) }
                .generateKeyPair()

        /** Signs with an arbitrary key; used to produce deliberately invalid signatures. */
        internal fun signWith(
            keyPair: KeyPair,
            body: ByteArray,
        ): String {
            val der =
                Signature.getInstance(ALGORITHM).run {
                    initSign(keyPair.private)
                    update(body)
                    sign()
                }
            return Base64.getEncoder().encodeToString(der)
        }

        private fun pem(keyPair: KeyPair): String {
            val lines = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray(StandardCharsets.US_ASCII))
            val body = lines.encodeToString(keyPair.public.encoded)
            return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
        }

        private fun encodePublicKey(keyPair: KeyPair): String =
            Base64.getEncoder().encodeToString(pem(keyPair).toByteArray(StandardCharsets.US_ASCII))

        private fun fingerprint(keyPair: KeyPair): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(keyPair.public.encoded)
                .take(FINGERPRINT_BYTES)
                .joinToString("") { "%02x".format(it) }

        private fun requireP256(keyPair: KeyPair) {
            val public = keyPair.public
            val private = keyPair.private
            require(public is ECPublicKey && private is ECPrivateKey) { "the key pair must be an EC key pair" }
            val p256 =
                AlgorithmParameters
                    .getInstance("EC")
                    .apply { init(ECGenParameterSpec(CURVE)) }
                    .getParameterSpec(ECParameterSpec::class.java)
            val params = public.params
            require(
                params.curve == p256.curve && params.order == p256.order && params.generator == p256.generator,
            ) { "the key pair must be on the P-256 (prime256v1) curve" }
        }
    }
}
