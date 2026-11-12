package io.github.damienmiheev.monobank.core

import java.math.BigDecimal
import java.util.Currency

/**
 * ISO 4217 numeric currency codes used by monobank (`ccy` fields).
 *
 * Merchant acquiring accepts only [UAH], [USD] and [EUR]: other codes get 400 `1001`
 * "invalid 'ccy'" (verified on monobank's test merchant). USD and EUR invoices are converted to
 * UAH when they are paid: `invoice/status` and the statement then report the amount in UAH
 * (`ccy` 980), and refunds are in UAH. The personal and corporate APIs report accounts and rates
 * in many more currencies.
 */
public object CurrencyCodes {
    /** Ukrainian hryvnia. */
    public const val UAH: Int = 980

    /** US dollar. */
    public const val USD: Int = 840

    /** Euro. */
    public const val EUR: Int = 978

    /** Polish zloty. */
    public const val PLN: Int = 985

    /** British pound. */
    public const val GBP: Int = 826
}

/**
 * Converts between minor units (kopiyky, cents) — which the acquiring, personal and corporate
 * APIs use for every amount — and major units (hryvnias, dollars).
 *
 * The number of minor-unit digits comes from ISO 4217 via [java.util.Currency]: 2 for UAH,
 * USD and EUR, 0 for JPY, 3 for KWD. The table is the running JDK's ISO 4217 data.
 */
public object MinorUnits {
    private val byNumericCode: Map<Int, Currency> by lazy {
        Currency.getAvailableCurrencies().associateBy { it.numericCode }
    }

    /**
     * Returns the number of minor-unit digits for an ISO 4217 numeric code.
     *
     * @throws IllegalArgumentException for an unknown code.
     */
    @JvmStatic
    public fun fractionDigits(currencyCode: Int): Int {
        val currency = requireNotNull(byNumericCode[currencyCode]) { "Unknown ISO 4217 currency code: $currencyCode" }
        val digits = currency.defaultFractionDigits
        require(digits >= 0) { "Currency $currencyCode (${currency.currencyCode}) has no minor unit" }
        return digits
    }

    /**
     * Converts a minor-unit amount to major units: `toMajor(4200, 980)` is `42.00`.
     */
    @JvmStatic
    public fun toMajor(
        amountMinor: Long,
        currencyCode: Int,
    ): BigDecimal = BigDecimal.valueOf(amountMinor, fractionDigits(currencyCode))

    /**
     * Converts a major-unit amount to minor units: `toMinor(BigDecimal("42.00"), 980)` is `4200`.
     *
     * @throws ArithmeticException when the amount has more decimals than the currency allows
     *   (for example `42.001` UAH) or does not fit in a [Long]. The SDK never rounds money.
     */
    @JvmStatic
    public fun toMinor(
        amountMajor: BigDecimal,
        currencyCode: Int,
    ): Long = amountMajor.movePointRight(fractionDigits(currencyCode)).longValueExact()
}
