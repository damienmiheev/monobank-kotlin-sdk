package io.github.damienmiheev.monobank.installments.guarantee

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.core.Redaction
import io.github.damienmiheev.monobank.installments.common.CreditProductType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Guarantee letter data (`OrderDataHeaderV2`, or `OrderDataHeader` from the deprecated v1 endpoint, which has no
 * contract fields in [header]).
 *
 * The letter contains the client's personal data: store it as such. [toString] hides the tax number and document
 * numbers.
 *
 * @property header Who issued the letter and when.
 * @property expansion Client, invoice, payee and credit details, with the bank's signature and seal.
 */
@ConsistentCopyVisibility
public data class GuaranteeLetterData
    @JsonCreator
    internal constructor(
        @param:JsonProperty("header") public val header: GuaranteeLetterHeader?,
        @param:JsonProperty("expansion") public val expansion: GuaranteeLetterExpansion?,
    )

/**
 * Header of a guarantee letter.
 *
 * @property requestId Order id (UUID).
 * @property answerDatetime When the letter was generated (sent without an offset; read as Kyiv time, which monobank's
 *   docs do not state).
 * @property fromOrganization Name of the lending bank.
 * @property organizationId EDRPOU code of the lending bank.
 * @property contractNumber Number of the contract between the bank and the store (v2 only).
 * @property contractDate Date of that contract (v2 only).
 */
@ConsistentCopyVisibility
public data class GuaranteeLetterHeader
    @JsonCreator
    internal constructor(
        @param:JsonProperty("request_id") public val requestId: String?,
        @param:JsonProperty("answer_datetime") public val answerDatetime: OffsetDateTime?,
        @param:JsonProperty("from_organization") public val fromOrganization: String?,
        @param:JsonProperty("organization_id") public val organizationId: String?,
        @param:JsonProperty("contract_number") public val contractNumber: String?,
        @param:JsonProperty("contract_date") public val contractDate: LocalDate?,
    )

/**
 * Body of a guarantee letter. [toString] prints only the lengths of [sign] and [stamp].
 *
 * @property customer The client.
 * @property invoice The invoice the credit pays for.
 * @property paymentDestination Account the money is paid to.
 * @property bank Credit details.
 * @property sign Bank's digital signature (Base64, detached CMS, DSTU 4145) over the fields listed in monobank's docs.
 * @property stamp Bank's electronic seal (Base64).
 */
@ConsistentCopyVisibility
public data class GuaranteeLetterExpansion
    @JsonCreator
    internal constructor(
        @param:JsonProperty("customer") public val customer: GuaranteeCustomer?,
        @param:JsonProperty("invoice") public val invoice: GuaranteeInvoice?,
        @param:JsonProperty("payment_destination") public val paymentDestination: GuaranteePaymentDestination?,
        @param:JsonProperty("bank") public val bank: GuaranteeBank?,
        @param:JsonProperty("sign") public val sign: String?,
        @param:JsonProperty("stamp") public val stamp: String?,
    ) {
        override fun toString(): String =
            "GuaranteeLetterExpansion(customer=$customer, invoice=$invoice, paymentDestination=$paymentDestination, " +
                "bank=$bank, sign=${sign?.let {
                    "<${it.length} chars>"
                }}, stamp=${stamp?.let { "<${it.length} chars>" }})"
    }

/**
 * The client on a guarantee letter. [toString] hides [inn].
 *
 * @property firstName First name.
 * @property lastName Last name.
 * @property middleName Patronymic.
 * @property inn Tax number (10 or 9 digits, or document series and number when the client has none).
 * @property document The client's identity document.
 */
@ConsistentCopyVisibility
public data class GuaranteeCustomer
    @JsonCreator
    internal constructor(
        @param:JsonProperty("first_name") public val firstName: String?,
        @param:JsonProperty("last_name") public val lastName: String?,
        @param:JsonProperty("middle_name") public val middleName: String?,
        @param:JsonProperty("inn") public val inn: String?,
        @param:JsonProperty("document") public val document: ClientDocuments?,
    ) {
        override fun toString(): String =
            "GuaranteeCustomer(firstName=$firstName, lastName=$lastName, middleName=$middleName, " +
                "inn=${Redaction.secret(inn)}, document=$document)"
    }

/**
 * The client's identity document; normally exactly one field is set.
 *
 * @property passport Ukrainian booklet passport.
 * @property idCard Ukrainian ID card.
 * @property residencePermit Residence permit.
 * @property internationalPassport International (foreign travel) passport.
 */
@ConsistentCopyVisibility
public data class ClientDocuments
    @JsonCreator
    internal constructor(
        @param:JsonProperty("passport") public val passport: PassportDocument?,
        @param:JsonProperty("id_card") public val idCard: IdCardDocument?,
        @param:JsonProperty("residence_permit") public val residencePermit: PassportDocument?,
        @param:JsonProperty("international_passport") public val internationalPassport: InternationalPassport?,
    )

/**
 * A booklet passport or a residence permit. [toString] hides [number].
 *
 * @property number Document number.
 * @property issued Issuing authority.
 * @property dateOfIssue Issue date, exactly as sent (normally `YYYY-MM-DD`).
 * @property series Document series.
 */
@ConsistentCopyVisibility
public data class PassportDocument
    @JsonCreator
    internal constructor(
        @param:JsonProperty("number") public val number: String?,
        @param:JsonProperty("issued") public val issued: String?,
        @param:JsonProperty("date_of_issue") public val dateOfIssue: String?,
        @param:JsonProperty("series") public val series: String?,
    ) {
        override fun toString(): String =
            "PassportDocument(number=${Redaction.secret(number)}, issued=$issued, dateOfIssue=$dateOfIssue, " +
                "series=$series)"
    }

/**
 * An ID card. [toString] hides [number] and [registryNumber].
 *
 * @property number Document number.
 * @property issued Issuing authority.
 * @property dateOfIssue Issue date, exactly as sent (normally `YYYY-MM-DD`).
 * @property validUntil Expiry date, exactly as sent (normally `YYYY-MM-DD`).
 * @property registryNumber Record number in the population register (13 digits, or 8 digits, a dash and 5 digits).
 */
@ConsistentCopyVisibility
public data class IdCardDocument
    @JsonCreator
    internal constructor(
        @param:JsonProperty("number") public val number: String?,
        @param:JsonProperty("issued") public val issued: String?,
        @param:JsonProperty("date_of_issue") public val dateOfIssue: String?,
        @param:JsonProperty("valid_until") public val validUntil: String?,
        @param:JsonProperty("registry_number") public val registryNumber: String?,
    ) {
        override fun toString(): String =
            "IdCardDocument(number=${Redaction.secret(number)}, issued=$issued, dateOfIssue=$dateOfIssue, " +
                "validUntil=$validUntil, registryNumber=${Redaction.secret(registryNumber)})"
    }

/**
 * An international passport. [toString] hides [number].
 *
 * @property number Document number.
 * @property dateOfIssue Issue date, exactly as sent (normally `YYYY-MM-DD`).
 * @property series Document series.
 * @property validUntil Expiry date, exactly as sent (normally `YYYY-MM-DD`).
 */
@ConsistentCopyVisibility
public data class InternationalPassport
    @JsonCreator
    internal constructor(
        @param:JsonProperty("number") public val number: String?,
        @param:JsonProperty("date_of_issue") public val dateOfIssue: String?,
        @param:JsonProperty("series") public val series: String?,
        @param:JsonProperty("valid_until") public val validUntil: String?,
    ) {
        override fun toString(): String =
            "InternationalPassport(number=${Redaction.secret(number)}, dateOfIssue=$dateOfIssue, series=$series, " +
                "validUntil=$validUntil)"
    }

/**
 * Invoice on a guarantee letter.
 *
 * @property invoiceNumber Invoice number.
 * @property invoiceDate Invoice date.
 * @property invoiceAmount Invoice amount in hryvnias, decimal.
 */
@ConsistentCopyVisibility
public data class GuaranteeInvoice
    @JsonCreator
    internal constructor(
        @param:JsonProperty("invoice_number") public val invoiceNumber: String?,
        @param:JsonProperty("invoice_date") public val invoiceDate: LocalDate?,
        @param:JsonProperty("invoice_amount") public val invoiceAmount: BigDecimal?,
    )

/**
 * Account the credit is paid to.
 *
 * @property destId EDRPOU code of the receiving company.
 * @property destName Name of the receiving company.
 * @property destMfo MFO code of the receiving bank.
 * @property destBankName Name of the receiving bank.
 * @property destAccNumber Receiving account number.
 */
@ConsistentCopyVisibility
public data class GuaranteePaymentDestination
    @JsonCreator
    internal constructor(
        @param:JsonProperty("dest_id") public val destId: String?,
        @param:JsonProperty("dest_name") public val destName: String?,
        @param:JsonProperty("dest_mfo") public val destMfo: String?,
        @param:JsonProperty("dest_bank_name") public val destBankName: String?,
        @param:JsonProperty("dest_acc_number") public val destAccNumber: String?,
    )

/**
 * Credit details on a guarantee letter.
 *
 * @property agreement Order id in the bank's system; the credit agreement number.
 * @property agreementDate Date the bank approved the credit.
 * @property creditAmount Approved credit amount in hryvnias, decimal.
 * @property customerPayAmount Approved down payment in hryvnias, decimal.
 * @property productTypes Credit product (currently only [CreditProductType.PAYMENT_INSTALLMENTS]).
 * @property bankId EDRPOU code of the lending bank.
 * @property bankName Name of the lending bank.
 * @property bankExecutive Full name of the bank's authorised person.
 * @property availablePartsCount Number of payments.
 * @property creditProduct Credit product name in the store's program.
 */
@ConsistentCopyVisibility
public data class GuaranteeBank
    @JsonCreator
    internal constructor(
        @param:JsonProperty("agreement") public val agreement: String?,
        @param:JsonProperty("agreement_date") public val agreementDate: LocalDate?,
        @param:JsonProperty("credit_amount") public val creditAmount: BigDecimal?,
        @param:JsonProperty("customer_pay_amount") public val customerPayAmount: BigDecimal?,
        @param:JsonProperty("product_types") public val productTypes: CreditProductType?,
        @param:JsonProperty("bank_id") public val bankId: String?,
        @param:JsonProperty("bank_name") public val bankName: String?,
        @param:JsonProperty("bank_executive") public val bankExecutive: String?,
        @param:JsonProperty("available_parts_count") public val availablePartsCount: Int?,
        @param:JsonProperty("credit_product") public val creditProduct: String?,
    )
