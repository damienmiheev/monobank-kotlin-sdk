package io.github.damienmiheev.monobank.installments.internal

import java.math.BigDecimal

/**
 * Checks a hryvnia amount: positive (or zero when [allowZero]) and without fractions of a kopiyka.
 */
internal fun requireHryvnias(
    value: BigDecimal,
    name: String,
    allowZero: Boolean = false,
): BigDecimal {
    if (allowZero) {
        require(value.signum() >= 0) { "$name must not be negative (hryvnias)" }
    } else {
        require(value.signum() > 0) { "$name must be positive (hryvnias)" }
    }
    require(value.stripTrailingZeros().scale() <= 2) {
        "$name must have at most 2 decimal places: it is in hryvnias, not kopiyky"
    }
    return value
}

internal fun requireNotBlank(
    value: String,
    name: String,
): String {
    require(value.isNotBlank()) { "$name must not be blank" }
    return value
}

/** Masks a phone for `toString()`: `+380*******67`. */
internal fun maskPhone(phone: String?): String? {
    if (phone == null) return null
    val visible = PHONE_PREFIX + PHONE_SUFFIX
    if (phone.length <= visible + 2) return "***"
    return phone.take(PHONE_PREFIX) + "*".repeat(phone.length - visible) + phone.takeLast(PHONE_SUFFIX)
}

private const val PHONE_PREFIX = 4
private const val PHONE_SUFFIX = 2
