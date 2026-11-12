package io.github.damienmiheev.monobank.acquiring.split

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SplitApiTest : AcquiringTestSupport() {
    @Test
    fun `receivers are unwrapped`() {
        enqueueFixture("GET_api_merchant_split_receiver_list.response.json")
        val receiver = mono.split.receivers().single()
        takeRequest("GET", "/api/merchant/split-receiver/list")
        assertThat(receiver.id).isEqualTo("sr_aB3dC5eF7g")
        assertThat(receiver.edrpou).isEqualTo("1234567890")
        assertThat(receiver.name).isEqualTo("ФОП Іванов Іван Іванович")
        enqueueJson("{}")
        assertThat(mono.split.receivers()).isEmpty()
    }

    @Test
    fun `receiver by id reads every field`() {
        enqueueFixture("GET_api_merchant_split_receiver_get_by_id.response.json")
        val receiver = mono.split.receiver("sr_aB3dC5eF7g")
        assertThat(takeRequest("GET", "/api/merchant/split-receiver/get-by-id").query("splitReceiverId"))
            .isEqualTo("sr_aB3dC5eF7g")
        assertThat(receiver.id).isEqualTo("sr_aB3dC5eF7g")
        assertThat(receiver.edrpou).isEqualTo("1234567890")
        assertThat(receiver.name).isEqualTo("Іванов Іван")
        assertThat(receiver.status).isEqualTo(SplitReceiverStatus.ACTIVE)
        assertThat(receiver.iban).isEqualTo("UA213996220000026007233566001")
    }

    @Test
    fun `unknown status and not found`() {
        enqueueJson("""{"id":"x","status":"frozen"}""")
        val receiver = mono.split.receiver("x")
        assertThat(receiver.status).isEqualTo(SplitReceiverStatus.UNKNOWN)
        assertThat(receiver.statusRaw).isEqualTo("frozen")
        enqueueJson("""{"errCode":"1004","errText":"not found"}""", status = 404)
        assertThatThrownBy { mono.split.receiver("y") }.isInstanceOf(MonobankNotFoundException::class.java)
        assertThatThrownBy { mono.split.receiver(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
