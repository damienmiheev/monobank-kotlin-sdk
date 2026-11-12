package io.github.damienmiheev.monobank.core

/**
 * Helpers that keep secrets and card data out of `toString()` output and logs.
 */
@InternalMonobankApi
public object Redaction {
    private val PRINTABLE_ASCII = 0x21..0x7E

    /**
     * Checks that a credential can be sent as an HTTP header value: printable ASCII without
     * spaces. Rejecting it here keeps the value out of the JDK's "invalid header value" message.
     *
     * @throws IllegalArgumentException naming [what], never echoing the value.
     */
    public fun requireHeaderSafe(
        value: String,
        what: String,
    ): String {
        require(value.isNotEmpty()) { "$what must not be empty" }
        require(value.all { it.code in PRINTABLE_ASCII }) { "$what contains characters not allowed in an HTTP header" }
        return value
    }

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
