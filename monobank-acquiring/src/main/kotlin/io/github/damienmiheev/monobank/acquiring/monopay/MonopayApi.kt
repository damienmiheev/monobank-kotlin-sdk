package io.github.damienmiheev.monobank.acquiring.monopay

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.acquiring.internal.AcquiringHttp
import java.time.OffsetDateTime

/**
 * Keys for the monopay button widget: you import an ECDSA P-256 public key and sign widget
 * requests with the private key ([MonopaySigner]).
 */
public class MonopayApi internal constructor(
    private val http: AcquiringHttp,
) {
    /**
     * Imports a public key; the result carries the `keyId` the widget needs.
     *
     * `POST /api/merchant/monopay/pubkey-import`. Not retried after an unknown outcome; check
     * [keys] first.
     */
    public fun importKey(request: ImportMonopayKeyRequest): ImportedMonopayKey =
        http
            .post("/api/merchant/monopay/pubkey-import", request, ImportKeyWire::class.java, idempotent = false)
            .result ?: ImportedMonopayKey(null)

    /**
     * Deletes a key.
     *
     * `POST /api/merchant/monopay/pubkey-delete`. Not retried after an unknown outcome.
     */
    public fun deleteKey(keyId: String) {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        http.postNoContent("/api/merchant/monopay/pubkey-delete", KeyIdRequest(keyId), idempotent = false)
    }

    /**
     * All imported keys.
     *
     * `GET /api/merchant/monopay/pubkey-list`. Safe to retry.
     */
    public fun keys(): List<MonopayKey> =
        http.get("/api/merchant/monopay/pubkey-list", KeyListWire::class.java).result.orEmpty()
}

/**
 * Request of `POST /api/merchant/monopay/pubkey-import`.
 *
 * @property keyValue Base64 of the PEM public key; [MonopaySigner.publicKeyValue] makes it.
 * @property keyName Your name for the key.
 * @property expiresAt When the key expires.
 */
@ConsistentCopyVisibility
public data class ImportMonopayKeyRequest internal constructor(
    @get:JsonProperty("keyValue") public val keyValue: String,
    @get:JsonProperty("keyName") public val keyName: String?,
    @get:JsonProperty("expiresAt") public val expiresAt: OffsetDateTime?,
) {
    public companion object {
        /**
         * Creates the request.
         *
         * @param keyValue Base64 of the PEM public key.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            keyValue: String,
            keyName: String? = null,
            expiresAt: OffsetDateTime? = null,
        ): ImportMonopayKeyRequest {
            require(keyValue.isNotBlank()) { "keyValue must not be blank" }
            return ImportMonopayKeyRequest(keyValue, keyName, expiresAt)
        }
    }
}

/**
 * An imported monopay button key.
 *
 * @property keyId Key id for the widget.
 * @property keyValue Base64 of the PEM public key.
 * @property keyName Your name for the key.
 * @property expiresAt When the key expires.
 */
@ConsistentCopyVisibility
public data class MonopayKey
    @JsonCreator
    internal constructor(
        @param:JsonProperty("keyId") public val keyId: String?,
        @param:JsonProperty("keyValue") public val keyValue: String?,
        @param:JsonProperty("keyName") public val keyName: String?,
        @param:JsonProperty("expiresAt") public val expiresAt: OffsetDateTime?,
    )

internal class KeyIdRequest(
    @get:JsonProperty("keyId") val keyId: String,
)

internal class ImportKeyWire
    @JsonCreator
    constructor(
        @param:JsonProperty("result") val result: ImportedMonopayKey?,
    )

/**
 * Answer of `POST /api/merchant/monopay/pubkey-import`.
 *
 * @property keyId Id of the imported key, for the widget's `keyId`; `null` if monobank did not return one.
 */
@ConsistentCopyVisibility
public data class ImportedMonopayKey
    @JsonCreator
    internal constructor(
        @param:JsonProperty("keyId") public val keyId: String?,
    )

internal class KeyListWire
    @JsonCreator
    constructor(
        @param:JsonProperty("result") val result: List<MonopayKey>?,
    )
