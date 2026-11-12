package io.github.damienmiheev.monobank.installments.qr

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import io.github.damienmiheev.monobank.installments.RefusingHttpClient
import io.github.damienmiheev.monobank.installments.common.Product
import io.github.damienmiheev.monobank.installments.order.OrderStateResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

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
        assertThat(result.cartIdOrNull).isEqualTo("0b5c2c6e-3d6f-4a59-9a8e-2f3c1d9b7a10")
        assertThat(
            takeJson("/api/v1/qr/cart"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_v1_qr_cart.request.json")))
    }

    @Test
    fun `409 is a normal conflict answer, not an exception`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(
                    409,
                ).setHeader("trace-id", "t-409")
                .setBody("""{"message":"QR-код вже має активний кошик"}"""),
        )

        val result = chast.qrCarts.create(request)

        assertThat(result).isEqualTo(QrCartResult.Conflict("QR-код вже має активний кошик", "t-409"))
        assertThat(result.cartIdOrNull).isNull()
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
        assertThatThrownBy { CreateQrCartRequest.of("", ORDER_ID, products, "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateQrCartRequest.of("q", " ", products, "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateQrCartRequest.of("q", "ORD-1", products, "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UUID")
        assertThatThrownBy { CreateQrCartRequest.of("q", ORDER_ID, emptyList(), "https://x") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateQrCartRequest.of("q", ORDER_ID, products, "") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { chast.qrCarts.cancel(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `create is retried after 429 because nothing was done`() {
        enqueueJson("""{"message":"slow down"}""", status = 429)
        enqueueFixture("POST_api_v1_qr_cart.response.json", status = 201)

        assertThat(chast.qrCarts.create(request)).isInstanceOf(QrCartResult.Created::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `create is retried when the request was never sent`() {
        val refusing = RefusingHttpClient()

        assertThatThrownBy { clientWith { it.httpClient(refusing) }.qrCarts.create(request) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.NOT_SENT)
            }
        // The first attempt plus two retries.
        assertThat(refusing.uris).hasSize(3)
    }

    @Test
    fun `create is not retried after a read timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThatThrownBy { clientWith { it.requestTimeout(Duration.ofMillis(300)) }.qrCarts.create(request) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }
}
