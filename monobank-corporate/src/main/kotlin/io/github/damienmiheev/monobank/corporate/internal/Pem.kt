package io.github.damienmiheev.monobank.corporate.internal

import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Base64

/** PEM reading for keys (BouncyCastle's `PemReader`, part of bcprov). */
internal object Pem {
    const val BEGIN: String = "-----BEGIN"

    /** [text] itself when it is PEM, otherwise the PEM text it is the base64 of. */
    fun textOf(text: String): String = if (text.contains(BEGIN)) text else decodeBase64Pem(text)

    private fun decodeBase64Pem(value: String): String {
        val bytes =
            try {
                Base64.getMimeDecoder().decode(value.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("not PEM text and not base64 of PEM text", e)
            }
        val decoded = String(bytes, StandardCharsets.US_ASCII)
        Arrays.fill(bytes, 0)
        require(decoded.contains(BEGIN)) { "not PEM text and not base64 of PEM text" }
        return decoded
    }

    /** All PEM blocks in [text]. */
    fun readObjects(text: String): List<PemObject> {
        val reader = PemReader(StringReader(text))
        return try {
            generateSequence { reader.readPemObject() }.toList()
        } catch (e: IOException) {
            throw IllegalArgumentException("malformed PEM: ${e.javaClass.simpleName}", e)
        }
    }
}
