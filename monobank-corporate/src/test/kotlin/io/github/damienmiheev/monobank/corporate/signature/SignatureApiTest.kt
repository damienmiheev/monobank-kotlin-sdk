package io.github.damienmiheev.monobank.corporate.signature

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankServerException
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.corporate.CorporateTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class SignatureApiTest : CorporateTestSupport() {
    private val hash = "A421FD4D4AB19BE76EC02A0F84AC2379822943FE85EB6ED7F22B30F73CB9CAF9"

    @Test
    fun `create sends the documents and reads the deeplink`() {
        enqueueFixture("POST_personal_signature_create.response.json")

        val response =
            mono.signatures.create(
                CreateSignatureRequest
                    .builder(
                        listOf(
                            SignDocument
                                .builder("Договір на поставку товарів", hash)
                                .hashType(HashType.GOST)
                                .type(DocumentType.PDF)
                                .link("https://example.com/agreement.pdf")
                                .build(),
                        ),
                    ).oneSigner(true)
                    .callbackUrl("https://example.com/some_random_data_for_security")
                    .build(),
            )

        assertThat(response.requestId).isEqualTo("sGtN4FnxYORZQU5Me1HbYhQ")
        assertThat(response.deeplink).isEqualTo("https://mbnk.app/sign/sGtN4FnxYORZQU5Me1HbYhQ")
        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/personal/signature/create")
        assertThat(request.getHeader("X-Request-Id")).isNull()
        assertThat(MonobankJson.mapper.readTree(request.body.readUtf8()))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_personal_signature_create.request.json")))
    }

    @Test
    fun `create leaves out optional fields and is not retried`() {
        enqueueJson("{}", status = 500)

        assertThatThrownBy {
            mono.signatures.create(
                CreateSignatureRequest.builder(listOf(SignDocument.builder("d", "ab01").build())).build(),
            )
        }.isInstanceOf(MonobankServerException::class.java)

        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"documents":[{"name":"d","hash":"ab01"}]}""")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `request builders validate before sending`() {
        val doc = SignDocument.builder("d", "ab").build()
        assertThatThrownBy { CreateSignatureRequest.builder(emptyList()).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CreateSignatureRequest.builder(List(11) { doc }).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CreateSignatureRequest.builder(List(10) { doc }).build().documents).hasSize(10)
        assertThatThrownBy {
            SignDocument
                .builder(
                    "d",
                    "xyz",
                ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SignDocument
                .builder(
                    " ",
                    "ab",
                ).build()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SignDocument.builder("d", "ab").hashType(HashType.UNKNOWN).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SignDocument.builder("d", "ab").type(DocumentType.UNKNOWN).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(DocumentType.values().map { it.value }).containsExactly(
            "pdf",
            "doc",
            "docx",
            "odt",
            "json",
            "xml",
            "html",
            "png",
            "jpg",
            "jpeg",
            "other",
            "unknown",
        )
    }

    @Test
    fun `status reads every documented field`() {
        enqueueFixture("GET_personal_signature_status.response.json")

        val status = mono.signatures.status("sGtN4FnxYORZQU5Me1HbYhQ")

        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/personal/signature/status?requestId=sGtN4FnxYORZQU5Me1HbYhQ")
        assertStatusFixture(status)
    }

    @Test
    fun `status survives unknown enums and missing optional fields, and is retried`() {
        enqueueJson("{}", status = 503)
        enqueueJson(
            """{"documents":[{"hash":"ab","status":"revoked","hashType":"Sha3","type":"tiff",
               "signers":[{"name":"x"}]}]}""",
        )

        val status = mono.signatures.status("r")

        val document = status.documents!!.single()
        assertThat(status.requestId).isNull()
        assertThat(document.status).isEqualTo(SigningStatus.UNKNOWN)
        assertThat(document.status!!.isFinal).isFalse()
        assertThat(document.hashType).isEqualTo(HashType.UNKNOWN)
        assertThat(document.type).isEqualTo(DocumentType.UNKNOWN)
        assertThat(document.name).isNull()
        assertThat(document.signers!!.single().date).isNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `cancel sends DELETE with the request id`() {
        enqueueFixture("DELETE_personal_signature_cancel.response.json")

        mono.signatures.cancel("sGtN4FnxYORZQU5Me1HbYhQ")

        val request = takeSignedRequest(::timeAndPath)
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.path).isEqualTo("/personal/signature/cancel?requestId=sGtN4FnxYORZQU5Me1HbYhQ")
        assertThatThrownBy { mono.signatures.cancel("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { mono.signatures.status(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `errors carry errorDescription`() {
        enqueueJson("""{"errorDescription":"Request not found"}""", status = 400)

        assertThatThrownBy { mono.signatures.cancel("x") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("Request not found")
            }
    }

    @Test
    fun `callback bodies parse like the status answer`() {
        assertStatusFixture(SignatureStatus.fromJson(fixture("GET_personal_signature_status.response.json")))
        assertThatThrownBy { SignatureStatus.fromJson("not json") }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isNull()
            }
        assertThat(SigningStatus.SIGNED.isFinal).isTrue()
        assertThat(SigningStatus.PENDING.isFinal).isFalse()
    }

    private fun assertStatusFixture(status: SignatureStatus) {
        assertThat(status.requestId).isEqualTo("sGtN4FnxYORZQU5Me1HbYhQ")
        val document = status.documents!!.single()
        assertThat(document.status).isEqualTo(SigningStatus.SIGNED)
        assertThat(document.name).isEqualTo("Договір на поставку товарів")
        assertThat(document.hash).isEqualTo(hash)
        assertThat(document.hashType).isEqualTo(HashType.GOST)
        assertThat(document.type).isEqualTo(DocumentType.PDF)
        assertThat(document.link).isEqualTo("https://example.com/agreement.pdf")
        val signer = document.signers!!.single()
        assertThat(signer.name).isEqualTo("Шевченко Роман Петрович")
        assertThat(signer.tin).isEqualTo("1234567890")
        assertThat(signer.certSerial).isEqualTo("382367105294AF970400000058B38300BAE33C02")
        assertThat(signer.signature).startsWith("MIIJQwIBADAN")
        assertThat(signer.date).isEqualTo(OffsetDateTime.parse("2025-01-21T18:15:00Z"))
        assertThat(signer.edrpou).isEqualTo("12345678")
        assertThat(signer.company).isEqualTo("ТОВ \"Сорока\"")
        assertThat(signer.post).isEqualTo("Директор")
        assertThat(signer.toString()).doesNotContain("1234567890").contains("***90")
    }
}
