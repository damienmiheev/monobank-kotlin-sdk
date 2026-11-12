package io.github.damienmiheev.monobank.testkit

/**
 * Paths served by [FakeMonobankAcquiring], for [FakeMonobankAcquiring.injectFault] and
 * [FakeMonobankAcquiring.requestCount].
 */
public object MonobankPaths {
    /** `GET /api/merchant/pubkey`. */
    public const val PUBKEY: String = "/api/merchant/pubkey"

    /** `GET /api/merchant/details`. */
    public const val DETAILS: String = "/api/merchant/details"

    /** `POST /api/merchant/invoice/create`. */
    public const val INVOICE_CREATE: String = "/api/merchant/invoice/create"

    /** `GET /api/merchant/invoice/status`. */
    public const val INVOICE_STATUS: String = "/api/merchant/invoice/status"

    /** `POST /api/merchant/invoice/remove`. */
    public const val INVOICE_REMOVE: String = "/api/merchant/invoice/remove"

    /** `POST /api/merchant/invoice/finalize`. */
    public const val INVOICE_FINALIZE: String = "/api/merchant/invoice/finalize"

    /** `POST /api/merchant/invoice/cancel`. */
    public const val INVOICE_CANCEL: String = "/api/merchant/invoice/cancel"

    /** `POST /api/merchant/wallet/payment`. */
    public const val WALLET_PAYMENT: String = "/api/merchant/wallet/payment"

    /** `GET /api/merchant/wallet`. */
    public const val WALLET: String = "/api/merchant/wallet"

    /** `DELETE /api/merchant/wallet/card`. */
    public const val WALLET_CARD: String = "/api/merchant/wallet/card"

    /** `GET /api/merchant/statement`. */
    public const val STATEMENT: String = "/api/merchant/statement"

    /** Matches every path in [FakeMonobankAcquiring.injectFault]; path-specific faults take precedence. */
    public const val ANY: String = "*"

    /** Every route the fake serves. */
    @JvmField
    public val ROUTES: Set<String> =
        setOf(
            PUBKEY,
            DETAILS,
            INVOICE_CREATE,
            INVOICE_STATUS,
            INVOICE_REMOVE,
            INVOICE_FINALIZE,
            INVOICE_CANCEL,
            WALLET_PAYMENT,
            WALLET,
            WALLET_CARD,
            STATEMENT,
        )
}
