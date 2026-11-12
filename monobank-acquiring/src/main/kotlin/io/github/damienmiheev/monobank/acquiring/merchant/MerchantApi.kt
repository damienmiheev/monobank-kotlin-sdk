package io.github.damienmiheev.monobank.acquiring.merchant

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp

/**
 * Merchant data: details, public key, statement, submerchants.
 */
public class MerchantApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * The public key that signs this merchant's webhooks.
     *
     * `GET /api/merchant/pubkey`. Cache it; `WebhookVerifier` does this for you.
     */
    public fun publicKey(): PublicKeyResponse = http.get("/api/merchant/pubkey", PublicKeyResponse::class.java)
}
