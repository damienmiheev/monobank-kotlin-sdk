package io.github.damienmiheev.monobank.acquiring.common

/**
 * Who can resolve a payment error, per monobank's error table.
 */
public enum class PaymentErrorParty {
    /** The bank that issued the card. */
    CARD_ISSUER,

    /** monobank (merchant settings, routing, limits, technical failures). */
    MONOBANK,

    /** The merchant's API request is wrong. */
    API,

    /** The customer (cancelled, link expired, missing card details). */
    CUSTOMER,
}

/**
 * Description of a payment error code.
 *
 * @property code The `errCode` value, e.g. `59`.
 * @property description English description.
 * @property party Who can resolve it.
 */
@ConsistentCopyVisibility
public data class PaymentErrorInfo internal constructor(
    public val code: String,
    public val description: String,
    public val party: PaymentErrorParty,
)

/**
 * Payment failure codes: the `errCode` of an invoice status or webhook (not of an HTTP error),
 * from monobank's "payment errors" table. The list is open: [describe] returns `null` for a code
 * that is not documented.
 */
public object PaymentErrorCodes {
    /** Insufficient funds on the card. */
    public const val INSUFFICIENT_FUNDS: String = "59"

    /** Wrong CVV. */
    public const val WRONG_CVV: String = "80"

    /** Payment link expired. */
    public const val PAYMENT_LINK_EXPIRED: String = "8001"

    /** The customer cancelled the payment. */
    public const val CANCELLED_BY_CUSTOMER: String = "8002"

    /** 3-D Secure problem. */
    public const val THREE_DS_PROBLEM: String = "8004"

    /** The merchant's limits for accepting payments are exceeded. */
    public const val MERCHANT_LIMIT_EXCEEDED: String = "8005"

    private val issuer = PaymentErrorParty.CARD_ISSUER
    private val mono = PaymentErrorParty.MONOBANK
    private val api = PaymentErrorParty.API
    private val customer = PaymentErrorParty.CUSTOMER

    private const val BLOCKED_BY_ISSUER = "The operation was blocked by the card issuer"
    private const val MERCHANT_SETTINGS = "Merchant (point of sale) configuration error"
    private const val TECHNICAL = "Technical failure on monobank's side"
    private const val THREE_DS_FAILED = "3-D Secure check failed"
    private const val THREE_DS_ERROR = "An error occurred during 3-D Secure"
    private const val CARD_LOST = "The card is lost; spending is restricted"
    private const val WRONG_EXPIRY = "Wrong card expiry date"

    private val table: Map<String, PaymentErrorInfo> =
        listOf(
            Triple("6", BLOCKED_BY_ISSUER, issuer),
            Triple("40", CARD_LOST, issuer),
            Triple("41", CARD_LOST, issuer),
            Triple("50", "Card spending is restricted", issuer),
            Triple("51", "The card has expired", issuer),
            Triple("52", "Wrong card number", issuer),
            Triple("54", "Technical failure at the card issuer", issuer),
            Triple("55", "Declined by the card issuer", issuer),
            Triple("56", "The card type does not support this payment", issuer),
            Triple("57", "Transaction not supported", issuer),
            Triple("58", "Card spending is restricted (for purchases)", issuer),
            Triple("59", "Insufficient funds on the card", issuer),
            Triple("60", "The card's limit on the number of debit operations is exceeded", issuer),
            Triple("61", "The card's internet limit is exceeded", issuer),
            Triple("62", "Limit of wrong PIN entries reached or exceeded", issuer),
            Triple("63", "The card's internet limit is exceeded", issuer),
            Triple("67", MERCHANT_SETTINGS, mono),
            Triple("68", "Declined by the card network", issuer),
            Triple("71", BLOCKED_BY_ISSUER, issuer),
            Triple("72", BLOCKED_BY_ISSUER, issuer),
            Triple("73", "Routing error", mono),
            Triple("74", MERCHANT_SETTINGS, mono),
            Triple("75", BLOCKED_BY_ISSUER, issuer),
            Triple("80", "Wrong CVV code", issuer),
            Triple("81", "Wrong CVV2 code", issuer),
            Triple("82", "Transaction not allowed with these conditions, or merchant configuration error", issuer),
            Triple("83", "Limit of payment attempts from cards exceeded", issuer),
            Triple("84", "Wrong 3-D Secure Cardholder Authentication Verification Value", mono),
            Triple("98", MERCHANT_SETTINGS, mono),
            Triple("1000", TECHNICAL, mono),
            Triple("1001", "Invalid request", api),
            Triple("1005", TECHNICAL, mono),
            Triple("1010", TECHNICAL, mono),
            Triple("1014", "Full card details are required for this payment", customer),
            Triple("1034", THREE_DS_FAILED, issuer),
            Triple("1035", THREE_DS_FAILED, issuer),
            Triple("1036", TECHNICAL, mono),
            Triple("1044", MERCHANT_SETTINGS, mono),
            Triple("1045", THREE_DS_FAILED, issuer),
            Triple("1053", MERCHANT_SETTINGS, mono),
            Triple("1054", THREE_DS_FAILED, mono),
            Triple("1056", "Transfers are possible only to cards of Ukrainian banks", mono),
            Triple("1064", "Only Mastercard or Visa cards are accepted", issuer),
            Triple("1066", MERCHANT_SETTINGS, mono),
            Triple("1077", "The amount is below the card network's minimum", api),
            Triple("1080", WRONG_EXPIRY, issuer),
            Triple("1090", "Customer information not found", mono),
            Triple("1115", MERCHANT_SETTINGS, mono),
            Triple("1121", MERCHANT_SETTINGS, mono),
            Triple("1145", "Below the minimum transfer amount", mono),
            Triple("1165", BLOCKED_BY_ISSUER, issuer),
            Triple("1187", "The recipient's name is required", api),
            Triple("1193", BLOCKED_BY_ISSUER, issuer),
            Triple("1194", "This top-up method works only with cards of other banks", mono),
            Triple("1200", "CVV code is required", issuer),
            Triple("1405", "The card network restricted transfers", issuer),
            Triple("1406", "The card is blocked by risk management", issuer),
            Triple("1407", "The operation was blocked by monobank's risk management", mono),
            Triple("1408", BLOCKED_BY_ISSUER, issuer),
            Triple("1411", "This kind of operation with hryvnia cards is temporarily restricted", mono),
            Triple("1413", BLOCKED_BY_ISSUER, issuer),
            Triple("1419", WRONG_EXPIRY, issuer),
            Triple("1420", TECHNICAL, mono),
            Triple("1421", THREE_DS_FAILED, issuer),
            Triple("1422", THREE_DS_ERROR, issuer),
            Triple("1425", THREE_DS_ERROR, issuer),
            Triple("1428", BLOCKED_BY_ISSUER, issuer),
            Triple("1429", THREE_DS_FAILED, issuer),
            Triple("1433", "Check the recipient's first and last name", mono),
            Triple("1436", "Cards issued in the aggressor state are not served", mono),
            Triple("1439", "Operation not allowed under the eVidnovlennia programme", mono),
            Triple("1458", "Declined at the 3-D Secure step", issuer),
            Triple("8001", "The payment link has expired", customer),
            Triple("8002", "The customer cancelled the payment", customer),
            Triple("8003", TECHNICAL, mono),
            Triple("8004", "3-D Secure problems", issuer),
            Triple("8005", "The merchant's payment acceptance limits are exceeded", mono),
            Triple("8006", "The merchant's payment acceptance limits are exceeded", mono),
        ).associate { (code, text, party) -> code to PaymentErrorInfo(code, text, party) }

    /** Describes a documented payment error code, or returns `null` for an undocumented one. */
    @JvmStatic
    public fun describe(code: String?): PaymentErrorInfo? = code?.trim()?.let { table[it] }

    /** Every documented payment error code. */
    @JvmStatic
    public fun all(): Collection<PaymentErrorInfo> = table.values
}
