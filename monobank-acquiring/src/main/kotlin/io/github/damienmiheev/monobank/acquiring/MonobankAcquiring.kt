package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.employee.EmployeeApi
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceApi
import io.github.damienmiheev.monobank.acquiring.merchant.MerchantApi
import io.github.damienmiheev.monobank.acquiring.monopay.MonopayApi
import io.github.damienmiheev.monobank.acquiring.pos.PosRefundApi
import io.github.damienmiheev.monobank.acquiring.qr.QrApi
import io.github.damienmiheev.monobank.acquiring.split.SplitApi
import io.github.damienmiheev.monobank.acquiring.subscription.SubscriptionApi
import io.github.damienmiheev.monobank.acquiring.terminal.TerminalApi
import io.github.damienmiheev.monobank.acquiring.wallet.WalletApi
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
    keyHttp: AcquiringHttp = AcquiringHttp.forKeyFetch(config),
) {
    /** Creates a client from [config]. */
    public constructor(config: AcquiringConfig) : this(config, AcquiringHttp(config))

    /** Invoices: create, status, refunds, holds, direct and synchronous payments, receipts, fiscal checks. */
    public val invoices: InvoiceApi = InvoiceApi(http)

    /** Merchant details, public key, statement (and lookup by reference), submerchants. */
    public val merchant: MerchantApi = MerchantApi(http)

    /** QR cash registers. */
    public val qr: QrApi = QrApi(http)

    /** Saved cards: list, charge by token, delete. */
    public val wallet: WalletApi = WalletApi(http)

    /** Subscriptions (recurring payments managed by monobank). */
    public val subscriptions: SubscriptionApi = SubscriptionApi(http)

    /** Split payment receivers. */
    public val split: SplitApi = SplitApi(http)

    /** monopay button keys. Sign widget requests with `MonopaySigner`. */
    public val monopay: MonopayApi = MonopayApi(http)

    /** Refunds of card-present (POS terminal) transactions. */
    public val posRefunds: PosRefundApi = PosRefundApi(http)

    /** Tap-to-Phone terminals and payment status. */
    public val terminals: TerminalApi = TerminalApi(http)

    /** Legacy tips employees; only in monobank's legacy (v2410) documentation. */
    @Deprecated("Only in monobank's legacy (v2410) documentation; GET /api/merchant/employee/list may stop working.")
    @Suppress("DEPRECATION")
    public val employees: EmployeeApi = EmployeeApi(http)

    /**
     * Verifies webhooks with this merchant's public key, fetched from `GET /api/merchant/pubkey`
     * on first use and re-fetched when it rotates. The key is fetched with a short timeout (at
     * most 5 s) and without retries, so a slow key endpoint cannot hold webhook threads for long;
     * see [WebhookVerifier] for the single-fetch and back-off rules.
     */
    public val webhooks: WebhookVerifier =
        MerchantApi(keyHttp).let { keyApi -> WebhookVerifier(WebhookKeyProvider { keyApi.publicKey().key }) }

    override fun toString(): String = "MonobankAcquiring($config)"

    public companion object {
        /** Creates a client with default settings for [token]. */
        @JvmStatic
        public fun create(token: String): MonobankAcquiring = MonobankAcquiring(AcquiringConfig.builder(token).build())
    }
}
