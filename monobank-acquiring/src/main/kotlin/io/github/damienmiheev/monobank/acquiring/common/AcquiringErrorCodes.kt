package io.github.damienmiheev.monobank.acquiring.common

/**
 * Error codes (`errCode`) that the acquiring API documents for failed requests. Compare them with
 * `MonobankApiException.errorCode`. monobank may add codes, so treat this list as open.
 *
 * Payment decline codes (such as `59`) are different: they come in the invoice status `errCode`.
 */
public object AcquiringErrorCodes {
    /** 400: the request is invalid; fix the parameters. */
    public const val BAD_REQUEST: String = "BAD_REQUEST"

    /**
     * 400: the request is invalid. On `subscription/remove` it means the subscription already has a
     * payment. On `invoice/finalize` (seen live, errText "wrong amount for finalization without
     * basket") it means a partial capture without `items` on a merchant with fiscal receipts or
     * split payments.
     */
    public const val INVALID_REQUEST: String = "1001"

    /** 400: an invalid value in `merchantPaymInfo`. */
    public const val INVALID_MERCHANT_PAYM_INFO: String = "INVALID_MERCHANT_PAYM_INFO"

    /** 400: the amount set on the QR cash register is already being paid, so it cannot be reset. */
    public const val ORDER_IN_PROGRESS: String = "ORDER_IN_PROGRESS"

    /** 400: the invoice was created as a hold, paid, but not finalized yet. */
    public const val HOLD_INVOICE_NOT_FINALIZED: String = "HOLD_INVOICE_NOT_FINALIZED"

    /**
     * 400: the refund amount is wrong, or the payment was already refunded. The refund may
     * already exist: check `invoices.status(invoiceId).cancelList` before treating it as failed.
     */
    public const val WRONG_CANCEL_AMOUNT: String = "WRONG_CANCEL_AMOUNT"

    /** 400: the card token was not found. */
    public const val TOKEN_NOT_FOUND: String = "TOKEN_NOT_FOUND"

    /** 403: the `X-Token` is invalid. */
    public const val FORBIDDEN: String = "FORBIDDEN"

    /** 404: nothing was found for one of the parameters. */
    public const val NOT_FOUND: String = "NOT_FOUND"

    /**
     * 404: nothing was found for one of the parameters (numeric form). Also (verified live) the
     * answer "order not finished" to a refund of an invoice whose capture is still `processing`:
     * wait until `status(invoiceId)` is `success`, then refund. The refund was not made.
     */
    public const val NOT_FOUND_NUMERIC: String = "1004"

    /** 405: wrong HTTP method. */
    public const val METHOD_NOT_ALLOWED: String = "METHOD_NOT_ALLOWED"

    /** 429: too many requests; wait before the next one. */
    public const val TOO_MANY_REQUESTS: String = "TMR"

    /** 500: unexpected error; the outcome is unknown. */
    public const val INTERNAL_ERROR: String = "INTERNAL_ERROR"

    /**
     * 500: the payment cannot be refunded (`invoice/cancel`). The only 5xx the SDK treats as a
     * definite rejection (`RequestOutcome.REJECTED`): nothing was done.
     */
    public const val CANCEL_NOT_AVAILABLE: String = "CANCEL_NOT_AVAILABLE"

    /**
     * 500: an external system failed, e.g. `mdes.decrypt 500` when a Google Pay test token is used.
     * Not documented as a definite rejection, so the outcome stays `RequestOutcome.UNKNOWN`: a
     * payment or capture may still have happened. Reconcile before retrying.
     */
    public const val EXTERNAL_ERROR: String = "EXTERNAL_ERROR"
}
