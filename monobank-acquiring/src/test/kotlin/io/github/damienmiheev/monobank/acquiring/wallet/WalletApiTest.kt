package io.github.damienmiheev.monobank.acquiring.wallet

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.AcquiringErrorCodes
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.Discount
import io.github.damienmiheev.monobank.acquiring.common.DiscountMode
import io.github.damienmiheev.monobank.acquiring.common.DiscountType
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNetworkException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalMonobankApi::class)
class WalletApiTest : AcquiringTestSupport() {
    @Test
    fun `cards are unwrapped and tokens are hidden in toString`() {
        enqueueFixture("GET_api_merchant_wallet.response.json")
        val card = mono.wallet.cards("c1376a611e17b059aeaf96b73258da9c").single()
        assertThat(takeRequest("GET", "/api/merchant/wallet").query("walletId"))
            .isEqualTo("c1376a611e17b059aeaf96b73258da9c")
        assertThat(card.cardToken).isEqualTo("67XZtXdR4NpKU3")
        assertThat(card.maskedPan).isEqualTo("424242******4242")
        assertThat(card.country).isEqualTo("804")
        assertThat(card.toString()).doesNotContain("67XZtXdR4NpKU3")
        enqueueJson("""{"wallet":[]}""")
        assertThat(mono.wallet.cards("w")).isEmpty()
    }

    @Test
    fun `payment sends the token request and reads the answer`() {
        enqueueFixture("POST_api_merchant_wallet_payment.response.json")
        val request =
            WalletPaymentRequest
                .builder("67XZtXdR4NpKU3", 4200, 980, InitiationKind.MERCHANT)
                .redirectUrl("https://shop/back")
                .webHookUrl("https://shop/hook")
                .paymentType(PaymentType.DEBIT)
                .merchantPaymInfo(
                    MerchantPaymInfo
                        .builder()
                        .reference("kb-1")
                        .destination("Підписка")
                        .comment("c")
                        .customerEmails(listOf("a@b.c"))
                        .discounts(listOf(Discount.percent(DiscountType.DISCOUNT, BigDecimal("10"))))
                        .basketOrder(
                            listOf(
                                BasketItem
                                    .builder("Табуретка", BigDecimal("2"), 2100, "sku")
                                    .total(4200)
                                    .unit("шт.")
                                    .tax(listOf(1))
                                    .build(),
                            ),
                        ).metadata(mapOf("key1" to "value1"))
                        .build(),
                ).build()

        val response = mono.wallet.payment(request)

        assertThat(response.invoiceId).isEqualTo("2210012MPLYwJjVUzchj")
        assertThat(response.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(response.tdsUrl).isEqualTo("https://example.com/tds/url")
        assertThat(response.failureReason).isEqualTo("Неправильний CVV код")
        assertThat(response.amount).isEqualTo(4200)
        assertThat(response.ccy).isEqualTo(980)
        assertThat(response.createdDate).isNotNull()
        assertThat(response.modifiedDate).isNotNull()
        val body = takeRequest("POST", "/api/merchant/wallet/payment").json()
        assertThat(body.path("cardToken").asText()).isEqualTo("67XZtXdR4NpKU3")
        assertThat(body.path("amount").asLong()).isEqualTo(4200)
        assertThat(body.path("ccy").asInt()).isEqualTo(980)
        assertThat(body.path("initiationKind").asText()).isEqualTo("merchant")
        assertThat(body.path("redirectUrl").asText()).isEqualTo("https://shop/back")
        assertThat(body.path("webHookUrl").asText()).isEqualTo("https://shop/hook")
        assertThat(body.path("paymentType").asText()).isEqualTo("debit")
        assertThat(body.at("/merchantPaymInfo/reference").asText()).isEqualTo("kb-1")
        assertThat(body.at("/merchantPaymInfo/customerEmails/0").asText()).isEqualTo("a@b.c")
        assertThat(body.at("/merchantPaymInfo/discounts/0/type").asText()).isEqualTo("DISCOUNT")
        assertThat(body.at("/merchantPaymInfo/discounts/0/mode").asText()).isEqualTo("PERCENT")
        assertThat(body.at("/merchantPaymInfo/basketOrder/0/total").asLong()).isEqualTo(4200)
        assertThat(body.at("/merchantPaymInfo/basketOrder/0/tax/0").asInt()).isEqualTo(1)
        assertThat(body.at("/merchantPaymInfo/metadata/key1").asText()).isEqualTo("value1")
        assertThat(body.has("aToken")).isFalse()
        assertThat(body.has("gToken")).isFalse()
        assertThat(request.toString()).doesNotContain("67XZtXdR4NpKU3")
    }

    @Test
    fun `3DS is required while processing with a tdsUrl`() {
        enqueueJson("""{"invoiceId":"i","tdsUrl":"https://3ds","status":"processing","amount":1,"ccy":980}""")
        val response = mono.wallet.payment(WalletPaymentRequest.builder("tok", 1, 980, InitiationKind.CLIENT).build())
        assertThat(response.isThreeDsRequired).isTrue()
        assertThat(response.createdDate).isNull()
    }

    @Test
    fun `Apple Pay and Google Pay tokens are sent and hidden`() {
        val apple = WalletPaymentRequest.applePay("{\"data\":\"apple-secret\"}", 10000, 980).build()
        enqueueFixture("POST_api_merchant_wallet_payment.response.json")
        mono.wallet.payment(apple)
        val appleBody = takeRequest().json()
        assertThat(appleBody.path("aToken").asText()).isEqualTo("{\"data\":\"apple-secret\"}")
        assertThat(appleBody.has("cardToken")).isFalse()
        assertThat(appleBody.has("initiationKind")).isFalse()

        val google =
            WalletPaymentRequest
                .googlePay("{\"signature\":\"google-secret\"}", 10000, 980)
                .redirectUrl("https://shop")
                .build()
        enqueueFixture("POST_api_merchant_wallet_payment.response.json")
        mono.wallet.payment(google)
        assertThat(takeRequest().json().path("gToken").asText()).contains("google-secret")

        assertThat(apple.toString()).doesNotContain("apple-secret")
        assertThat(google.toString()).doesNotContain("google-secret")
    }

    @Test
    fun `invalid payments are rejected before sending`() {
        assertThatThrownBy { WalletPaymentRequest.builder("", 1, 980, InitiationKind.CLIENT) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WalletPaymentRequest.builder("t", 0, 980, InitiationKind.CLIENT).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            WalletPaymentRequest
                .builder("t", 1, 980, InitiationKind.CLIENT)
                .paymentType(PaymentType.VERIFICATION)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            WalletPaymentRequest
                .builder("t", 1, 980, InitiationKind.CLIENT)
                .initiationKind(null)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a token payment is never retried after a 5xx or a read timeout`() {
        val request = WalletPaymentRequest.builder("tok", 100, 980, InitiationKind.MERCHANT).build()
        enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500)
        assertThatThrownBy { mono.wallet.payment(request) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThatThrownBy { mono.wallet.payment(request) }
            .isInstanceOfSatisfying(MonobankNetworkException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `unknown token is a typed error`() {
        enqueueJson("""{"errCode":"TOKEN_NOT_FOUND","errText":"token not found"}""", status = 400)
        assertThatThrownBy {
            mono.wallet.payment(
                WalletPaymentRequest.builder("t", 1, 980, InitiationKind.CLIENT).build(),
            )
        }.isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
            assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.TOKEN_NOT_FOUND)
        }
    }

    @Test
    fun `delete card sends the token in the query and is not retried`() {
        enqueueFixture("DELETE_api_merchant_wallet_card.response.json")
        mono.wallet.deleteCard("67XZtXdR4NpKU3")
        assertThat(takeRequest("DELETE", "/api/merchant/wallet/card").query("cardToken")).isEqualTo("67XZtXdR4NpKU3")

        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.wallet.deleteCard("t") }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a 5xx EXTERNAL_ERROR on a payment is an unknown outcome, never a rejection`() {
        enqueueJson("""{"errCode":"EXTERNAL_ERROR","errText":"mdes.decrypt 500"}""", status = 500)
        assertThatThrownBy {
            mono.wallet.payment(WalletPaymentRequest.builder("tok", 100, 980, InitiationKind.MERCHANT).build())
        }.isInstanceOfSatisfying(MonobankServerException::class.java) {
            assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.EXTERNAL_ERROR)
            assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
        }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `hold works only in UAH`() {
        assertThatThrownBy {
            WalletPaymentRequest.builder("t", 1, 840, InitiationKind.CLIENT).paymentType(PaymentType.HOLD).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            WalletPaymentRequest
                .builder("t", 1, 980, InitiationKind.CLIENT)
                .paymentType(PaymentType.HOLD)
                .build()
                .ccy,
        ).isEqualTo(980)
    }

    @Test
    fun `metadata accepts any JSON values`() {
        enqueueFixture("POST_api_merchant_wallet_payment.response.json")
        mono.wallet.payment(
            WalletPaymentRequest
                .builder("t", 1, 980, InitiationKind.CLIENT)
                .merchantPaymInfo(
                    MerchantPaymInfo.builder().metadata(mapOf("n" to 5, "ok" to true, "s" to "v")).build(),
                ).build(),
        )
        val metadata = takeRequest().json().at("/merchantPaymInfo/metadata")
        assertThat(metadata.path("n").asInt()).isEqualTo(5)
        assertThat(metadata.path("ok").asBoolean()).isTrue()
        assertThat(metadata.path("s").asText()).isEqualTo("v")
    }
}

class DiscountTest {
    @org.junit.jupiter.api.Test
    fun `discount factories are unambiguous about units and validate input`() {
        val value =
            io.github.damienmiheev.monobank.acquiring.common.Discount
                .value(io.github.damienmiheev.monobank.acquiring.common.DiscountType.DISCOUNT, 300)
        org.assertj.core.api.Assertions
            .assertThat(
                value.mode,
            ).isEqualTo(io.github.damienmiheev.monobank.acquiring.common.DiscountMode.VALUE)
        org.assertj.core.api.Assertions
            .assertThat(
                String(
                    io.github.damienmiheev.monobank.core.json.MonobankJson
                        .write(value),
                ),
            ).contains("\"value\":300")
        val pct =
            io.github.damienmiheev.monobank.acquiring.common.Discount
                .percent(
                    io.github.damienmiheev.monobank.acquiring.common.DiscountType.EXTRA_CHARGE,
                    java.math.BigDecimal("10").stripTrailingZeros(),
                )
        org.assertj.core.api.Assertions
            .assertThat(
                String(
                    io.github.damienmiheev.monobank.core.json.MonobankJson
                        .write(pct),
                ),
            ).contains("\"value\":10")
            .doesNotContain("E+")
        listOf("0", "-1", "100.01", "12.345").forEach { bad ->
            org.assertj.core.api.Assertions
                .assertThatThrownBy {
                    io.github.damienmiheev.monobank.acquiring.common.Discount
                        .percent(
                            io.github.damienmiheev.monobank.acquiring.common.DiscountType.DISCOUNT,
                            java.math.BigDecimal(bad),
                        )
                }.isInstanceOf(IllegalArgumentException::class.java)
        }
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                io.github.damienmiheev.monobank.acquiring.common.Discount
                    .value(io.github.damienmiheev.monobank.acquiring.common.DiscountType.DISCOUNT, 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

class PaymentResponseErrCodeTest {
    @org.junit.jupiter.api.Test
    fun `errCode is read when monobank sends it and null otherwise`() {
        fun read(json: String) =
            io.github.damienmiheev.monobank.core.json.MonobankJson
                .read(json.toByteArray(), io.github.damienmiheev.monobank.acquiring.invoice.PaymentResponse::class.java)
        val declined =
            read("""{"invoiceId":"i","status":"failure","failureReason":"x","errCode":"59","amount":1,"ccy":980}""")
        org.assertj.core.api.Assertions
            .assertThat(declined.errCode)
            .isEqualTo("59")
        val ok = read("""{"invoiceId":"i","status":"success","amount":1,"ccy":980}""")
        org.assertj.core.api.Assertions
            .assertThat(ok.errCode)
            .isNull()
    }
}
