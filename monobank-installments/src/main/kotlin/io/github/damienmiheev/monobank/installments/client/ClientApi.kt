package io.github.damienmiheev.monobank.installments.client

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.core.Redaction
import io.github.damienmiheev.monobank.installments.internal.InstallmentsHttp
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank

/**
 * Client checks: is a phone number a monobank client who can pay in parts.
 */
public class ClientApi internal constructor(
    private val http: InstallmentsHttp,
) {
    /**
     * Checks whether [phone] belongs to a monobank client. Use it before offering installments.
     *
     * `POST /api/v2/client/validate`. Retried (read-only). monobank rate-limits it (HTTP 429).
     *
     * @param phone Phone in international format, e.g. `+380501234567`.
     */
    public fun validate(phone: String): ValidateClientResponse =
        http.post(
            "/api/v2/client/validate",
            PhoneBody(requireNotBlank(phone, "phone")),
            ValidateClientResponse::class.java,
            idempotent = true,
        )

    /**
     * Checks whether [phone] belongs to a monobank client and returns the client's name and tax number (legacy).
     *
     * `POST /api/client/validate`. Retried (read-only). Deprecated by monobank in favour of `/api/v2/client/validate`.
     */
    @Deprecated(
        "Deprecated by monobank. Use validate(phone) (POST /api/v2/client/validate).",
        ReplaceWith("validate(phone)"),
    )
    public fun validateWithDetails(phone: String): LegacyValidateClientResponse =
        http.post(
            "/api/client/validate",
            PhoneBody(requireNotBlank(phone, "phone")),
            LegacyValidateClientResponse::class.java,
            idempotent = true,
        )

    private class PhoneBody(
        @get:JsonProperty("phone") val phone: String,
    )
}

/**
 * Answer of `POST /api/v2/client/validate`.
 *
 * @property found `true` when the phone belongs to a monobank client. An answer without it fails to parse rather
 *   than reading as `false`.
 */
@ConsistentCopyVisibility
public data class ValidateClientResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("found") public val found: Boolean,
    )

/**
 * Answer of the deprecated `POST /api/client/validate`.
 *
 * @property found `true` when the phone belongs to a monobank client.
 * @property client The client's name and tax number, when found.
 */
@ConsistentCopyVisibility
public data class LegacyValidateClientResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("found") public val found: Boolean,
        @param:JsonProperty("client") public val client: ClientInfo?,
    )

/**
 * A monobank client. [toString] hides [inn].
 *
 * @property firstName First name.
 * @property lastName Last name.
 * @property middleName Patronymic.
 * @property inn Tax number.
 */
@ConsistentCopyVisibility
public data class ClientInfo
    @JsonCreator
    internal constructor(
        @param:JsonProperty("first_name") public val firstName: String?,
        @param:JsonProperty("last_name") public val lastName: String?,
        @param:JsonProperty("middle_name") public val middleName: String?,
        @param:JsonProperty("inn") public val inn: String?,
    ) {
        override fun toString(): String =
            "ClientInfo(firstName=$firstName, lastName=$lastName, middleName=$middleName, inn=${Redaction.secret(inn)})"
    }
