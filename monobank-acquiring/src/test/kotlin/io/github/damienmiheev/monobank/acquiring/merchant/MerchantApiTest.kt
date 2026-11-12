package io.github.damienmiheev.monobank.acquiring.merchant

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus
import io.github.damienmiheev.monobank.core.MonobankAuthException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class MerchantApiTest : AcquiringTestSupport() {
    private val from = Instant.ofEpochSecond(1_649_329_978)
    private val to = Instant.ofEpochSecond(1_649_416_378)

    @Test
    fun `details reads every field`() {
        enqueueFixture("GET_api_merchant_details.response.json")
        val details = mono.merchant.details()
        takeRequest("GET", "/api/merchant/details")
        assertThat(details.merchantId).isEqualTo("12o4Vv7EWy")
        assertThat(details.merchantName).isEqualTo("Your Favourite Company")
        assertThat(details.edrpou).isEqualTo("4242424242")
    }

    @Test
    fun `public key is read`() {
        enqueueFixture("GET_api_merchant_pubkey.response.json")
        assertThat(mono.merchant.publicKey().key).isNotBlank()
        takeRequest("GET", "/api/merchant/pubkey")
    }

    @Test
    fun `statement sends unix seconds and unwraps the list`() {
        enqueueFixture("GET_api_merchant_statement.response.json")

        val items = mono.merchant.statement(from, to, "0a8637b3bccb42aa93fdeb791b8b58e9")

        val request = takeRequest("GET", "/api/merchant/statement")
        assertThat(request.query("from")).isEqualTo("1649329978")
        assertThat(request.query("to")).isEqualTo("1649416378")
        assertThat(request.query("code")).isEqualTo("0a8637b3bccb42aa93fdeb791b8b58e9")
        val item = items.single()
        assertThat(item.invoiceId).isEqualTo("2205175v4MfatvmUL2oR")
        assertThat(item.status).isEqualTo(InvoiceStatus.SUCCESS)
        assertThat(item.maskedPan).isEqualTo("444403******1902")
        assertThat(item.date).isEqualTo(OffsetDateTime.parse("2024-05-01T12:30:00+03:00"))
        assertThat(item.paymentScheme).isEqualTo(PaymentScheme.BNPL_LATER_30)
        assertThat(item.amount).isEqualTo(4200)
        assertThat(item.profitAmount).isEqualTo(4100)
        assertThat(item.agentFee).isEqualTo(500)
        assertThat(item.ccy).isEqualTo(980)
        assertThat(item.approvalCode).isEqualTo("662476")
        assertThat(item.rrn).isEqualTo("060189181768")
        assertThat(item.reference).isEqualTo("84d0070ee4e44667b31371d8f8813947")
        assertThat(item.shortQrId).isEqualTo("OBJE")
        assertThat(item.destination).isEqualTo("Покупка щастя")
        val cancel = item.cancelList!!.single()
        assertThat(cancel.amount).isEqualTo(4200)
        assertThat(cancel.ccy).isEqualTo(980)
        assertThat(cancel.date).isNotNull()
        assertThat(cancel.approvalCode).isEqualTo("662476")
        assertThat(cancel.rrn).isEqualTo("060189181768")
        assertThat(cancel.maskedPan).isEqualTo("444403******1902")
        val split = item.splitReceiverList!!.single()
        assertThat(split.splitReceiverId).isEqualTo("sr_aB3dC5eF7g")
        assertThat(split.amount).isEqualTo(4200)
        assertThat(split.profit).isEqualTo(4100)
        assertThat(split.agentFee).isEqualTo(500)
        assertThat(item.metadata).containsEntry("key1", "value1").containsEntry("key2", "value2")
    }

    @Test
    fun `statement without to and code sends only from, and survives odd data`() {
        enqueueJson(
            """{"list":[{"invoiceId":"i","status":"chargeback","amount":1,"ccy":980,
               "paymentScheme":"bnpl_parts_12","metadata":{"n":5,"nested":{"a":true}}}]}""",
        )
        val item = mono.merchant.statement(from).single()
        val request = takeRequest("GET", "/api/merchant/statement")
        assertThat(request.requestUrl!!.queryParameterNames).containsExactly("from")
        assertThat(item.status).isEqualTo(InvoiceStatus.UNKNOWN)
        assertThat(item.statusRaw).isEqualTo("chargeback")
        assertThat(item.paymentScheme).isEqualTo(PaymentScheme.UNKNOWN)
        assertThat(item.paymentSchemeRaw).isEqualTo("bnpl_parts_12")
        assertThat(item.metadata!!["n"]).isEqualTo(5)
        assertThat(item.cancelList).isNull()
        assertThat(item.maskedPan).isNull()
    }

    @Test
    fun `an empty statement is an empty list`() {
        enqueueJson("{}")
        assertThat(mono.merchant.statement(from)).isEmpty()
        enqueueJson("""{"list":[]}""")
        assertThat(mono.merchant.statement(from)).isEmpty()
    }

    @Test
    fun `statement rejects a window that ends before it starts`() {
        assertThatThrownBy { mono.merchant.statement(to, from) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `statement is retried after a server error`() {
        enqueueJson("{}", status = 500)
        enqueueFixture("GET_api_merchant_statement.response.json")
        assertThat(mono.merchant.statement(from)).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `findByReference filters the statement on the client`() {
        enqueueJson(
            """{"list":[
               {"invoiceId":"a","status":"success","amount":1,"ccy":980,"reference":"order-1"},
               {"invoiceId":"b","status":"failure","amount":1,"ccy":980,"reference":"order-2"},
               {"invoiceId":"c","status":"failure","amount":1,"ccy":980},
               {"invoiceId":"d","status":"success","amount":1,"ccy":980,"reference":"order-1"}]}""",
        )

        val found = mono.merchant.findByReference("order-1", from, to)

        assertThat(found.map { it.invoiceId }).containsExactly("a", "d")
        val request = takeRequest("GET", "/api/merchant/statement")
        assertThat(request.query("from")).isEqualTo("1649329978")
        assertThat(request.query("reference")).isNull()
        assertThatThrownBy {
            mono.merchant.findByReference(
                "",
                from,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `submerchants are unwrapped`() {
        enqueueFixture("GET_api_merchant_submerchant_list.response.json")
        val sub = mono.merchant.submerchants().single()
        takeRequest("GET", "/api/merchant/submerchant/list")
        assertThat(sub.code).isEqualTo("0a8637b3bccb42aa93fdeb791b8b58e9")
        assertThat(sub.edrpou).isEqualTo("4242424242")
        assertThat(sub.iban).isEqualTo("UA213996220000026007233566001")
        assertThat(sub.owner).isEqualTo("ТОВ Ворона")
        enqueueJson("{}")
        assertThat(mono.merchant.submerchants()).isEmpty()
    }

    @Test
    fun `403 is an auth error`() {
        enqueueJson("""{"errCode":"FORBIDDEN","errText":"forbidden"}""", status = 403)
        assertThatThrownBy { mono.merchant.details() }
            .isInstanceOfSatisfying(MonobankAuthException::class.java) {
                assertThat(it.errorCode).isEqualTo("FORBIDDEN")
                assertThat(it.outcome).isEqualTo(RequestOutcome.REJECTED)
            }
        assertThat(server.requestCount).isEqualTo(1)
    }
}
