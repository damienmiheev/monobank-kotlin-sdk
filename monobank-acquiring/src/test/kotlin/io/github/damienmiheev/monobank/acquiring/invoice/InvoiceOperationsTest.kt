package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.AcquiringErrorCodes
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus
import io.github.damienmiheev.monobank.acquiring.common.PaymentType
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

class InvoiceOperationsTest : AcquiringTestSupport() {
    private val item =
        FiscalizationItem
            .builder("Табуретка", BigDecimal("2"), 2100, "d21da1c47f3c45fca10a10c32518bdeb")
            .barcode("3b2a558cc6e44e218cdce301d80a1779")
            .header("Хідер")
            .footer("Футер")
            .tax(listOf(0))
            .uktzed("uktzedcode")
            .splitReceiverId("sr_1")
            .build()

    @Test
    fun `cancel sends refund with extRef and items and reads the status`() {
        enqueueFixture("POST_api_merchant_invoice_cancel.response.json")

        val response =
            mono.invoices.cancel(
                CancelInvoiceRequest
                    .builder("p2_9ZgpZVsl3")
                    .extRef("635ace02599849e981b2cd7a65f417fe")
                    .amount(5000)
                    .items(listOf(item))
                    .build(),
            )

        assertThat(response.status).isEqualTo(OperationStatus.PROCESSING)
        assertThat(response.statusRaw).isEqualTo("processing")
        assertThat(response.createdDate).isEqualTo(OffsetDateTime.parse("2024-05-01T12:30:00+03:00"))
        assertThat(response.modifiedDate).isEqualTo(OffsetDateTime.parse("2024-05-01T12:30:00+03:00"))
        val body = takeRequest("POST", "/api/merchant/invoice/cancel").json()
        assertThat(body.path("invoiceId").asText()).isEqualTo("p2_9ZgpZVsl3")
        assertThat(body.path("extRef").asText()).isEqualTo("635ace02599849e981b2cd7a65f417fe")
        assertThat(body.path("amount").isIntegralNumber).isTrue()
        assertThat(body.path("amount").asLong()).isEqualTo(5000)
        val sent = body.at("/items/0")
        assertThat(sent.path("name").asText()).isEqualTo("Табуретка")
        assertThat(sent.path("qty").decimalValue()).isEqualByComparingTo("2")
        assertThat(sent.path("sum").asLong()).isEqualTo(2100)
        assertThat(sent.path("code").asText()).isEqualTo("d21da1c47f3c45fca10a10c32518bdeb")
        assertThat(sent.path("barcode").asText()).isEqualTo("3b2a558cc6e44e218cdce301d80a1779")
        assertThat(sent.path("header").asText()).isEqualTo("Хідер")
        assertThat(sent.path("footer").asText()).isEqualTo("Футер")
        assertThat(sent.path("tax").map { it.asInt() }).containsExactly(0)
        assertThat(sent.path("uktzed").asText()).isEqualTo("uktzedcode")
        assertThat(sent.path("splitReceiverId").asText()).isEqualTo("sr_1")
    }

    @Test
    fun `full refund omits amount and items`() {
        enqueueFixture("POST_api_merchant_invoice_cancel.response.json")
        mono.invoices.cancel(CancelInvoiceRequest.builder("inv").build())
        val body = takeRequest().json()
        assertThat(body.fieldNames().asSequence().toList()).containsExactly("invoiceId")
    }

    @Test
    fun `cancel keeps an unknown status raw`() {
        enqueueJson("""{"status":"queued"}""")
        val response = mono.invoices.cancel(CancelInvoiceRequest.builder("inv").build())
        assertThat(response.status).isEqualTo(OperationStatus.UNKNOWN)
        assertThat(response.statusRaw).isEqualTo("queued")
        assertThat(response.createdDate).isNull()
    }

    @Test
    fun `500 CANCEL_NOT_AVAILABLE is a definite rejection and is not retried`() {
        enqueueJson("""{"errCode":"CANCEL_NOT_AVAILABLE","errText":"cancel not available"}""", status = 500)

        assertThatThrownBy { mono.invoices.cancel(CancelInvoiceRequest.builder("inv").build()) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.statusCode).isEqualTo(500)
                assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.CANCEL_NOT_AVAILABLE)
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a refund is not retried after a 5xx with unknown outcome`() {
        enqueueJson("""{"errCode":"INTERNAL_ERROR","errText":"internal server error"}""", status = 500)
        enqueueFixture("POST_api_merchant_invoice_cancel.response.json")

        assertThatThrownBy { mono.invoices.cancel(CancelInvoiceRequest.builder("inv").amount(100).build()) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a refund is retried after 429 because it was not performed`() {
        enqueueJson("""{"errCode":"TMR","errText":"too many requests"}""", status = 429)
        enqueueFixture("POST_api_merchant_invoice_cancel.response.json")

        assertThat(mono.invoices.cancel(CancelInvoiceRequest.builder("inv").build()).status)
            .isEqualTo(OperationStatus.PROCESSING)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `WRONG_CANCEL_AMOUNT is a typed 400`() {
        enqueueJson("""{"errCode":"WRONG_CANCEL_AMOUNT","errText":"wrong amount"}""", status = 400)
        assertThatThrownBy { mono.invoices.cancel(CancelInvoiceRequest.builder("inv").amount(1).build()) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.WRONG_CANCEL_AMOUNT)
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
    }

    @Test
    fun `cancel validates its input`() {
        assertThatThrownBy { CancelInvoiceRequest.builder(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CancelInvoiceRequest.builder("i").amount(0).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `remove sends the invoice id and accepts an empty object`() {
        enqueueFixture("POST_api_merchant_invoice_remove.response.json")
        mono.invoices.remove("p2_9ZgpZVsl3")
        val body = takeRequest("POST", "/api/merchant/invoice/remove").json()
        assertThat(body.path("invoiceId").asText()).isEqualTo("p2_9ZgpZVsl3")
    }

    @Test
    fun `remove accepts an empty body`() {
        enqueueJson("")
        mono.invoices.remove("i")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `remove failures surface as typed exceptions with the raw errCode`() {
        // HYPOTHETICAL body: monobank does not document the error for removing a paid invoice.
        // This only shows that any 4xx comes through typed, with errorCode and errText kept.
        enqueueJson("""{"errCode":"BAD_REQUEST","errText":"invoice already paid"}""", status = 400)
        assertThatThrownBy { mono.invoices.remove("paid") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.statusCode).isEqualTo(400)
                assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.BAD_REQUEST)
                assertThat(it.errorText).isEqualTo("invoice already paid")
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
        enqueueJson("""{"errCode":"NOT_FOUND","errText":"invoice not found"}""", status = 404)
        assertThatThrownBy { mono.invoices.remove("missing") }
            .isInstanceOfSatisfying(MonobankNotFoundException::class.java) {
                assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.NOT_FOUND)
            }
    }

    @Test
    fun `remove is not retried after a 5xx`() {
        enqueueJson("{}", status = 503)
        assertThatThrownBy { mono.invoices.remove("i") }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `finalize sends the required amount and items`() {
        enqueueFixture("POST_api_merchant_invoice_finalize.response.json")

        val response =
            mono.invoices.finalize(FinalizeInvoiceRequest.builder("p2_9ZgpZVsl3", 4200).items(listOf(item)).build())

        assertThat(response.status).isEqualTo(OperationStatus.SUCCESS)
        val body = takeRequest("POST", "/api/merchant/invoice/finalize").json()
        assertThat(body.path("invoiceId").asText()).isEqualTo("p2_9ZgpZVsl3")
        assertThat(body.path("amount").asLong()).isEqualTo(4200)
        assertThat(body.at("/items/0/code").asText()).isEqualTo("d21da1c47f3c45fca10a10c32518bdeb")
    }

    @Test
    fun `finalize without items and validation`() {
        enqueueJson("""{"status":"success"}""")
        mono.invoices.finalize(FinalizeInvoiceRequest.of("i", 100))
        val body = takeRequest().json()
        assertThat(body.has("items")).isFalse()
        assertThat(body.path("amount").asLong()).isEqualTo(100)
        assertThatThrownBy { FinalizeInvoiceRequest.of("i", 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FinalizeInvoiceRequest.of("", 1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `finalize is not retried after a 5xx`() {
        enqueueJson("{}", status = 502)
        assertThatThrownBy { mono.invoices.finalize(FinalizeInvoiceRequest.of("i", 1)) }
            .isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `payment-direct sends card data and reads tdsUrl`() {
        enqueueFixture("POST_api_merchant_invoice_payment_direct.response.json")
        val request =
            PaymentDirectRequest
                .builder(
                    4200,
                    CardData.of("4242424242424242", "0642", "123"),
                    InitiationKind.CLIENT,
                    "https://shop.example/return",
                ).ccy(980)
                .merchantPaymInfo(MerchantPaymInfo.builder().reference("ref-1").build())
                .webHookUrl("https://shop.example/hook")
                .paymentType(PaymentType.HOLD)
                .saveCardData(SaveCardData.saveTo("w1"))
                .redirectUrl("https://shop.example/back")
                .initiationKind(InitiationKind.CLIENT)
                .build()

        val response = mono.invoices.paymentDirect(request)

        assertThat(response.invoiceId).isEqualTo("2210012MPLYwJjVUzchj")
        assertThat(response.tdsUrl).isEqualTo("https://example.com/tds/url")
        assertThat(response.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(response.isThreeDsRequired).isFalse()
        assertThat(response.failureReason).isEqualTo("Неправильний CVV код")
        assertThat(response.amount).isEqualTo(4200)
        assertThat(response.ccy).isEqualTo(980)
        assertThat(response.createdDate).isNotNull()
        assertThat(response.modifiedDate).isNotNull()
        val body = takeRequest("POST", "/api/merchant/invoice/payment-direct").json()
        assertThat(body.path("amount").asLong()).isEqualTo(4200)
        assertThat(body.path("ccy").asInt()).isEqualTo(980)
        assertThat(body.at("/cardData/pan").asText()).isEqualTo("4242424242424242")
        assertThat(body.at("/cardData/exp").asText()).isEqualTo("0642")
        assertThat(body.at("/cardData/cvv").isTextual).isTrue()
        assertThat(body.at("/cardData/cvv").asText()).isEqualTo("123")
        assertThat(body.at("/merchantPaymInfo/reference").asText()).isEqualTo("ref-1")
        assertThat(body.path("paymentType").asText()).isEqualTo("hold")
        assertThat(body.at("/saveCardData/walletId").asText()).isEqualTo("w1")
        assertThat(body.path("redirectUrl").asText()).isEqualTo("https://shop.example/back")
        assertThat(body.path("webHookUrl").asText()).isEqualTo("https://shop.example/hook")
        assertThat(body.path("initiationKind").asText()).isEqualTo("client")
    }

    @Test
    fun `payment-direct never prints card data and is not retried`() {
        val request =
            PaymentDirectRequest
                .builder(
                    100,
                    CardData.of("4242424242424242", "0642", "987"),
                    InitiationKind.CLIENT,
                    "https://shop.example/return",
                ).build()
        val text = request.toString()
        assertThat(text).doesNotContain("4242424242424242").doesNotContain("0642").doesNotContain("987")
        assertThat(text).contains("***4242")

        enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500)
        assertThatThrownBy { mono.invoices.paymentDirect(request) }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `payment-direct requires a 3DS redirect while processing`() {
        enqueueJson(
            """{"invoiceId":"i","tdsUrl":"https://tds","status":"processing","amount":1,"ccy":980,
               "createdDate":"2024-05-01T12:30:00+03:00","modifiedDate":"2024-05-01T12:30:00+03:00"}""",
        )
        val response =
            mono.invoices.paymentDirect(
                PaymentDirectRequest
                    .builder(
                        1,
                        CardData.of("4111", "0130", "1"),
                        InitiationKind.CLIENT,
                        "https://shop.example/return",
                    ).build(),
            )
        assertThat(response.isThreeDsRequired).isTrue()
    }

    @Test
    fun `payment-direct rejects invalid input`() {
        assertThatThrownBy { CardData.of("4242", "642", "1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            PaymentDirectRequest
                .builder(1, CardData.of("4242", "0642", "1"), InitiationKind.CLIENT, "https://shop.example/return")
                .paymentType(PaymentType.VERIFICATION)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sync-payment with card data sends every field and returns the invoice state`() {
        enqueueFixture("POST_api_merchant_invoice_sync_payment.response.json")
        val card =
            SyncCardData
                .builder("4242424242424242", SyncCardType.FPAN, "0642", "02")
                .cvv("123")
                .cavv("cavv-secret")
                .tavv("tavv-secret")
                .dsTranId("12")
                .tReqID("51")
                .mit("1")
                .sst(2)
                .tid("12")
                .build()
        val request =
            SyncPaymentRequest
                .card(4200, 980, card)
                .merchantPaymInfo(
                    MerchantPaymInfo
                        .builder()
                        .reference("ref")
                        .destination("Покупка щастя")
                        .build(),
                ).build()

        val state = mono.invoices.syncPayment(request)

        assertThat(state.invoiceId).isEqualTo("p2_9ZgpZVsl3")
        assertThat(state.status).isEqualTo(InvoiceStatus.CREATED)
        assertThat(state.errCode).isEqualTo("59")
        assertThat(state.walletData!!.status).isEqualTo(CardTokenStatus.NEW)
        val body = takeRequest("POST", "/api/merchant/invoice/sync-payment").json()
        assertThat(body.path("amount").asLong()).isEqualTo(4200)
        assertThat(body.path("ccy").asInt()).isEqualTo(980)
        assertThat(body.at("/merchantPaymInfo/reference").asText()).isEqualTo("ref")
        val sent = body.path("cardData")
        assertThat(sent.path("pan").asText()).isEqualTo("4242424242424242")
        assertThat(sent.path("type").asText()).isEqualTo("FPAN")
        assertThat(sent.path("exp").asText()).isEqualTo("0642")
        assertThat(sent.path("cvv").asText()).isEqualTo("123")
        assertThat(sent.path("eciIndicator").asText()).isEqualTo("02")
        assertThat(sent.path("cavv").asText()).isEqualTo("cavv-secret")
        assertThat(sent.path("tavv").asText()).isEqualTo("tavv-secret")
        assertThat(sent.path("dsTranId").asText()).isEqualTo("12")
        assertThat(sent.path("tReqID").asText()).isEqualTo("51")
        assertThat(sent.path("mit").asText()).isEqualTo("1")
        assertThat(sent.path("sst").asInt()).isEqualTo(2)
        assertThat(sent.path("tid").asText()).isEqualTo("12")
        assertThat(body.has("applePay")).isFalse()
        assertThat(body.has("googlePay")).isFalse()

        assertThat(request.toString())
            .doesNotContain("4242424242424242")
            .doesNotContain("0642")
            .doesNotContain("cavv-secret")
            .doesNotContain("tavv-secret")
    }

    @Test
    fun `sync-payment with Apple Pay and Google Pay data`() {
        val apple = DeviceTokenData.of("5555555555554444", "0642", "05", "AQAAAAoAR9qDi9kAAAAAgGpLpoA=")
        enqueueFixture("POST_api_merchant_invoice_sync_payment.response.json")
        mono.invoices.syncPayment(SyncPaymentRequest.applePay(100, 980, apple).build())
        val appleBody = takeRequest().json()
        assertThat(appleBody.at("/applePay/token").asText()).isEqualTo("5555555555554444")
        assertThat(appleBody.at("/applePay/exp").asText()).isEqualTo("0642")
        assertThat(appleBody.at("/applePay/eciIndicator").asText()).isEqualTo("05")
        assertThat(appleBody.at("/applePay/cryptogram").asText()).isEqualTo("AQAAAAoAR9qDi9kAAAAAgGpLpoA=")
        assertThat(appleBody.has("cardData")).isFalse()

        enqueueFixture("POST_api_merchant_invoice_sync_payment.response.json")
        mono.invoices.syncPayment(
            SyncPaymentRequest.googlePay(100, 980, DeviceTokenData.of("4111", "0130", "07")).build(),
        )
        val googleBody = takeRequest().json()
        assertThat(googleBody.at("/googlePay/token").asText()).isEqualTo("4111")
        assertThat(googleBody.at("/googlePay").has("cryptogram")).isFalse()

        assertThat(apple.toString()).doesNotContain("5555555555554444").doesNotContain("AQAAAAoAR9qDi9kAAAAAgGpLpoA=")
    }

    @Test
    fun `sync-payment is a payment and is never retried after an unknown outcome`() {
        enqueueJson("""{"errCode":"INTERNAL_ERROR"}""", status = 500)
        enqueueFixture("POST_api_merchant_invoice_sync_payment.response.json")
        val request =
            SyncPaymentRequest
                .card(100, 980, SyncCardData.builder("4242", SyncCardType.DPAN, "0642", "02").build())
                .build()
        assertThatThrownBy { mono.invoices.syncPayment(request) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `receipt decodes the base64 PDF`() {
        enqueueFixture("GET_api_merchant_invoice_receipt.response.json")
        val pdf = mono.invoices.receipt("p2_9ZgpZVsl3")
        assertThat(String(pdf, Charsets.US_ASCII)).startsWith("%PDF-1.4")
        val request = takeRequest("GET", "/api/merchant/invoice/receipt")
        assertThat(request.query("invoiceId")).isEqualTo("p2_9ZgpZVsl3")
        assertThat(request.query("email")).isNull()
    }

    @Test
    fun `receipt tolerates line breaks but rejects invalid base64 and missing files`() {
        enqueueJson("""{"file":"JVBERi0x\nLjQK"}""")
        assertThat(String(mono.invoices.receipt("i"), Charsets.US_ASCII)).isEqualTo("%PDF-1.4\n")

        enqueueJson("""{"file":"CJFVBERi0xLj4QK..."}""")
        assertThatThrownBy { mono.invoices.receipt("i") }.isInstanceOf(MonobankSerializationException::class.java)

        enqueueJson("{}")
        assertThatThrownBy { mono.invoices.receipt("i") }.isInstanceOf(MonobankSerializationException::class.java)
    }

    @Test
    fun `receipt without email is retried, with email it is not`() {
        enqueueJson("{}", status = 500)
        enqueueFixture("GET_api_merchant_invoice_receipt.response.json")
        assertThat(mono.invoices.receipt("i")).isNotEmpty()
        assertThat(server.requestCount).isEqualTo(2)

        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.invoices.sendReceipt("i", "me@mail.com") }
            .isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `sendReceipt passes the email and returns the file when present`() {
        enqueueFixture("GET_api_merchant_invoice_receipt.response.json")
        assertThat(mono.invoices.sendReceipt("i", "me+1@mail.com")).isNotNull()
        val request = takeRequest("GET", "/api/merchant/invoice/receipt")
        assertThat(request.path).contains("email=me%2B1%40mail.com")
        assertThat(request.query("email")).isEqualTo("me+1@mail.com")

        enqueueJson("{}")
        assertThat(mono.invoices.sendReceipt("i", "me@mail.com")).isNull()
    }

    @Test
    fun `fiscal checks are unwrapped and parsed`() {
        enqueueFixture("GET_api_merchant_invoice_fiscal_checks.response.json")

        val checks = mono.invoices.fiscalChecks("p2_9ZgpZVsl3")

        assertThat(takeRequest("GET", "/api/merchant/invoice/fiscal-checks").query("invoiceId"))
            .isEqualTo("p2_9ZgpZVsl3")
        val check = checks.single()
        assertThat(check.id).isEqualTo("a2fd4aef-cdb8-4e25-9b36-b6d4672c554d")
        assertThat(check.type).isEqualTo(FiscalCheckType.SALE)
        assertThat(check.status).isEqualTo(FiscalCheckStatus.DONE)
        assertThat(check.statusDescription).isEmpty()
        assertThat(check.taxUrl).isEqualTo("https://cabinet.tax.gov.ua/cashregs/check")
        assertThat(check.fiscalizationSource).isEqualTo(FiscalizationSource.MONOPAY)
        assertThat(String(check.decodeFile()!!, Charsets.US_ASCII)).startsWith("%PDF")
        assertThat(check.toString()).doesNotContain(check.file)
    }

    @Test
    fun `fiscal checks tolerate an empty answer and unknown values`() {
        enqueueJson("{}")
        assertThat(mono.invoices.fiscalChecks("i")).isEmpty()
        enqueueJson("""{"checks":[{"id":"c","type":"refund","status":"weird","fiscalizationSource":"prro"}]}""")
        val check = mono.invoices.fiscalChecks("i").single()
        assertThat(check.type).isEqualTo(FiscalCheckType.UNKNOWN)
        assertThat(check.typeRaw).isEqualTo("refund")
        assertThat(check.status).isEqualTo(FiscalCheckStatus.UNKNOWN)
        assertThat(check.fiscalizationSource).isEqualTo(FiscalizationSource.UNKNOWN)
        assertThat(check.decodeFile()).isNull()
    }

    @Test
    fun `verification invoice saves a card without charging`() {
        enqueueFixture("POST_api_merchant_invoice_create.response.json")
        val request = CreateInvoiceRequest.verification("customer-42").webHookUrl("https://shop/hook").build()

        mono.invoices.create(request)

        val body = takeRequest("POST", "/api/merchant/invoice/create").json()
        assertThat(body.path("amount").asLong()).isZero()
        assertThat(body.path("paymentType").asText()).isEqualTo("verification")
        assertThat(body.at("/saveCardData/saveCard").asBoolean()).isTrue()
        assertThat(body.at("/saveCardData/walletId").asText()).isEqualTo("customer-42")
    }

    @Test
    fun `verification and payment type rules are checked before sending`() {
        assertThatThrownBy {
            CreateInvoiceRequest
                .builder(
                    100,
                ).paymentType(PaymentType.VERIFICATION)
                .saveCardData(SaveCardData.saveTo("w"))
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CreateInvoiceRequest.builder(0).paymentType(PaymentType.VERIFICATION).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CreateInvoiceRequest.builder(100).paymentType(PaymentType.UNKNOWN).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateInvoiceRequest.verification(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `invoice status keeps raw values and finds refunds by extRef`() {
        enqueueJson(
            """{"invoiceId":"i","status":"partially_reversed","amount":100,"ccy":980,
               "cancelList":[{"status":"success","amount":50,"extRef":"r1"},{"status":"in_review","extRef":"r2"}],
               "paymentInfo":{"paymentSystem":"prostir"},
               "walletData":{"cardToken":"tok-1234567890","walletId":"w","status":"created"}}""",
        )
        val state = mono.invoices.status("i")
        assertThat(state.status).isEqualTo(InvoiceStatus.UNKNOWN)
        assertThat(state.statusRaw).isEqualTo("partially_reversed")
        assertThat(state.cancelFor("r1")!!.status).isEqualTo(OperationStatus.SUCCESS)
        assertThat(state.cancelFor("r2")!!.status).isEqualTo(OperationStatus.UNKNOWN)
        assertThat(state.cancelFor("r2")!!.statusRaw).isEqualTo("in_review")
        assertThat(state.cancelFor("none")).isNull()
        assertThat(state.paymentInfo!!.paymentSystemRaw).isEqualTo("prostir")
        assertThat(state.paymentInfo!!.paymentMethod).isNull()
        assertThat(state.walletData!!.isCardSaved).isTrue()
        assertThat(state.toString()).doesNotContain("tok-1234567890")
    }

    @Test
    fun `finalize with a 5xx EXTERNAL_ERROR is an unknown outcome`() {
        enqueueJson("""{"errCode":"EXTERNAL_ERROR","errText":"external"}""", status = 500)
        assertThatThrownBy { mono.invoices.finalize(FinalizeInvoiceRequest.of("i", 1)) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
    }

    @Test
    fun `an event with the same modifiedDate is not older and must be applied`() {
        fun state(date: String?) =
            io.github.damienmiheev.monobank.core.json.MonobankJson.read(
                """{"invoiceId":"i","status":"success","amount":1,"ccy":980${date?.let {
                    ""","modifiedDate":"$it""""
                } ?: ""}}""".toByteArray(),
                InvoiceStatusResponse::class.java,
            )
        val first = state("2024-01-01T10:00:00Z")
        val sameTime = state("2024-01-01T10:00:00Z")
        val later = state("2024-01-01T10:00:01Z")
        assertThat(sameTime.isOlderThan(first)).isFalse()
        assertThat(sameTime.isNewerThan(first)).isFalse()
        assertThat(first.isOlderThan(later)).isTrue()
        assertThat(later.isOlderThan(first)).isFalse()
        assertThat(state(null).isOlderThan(first)).isFalse()
        assertThat(first.isOlderThan(state(null))).isFalse()
    }

    @Test
    fun `expiry and hold currency are checked before sending`() {
        listOf("1342", "0042", "06a2", "064", "\u0660\u0666\u0664\u0662").forEach { exp ->
            assertThatThrownBy {
                CardData.of(
                    "4242",
                    exp,
                    "1",
                )
            }.describedAs(exp).isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                DeviceTokenData.of(
                    "4242",
                    exp,
                    "02",
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { SyncCardData.builder("4242", SyncCardType.FPAN, exp, "02") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        // Hold currencies are not checked locally: monobank answers 400 1001 for a non-UAH invoice hold.
        assertThat(
            CreateInvoiceRequest
                .builder(100)
                .ccy(978)
                .paymentType(PaymentType.HOLD)
                .build()
                .ccy,
        ).isEqualTo(978)
        assertThat(
            CreateInvoiceRequest
                .builder(100)
                .paymentType(PaymentType.HOLD)
                .build()
                .ccy,
        ).isNull()
    }

    @Test
    fun `invoiceIdOf reads the id of a body that does not parse`() {
        val broken =
            """{"invoiceId":"p2_x","status":"success","walletData":{"cardToken":"SECRET-TOKEN-1"}}"""
                .toByteArray()
        assertThatThrownBy {
            io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
                .parse(broken)
        }.isInstanceOfSatisfying(MonobankSerializationException::class.java) {
            val trace =
                java.io
                    .StringWriter()
                    .also { w -> it.printStackTrace(java.io.PrintWriter(w)) }
                    .toString()
            assertThat(trace).doesNotContain("SECRET-TOKEN-1")
        }
        assertThat(
            io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
                .invoiceIdOf(broken),
        ).isEqualTo("p2_x")
        assertThat(
            io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
                .invoiceIdOf("nope".toByteArray()),
        ).isNull()
        assertThat(
            io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser
                .invoiceIdOf("{}".toByteArray()),
        ).isNull()
    }

    @Test
    fun `fractional amounts fail instead of being truncated`() {
        enqueueJson("""{"invoiceId":"i","status":"success","amount":100.5,"ccy":980}""")
        assertThatThrownBy { mono.invoices.status("i") }.isInstanceOf(MonobankSerializationException::class.java)
    }
}
