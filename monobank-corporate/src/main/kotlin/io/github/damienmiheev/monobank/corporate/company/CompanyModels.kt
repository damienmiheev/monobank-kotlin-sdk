package io.github.damienmiheev.monobank.corporate.company

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.CorporateKey
import java.util.Base64

/**
 * Request of `POST /personal/auth/registration`: the company's application for access to the
 * corporate API. A person at monobank reviews it; poll [CompanyApi.registrationStatus].
 *
 * Every field is required. Build it with [builder].
 *
 * **Experimental:** the corporate API's request signing has not been confirmed against the
 * live monobank service yet; this API may change in a minor release.
 *
 * @property pubkey Base64 of the PEM public key file (`openssl ec -pubout` output), see
 *   [CorporateKey.publicKeyPemBase64]. The same string identifies the application in
 *   [CompanyApi.registrationStatus].
 * @property name Company name.
 * @property description What the service does and why it needs the API. Community reports say
 *   monobank rejects descriptions longer than 100 characters (not in the docs).
 * @property contactPerson Contact person.
 * @property phone Contact phone, digits with country code, e.g. `380671234567`.
 * @property email Contact email.
 * @property logo Base64 of the company logo image, shown to clients when they grant access.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class RegistrationRequest internal constructor(
    @get:JsonProperty("pubkey") public val pubkey: String,
    @get:JsonProperty("name") public val name: String,
    @get:JsonProperty("description") public val description: String,
    @get:JsonProperty("contactPerson") public val contactPerson: String,
    @get:JsonProperty("phone") public val phone: String,
    @get:JsonProperty("email") public val email: String,
    @get:JsonProperty("logo") public val logo: String,
) {
    /** Leaves out the (long) base64 key and logo. */
    override fun toString(): String =
        "RegistrationRequest(pubkey=<${pubkey.length} chars>, name=$name, description=$description, " +
            "contactPerson=$contactPerson, phone=$phone, email=$email, logo=<${logo.length} chars>)"

    /** Builder for [RegistrationRequest]; every setter is required before [build]. */
    public class Builder internal constructor() {
        private var pubkey: String? = null
        private var name: String? = null
        private var description: String? = null
        private var contactPerson: String? = null
        private var phone: String? = null
        private var email: String? = null
        private var logo: String? = null

        /** Base64 of the PEM public key file. */
        public fun pubkey(pubkey: String): Builder = apply { this.pubkey = pubkey }

        /** Uses [CorporateKey.publicKeyPemBase64] of [key]. */
        public fun pubkey(key: CorporateKey): Builder = apply { pubkey = key.publicKeyPemBase64 }

        /** Company name. */
        public fun name(name: String): Builder = apply { this.name = name }

        /** What the service does and why it needs the API. */
        public fun description(description: String): Builder = apply { this.description = description }

        /** Contact person. */
        public fun contactPerson(contactPerson: String): Builder = apply { this.contactPerson = contactPerson }

        /** Contact phone, e.g. `380671234567`. */
        public fun phone(phone: String): Builder = apply { this.phone = phone }

        /** Contact email. */
        public fun email(email: String): Builder = apply { this.email = email }

        /** Logo image, already base64-encoded. */
        public fun logo(base64: String): Builder = apply { logo = base64 }

        /** Logo image file bytes (PNG, JPEG); base64-encoded here. */
        public fun logo(image: ByteArray): Builder = apply { logo = Base64.getEncoder().encodeToString(image) }

        /**
         * Builds the request.
         *
         * @throws IllegalArgumentException when a field is missing or blank.
         */
        public fun build(): RegistrationRequest =
            RegistrationRequest(
                required(pubkey, "pubkey"),
                required(name, "name"),
                required(description, "description"),
                required(contactPerson, "contactPerson"),
                required(phone, "phone"),
                required(email, "email"),
                required(logo, "logo"),
            )

        private fun required(
            value: String?,
            field: String,
        ): String {
            require(!value.isNullOrBlank()) { "$field is required" }
            return value
        }
    }

    public companion object {
        /** Starts a request. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * State of the company's registration application. Unknown future values are read as [UNKNOWN].
 *
 * **Experimental:** see [RegistrationRequest].
 *
 * @property value Wire value.
 */
@ExperimentalMonobankApi
public enum class RegistrationStatus(
    @get:JsonValue public val value: String,
) {
    /** Received, waiting for monobank's review. */
    NEW("New"),

    /** Declined. Improve the description and apply again (with a new key). */
    DECLINED("Declined"),

    /** Approved: the key id is active and clients can grant access. */
    APPROVED("Approved"),

    /** A value this SDK version does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}

/**
 * Answer of `POST /personal/auth/registration`.
 *
 * **Experimental:** see [RegistrationRequest].
 *
 * @property status State of the new application, normally [RegistrationStatus.NEW].
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class RegistrationResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val status: RegistrationStatus?,
    )

/**
 * Answer of `POST /personal/auth/registration/status`.
 *
 * **Experimental:** see [RegistrationRequest].
 *
 * @property status State of the application.
 * @property keyId The company key id (`X-Key-Id`), documented as always present; it should
 *   equal [io.github.damienmiheev.monobank.corporate.CorporateSigner.keyId].
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class RegistrationStatusResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("status") public val status: RegistrationStatus,
        @param:JsonProperty("keyId") public val keyId: String?,
    )

/** Body of `POST /personal/auth/registration/status`. */
internal class RegistrationStatusRequest(
    @get:JsonProperty("pubkey") val pubkey: String,
)

/** Body of `POST /personal/corp/webhook`. */
internal class SetWebhookRequest(
    @get:JsonProperty("webHookUrl") val webHookUrl: String,
)

/**
 * Answer of `GET /personal/corp/settings`: the company as monobank has it.
 *
 * **Experimental:** see [RegistrationRequest].
 *
 * @property id Company id. Listed as required in the docs but not described; `null` if absent.
 * @property pubkey Base64 of the registered PEM public key.
 * @property name Company name.
 * @property permission Granted permissions, one letter each, e.g. `psf`. The current docs do not
 *   list the letters; older docs and community clients use `s` = statements (and balance),
 *   `p` = personal data (client name), `f` = sole-proprietor (FOP) accounts.
 * @property logo Base64 of the company logo.
 * @property webhook The company webhook URL set with [CompanyApi.setWebhook], if any.
 */
@ExperimentalMonobankApi
@ConsistentCopyVisibility
public data class CompanySettings
    @JsonCreator
    internal constructor(
        @param:JsonProperty("id") public val id: String?,
        @param:JsonProperty("pubkey") public val pubkey: String?,
        @param:JsonProperty("name") public val name: String?,
        @param:JsonProperty("permission") public val permission: String?,
        @param:JsonProperty("logo") public val logo: String?,
        @param:JsonProperty("webhook") public val webhook: String?,
    ) {
        /** Leaves out the (long) base64 logo. */
        override fun toString(): String =
            "CompanySettings(id=$id, pubkey=$pubkey, name=$name, permission=$permission, " +
                "logo=${logo?.let { "<${it.length} chars>" }}, webhook=$webhook)"
    }
