package io.github.damienmiheev.monobank.acquiring.wallet

import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentResponse

/**
 * Saved cards (tokenization). Cards are saved by creating an invoice with `saveCardData`
 * (or `CreateInvoiceRequest.verification`); card saving must be enabled by monobank support.
 */
public class WalletApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Saved cards in the wallet [walletId].
     *
     * `GET /api/merchant/wallet`. Safe to retry.
     */
    public fun cards(walletId: String): List<WalletCard> {
        require(walletId.isNotBlank()) { "walletId must not be blank" }
        return http
            .get("/api/merchant/wallet", WalletWire::class.java, query = listOf("walletId" to walletId))
            .wallet
            .orEmpty()
    }

    /**
     * Charges a saved card (or an Apple Pay / Google Pay token).
     *
     * `POST /api/merchant/wallet/payment`. A payment: never retried after an unknown outcome. If
     * this throws with `RequestOutcome.UNKNOWN`, look the payment up with
     * `MerchantApi.findByReference` before charging again. When [PaymentResponse.isThreeDsRequired],
     * send the customer to [PaymentResponse.tdsUrl]; the final status comes in a webhook.
     */
    public fun payment(request: WalletPaymentRequest): PaymentResponse =
        http.post("/api/merchant/wallet/payment", request, PaymentResponse::class.java, idempotent = false)

    /**
     * Deletes a saved card.
     *
     * `DELETE /api/merchant/wallet/card?cardToken=`. Not retried after an unknown outcome (a
     * repeated call may fail although the first one worked). The SDK never logs the query string,
     * so the token stays out of logs.
     */
    public fun deleteCard(cardToken: String) {
        require(cardToken.isNotBlank()) { "cardToken must not be blank" }
        http.delete("/api/merchant/wallet/card", listOf("cardToken" to cardToken), idempotent = false)
    }
}
