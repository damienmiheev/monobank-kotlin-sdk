package io.github.damienmiheev.monobank.installments.qr

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.common.Product
import io.github.damienmiheev.monobank.installments.internal.InstallmentsErrorDecoder
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank
import io.github.damienmiheev.monobank.installments.internal.traceId
import java.util.UUID

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
     *
     * **Unknown outcome** (timeout or 5xx thrown with `RequestOutcome.UNKNOWN`): the cart may exist, but its id is
     * lost and there is no endpoint to look it up. Call [cancel] for the QR code, then create a new cart with a
     * **new** `store_order_id`. If the client already scanned the lost cart, a `SUCCESS` / `ACTIVE` callback arrives
     * for an order id you have never seen: callback handlers must accept unknown ids and match them to your purchase
     * with `orders.data(orderId).storeOrderId` (return the goods' money with `orders.returnOrder` if you did not hand
     * them over).
     */
    public fun create(request: CreateQrCartRequest): QrCartResult {
        val response = http.execute("/api/v1/qr/cart", request, idempotent = false, acceptedStatuses = setOf(CONFLICT))
        if (response.statusCode == CONFLICT) {
            return QrCartResult.Conflict(InstallmentsErrorDecoder.messageOrNull(response), response.traceId())
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
 * @property storeOrderId Your order id, a UUID (the spec's format). It must be unique for every cart: reusing it
 *   returns 409, even after the cart was cancelled.
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
        private val UUID_FORMAT =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * Creates a request; every field is required.
         *
         * @param qrId The store's QR code id.
         * @param storeOrderId Your order id, a UUID new for every cart (e.g. `UUID.randomUUID().toString()`).
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
            require(UUID_FORMAT.matches(storeOrderId)) { "storeOrderId must be a UUID" }
            require(products.isNotEmpty()) { "products must not be empty" }
            requireNotBlank(resultCallback, "resultCallback")
            return CreateQrCartRequest(qrId, storeOrderId, products.toList(), resultCallback)
        }
    }
}

/** Answer of `POST /api/v1/qr/cart`. */
public sealed class QrCartResult {
    /** The cart id, or `null` for [Conflict]. */
    public abstract val cartIdOrNull: String?

    /** The cart was put on the QR code (HTTP 201). */
    @ConsistentCopyVisibility
    public data class Created internal constructor(
        /** Cart id (UUID); it becomes the order id once the code is scanned. */
        public val cartId: String,
    ) : QrCartResult() {
        override val cartIdOrNull: String get() = cartId
    }

    /**
     * HTTP 409, nothing was created. Either the QR code already has an active cart (wait, or [QrCartApi.cancel] your
     * own cart) or the `store_order_id` was used before (use a new one). Only [message] tells the two apart.
     */
    @ConsistentCopyVisibility
    public data class Conflict internal constructor(
        /** monobank's message, if any. */
        public val message: String?,
        /** monobank's trace id (`trace-id` header), to quote to monobank support. */
        public val traceId: String?,
    ) : QrCartResult() {
        override val cartIdOrNull: String? get() = null
    }
}

/** Wire answer of `POST /api/v1/qr/cart`. */
internal class QrCartIdentifier
    @JsonCreator
    constructor(
        @param:JsonProperty("id") val id: String,
    )
