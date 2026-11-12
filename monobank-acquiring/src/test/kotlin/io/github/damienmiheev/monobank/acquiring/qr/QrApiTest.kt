package io.github.damienmiheev.monobank.acquiring.qr

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.AcquiringErrorCodes
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankServerException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class QrApiTest : AcquiringTestSupport() {
    @Test
    fun `details reads every field`() {
        enqueueFixture("GET_api_merchant_qr_details.response.json")
        val details = mono.qr.details("OBJE")
        assertThat(takeRequest("GET", "/api/merchant/qr/details").query("qrId")).isEqualTo("OBJE")
        assertThat(details.shortQrId).isEqualTo("OBJE")
        assertThat(details.invoiceId).isEqualTo("4EwIUTA12JIZ")
        assertThat(details.amount).isEqualTo(4200)
        assertThat(details.ccy).isEqualTo(980)
    }

    @Test
    fun `details without an amount`() {
        enqueueJson("""{"shortQrId":"OBJE"}""")
        val details = mono.qr.details("OBJE")
        assertThat(details.invoiceId).isNull()
        assertThat(details.amount).isNull()
    }

    @Test
    fun `unknown QR is a not-found error`() {
        enqueueJson("""{"errCode":"NOT_FOUND","errText":"invalid 'qrId'"}""", status = 404)
        assertThatThrownBy { mono.qr.details("x") }.isInstanceOf(MonobankNotFoundException::class.java)
    }

    @Test
    fun `list is unwrapped`() {
        enqueueFixture("GET_api_merchant_qr_list.response.json")
        val qr = mono.qr.list().single()
        takeRequest("GET", "/api/merchant/qr/list")
        assertThat(qr.shortQrId).isEqualTo("OBJE")
        assertThat(qr.qrId).isEqualTo("XJ_DiM4rTd5V")
        assertThat(qr.amountType).isEqualTo(QrAmountType.MERCHANT)
        assertThat(qr.pageUrl).isEqualTo("https://pay.mbnk.biz/XJ_DiM4rTd5V")
        enqueueJson("""{"list":[{"qrId":"q","amountType":"dynamic"}]}""")
        val odd = mono.qr.list().single()
        assertThat(odd.amountType).isEqualTo(QrAmountType.UNKNOWN)
        assertThat(odd.amountTypeRaw).isEqualTo("dynamic")
        enqueueJson("{}")
        assertThat(mono.qr.list()).isEmpty()
    }

    @Test
    fun `reset amount sends the QR id and is not retried`() {
        enqueueFixture("POST_api_merchant_qr_reset_amount.response.json")
        mono.qr.resetAmount("XJ_DiM4rTd5V")
        assertThat(takeRequest("POST", "/api/merchant/qr/reset-amount").json().path("qrId").asText())
            .isEqualTo("XJ_DiM4rTd5V")

        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.qr.resetAmount("q") }.isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `reset of an amount being paid is ORDER_IN_PROGRESS`() {
        enqueueJson("""{"errCode":"ORDER_IN_PROGRESS","errText":"in progress"}""", status = 400)
        assertThatThrownBy { mono.qr.resetAmount("q") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorCode).isEqualTo(AcquiringErrorCodes.ORDER_IN_PROGRESS)
            }
    }
}
