package io.github.damienmiheev.monobank.installments.qr

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import io.github.damienmiheev.monobank.installments.common.Product
import io.github.damienmiheev.monobank.installments.order.OrderStateResult
import okhttp3.mockwebserver.MockResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QrCartApiTest : InstallmentsTestSupport() {
    private val request =
        CreateQrCartRequest.of(
            "qr-abc-123",
            ORDER_ID,
            listOf(Product.of("Телевізор Samsung 55\"", 2, BigDecimal("9999.99"))),
            "https://shop.example.com/callback",
        )

    @Test
    fun `create sends the documented body and reads the 201 answer`() {
        enqueueFixture("POST_api_v1_qr_cart.response.json", status = 201)

        val result = chast.qrCarts.create(request)

        assertThat(result).isEqualTo(QrCartResult.Created("0b5c2c6e-3d6f-4a59-9a8e-2f3c1d9b7a10"))
        assertThat(
            takeJson("/api/v1/qr/cart"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_v1_qr_cart.request.json")))
    }

    @Test
    fun `409 is a normal conflict answer, not an exception`() {
        enqueueJson("""{"message":"QR-код вже має активний кошик"}""", status = 409)

        val result = chast.qrCarts.create(request)

        assertThat(result).isEqualTo(QrCartResult.Conflict("QR-код вже має активний кошик"))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `create is not retried after an unknown outcome`() {
        enqueueJson("""{"message":"oops"}""", status = 500)

        assertThatThrownBy { chast.qrCarts.create(request) }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `qr id that does not belong to the store is a bad request`() {
        enqueueJson("""{"message":"qr_id не належить магазину"}""", status = 400)

        assertThatThrownBy { chast.qrCarts.create(request) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("qr_id не належить магазину")
            }
    }

    @Test
    fun `order state answers not-found until the cart is scanned`() {
        enqueueFixture("POST_api_v1_qr_cart.response.json", status = 201)
        enqueueJson("""{"message":"Замовлення не знайдене"}""", status = 404)
        enqueueJson(
            """{"order_id":"0b5c2c6e-3d6f-4a59-9a8e-2f3c1d9b7a10","state":"SUCCESS","order_sub_state":"ACTIVE"}""",
        )

        val cartId = (chast.qrCarts.create(request) as QrCartResult.Created).cartId
        assertThat(chast.orders.state(cartId)).isInstanceOf(OrderStateResult.NotFound::class.java)
        assertThat(
            chast.orders
                .state(cartId)
                .stateOrNull!!
                .orderId,
        ).isEqualTo(cartId)
    }

    @Test
    fun `cancel sends the qr id and accepts an empty 200`() {
        server.enqueue(MockResponse().setResponseCode(200))

        chast.qrCarts.cancel("qr-abc-123")

        assertThat(takeJson("/api/v1/qr/cart/cancel"))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_v1_qr_cart_cancel.request.json")))
    }

    @Test
    fun `cancel is not retried after an unknown outcome`() {
        enqueueJson("{}", status = 503)

        assertThatThrownBy { chast.qrCarts.cancel("qr-abc-123") }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `request validation`() {
        val products = listOf(Product.of("A", 1, BigDecimal.ONE))
        assertThatThrownBy { CreateQrCartRequest.of("", "s", products, "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateQrCartRequest.of("q", " ", products, "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateQrCartRequest.of("q", "s", emptyList(), "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateQrCartRequest.of("q", "s", products, "") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { chast.qrCarts.cancel(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
