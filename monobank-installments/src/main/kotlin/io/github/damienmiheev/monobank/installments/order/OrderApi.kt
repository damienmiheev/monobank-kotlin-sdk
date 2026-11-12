package io.github.damienmiheev.monobank.installments.order

import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.internal.InstallmentsErrorDecoder
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import io.github.damienmiheev.monobank.installments.internal.OrderIdBody
import io.github.damienmiheev.monobank.installments.internal.requireOrderId
import io.github.damienmiheev.monobank.installments.internal.traceId

/**
 * Installment orders: create, poll, confirm, reject, return, details.
 *
 * Every call is a signed `POST`. "Retried" below means the SDK repeats the call after a timeout, a connection
 * reset or a 5xx (per `RetryPolicy`); calls that never reached monobank and 429 answers are retried for every
 * operation.
 */
public class OrderApi internal constructor(
    private val http: InstallmentsHttp,
) {
    /**
     * Creates an installment order; the client then approves it in the monobank app.
     *
     * `POST /api/order/create`, answered with HTTP 201. Retried: monobank documents this call as idempotent on
     * `store_order_id` (a repeat returns the existing order's id instead of creating another), so always send a
     * stable [CreateOrderRequest.storeOrderId] per purchase. HTTP 403 (`MonobankAuthException`) means the store may
     * not create orders.
     *
     * After an order ends in `FAIL`, a new attempt for the same purchase needs a **new** `store_order_id`: reusing the
     * old one just returns the failed order's id.
     */
    public fun create(request: CreateOrderRequest): CreateOrderResponse =
        http.post("/api/order/create", request, CreateOrderResponse::class.java, idempotent = true)

    /**
     * Current state of an order. Use it to poll when you do not use callbacks, or to recover a missed callback.
     *
     * `POST /api/order/state`. Retried (read-only). A 404 answer is returned as [OrderStateResult.NotFound]
     * instead of thrown: for a QR cart the order does not exist until the QR code is scanned.
     */
    public fun state(orderId: String): OrderStateResult {
        val response =
            http.execute(
                "/api/order/state",
                OrderIdBody(requireOrderId(orderId)),
                idempotent = true,
                acceptedStatuses = setOf(NOT_FOUND),
            )
        if (response.statusCode == NOT_FOUND) {
            return OrderStateResult.NotFound(InstallmentsErrorDecoder.messageOrNull(response), response.traceId())
        }
        return OrderStateResult.Found(MonobankJson.read(response.body, OrderStateInfo::class.java))
    }

    /**
     * Confirms that the goods were handed over; the installment becomes `SUCCESS` / `ACTIVE`. Call it only in
     * `IN_PROCESS` / `WAITING_FOR_STORE_CONFIRM`. Not needed for QR carts (confirmed automatically).
     *
     * `POST /api/order/confirm`. Not retried after an unknown outcome: check [state] before calling it again.
     */
    public fun confirm(orderId: String): OrderStateInfo =
        http.post(
            "/api/order/confirm",
            OrderIdBody(requireOrderId(orderId)),
            OrderStateInfo::class.java,
            idempotent = false,
        )

    /**
     * Rejects the order on the store's side (goods will not be handed over); it becomes `FAIL` /
     * `REJECTED_BY_STORE` and the client's limit is restored.
     *
     * `POST /api/order/reject`. Not retried after an unknown outcome: check [state] before calling it again.
     */
    public fun reject(orderId: String): OrderStateInfo =
        http.post(
            "/api/order/reject",
            OrderIdBody(requireOrderId(orderId)),
            OrderStateInfo::class.java,
            idempotent = false,
        )

    /**
     * Returns goods, fully or partly, on an order in state `SUCCESS`. A full return moves it to `SUCCESS` /
     * `RETURNED`.
     *
     * `POST /api/order/return`. Not retried after an unknown outcome (monobank does not document returns as
     * idempotent): check [data]'s `reverseList` before returning again.
     *
     * Returning normally means monobank accepted the return (every error is thrown); do not repeat it based on
     * [ReturnOrderResponse.status], which is informational and may be missing.
     */
    public fun returnOrder(request: ReturnOrderRequest): ReturnOrderResponse {
        val response = http.execute("/api/order/return", request, idempotent = false)
        if (response.body.isEmpty()) return ReturnOrderResponse(null)
        return MonobankJson.read(response.body, ReturnOrderResponse::class.java)
    }

    /**
     * Order details: sum, invoice, card, IBAN and the history of returns.
     *
     * `POST /api/order/data`. Retried (read-only).
     */
    public fun data(orderId: String): OrderData =
        http.post("/api/order/data", OrderIdBody(requireOrderId(orderId)), OrderData::class.java, idempotent = true)

    /**
     * Order details (legacy).
     *
     * `POST /api/order/info`. Retried (read-only). Deprecated by monobank in favour of `/api/order/data`.
     */
    @Deprecated("Deprecated by monobank. Use data(orderId) (POST /api/order/data).", ReplaceWith("data(orderId)"))
    public fun info(orderId: String): OrderData =
        http.post("/api/order/info", OrderIdBody(requireOrderId(orderId)), OrderData::class.java, idempotent = true)

    /**
     * Whether the client repaid the installment in full. Works only for orders in state `SUCCESS`.
     *
     * `POST /api/order/check/paid`. Retried (read-only).
     */
    public fun checkPaid(orderId: String): CheckPaidResponse =
        http.post(
            "/api/order/check/paid",
            OrderIdBody(requireOrderId(orderId)),
            CheckPaidResponse::class.java,
            idempotent = true,
        )

    private companion object {
        const val NOT_FOUND = 404
    }
}
