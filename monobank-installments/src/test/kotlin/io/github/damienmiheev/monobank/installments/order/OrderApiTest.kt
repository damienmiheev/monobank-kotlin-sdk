package io.github.damienmiheev.monobank.installments.order

import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import io.github.damienmiheev.monobank.installments.MonobankInstallments
import io.github.damienmiheev.monobank.installments.common.InvoiceSource
import io.github.damienmiheev.monobank.installments.common.OrderSource
import io.github.damienmiheev.monobank.installments.common.OrderState
import io.github.damienmiheev.monobank.installments.common.OrderSubState
import io.github.damienmiheev.monobank.installments.common.Product
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

class OrderApiTest : InstallmentsTestSupport() {
    private fun fullCreateRequest(): CreateOrderRequest =
        CreateOrderRequest
            .builder(
                storeOrderId = "ORD-2024-001234",
                clientPhone = "+380501234567",
                totalSum = BigDecimal("2499.99"),
                invoice =
                    CreateOrderInvoice.of(
                        "INV-2024-001234",
                        LocalDate.of(2024, 1, 15),
                        InvoiceSource.INTERNET,
                        "STORE-001",
                    ),
                availablePrograms = listOf(AvailableProgram.of(listOf(3, 6, 10), "payment_installments")),
                products = listOf(Product.of("Телевізор Samsung 55\"", 2, BigDecimal("9999.99"))),
            ).resultCallback("https://shop.example.com/api/order/callback")
            .additionalParams(
                CreateOrderAdditionalParams
                    .builder()
                    .sellerPhone("+380501234568")
                    .nds(BigDecimal("416.67"))
                    .extInitialSum(BigDecimal("500"))
                    .build(),
            ).financialCompanyMerchantInfo(
                FinancialCompanyMerchantInfo.of("Електроніка Плюс", "12345678", "UA123456789012345678901234567"),
            ).build()

    @Test
    fun `create sends exactly the documented snake_case body and reads the 201 answer`() {
        enqueueFixture("POST_api_order_create.response.json", status = 201)

        val response = chast.orders.create(fullCreateRequest())

        assertThat(response.orderId).isEqualTo(ORDER_ID)
        val body = takeJson("/api/order/create")
        assertThat(body).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_create.request.json")))
    }

    @Test
    fun `create leaves optional fields out`() {
        enqueueFixture("POST_api_order_create.response.json", status = 201)

        chast.orders.create(
            CreateOrderRequest
                .builder(
                    "o-1",
                    "+380501234561",
                    BigDecimal("100"),
                    CreateOrderInvoice.of("1", LocalDate.of(2024, 2, 29), InvoiceSource.STORE),
                    listOf(AvailableProgram.of(listOf(3))),
                    listOf(Product.of("A", 1, BigDecimal("100"))),
                ).build(),
        )

        val body = takeJson("/api/order/create")
        assertThat(body.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder(
                "store_order_id",
                "client_phone",
                "total_sum",
                "invoice",
                "available_programs",
                "products",
            )
        assertThat(body.at("/invoice/source").asText()).isEqualTo("STORE")
        assertThat(body.at("/invoice/date").asText()).isEqualTo("2024-02-29")
        assertThat(body.at("/invoice").has("point_id")).isFalse()
        assertThat(body.at("/available_programs/0").has("type")).isFalse()
    }

    @Test
    fun `amounts are written as plain decimals without float artifacts`() {
        enqueueFixture("POST_api_order_create.response.json", status = 201)

        chast.orders.create(
            CreateOrderRequest
                .builder(
                    "o-2",
                    "+380501234561",
                    BigDecimal("1500.00"),
                    CreateOrderInvoice.of("1", LocalDate.of(2024, 1, 1), InvoiceSource.INTERNET),
                    listOf(AvailableProgram.of(listOf(3, 25))),
                    listOf(
                        Product.of("A", 1, BigDecimal("1E+3")),
                        Product.of("B", 3, BigDecimal("0.10")),
                        Product.of("C", 1, BigDecimal("499.7")),
                    ),
                ).additionalParams(CreateOrderAdditionalParams.builder().nds(BigDecimal("0.00")).build())
                .build(),
        )

        val raw = takeRequest("/api/order/create").body.readUtf8()
        for (expected in listOf("total_sum\":1500.00", "sum\":1000", "sum\":0.10", "sum\":499.7", "nds\":0.00")) {
            assertThat(raw).containsPattern(Regex.escape("\"$expected") + "[,}]")
        }
        assertThat(raw).doesNotContain("E+").doesNotContain("E-")
    }

    @Test
    fun `create validates amounts and required fields before sending`() {
        val invoice = CreateOrderInvoice.of("1", LocalDate.of(2024, 1, 1), InvoiceSource.INTERNET)
        val programs = listOf(AvailableProgram.of(listOf(3)))
        val products = listOf(Product.of("A", 1, BigDecimal("10")))

        fun build(
            id: String = "o",
            sum: BigDecimal = BigDecimal("10"),
            programs: List<AvailableProgram> = listOf(AvailableProgram.of(listOf(3))),
            items: List<Product> = products,
        ) = CreateOrderRequest.builder(id, "+380501234561", sum, invoice, programs, items).build()

        assertThatThrownBy { build(sum = BigDecimal("10.001")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("hryvnias")
        assertThatThrownBy { build(sum = BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { build(sum = BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { build(id = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { build(id = "x".repeat(65)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { build(programs = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { build(programs = programs + programs) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AvailableProgram.of(listOf(2, 6)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AvailableProgram.of(listOf(3, 26)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(AvailableProgram.of(listOf(3, 25)).availablePartsCount).containsExactly(3, 25)
        assertThatThrownBy { build(items = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Product.of("A", 0, BigDecimal.ONE) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Product.of("x".repeat(501), 1, BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AvailableProgram.of(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FinancialCompanyMerchantInfo.of(null, "12AB", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateOrderInvoice.of("1", LocalDate.now(), InvoiceSource.STORE, "p".repeat(51)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        // Trailing zeros beyond kopiyky are fine: the value is still whole kopiyky.
        assertThat(build(sum = BigDecimal("10.5000")).totalSum).isEqualByComparingTo("10.50")
        assertThat(build(programs = programs).availablePrograms).hasSize(1)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `create is retried because monobank deduplicates it by store_order_id`() {
        enqueueJson("""{"message":"temporary"}""", status = 503)
        enqueueFixture("POST_api_order_create.response.json", status = 201)

        assertThat(chast.orders.create(fullCreateRequest()).orderId).isEqualTo(ORDER_ID)

        assertThat(server.requestCount).isEqualTo(2)
        val first = takeJson("/api/order/create")
        val second = takeJson("/api/order/create")
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `create forbidden is an auth error with monobank's message`() {
        enqueueJson("""{"message":"Створення заявок заборонено"}""", status = 403)

        assertThatThrownBy { chast.orders.create(fullCreateRequest()) }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.statusCode).isEqualTo(403)
                assertThat(it.errorText).isEqualTo("Створення заявок заборонено")
                assertThat(it.errorCode).isNull()
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `state reads every field`() {
        enqueueFixture("POST_api_order_state.response.json")

        val result = chast.orders.state(ORDER_ID)

        assertThat(
            takeJson("/api/order/state"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_state.request.json")))
        assertThat(result).isInstanceOf(OrderStateResult.Found::class.java)
        val state = result.stateOrNull!!
        assertThat(state.orderId).isEqualTo(ORDER_ID)
        assertThat(state.state).isEqualTo(OrderState.IN_PROCESS)
        assertThat(state.subState).isEqualTo(OrderSubState.WAITING_FOR_STORE_CONFIRM)
        assertThat(state.message).isEqualTo("Очікується підтвердження магазину")
        assertThat(state.isWaitingForStoreConfirm).isTrue()
        assertThat(state.state.isFinal).isFalse()
    }

    @Test
    fun `state 404 is a normal not-found answer, not an exception, and is not retried`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("trace-id", "2026-11-12 16:34:46#abc")
                .setBody("""{"message":"Замовлення не знайдене"}"""),
        )

        val result = chast.orders.state(ORDER_ID)

        assertThat(result).isEqualTo(OrderStateResult.NotFound("Замовлення не знайдене", "2026-11-12 16:34:46#abc"))
        assertThat(result.stateOrNull).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `state 404 without a body is still not-found`() {
        enqueueJson("", status = 404)
        assertThat(chast.orders.state(ORDER_ID)).isEqualTo(OrderStateResult.NotFound(null, null))
        // monobank's sandbox answers an unknown order with an HTML 404 page.
        enqueueJson("<html>gateway</html>", status = 404)
        assertThat(chast.orders.state(ORDER_ID)).isEqualTo(OrderStateResult.NotFound(null, null))
    }

    @Test
    fun `state is retried on server errors`() {
        enqueueJson("""{"message":"oops"}""", status = 500)
        enqueueFixture("POST_api_order_state.response.json")

        assertThat(
            chast.orders
                .state(ORDER_ID)
                .stateOrNull!!
                .orderId,
        ).isEqualTo(ORDER_ID)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `state survives unknown values, the undocumented QR sub-state and missing optional fields`() {
        enqueueJson("""{"order_id":"o","state":"PAUSED","order_sub_state":"SOMETHING_NEW","extra":1}""")
        enqueueJson("""{"order_id":"o","state":"FAIL","order_sub_state":"CONFIRM_TIME_EXPIRED"}""")
        enqueueJson("""{"order_id":"o","state":"SUCCESS"}""")

        val unknown = chast.orders.state("o").stateOrNull!!
        assertThat(unknown.state).isEqualTo(OrderState.UNKNOWN)
        assertThat(unknown.state.isFinal).isFalse()
        assertThat(unknown.subState).isEqualTo(OrderSubState.UNKNOWN)
        assertThat(
            chast.orders
                .state("o")
                .stateOrNull!!
                .subState,
        ).isEqualTo(OrderSubState.CONFIRM_TIME_EXPIRED)
        val minimal = chast.orders.state("o").stateOrNull!!
        assertThat(minimal.subState).isNull()
        assertThat(minimal.message).isNull()
        assertThat(minimal.state.isFinal).isTrue()
    }

    @Test
    fun `confirm sends the order id and reads the new state`() {
        enqueueFixture("POST_api_order_confirm.response.json")

        val state = chast.orders.confirm(ORDER_ID)

        assertThat(
            takeJson("/api/order/confirm"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_confirm.request.json")))
        assertThat(state.state).isEqualTo(OrderState.SUCCESS)
        assertThat(state.subState).isEqualTo(OrderSubState.ACTIVE)
        assertThat(state.message).isEqualTo("Заявка підтверджена")
    }

    @Test
    fun `reject sends the order id and reads the new state`() {
        enqueueFixture("POST_api_order_reject.response.json")

        val state = chast.orders.reject(ORDER_ID)

        assertThat(
            takeJson("/api/order/reject"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_reject.request.json")))
        assertThat(state.state).isEqualTo(OrderState.FAIL)
        assertThat(state.subState).isEqualTo(OrderSubState.REJECTED_BY_STORE)
    }

    @Test
    fun `confirm, reject and return are not retried after an unknown outcome`() {
        enqueueJson("""{"message":"oops"}""", status = 500)
        enqueueJson("""{"message":"oops"}""", status = 502)
        enqueueJson("""{"message":"oops"}""", status = 504)

        assertThatThrownBy { chast.orders.confirm(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
                assertThat(it.errorText).isEqualTo("oops")
            }
        assertThatThrownBy { chast.orders.reject(ORDER_ID) }.isInstanceOf(MonobankServerException::class.java)
        assertThatThrownBy {
            chast.orders.returnOrder(ReturnOrderRequest.builder(ORDER_ID, BigDecimal("1"), "r", true).build())
        }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `non-idempotent calls are still retried after 429 because nothing was done`() {
        enqueueJson("""{"message":"slow down"}""", status = 429)
        enqueueFixture("POST_api_order_confirm.response.json")

        assertThat(chast.orders.confirm(ORDER_ID).subState).isEqualTo(OrderSubState.ACTIVE)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `return sends the documented body`() {
        enqueueFixture("POST_api_order_return.response.json")

        val response =
            chast.orders.returnOrder(
                ReturnOrderRequest
                    .builder(ORDER_ID, BigDecimal("1250.5"), "RET-12345", returnMoneyToCard = true)
                    .nds(BigDecimal("208.42"))
                    .build(),
            )

        assertThat(response.status).isEqualTo("OK")
        assertThat(
            takeJson("/api/order/return"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_return.request.json")))
    }

    @Test
    fun `return without VAT omits additional params and tolerates an empty answer`() {
        enqueueJson("")

        val response =
            chast.orders.returnOrder(ReturnOrderRequest.builder(ORDER_ID, BigDecimal("100.00"), "r-2", false).build())

        // Returning normally is the success signal; the empty body only leaves the informational status unset.
        assertThat(response.status).isNull()
        val raw = takeRequest("/api/order/return").body.readUtf8()
        assertThat(
            raw,
        ).contains("\"sum\":100.00").contains("\"return_money_to_card\":false").doesNotContain("additional_params")
    }

    @Test
    fun `return validates its arguments`() {
        assertThatThrownBy { ReturnOrderRequest.builder(ORDER_ID, BigDecimal("0.50"), "r", true).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReturnOrderRequest.builder(ORDER_ID, BigDecimal("0"), "r", true).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReturnOrderRequest.builder(ORDER_ID, BigDecimal("1.234"), "r", true).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReturnOrderRequest.builder(ORDER_ID, BigDecimal("1"), "", true).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReturnOrderRequest.builder(" ", BigDecimal("1"), "r", true).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `data reads every field`() {
        enqueueFixture("POST_api_order_data.response.json")

        val data = chast.orders.data(ORDER_ID)

        assertThat(
            takeJson("/api/order/data"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_data.request.json")))
        assertOrderData(data)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated info calls its own path and reads the same shape`() {
        enqueueFixture("POST_api_order_info.response.json")

        val data = chast.orders.info(ORDER_ID)

        assertThat(
            takeJson("/api/order/info"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_info.request.json")))
        assertOrderData(data)
    }

    private fun assertOrderData(data: OrderData) {
        assertThat(data.totalSum).isEqualTo(BigDecimal("2499.99"))
        assertThat(data.source).isEqualTo(OrderSource.INTERNET)
        assertThat(data.invoiceNumber).isEqualTo("INV-001234")
        assertThat(data.invoiceDate).isEqualTo(LocalDate.of(2024, 1, 23))
        assertThat(data.pointId).isEqualTo("STORE-001")
        assertThat(data.storeOrderId).isEqualTo("ORD-2024-001234")
        // Sent without an offset: Kyiv time (UTC+2 in January).
        assertThat(data.createTimestamp).isEqualTo(OffsetDateTime.parse("2024-01-16T16:49:51+02:00"))
        assertThat(data.reverseList).singleElement().satisfies({
            assertThat(it.sum).isEqualByComparingTo("500")
            assertThat(it.timestamp).isEqualTo(OffsetDateTime.parse("2024-01-16T16:49:51+02:00"))
        })
        assertThat(data.maskedCard).isEqualTo("5375 41** **** 1234")
        assertThat(data.iban).isEqualTo("UA123456789012345678901234567")
    }

    @Test
    fun `data survives unknown sources, offsets and an empty object`() {
        enqueueJson("""{"source":"PARTNER","create_timestamp":"2024-07-01T10:00:00+03:00","total_sum":0.10}""")
        enqueueJson("{}")

        val data = chast.orders.data("o")
        assertThat(data.source).isEqualTo(OrderSource.UNKNOWN)
        assertThat(data.createTimestamp).isEqualTo(OffsetDateTime.parse("2024-07-01T10:00:00+03:00"))
        assertThat(data.totalSum).isEqualTo(BigDecimal("0.10"))
        val empty = chast.orders.data("o")
        assertThat(empty.totalSum).isNull()
        assertThat(empty.reverseList).isNull()
    }

    @Test
    fun `data is retried and an unknown order is a not-found error`() {
        enqueueJson("{}", status = 502)
        enqueueJson("""{"message":"Замовлення не знайдене"}""", status = 404)

        assertThatThrownBy { chast.orders.data(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankNotFoundException::class.java) {
                assertThat(it.errorText).isEqualTo("Замовлення не знайдене")
            }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `checkPaid reads both flags`() {
        enqueueFixture("POST_api_order_check_paid.response.json")
        enqueueJson("""{"fully_paid":false}""")

        val paid = chast.orders.checkPaid(ORDER_ID)
        assertThat(
            takeJson("/api/order/check/paid"),
        ).isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_check_paid.request.json")))
        assertThat(paid.fullyPaid).isTrue()
        assertThat(paid.bankCanReturnMoneyToCard).isTrue()
        val notPaid = chast.orders.checkPaid(ORDER_ID)
        assertThat(notPaid.fullyPaid).isFalse()
        assertThat(notPaid.bankCanReturnMoneyToCard).isNull()
    }

    @Test
    fun `validation errors expose monobank's message and blank ids are rejected locally`() {
        enqueueJson("""{"message":"order_id: must be a valid UUID"}""", status = 400)

        assertThatThrownBy { chast.orders.checkPaid("not-a-uuid") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.statusCode).isEqualTo(400)
                assertThat(it.errorText).isEqualTo("order_id: must be a valid UUID")
                assertThat(it.message).contains("order_id: must be a valid UUID")
            }
        assertThatThrownBy { chast.orders.state(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { chast.orders.confirm("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a 2xx confirm answer without the optional fields still reports success of the action`() {
        enqueueJson("""{"state":"SUCCESS"}""")
        val withState = chast.orders.confirm(ORDER_ID)
        assertThat(withState.orderId).isEqualTo(ORDER_ID)
        assertThat(withState.state).isEqualTo(OrderState.SUCCESS)

        enqueueJson("{}")
        val empty = chast.orders.reject(ORDER_ID)
        assertThat(empty.orderId).isEqualTo(ORDER_ID)
        assertThat(empty.state).isEqualTo(OrderState.UNKNOWN)
    }

    @Test
    fun `a 2xx answer that is not JSON is a serialization error with unknown outcome`() {
        enqueueJson("not json")

        assertThatThrownBy { chast.orders.confirm(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
    }

    @Test
    fun `create is retried after a read timeout, which is an unknown outcome`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        enqueueFixture("POST_api_order_create.response.json", status = 201)

        val response = shortTimeoutClient().orders.create(fullCreateRequest())

        assertThat(response.orderId).isEqualTo(ORDER_ID)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `confirm is not retried after a read timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThatThrownBy { shortTimeoutClient().orders.confirm(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `confirm and reject of an unknown order throw not-found once, and 400 is not retried`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(
                    404,
                ).setHeader("trace-id", "t-1")
                .setBody("""{"message":"Замовлення не знайдене"}"""),
        )
        enqueueJson("""{"message":"Замовлення не знайдене"}""", status = 404)
        enqueueJson("""{"message":"Заявка вже підтверджена"}""", status = 400)

        assertThatThrownBy { chast.orders.confirm(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankNotFoundException::class.java) {
                assertThat(it.errorText).isEqualTo("Замовлення не знайдене")
                assertThat(it.traceId).isEqualTo("t-1")
            }
        assertThatThrownBy { chast.orders.reject(ORDER_ID) }.isInstanceOf(MonobankNotFoundException::class.java)
        assertThatThrownBy { chast.orders.confirm(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("Заявка вже підтверджена")
            }
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `toString masks the client phone, the seller phone and the IBAN`() {
        val request = fullCreateRequest()
        assertThat(request.toString())
            .doesNotContain("+380501234567")
            .doesNotContain("+380501234568")
            .contains("+380*******67")
            .contains("ORD-2024-001234")
        enqueueFixture("POST_api_order_data.response.json")
        val data = chast.orders.data(ORDER_ID)
        assertThat(data.toString()).doesNotContain("UA123456789012345678901234567").contains("ORD-2024-001234")
    }

    @Test
    fun `missing fully_paid fails instead of reading as false`() {
        enqueueJson("""{"bank_can_return_money_to_card":true}""")

        assertThatThrownBy { chast.orders.checkPaid(ORDER_ID) }.isInstanceOf(MonobankSerializationException::class.java)
    }

    @Test
    fun `Spring default error bodies still give a message`() {
        enqueueJson(
            """{"timestamp":"2026-01-01T00:00:00","status":400,"error":"Bad Request","path":"/api/order/data"}""",
            400,
        )

        assertThatThrownBy { chast.orders.data(ORDER_ID) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("Bad Request")
            }
    }

    private fun shortTimeoutClient(): MonobankInstallments = clientWith { it.requestTimeout(Duration.ofMillis(300)) }
}
