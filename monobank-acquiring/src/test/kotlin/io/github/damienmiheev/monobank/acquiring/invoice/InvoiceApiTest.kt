package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.BasketItem
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentSystem
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.core.CurrencyCodes
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.json.MonobankJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

class InvoiceApiTest : AcquiringTestSupport() {
    @Test
    fun `create sends the full request and reads the answer`() {
        enqueueFixture("POST_api_merchant_invoice_create.response.json")

        val response =
            mono.invoices.create(
                CreateInvoiceRequest
                    .builder(4200)
                    .ccy(CurrencyCodes.UAH)
                    .merchantPaymInfo(
                        MerchantPaymInfo
                            .builder()
                            .reference("order-1")
                            .destination("Покупка")
                            .basketOrder(listOf(BasketItem.builder("Табурет", BigDecimal("2"), 2100, "SKU-1").build()))
                            .metadata(mapOf("k" to "v"))
                            .build(),
                    ).redirectUrl("https://shop.example/done")
                    .webHookUrl("https://shop.example/hook")
                    .validity(3600)
                    .paymentType(PaymentType.DEBIT)
                    .saveCardData(SaveCardData.saveTo("wallet-1"))
                    .withAppUrl(true)
                    .build(),
            )

        assertThat(response.invoiceId).isEqualTo("p2_9ZgpZVsl3")
        assertThat(response.pageUrl).startsWith("https://")
        val request = takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/merchant/invoice/create")
        val body = MonobankJson.mapper.readTree(request.body.readUtf8())
        assertThat(body.path("amount").asLong()).isEqualTo(4200)
        assertThat(body.path("ccy").asInt()).isEqualTo(980)
        assertThat(body.path("paymentType").asText()).isEqualTo("debit")
        assertThat(body.at("/saveCardData/saveCard").asBoolean()).isTrue()
        assertThat(body.at("/saveCardData/walletId").asText()).isEqualTo("wallet-1")
        assertThat(body.at("/merchantPaymInfo/reference").asText()).isEqualTo("order-1")
        assertThat(body.at("/merchantPaymInfo/basketOrder/0/qty").decimalValue()).isEqualByComparingTo("2")
        assertThat(body.at("/merchantPaymInfo/basketOrder/0/sum").asLong()).isEqualTo(2100)
        assertThat(body.at("/merchantPaymInfo/metadata/k").asText()).isEqualTo("v")
        assertThat(body.has("qrId")).isFalse()
        assertThat(body.path("withAppUrl").asBoolean()).isTrue()
    }

    @Test
    fun `create rejects non-positive amounts before sending`() {
        assertThatThrownBy {
            CreateInvoiceRequest
                .builder(
                    0,
                ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `create is not retried after an unknown outcome`() {
        enqueueJson("""{"errCode":"INTERNAL_ERROR","errText":"oops"}""", status = 500)

        assertThatThrownBy { mono.invoices.create(CreateInvoiceRequest.builder(100).build()) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
                assertThat(it.errorCode).isEqualTo("INTERNAL_ERROR")
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `status reads every documented field`() {
        enqueueFixture("GET_api_merchant_invoice_status.response.json")

        val status = mono.invoices.status("p2_9ZgpZVsl3")

        val request = takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/merchant/invoice/status?invoiceId=p2_9ZgpZVsl3")
        assertThat(status.invoiceId).isEqualTo("p2_9ZgpZVsl3")
        assertThat(status.status).isEqualTo(InvoiceStatus.CREATED)
        assertThat(status.amount).isEqualTo(4200)
        assertThat(status.ccy).isEqualTo(980)
        assertThat(status.finalAmount).isEqualTo(4200)
        assertThat(status.errCode).isEqualTo("59")
        assertThat(status.createdDate).isEqualTo(OffsetDateTime.parse("2024-05-01T12:30:00+03:00"))
        assertThat(status.reference).isEqualTo("84d0070ee4e44667b31371d8f8813947")
        assertThat(status.cancelList).singleElement().satisfies({
            assertThat(it.status).isEqualTo(OperationStatus.PROCESSING)
            assertThat(it.amount).isEqualTo(4200)
            assertThat(it.extRef).isEqualTo("635ace02599849e981b2cd7a65f417fe")
        })
        assertThat(status.paymentInfo!!.maskedPan).isEqualTo("444403******1902")
        assertThat(status.paymentInfo!!.paymentSystem).isEqualTo(PaymentSystem.VISA)
        assertThat(status.paymentInfo!!.tranId).isEqualTo("13194036")
        assertThat(status.walletData!!.cardToken).isEqualTo("67XZtXdR4NpKU3")
        assertThat(status.walletData!!.status).isEqualTo(CardTokenStatus.NEW)
    }

    @Test
    fun `status survives unknown enums and missing optional fields`() {
        enqueueJson(
            """{"invoiceId":"i1","status":"brand_new_status","amount":1,"ccy":980,
               "paymentInfo":{"paymentSystem":"unionpay","paymentMethod":"crypto"},
               "walletData":{"status":"weird"},"newField":1}""",
        )

        val status = mono.invoices.status("i1")

        assertThat(status.status).isEqualTo(InvoiceStatus.UNKNOWN)
        assertThat(status.status.isFinal).isFalse()
        assertThat(status.paymentInfo!!.paymentSystem).isEqualTo(PaymentSystem.UNKNOWN)
        assertThat(status.walletData!!.status).isEqualTo(CardTokenStatus.UNKNOWN)
        assertThat(status.cancelList).isNull()
    }

    @Test
    fun `status is retried on server errors`() {
        enqueueJson("{}", status = 502)
        enqueueFixture("GET_api_merchant_invoice_status.response.json")

        assertThat(mono.invoices.status("p2_9ZgpZVsl3").invoiceId).isEqualTo("p2_9ZgpZVsl3")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `status query is url-encoded and blank ids are rejected`() {
        enqueueFixture("GET_api_merchant_invoice_status.response.json")
        mono.invoices.status("a/b?c&d")
        assertThat(takeRequest().path).isEqualTo("/api/merchant/invoice/status?invoiceId=a%2Fb%3Fc%26d")
        assertThatThrownBy { mono.invoices.status(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `400 errors expose errCode and errText`() {
        enqueueJson("""{"errCode":"BAD_REQUEST","errText":"invalid 'invoiceId'"}""", status = 400)

        assertThatThrownBy { mono.invoices.status("x") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorCode).isEqualTo("BAD_REQUEST")
                assertThat(it.errorText).isEqualTo("invalid 'invoiceId'")
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
    }

    @Test
    fun `newer state wins by modifiedDate`() {
        fun state(date: String?) =
            MonobankJson.read(
                """{"invoiceId":"i","status":"success","amount":1,"ccy":980${date?.let {
                    ""","modifiedDate":"$it""""
                } ?: ""}}"""
                    .toByteArray(),
                InvoiceStatusResponse::class.java,
            )
        val older = state("2024-01-01T10:00:00Z")
        val newer = state("2024-01-01T10:00:01Z")
        assertThat(newer.isNewerThan(older)).isTrue()
        assertThat(older.isNewerThan(newer)).isFalse()
        assertThat(state(null).isNewerThan(older)).isFalse()
        assertThat(older.isNewerThan(state(null))).isTrue()
    }

    @Test
    fun `toString never prints the card token or the API token`() {
        enqueueFixture("GET_api_merchant_invoice_status.response.json")
        val status = mono.invoices.status("p2_9ZgpZVsl3")
        assertThat(status.toString()).doesNotContain("67XZtXdR4NpKU3").contains("***KU3".takeLast(3))
        assertThat(mono.toString()).doesNotContain(TOKEN)
    }
}

class ModifiedDatePrecisionTest {
    private fun state(date: String) =
        io.github.damienmiheev.monobank.core.json.MonobankJson.read(
            """{"invoiceId":"i","status":"hold","amount":1,"ccy":980,"modifiedDate":"$date"}""".toByteArray(),
            InvoiceStatusResponse::class.java,
        )

    @org.junit.jupiter.api.Test
    fun `ordering ignores sub-second differences between endpoints`() {
        // Seen live: the wallet/payment answer had fractions, the later invoice/status whole seconds.
        val paymentAnswer = state("2026-11-12T19:04:33.525289Z")
        val statusAfter = state("2026-11-12T19:04:33Z")
        org.assertj.core.api.Assertions
            .assertThat(statusAfter.isOlderThan(paymentAnswer))
            .isFalse()
        org.assertj.core.api.Assertions
            .assertThat(paymentAnswer.isNewerThan(statusAfter))
            .isFalse()
        val nextSecond = state("2026-11-12T19:04:34Z")
        org.assertj.core.api.Assertions
            .assertThat(nextSecond.isNewerThan(paymentAnswer))
            .isTrue()
        org.assertj.core.api.Assertions
            .assertThat(paymentAnswer.isOlderThan(nextSecond))
            .isTrue()
    }
}
