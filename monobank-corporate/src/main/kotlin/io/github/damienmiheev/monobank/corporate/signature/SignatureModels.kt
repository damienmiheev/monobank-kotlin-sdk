package io.github.damienmiheev.monobank.corporate.signature

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.core.Redaction
import io.github.damienmiheev.monobank.core.json.MonobankJson
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

/**
 * Algorithm the document hash was computed with. Unknown future values are read as [UNKNOWN].
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 *
 * @property value Wire value.
 */
@ExperimentalMonobankApi
public enum class HashType(
    @get:JsonValue public val value: String,
) {
    /**
     * GOST 34.311-95 (monobank's default when the field is omitted). Java example:
     * https://github.com/kolja24/java-example-hash-34311
     */
    GOST("Gost"),

    /** DSTU 7564:2014 Kupyna-256. */
    DSTU256("Dstu256"),

    /** A value this SDK version does not know. Never send it. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Type of a document to sign. Unknown future values are read as [UNKNOWN].
 *
 * **Experimental:** see [HashType].
 *
 * @property value Wire value.
 */
@ExperimentalMonobankApi
public enum class DocumentType(
    @get:JsonValue public val value: String,
) {
    /** PDF. */
    PDF("pdf"),

    /** Word 97–2003 document. */
    DOC("doc"),

    /** Word document. */
    DOCX("docx"),

    /** OpenDocument text. */
    ODT("odt"),

    /** JSON. */
    JSON("json"),

    /** XML. */
    XML("xml"),

    /** HTML. */
    HTML("html"),

    /** PNG image. */
    PNG("png"),

    /** JPG image. */
    JPG("jpg"),

    /** JPEG image. */
    JPEG("jpeg"),

    /** Any other type. */
    OTHER("other"),

    /** A value this SDK version does not know. Never send it. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Signing state of one document. Unknown future values are read as [UNKNOWN].
 *
 * **Experimental:** see [HashType].
 *
 * @property value Wire value.
 */
@ExperimentalMonobankApi
public enum class SigningStatus(
    @get:JsonValue public val value: String,
) {
    /** Waiting for the signature. */
    PENDING("pending"),

    /** Signed. */
    SIGNED("signed"),

    /** The request was cancelled. */
    CANCELED("canceled"),

    /** The request expired (after 3 days) unsigned. */
    EXPIRED("expired"),

    /** A status this SDK version does not know. Treat as not final and ask again. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
    ;

    /** `true` when the status can no longer change. */
    public val isFinal: Boolean
        get() = this == SIGNED || this == CANCELED || this == EXPIRED
}

/**
 * One document to sign, in [CreateSignatureRequest]. Build with [builder].
 *
 * **Experimental:** see [HashType].
 *
 * @property name Document name shown to the signer.
 * @property hash Document hash, hex.
 * @property hashType Hash algorithm; monobank assumes [HashType.GOST] when absent.
 * @property type Document type.
 * @property link Link to the document.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class SignDocument internal constructor(
    @get:JsonProperty("name") public val name: String,
    @get:JsonProperty("hash") public val hash: String,
    @get:JsonProperty("hashType") public val hashType: HashType?,
    @get:JsonProperty("type") public val type: DocumentType?,
    @get:JsonProperty("link") public val link: String?,
) {
    /** Builder for [SignDocument]. */
    public class Builder internal constructor(
        private val name: String,
        private val hash: String,
    ) {
        private var hashType: HashType? = null
        private var type: DocumentType? = null
        private var link: String? = null

        /** Hash algorithm (default on monobank's side: [HashType.GOST]). */
        public fun hashType(hashType: HashType?): Builder = apply { this.hashType = hashType }

        /** Document type. */
        public fun type(type: DocumentType?): Builder = apply { this.type = type }

        /** Link to the document. */
        public fun link(link: String?): Builder = apply { this.link = link }

        /**
         * Builds the document.
         *
         * @throws IllegalArgumentException for a blank name, a hash that is not hex, or an
         *   `UNKNOWN` enum value.
         */
        public fun build(): SignDocument {
            require(name.isNotBlank()) { "name must not be blank" }
            require(hash.isNotEmpty() && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "hash must be hex"
            }
            require(hashType != HashType.UNKNOWN) { "hashType UNKNOWN cannot be sent" }
            require(type != DocumentType.UNKNOWN) { "type UNKNOWN cannot be sent" }
            return SignDocument(name, hash, hashType, type, link)
        }
    }

    public companion object {
        /**
         * Starts a document.
         *
         * @param name Document name shown to the signer.
         * @param hash Document hash in hex, computed with the [HashType] you set (GOST 34.311-95 by default).
         */
        @JvmStatic
        public fun builder(
            name: String,
            hash: String,
        ): Builder = Builder(name, hash)
    }
}

/**
 * Request of `POST /personal/signature/create`: ask a monobank client to sign documents with
 * monoKEP (their qualified electronic signature). Build with [builder].
 *
 * **Experimental:** see [HashType].
 *
 * @property documents 1 to 10 documents.
 * @property oneSigner `true` (monobank's default) when only one person may sign.
 * @property callbackUrl monobank POSTs the signing state (the same body as
 *   [SignatureApi.status] returns) here when it changes. Parse it with [SignatureStatus.fromJson].
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class CreateSignatureRequest internal constructor(
    @get:JsonProperty("documents") public val documents: List<SignDocument>,
    @get:JsonProperty("oneSigner") public val oneSigner: Boolean?,
    @get:JsonProperty("callbackUrl") public val callbackUrl: String?,
) {
    /** Builder for [CreateSignatureRequest]. */
    public class Builder internal constructor(
        private val documents: List<SignDocument>,
    ) {
        private var oneSigner: Boolean? = null
        private var callbackUrl: String? = null

        /** Whether only one person may sign (monobank's default: `true`). */
        public fun oneSigner(oneSigner: Boolean?): Builder = apply { this.oneSigner = oneSigner }

        /** Where monobank POSTs signing state changes. */
        public fun callbackUrl(url: String?): Builder = apply { callbackUrl = url }

        /**
         * Builds the request.
         *
         * @throws IllegalArgumentException unless there are 1 to 10 documents.
         */
        public fun build(): CreateSignatureRequest {
            require(documents.size in 1..MAX_DOCUMENTS) { "documents must hold 1 to $MAX_DOCUMENTS items" }
            return CreateSignatureRequest(documents, oneSigner, callbackUrl)
        }
    }

    public companion object {
        /** Most documents in one request. */
        public const val MAX_DOCUMENTS: Int = 10

        /** Starts a request for [documents] (1 to 10). */
        @JvmStatic
        public fun builder(documents: List<SignDocument>): Builder = Builder(documents.toList())
    }
}

/**
 * Answer of `POST /personal/signature/create`.
 *
 * **Experimental:** see [HashType].
 *
 * @property requestId Signing request id, for [SignatureApi.status] and [SignatureApi.cancel].
 * @property deeplink Link for the signer to open in the monobank app (as a QR code on a desktop).
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class CreateSignatureResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("requestId") public val requestId: String,
        @param:JsonProperty("deeplink") public val deeplink: String,
    )

/**
 * Signing state of a request: answer of `GET /personal/signature/status` and body of the
 * `callbackUrl` call.
 *
 * **Experimental:** see [HashType].
 *
 * @property requestId Signing request id.
 * @property documents The documents with their state and signers.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class SignatureStatus
    @JsonCreator
    internal constructor(
        @param:JsonProperty("requestId") public val requestId: String?,
        @param:JsonProperty("documents") public val documents: List<DocumentStatus>?,
    ) {
        public companion object {
            /**
             * Parses the body monobank POSTs to `callbackUrl`. The callback is not signed:
             * confirm with [SignatureApi.status] before acting on it.
             *
             * @throws io.github.damienmiheev.monobank.core.MonobankSerializationException when the
             *   body is not a signing state.
             */
            @JvmStatic
            public fun fromJson(body: ByteArray): SignatureStatus =
                MonobankJson.read(body, SignatureStatus::class.java, outcome = null)

            /** Parses the callback body; see [fromJson]. */
            @JvmStatic
            public fun fromJson(body: String): SignatureStatus = fromJson(body.toByteArray(StandardCharsets.UTF_8))
        }
    }

/**
 * Signing state of one document.
 *
 * **Experimental:** see [HashType].
 *
 * @property status Signing state.
 * @property name Document name.
 * @property hash Document hash, hex.
 * @property hashType Hash algorithm.
 * @property type Document type.
 * @property link Link to the document.
 * @property signers Who signed (at most 20).
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class DocumentStatus
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val status: SigningStatus?,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("hash") public val hash: String,
        @param:JsonProperty("hashType") public val hashType: HashType?,
        @param:JsonProperty("type") public val type: DocumentType?,
        @param:JsonProperty("link") public val link: String?,
        @param:JsonProperty("signers") public val signers: List<Signer>?,
    )

/**
 * One signature on a document. [toString] shows only the last digits of [tin].
 *
 * **Experimental:** see [HashType].
 *
 * @property name Signer's full name.
 * @property tin Signer's taxpayer number (RNOKPP).
 * @property certSerial Serial number of the signing certificate.
 * @property signature The signature, base64.
 * @property date When it was signed.
 * @property edrpou EDRPOU code of the signer's company, when signed for a company.
 * @property company Signer's company.
 * @property post Signer's position.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class Signer
    @JsonCreator
    internal constructor(
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("tin") public val tin: String?,
        @param:JsonProperty("certSerial") public val certSerial: String?,
        @param:JsonProperty("signature") public val signature: String?,
        @param:JsonProperty("date") public val date: OffsetDateTime?,
        @param:JsonProperty("edrpou") public val edrpou: String?,
        @param:JsonProperty("company") public val company: String?,
        @param:JsonProperty("post") public val post: String?,
    ) {
        override fun toString(): String =
            "Signer(name=$name, tin=${Redaction.tail(tin, 2)}, certSerial=$certSerial, " +
                "signature=${signature?.let { "<${it.length} chars>" }}, date=$date, edrpou=$edrpou, " +
                "company=$company, post=$post)"
    }
