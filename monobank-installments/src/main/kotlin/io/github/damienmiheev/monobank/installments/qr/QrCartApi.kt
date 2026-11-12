package io.github.damienmiheev.monobank.installments.qr

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.common.Product
import io.github.damienmiheev.monobank.installments.internal.InstallmentsErrorDecoder
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank

/**
 * QR flow: put a cart on the store's QR code; the client scans it in the monobank app and approves.
 *
 * Differences from `orders.create`: no `orders.confirm` is needed (the order is confirmed automatically), the
 * callback arrives on `SUCCESS` / `ACTIVE` (hand over the goods then) and on `FAIL` / `CONFIRM_TIME_EXPIRED` when
 * nobody scanned the code within 15 minutes, and `orders.state` answers "not found" until the code is scanned.
 */
public class QrCartApi internal constructor(
    private val http: InstallmentsHttp,
) {
    /**
     * Puts a cart on a QR code. The returned [QrCartResult.Created.cartId] becomes the order id once the client
     * scans the code: poll `orders.state` with it and match callbacks by it.
     *
     * `POST /api/v1/qr/cart`, answered with HTTP 201. A 409 answer is returned as [QrCartResult.Conflict] instead of
     * thrown: the QR code already has an active cart, or the `store_order_id` was used before (even for a cancelled
     * cart). Not retried after an unknown outcome: a repeat with the same `store_order_id` would only get a 409.
     */
    public fun create(request: CreateQrCartRequest): QrCartResult {
        val response = http.execute("/api/v1/qr/cart", request, idempotent = false, acceptedStatuses = setOf(CONFLICT))
        if (response.statusCode == CONFLICT) {
            val message = runCatching { InstallmentsErrorDecoder.decode(response.body).text }.getOrNull()
            return QrCartResult.Conflict(message)
        }
        return QrCartResult.Created(MonobankJson.read(response.body, QrCartIdentifier::class.java).id)
    }

    /**
     * Clears the cart and frees the QR code. If the client already scanned it, the purchase continues: when a
     * `SUCCESS` / `ACTIVE` callback arrives for goods you did not hand over, return them with `orders.returnOrder`.
     *
     * `POST /api/v1/qr/cart/cancel`. Not retried after an unknown outcome (a late repeat could clear a cart put on
     * the code in the meantime); calling it again yourself is safe when no new cart was created.
     *
     * @param qrId The store's QR code id.
     */
    public fun cancel(qrId: String) {
        http.execute("/api/v1/qr/cart/cancel", QrIdBody(requireNotBlank(qrId, "qrId")), idempotent = false)
    }

    private class QrIdBody(
        @get:JsonProperty("qr_id") val qrId: String,
    )

    private companion object {
        const val CONFLICT = 409
    }
}

/**
 * Request of `POST /api/v1/qr/cart`.
 *
 * @property qrId The store's QR code id.
 * @property storeOrderId Your order id (a UUID per the spec). It must be unique for every cart: reusing it returns
 *   409, even after the cart was cancelled.
 * @property products Products in the cart, at least one.
 * @property resultCallback URL monobank POSTs state changes to.
 */
@ConsistentCopyVisibility
public data class CreateQrCartRequest internal constructor(
    @get:JsonProperty("qr_id") public val qrId: String,
    @get:JsonProperty("store_order_id") public val storeOrderId: String,
    @get:JsonProperty("products") public val products: List<Product>,
    @get:JsonProperty("result_callback") public val resultCallback: String,
) {
    public companion object {
        /**
         * Creates a request; every field is required.
         *
         * @param qrId The store's QR code id.
         * @param storeOrderId Your order id, new for every cart (e.g. `UUID.randomUUID().toString()`).
         * @param products Products, at least one.
         * @param resultCallback Callback URL.
         */
        @JvmStatic
        public fun of(
            qrId: String,
            storeOrderId: String,
            products: List<Product>,
            resultCallback: String,
        ): CreateQrCartRequest {
            requireNotBlank(qrId, "qrId")
            requireNotBlank(storeOrderId, "storeOrderId")
            require(products.isNotEmpty()) { "products must not be empty" }
            requireNotBlank(resultCallback, "resultCallback")
            return CreateQrCartRequest(qrId, storeOrderId, products.toList(), resultCallback)
        }
    }
}

/** Answer of `POST /api/v1/qr/cart`. */
public sealed class QrCartResult {
    /** The cart was put on the QR code (HTTP 201). */
    @ConsistentCopyVisibility
    public data class Created internal constructor(
        /** Cart id (UUID); it becomes the order id once the code is scanned. */
        public val cartId: String,
    ) : QrCartResult()

    /**
     * HTTP 409: the QR code already has an active cart, or the `store_order_id` was used before.
     * Nothing was created.
     */
    @ConsistentCopyVisibility
    public data class Conflict internal constructor(
        /** monobank's message, if any. */
        public val message: String?,
    ) : QrCartResult()
}

/** Wire answer of `POST /api/v1/qr/cart`. */
internal class QrCartIdentifier
    @JsonCreator
    constructor(
        @param:JsonProperty("id") val id: String,
    )
