package io.github.damienmiheev.monobank.acquiring.merchant

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Answer of `GET /api/merchant/pubkey`.
 *
 * @property key Base64 of the PEM-encoded X.509 ECDSA public key that signs webhooks.
 */
@ConsistentCopyVisibility
public data class PublicKeyResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("key") public val key: String,
    )
