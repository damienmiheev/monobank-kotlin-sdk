package io.github.damienmiheev.monobank.installments.guarantee

import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.RequestOutcome
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import java.nio.charset.StandardCharsets

/**
 * Guarantee letters: the bank's confirmation that it pays the store for an order, as a PDF or as data.
 *
 * monobank also serves these endpoints as XML; this SDK always uses JSON.
 */
public class GuaranteeLetterApi internal constructor(
    private val http: InstallmentsHttp,
) {
    /**
     * Downloads the guarantee letter as a PDF document.
     *
     * `POST /api/order/guarantee/letter`. Retried (read-only).
     *
     * @return The PDF file's bytes.
     * @throws MonobankSerializationException when the answer is not a PDF.
     */
    public fun pdf(request: GuaranteeLetterRequest): ByteArray {
        val body =
            http
                .execute(
                    "/api/order/guarantee/letter",
                    request,
                    idempotent = true,
                    headers = mapOf("Accept" to "application/pdf"),
                ).body
        if (!looksLikePdf(body)) {
            throw MonobankSerializationException(
                "monobank answered POST /api/order/guarantee/letter with something that is not a PDF " +
                    "(${body.size} bytes)",
                null,
                RequestOutcome.UNKNOWN,
            )
        }
        return body
    }

    /**
     * Guarantee letter data, including the store contract number and date.
     *
     * `POST /api/v2/order/data/for/guarantee/letter`. Retried (read-only).
     */
    public fun data(request: GuaranteeLetterRequest): GuaranteeLetterData =
        http.post(
            "/api/v2/order/data/for/guarantee/letter",
            request,
            GuaranteeLetterData::class.java,
            idempotent = true,
        )

    /**
     * Guarantee letter data without the contract fields (legacy).
     *
     * `POST /api/order/data/for/guarantee/letter`. Retried (read-only). Deprecated by monobank in favour of the v2
     * endpoint ([data]).
     */
    @Deprecated(
        "Deprecated by monobank. Use data(request) (POST /api/v2/order/data/for/guarantee/letter).",
        ReplaceWith("data(request)"),
    )
    public fun dataV1(request: GuaranteeLetterRequest): GuaranteeLetterData =
        http.post(
            "/api/order/data/for/guarantee/letter",
            request,
            GuaranteeLetterData::class.java,
            idempotent = true,
        )

    private companion object {
        /** PDF readers accept the `%PDF-` header anywhere in the first 1024 bytes. */
        const val PDF_HEADER_WINDOW = 1024
        val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)

        fun looksLikePdf(body: ByteArray): Boolean {
            val limit = minOf(body.size, PDF_HEADER_WINDOW) - PDF_MAGIC.size
            return (0..limit).any { start -> PDF_MAGIC.indices.all { body[start + it] == PDF_MAGIC[it] } }
        }
    }
}
