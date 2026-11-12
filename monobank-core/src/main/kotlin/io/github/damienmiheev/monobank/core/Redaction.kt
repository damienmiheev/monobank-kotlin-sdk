package io.github.damienmiheev.monobank.core

/**
 * Helpers that keep secrets and card data out of `toString()` output and logs.
 */
@InternalMonobankApi
public object Redaction {
    /** Placeholder for a fully hidden value. */
    public const val HIDDEN: String = "***"

    /** Hides a secret completely; `null` stays `null`. */
    public fun secret(value: String?): String? = value?.let { HIDDEN }

    /** Keeps the last [visible] characters, e.g. a card token `***a1b2`. */
    public fun tail(
        value: String?,
        visible: Int = 4,
    ): String? =
        value?.let {
            if (it.length <= visible * 2) HIDDEN else HIDDEN + it.takeLast(visible)
        }
}
