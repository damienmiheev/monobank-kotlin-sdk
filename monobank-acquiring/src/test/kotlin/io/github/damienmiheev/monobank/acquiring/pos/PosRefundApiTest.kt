package io.github.damienmiheev.monobank.acquiring.pos

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankServerException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class PosRefundApiTest : AcquiringTestSupport() {
    @Test
    fun `create sends rrn, amount and the required Accept-Language header`() {
        enqueueFixture("POST_api_merchant_pos_transaction_cancel.response.json")

        val response = mono.posRefunds.create(PosRefundRequest.of("060189181768", 4200))

        assertThat(response.status).isEqualTo("ok")
        assertThat(response.tranId).isEqualTo("acq123456789")
        val request = takeRequest("POST", "/api/merchant/pos-transaction-cancel")
        assertThat(request.getHeader("Accept-Language")).isEqualTo("uk")
        val body = request.json()
        assertThat(body.path("rrn").asText()).isEqualTo("060189181768")
        assertThat(body.path("amount").asLong()).isEqualTo(4200)
    }

    @Test
    fun `create can ask for English errors`() {
        enqueueJson("""{"errCode":"BAD_REQUEST","errText":"amount exceeds the remaining balance"}""", status = 400)
        assertThatThrownBy { mono.posRefunds.create(PosRefundRequest.of("1", 1), ResponseLanguage.EN) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("amount exceeds the remaining balance")
            }
        assertThat(takeRequest().getHeader("Accept-Language")).isEqualTo("en")
    }

    @Test
    fun `a POS refund is not retried after a 5xx and validates input`() {
        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.posRefunds.create(PosRefundRequest.of("1", 1)) }
            .isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThatThrownBy { PosRefundRequest.of("", 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PosRefundRequest.of("1", 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `status reads every field`() {
        enqueueFixture("GET_api_merchant_pos_transaction_cancel_status.response.json")

        val status = mono.posRefunds.status("1234567890")

        assertThat(takeRequest("GET", "/api/merchant/pos-transaction-cancel/status").query("cancelRef"))
            .isEqualTo("1234567890")
        assertThat(status.amount).isEqualTo(4200)
        assertThat(status.cancelRef).isEqualTo("1234567890")
        assertThat(status.createTime).isEqualTo(OffsetDateTime.parse("2026-10-01T10:30:00Z"))
        assertThat(status.modifyTime).isEqualTo(OffsetDateTime.parse("2026-10-01T11:30:00Z"))
        assertThat(status.originalTranId).isEqualTo("acq123456789")
        assertThat(status.rrn).isEqualTo("060189181768")
        assertThat(status.status).isEqualTo(PosRefundStatus.NEW)
    }

    @Test
    fun `status survives unknown values, is retried, and reports not found`() {
        enqueueJson("{}", status = 502)
        enqueueJson("""{"status":"chargeback"}""")
        val status = mono.posRefunds.status("r")
        assertThat(status.status).isEqualTo(PosRefundStatus.UNKNOWN)
        assertThat(status.statusRaw).isEqualTo("chargeback")
        assertThat(server.requestCount).isEqualTo(2)

        enqueueJson("""{"errCode":"NOT_FOUND","errText":"refund not found"}""", status = 404)
        assertThatThrownBy { mono.posRefunds.status("x") }.isInstanceOf(MonobankNotFoundException::class.java)
    }
}
