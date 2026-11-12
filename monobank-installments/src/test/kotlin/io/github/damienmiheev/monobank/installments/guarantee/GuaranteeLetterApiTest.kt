package io.github.damienmiheev.monobank.installments.guarantee

import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.core.json.MonobankJson
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import io.github.damienmiheev.monobank.installments.common.CreditProductType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class GuaranteeLetterApiTest : InstallmentsTestSupport() {
    private val request = GuaranteeLetterRequest.of(ORDER_ID, "INV-002334", LocalDate.of(2024, 1, 23))

    @Test
    fun `pdf returns the exact bytes and asks for a PDF`() {
        val pdf = fixtureBytes("POST_api_order_guarantee_letter.response.pdf")
        enqueueBytes(pdf, "application/pdf")

        val bytes = chast.guaranteeLetters.pdf(request)

        assertThat(bytes).isEqualTo(pdf)
        val recorded = takeRequest("/api/order/guarantee/letter")
        assertThat(recorded.headers.values("Accept")).anyMatch { it.contains("application/pdf") }
        assertThat(MonobankJson.mapper.readTree(recorded.body.readUtf8()))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_guarantee_letter.request.json")))
    }

    @Test
    fun `pdf keeps binary bytes intact`() {
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(256) { it.toByte() }
        enqueueBytes(pdf, "application/pdf")

        assertThat(chast.guaranteeLetters.pdf(GuaranteeLetterRequest.of(ORDER_ID))).isEqualTo(pdf)
        val body = MonobankJson.mapper.readTree(takeRequest("/api/order/guarantee/letter").body.readUtf8())
        assertThat(body.has("invoice")).isFalse()
    }

    @Test
    fun `an answer that is not a PDF is a serialization error`() {
        enqueueJson("""{"status":"ok"}""")

        assertThatThrownBy { chast.guaranteeLetters.pdf(request) }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) {
                assertThat(it.outcome).isEqualTo(RequestOutcome.UNKNOWN)
            }
    }

    @Test
    fun `pdf errors are JSON and are thrown`() {
        enqueueJson("""{"message":"Помилка у запиті"}""", status = 400)

        assertThatThrownBy { chast.guaranteeLetters.pdf(request) }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorText).isEqualTo("Помилка у запиті")
            }
    }

    @Test
    fun `pdf is retried on server errors`() {
        enqueueJson("{}", status = 500)
        enqueueBytes("%PDF-1.4".toByteArray(), "application/pdf")

        assertThat(chast.guaranteeLetters.pdf(request)).startsWith(*"%PDF".toByteArray())
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `data v2 reads every field`() {
        enqueueFixture("POST_api_v2_order_data_for_guarantee_letter.response.json")

        val data = chast.guaranteeLetters.data(request)

        assertThat(takeJson("/api/v2/order/data/for/guarantee/letter"))
            .isEqualTo(
                MonobankJson.mapper.readTree(fixture("POST_api_v2_order_data_for_guarantee_letter.request.json")),
            )
        val header = data.header!!
        assertThat(header.requestId).isEqualTo(ORDER_ID)
        assertThat(header.answerDatetime).isEqualTo(OffsetDateTime.parse("2018-01-25T12:36:01+02:00"))
        assertThat(header.fromOrganization).isEqualTo("Монобанк")
        assertThat(header.organizationId).isEqualTo("21133352")
        assertThat(header.contractNumber).isEqualTo("12345678")
        assertThat(header.contractDate).isEqualTo(LocalDate.of(2018, 1, 25))
        assertExpansion(data.expansion!!)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated data v1 has no contract fields`() {
        enqueueFixture("POST_api_order_data_for_guarantee_letter.response.json")

        val data = chast.guaranteeLetters.dataV1(request)

        assertThat(takeJson("/api/order/data/for/guarantee/letter"))
            .isEqualTo(MonobankJson.mapper.readTree(fixture("POST_api_order_data_for_guarantee_letter.request.json")))
        assertThat(data.header!!.contractNumber).isNull()
        assertThat(data.header!!.contractDate).isNull()
        assertThat(data.header!!.requestId).isEqualTo(ORDER_ID)
        assertExpansion(data.expansion!!)
    }

    private fun assertExpansion(expansion: GuaranteeLetterExpansion) {
        val customer = expansion.customer!!
        assertThat(customer.firstName).isEqualTo("Олександр")
        assertThat(customer.lastName).isEqualTo("Сідоренко")
        assertThat(customer.middleName).isEqualTo("Ігорович")
        assertThat(customer.inn).isEqualTo("AA123456")
        val documents = customer.document!!
        assertThat(documents.passport).isEqualTo(documents.residencePermit)
        with(documents.passport!!) {
            assertThat(number).isEqualTo("123456")
            assertThat(issued).isEqualTo("1234")
            assertThat(dateOfIssue).isEqualTo("1901-01-29")
            assertThat(series).isEqualTo("AA")
        }
        with(documents.idCard!!) {
            assertThat(number).isEqualTo("123456")
            assertThat(issued).isEqualTo("1234")
            assertThat(dateOfIssue).isEqualTo("1901-01-29")
            assertThat(validUntil).isEqualTo(LocalDate.of(2025, 1, 25))
            assertThat(registryNumber).isEqualTo("12345678-12345")
        }
        with(documents.internationalPassport!!) {
            assertThat(number).isEqualTo("123456")
            assertThat(dateOfIssue).isEqualTo("1901-01-29")
            assertThat(series).isEqualTo("FV")
            assertThat(validUntil).isEqualTo(LocalDate.of(2025, 1, 25))
        }
        with(expansion.invoice!!) {
            assertThat(invoiceNumber).isEqualTo("1234-1234-1234")
            assertThat(invoiceDate).isEqualTo(LocalDate.of(2018, 1, 25))
            assertThat(invoiceAmount).isEqualTo(BigDecimal("1234.56"))
        }
        with(expansion.paymentDestination!!) {
            assertThat(destId).isEqualTo("12345678")
            assertThat(destName).isEqualTo("any super company")
            assertThat(destMfo).isEqualTo("123456")
            assertThat(destBankName).isEqualTo("any bank")
            assertThat(destAccNumber).isEqualTo("1234567890123")
        }
        with(expansion.bank!!) {
            assertThat(agreement).isEqualTo("123-123-1234")
            assertThat(agreementDate).isEqualTo(LocalDate.of(2020, 1, 25))
            assertThat(creditAmount).isEqualTo(BigDecimal("1234.56"))
            assertThat(customerPayAmount).isEqualTo(BigDecimal("123.35"))
            assertThat(productTypes).isEqualTo(CreditProductType.PAYMENT_INSTALLMENTS)
            assertThat(bankId).isEqualTo("12345678")
            assertThat(bankName).isEqualTo("any bank")
            assertThat(bankExecutive).isEqualTo("Сідоренко Олександр Ігорович")
            assertThat(availablePartsCount).isEqualTo(12)
            assertThat(creditProduct).isEqualTo("MONO 234")
        }
        assertThat(expansion.sign).isEqualTo("12E0F76458C6D1E4")
        assertThat(expansion.stamp).isEqualTo("1199A0F5E7C4A212")
        val printed = expansion.toString()
        assertThat(printed).doesNotContain("AA123456").doesNotContain("number=123456").doesNotContain("12345678-12345")
        assertThat(printed).contains("Олександр")
    }

    @Test
    fun `data survives unknown products and missing sections`() {
        enqueueJson("""{"header":{"request_id":"r"},"expansion":{"bank":{"product_types":"БР"}}}""")

        val data = chast.guaranteeLetters.data(GuaranteeLetterRequest.of("r"))

        assertThat(data.expansion!!.bank!!.productTypes).isEqualTo(CreditProductType.UNKNOWN)
        assertThat(data.expansion!!.customer).isNull()
        assertThat(data.header!!.answerDatetime).isNull()
    }

    @Test
    fun `request factories validate and build the invoice only when given`() {
        assertThat(GuaranteeLetterRequest.of("o", null, null).invoice).isNull()
        assertThat(GuaranteeLetterRequest.of("o", "N", null).invoice!!.number).isEqualTo("N")
        assertThatThrownBy { GuaranteeLetterRequest.of(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            GuaranteeLetterRequest.of(
                "o",
                "",
                null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
