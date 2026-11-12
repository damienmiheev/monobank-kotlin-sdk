package io.github.damienmiheev.monobank.acquiring.monopay

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankNotFoundException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.RequestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class MonopayApiTest : AcquiringTestSupport() {
    private val keyValue =
        "LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUZrd0V3WUhLb1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FFakdQUmJiTmt1L2tXZElWMFFh" +
            "eFlib1dtWGZINQpPS05BdU4yTDUxQVlUeU9QV2JwOEZHWndhNFFQUjFYaStROVZrMDRNaFhxbVJyZzkycDNMbVpJU1lnPT0KLS0tLS1F" +
            "TkQgUFVCTElDIEtFWS0tLS0tCg=="

    @Test
    fun `import sends the key and returns keyId`() {
        enqueueFixture("POST_api_merchant_monopay_pubkey_import.response.json")

        val keyId =
            mono.monopay.importKey(
                ImportMonopayKeyRequest.of(keyValue, "my_key", OffsetDateTime.parse("2026-02-02T15:04:05+03:00")),
            )

        assertThat(keyId.keyId).isEqualTo("28F91hHGtzoSFJ")
        val body = takeRequest("POST", "/api/merchant/monopay/pubkey-import").json()
        assertThat(body).isEqualTo(
            io.github.damienmiheev.monobank.core.json.MonobankJson.mapper
                .readTree(fixture("POST_api_merchant_monopay_pubkey_import.request.json")),
        )
    }

    @Test
    fun `import with only the key, a missing result, and no retry`() {
        enqueueJson("{}")
        assertThat(mono.monopay.importKey(ImportMonopayKeyRequest.of(keyValue)).keyId).isNull()
        assertThat(
            takeRequest()
                .json()
                .fieldNames()
                .asSequence()
                .toList(),
        ).containsExactly("keyValue")

        enqueueJson("""{"errCode":"INTERNAL_ERROR","errText":"internal server error"}""", status = 500)
        assertThatThrownBy { mono.monopay.importKey(ImportMonopayKeyRequest.of(keyValue)) }
            .isInstanceOfSatisfying(MonobankServerException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
        assertThat(server.requestCount).isEqualTo(2)

        enqueueJson("""{"errCode":"BAD_REQUEST","errText":"empty 'keyValue'"}""", status = 400)
        assertThatThrownBy { mono.monopay.importKey(ImportMonopayKeyRequest.of("x")) }
            .isInstanceOf(MonobankBadRequestException::class.java)
        assertThatThrownBy { ImportMonopayKeyRequest.of(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `delete sends keyId`() {
        enqueueFixture("POST_api_merchant_monopay_pubkey_delete.response.json")
        mono.monopay.deleteKey("28F91hHGtzoSFJ")
        assertThat(takeRequest("POST", "/api/merchant/monopay/pubkey-delete").json().path("keyId").asText())
            .isEqualTo("28F91hHGtzoSFJ")
        enqueueJson("""{"errCode":"NOT_FOUND","errText":"key not found"}""", status = 404)
        assertThatThrownBy { mono.monopay.deleteKey("x") }.isInstanceOf(MonobankNotFoundException::class.java)
    }

    @Test
    fun `list unwraps result`() {
        enqueueFixture("GET_api_merchant_monopay_pubkey_list.response.json")
        val key = mono.monopay.keys().single()
        takeRequest("GET", "/api/merchant/monopay/pubkey-list")
        assertThat(key.keyId).isEqualTo("28F91hHGtzoSFJ")
        assertThat(key.keyValue).isEqualTo("LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0K...")
        assertThat(key.keyName).isEqualTo("my_key")
        assertThat(key.expiresAt).isEqualTo(OffsetDateTime.parse("2026-02-02T15:04:05+03:00"))
        enqueueJson("{}")
        assertThat(mono.monopay.keys()).isEmpty()
    }
}
