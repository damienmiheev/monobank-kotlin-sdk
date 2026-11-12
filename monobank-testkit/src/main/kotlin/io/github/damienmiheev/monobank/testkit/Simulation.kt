package io.github.damienmiheev.monobank.testkit

/**
 * What happens to the card when a customer pays an invoice created with `saveCardData.saveCard = true`.
 */
public enum class CardSaving {
    /**
     * The card is saved: a separate card webhook moves `walletData.status` from `new` to `created`,
     * and the token can then be charged with `wallet/payment` and is listed by `GET /api/merchant/wallet`.
     */
    SAVED,

    /** The card could not be saved: a separate card webhook reports `walletData.status = failed`. */
    FAILED,
}

/**
 * How the simulated customer pays an invoice in [FakeMonobankAcquiring.payInvoice]: approved or
 * declined, with which card, and what happens to card saving.
 *
 * ```kotlin
 * fake.payInvoice(invoiceId, CustomerPayment.declined("59", "Неправильний CVV код"))
 * val mastercard = CustomerPayment.builder().maskedPan("537541******1234").paymentSystem("mastercard").build()
 * fake.payInvoice(invoiceId, mastercard)
 * ```
 *
 * @property approved `true` for a successful payment (or hold), `false` for a decline.
 * @property errCode Payment error code reported for a decline (monobank's "payment errors" list).
 * @property failureReason Human-readable decline reason.
 * @property maskedPan Masked card number; also identifies the card for card saving (the same
 *   masked number saved twice into one wallet keeps one token).
 * @property paymentSystem `visa` or `mastercard`.
 * @property paymentMethod `pan`, `apple`, `google`, `monobank`, `wallet` or `direct`.
 * @property bank Card issuer name.
 * @property country Card issuer country, ISO 3166-1 numeric.
 * @property cardSaving Card saving result, for invoices that save the card.
 */
public class CustomerPayment private constructor(
    builder: Builder,
) {
    public val approved: Boolean = builder.approved
    public val errCode: String? = builder.errCode
    public val failureReason: String? = builder.failureReason
    public val maskedPan: String = builder.maskedPan
    public val paymentSystem: String = builder.paymentSystem
    public val paymentMethod: String = builder.paymentMethod
    public val bank: String? = builder.bank
    public val country: String? = builder.country
    public val cardSaving: CardSaving = builder.cardSaving

    override fun toString(): String =
        "CustomerPayment(approved=$approved, errCode=$errCode, maskedPan=$maskedPan, " +
            "paymentSystem=$paymentSystem, paymentMethod=$paymentMethod, cardSaving=$cardSaving)"

    /** Builder for [CustomerPayment]; starts as an approved Visa payment with card saving succeeding. */
    public class Builder internal constructor() {
        internal var approved: Boolean = true
        internal var errCode: String? = null
        internal var failureReason: String? = null
        internal var maskedPan: String = DEFAULT_MASKED_PAN
        internal var paymentSystem: String = "visa"
        internal var paymentMethod: String = "pan"
        internal var bank: String? = "Універсал Банк"
        internal var country: String? = "804"
        internal var cardSaving: CardSaving = CardSaving.SAVED

        /** Declines the payment with [errCode] and [failureReason]. */
        public fun declined(
            errCode: String,
            failureReason: String,
        ): Builder =
            apply {
                approved = false
                this.errCode = errCode
                this.failureReason = failureReason
            }

        /** Masked card number, e.g. `444403******1902`. */
        public fun maskedPan(maskedPan: String): Builder =
            apply {
                require(maskedPan.isNotBlank()) { "maskedPan must not be blank" }
                this.maskedPan = maskedPan
            }

        /** Card network: `visa` or `mastercard`. */
        public fun paymentSystem(paymentSystem: String): Builder = apply { this.paymentSystem = paymentSystem }

        /** Payment method: `pan`, `apple`, `google`, `monobank`, `wallet` or `direct`. */
        public fun paymentMethod(paymentMethod: String): Builder = apply { this.paymentMethod = paymentMethod }

        /** Card issuer name; `null` omits it. */
        public fun bank(bank: String?): Builder = apply { this.bank = bank }

        /** Card issuer country (ISO 3166-1 numeric); `null` omits it. */
        public fun country(country: String?): Builder = apply { this.country = country }

        /** Card saving result for invoices that save the card. */
        public fun cardSaving(cardSaving: CardSaving): Builder = apply { this.cardSaving = cardSaving }

        /** Builds the payment. */
        public fun build(): CustomerPayment = CustomerPayment(this)
    }

    public companion object {
        /** The masked card number used when none is given (monobank's documentation example). */
        public const val DEFAULT_MASKED_PAN: String = "444403******1902"

        /** Payment error code used by [declined] without arguments: "wrong CVV" in monobank's examples. */
        public const val DEFAULT_DECLINE_CODE: String = "59"

        /** Starts a payment description. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** An approved payment with the default card, card saving succeeding. */
        @JvmStatic
        public fun approved(): CustomerPayment = Builder().build()

        /** A declined payment with [errCode] and [failureReason]. */
        @JvmStatic
        public fun declined(
            errCode: String,
            failureReason: String,
        ): CustomerPayment = Builder().declined(errCode, failureReason).build()

        /** A payment declined with code `59` ("wrong CVV"), as in monobank's examples. */
        @JvmStatic
        public fun declined(): CustomerPayment = declined(DEFAULT_DECLINE_CODE, "Неправильний CVV код")
    }
}

/**
 * The result of the next `POST /api/merchant/wallet/payment` (a charge of a saved card),
 * queued with [FakeMonobankAcquiring.enqueueWalletPaymentOutcome]. Without a queued outcome
 * the charge succeeds.
 */
public class WalletPaymentOutcome private constructor(
    internal val kind: Kind,
    internal val errCode: String?,
    internal val failureReason: String?,
) {
    internal enum class Kind { SUCCESS, DECLINED, PROCESSING, THREE_DS }

    override fun toString(): String = "WalletPaymentOutcome($kind${errCode?.let { ", errCode=$it" } ?: ""})"

    public companion object {
        /**
         * The charge succeeds at once: the answer says `success` and `invoice/status` says `success`.
         * For a hold the answer says `processing` (verified live) and `invoice/status` says `hold`.
         */
        @JvmStatic
        public fun success(): WalletPaymentOutcome = WalletPaymentOutcome(Kind.SUCCESS, null, null)

        /** The charge is declined at once with [errCode] and [failureReason]. */
        @JvmStatic
        public fun declined(
            errCode: String,
            failureReason: String,
        ): WalletPaymentOutcome = WalletPaymentOutcome(Kind.DECLINED, errCode, failureReason)

        /** The charge is declined with code `59` ("wrong CVV"). */
        @JvmStatic
        public fun declined(): WalletPaymentOutcome =
            declined(CustomerPayment.DEFAULT_DECLINE_CODE, "Неправильний CVV код")

        /**
         * The answer is `processing`; the invoice stays `processing` until
         * [FakeMonobankAcquiring.payInvoice] (or [FakeMonobankAcquiring.expireInvoice]) settles it.
         */
        @JvmStatic
        public fun processing(): WalletPaymentOutcome = WalletPaymentOutcome(Kind.PROCESSING, null, null)

        /**
         * 3-D Secure is required: the answer is `processing` with a `tdsUrl`, and the invoice stays
         * `processing` until [FakeMonobankAcquiring.payInvoice] (customer passed 3-D Secure) or
         * [FakeMonobankAcquiring.expireInvoice] (customer never did).
         */
        @JvmStatic
        public fun threeDs(): WalletPaymentOutcome = WalletPaymentOutcome(Kind.THREE_DS, null, null)
    }
}

/** When [FakeMonobankAcquiring] sends webhooks. */
public enum class WebhookDelivery {
    /** Webhooks are sent in the background, in order, right after each change (plus any configured delay). */
    AUTOMATIC,

    /** Webhooks wait in a queue until [FakeMonobankAcquiring.deliverWebhooksNow] (or its variants) sends them. */
    MANUAL,
}

/**
 * How refunds and hold releases (`POST /api/merchant/invoice/cancel`) complete, and what the
 * synchronous answer says. monobank documents all three answer statuses (`processing`, `success`,
 * `failure`) but not when each is used, so the fake lets the test choose.
 */
public enum class RefundSettlement {
    /**
     * The answer says `processing`, and the refund completes (`success`) immediately afterwards,
     * with a webhook. The default.
     */
    AUTOMATIC,

    /**
     * The answer says `processing`, and the refund stays `processing` until
     * [FakeMonobankAcquiring.settleRefund].
     */
    MANUAL,

    /** The refund completes before the answer, which says `success`; a webhook follows. */
    IMMEDIATE_SUCCESS,

    /** The refund fails before the answer, which says `failure`; a webhook follows and nothing is refunded. */
    IMMEDIATE_FAILURE,
}
