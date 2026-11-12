package io.github.damienmiheev.monobank.acquiring.invoice

import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import java.util.Base64

/** Decodes the base64 PDF files that receipts and fiscal checks carry. */
internal object Base64Pdf {
    fun decode(base64: String): ByteArray =
        try {
            // Strict decoder after dropping line breaks that some encoders insert.
            Base64.getDecoder().decode(base64.filterNot { it.isWhitespace() })
        } catch (e: IllegalArgumentException) {
            throw MonobankSerializationException(
                "monobank returned a file that is not valid base64",
                e,
                RequestOutcome.UNKNOWN,
            )
        }
}
