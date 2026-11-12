package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceApi
import io.github.damienmiheev.monobank.acquiring.merchant.MerchantApi
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookKeyProvider
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier

/**
 * Client for the monobank merchant acquiring API (https://monobank.ua/api-docs/acquiring).
 *
 * Thread-safe; create one per merchant token and reuse it.
 *
 * ```kotlin
 * val mono = MonobankAcquiring(AcquiringConfig.builder(token).build())
 * val invoice = mono.invoices.create(CreateInvoiceRequest.builder(4200).build())
 * ```
 */
public class MonobankAcquiring internal constructor(
    public val config: AcquiringConfig,
    http: AcquiringHttp,
) {
    /** Creates a client from [config]. */
    public constructor(config: AcquiringConfig) : this(config, AcquiringHttp(config))

    /** Invoices: create, status, refunds, holds, receipts. */
    public val invoices: InvoiceApi = InvoiceApi(http)

    /** Merchant details, public key, statement, submerchants. */
    public val merchant: MerchantApi = MerchantApi(http)

    /**
     * Verifies webhooks with this merchant's public key, fetched from `GET /api/merchant/pubkey`
     * on first use and re-fetched when it rotates.
     */
    public val webhooks: WebhookVerifier = WebhookVerifier(WebhookKeyProvider { merchant.publicKey().key })

    override fun toString(): String = "MonobankAcquiring($config)"

    public companion object {
        /** Creates a client with default settings for [token]. */
        @JvmStatic
        public fun create(token: String): MonobankAcquiring = MonobankAcquiring(AcquiringConfig.builder(token).build())
    }
}
