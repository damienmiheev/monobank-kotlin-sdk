package io.github.damienmiheev.monobank.personal.client

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Account (card) type. monobank adds new card types from time to time; values this SDK version
 * does not know are read as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class AccountType(
    @get:JsonValue public val value: String,
) {
    /** Black card. */
    BLACK("black"),

    /** White card. */
    WHITE("white"),

    /** Platinum card. */
    PLATINUM("platinum"),

    /** Iron card. */
    IRON("iron"),

    /** Account of an individual entrepreneur (ФОП). */
    FOP("fop"),

    /** Yellow (children's) card. */
    YELLOW("yellow"),

    /** eAid (єПідтримка) card for government payments. */
    E_AID("eAid"),

    /** A type this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Kind of cashback credited to an account. Unknown future values are read as [UNKNOWN].
 *
 * @property value Wire value.
 */
public enum class CashbackType(
    @get:JsonValue public val value: String,
) {
    /** No cashback. */
    NONE("None"),

    /** Cashback in hryvnias. */
    UAH("UAH"),

    /** Cashback in miles. */
    MILES("Miles"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}
