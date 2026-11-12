package io.github.damienmiheev.monobank.installments

import io.github.damienmiheev.monobank.installments.callback.InstallmentsCallbackVerifier
import io.github.damienmiheev.monobank.installments.client.ClientApi
import io.github.damienmiheev.monobank.installments.guarantee.GuaranteeLetterApi
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import io.github.damienmiheev.monobank.installments.order.OrderApi
import io.github.damienmiheev.monobank.installments.qr.QrCartApi
import io.github.damienmiheev.monobank.installments.report.StoreApi

/**
 * Client for monobank "Покупка частинами" (pay in parts), the installments partner API
 * (https://monobank.ua/api-docs/chast).
 *
 * Every request is a `POST` signed with the store secret (`store-id` and `signature` headers). Amounts are
 * **hryvnias as decimals** (`BigDecimal("2499.99")`), not kopiyky as in the acquiring API.
 *
 * Thread-safe; create one per store and reuse it.
 *
 * Personal data in `toString()`: identifiers (client phone, IBAN, tax number, document numbers) are masked; names
 * are shown. The `internal` constructors of model classes are visible from Java but are not part of the API.
 *
 * ```kotlin
 * val chast = MonobankInstallments(InstallmentsConfig.builder(storeId, storeSecret).build())
 * val order = chast.orders.create(request)
 * ```
 */
public class MonobankInstallments internal constructor(
    public val config: InstallmentsConfig,
    http: InstallmentsHttp,
) {
    /** Creates a client from [config]. */
    public constructor(config: InstallmentsConfig) : this(config, InstallmentsHttp(config))

    /** Orders: create, state, confirm, reject, return, details. */
    public val orders: OrderApi = OrderApi(http)

    /** Client checks. */
    public val clients: ClientApi = ClientApi(http)

    /** Guarantee letters (PDF and data). */
    public val guaranteeLetters: GuaranteeLetterApi = GuaranteeLetterApi(http)

    /** Store reports. */
    public val store: StoreApi = StoreApi(http)

    /** QR carts. */
    public val qrCarts: QrCartApi = QrCartApi(http)

    /** Verifies callbacks with this store's secret. */
    public val callbacks: InstallmentsCallbackVerifier = InstallmentsCallbackVerifier(config.signer)

    override fun toString(): String = "MonobankInstallments($config)"

    public companion object {
        /** Creates a production client with default settings. */
        @JvmStatic
        public fun create(
            storeId: String,
            storeSecret: String,
        ): MonobankInstallments = MonobankInstallments(InstallmentsConfig.builder(storeId, storeSecret).build())
    }
}
