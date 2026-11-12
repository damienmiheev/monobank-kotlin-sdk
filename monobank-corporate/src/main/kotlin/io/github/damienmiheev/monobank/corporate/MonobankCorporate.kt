package io.github.damienmiheev.monobank.corporate

import io.github.damienmiheev.monobank.core.ExperimentalMonobankApi
import io.github.damienmiheev.monobank.corporate.access.AccessApi
import io.github.damienmiheev.monobank.corporate.client.ClientApi
import io.github.damienmiheev.monobank.corporate.company.CompanyApi
import io.github.damienmiheev.monobank.corporate.internal.CorporateHttp
import io.github.damienmiheev.monobank.corporate.signature.SignatureApi

/**
 * Client for monobank's open API for providers ("corporate" API,
 * https://api.monobank.ua/docs/corporate.html): companies that serve monobank clients read a
 * client's accounts and statements after the client approves access in the monobank app, and
 * can ask clients to sign documents with monoKEP.
 *
 * Every request is signed with the company's secp256k1 key: headers `X-Key-Id`, `X-Time`
 * (Unix seconds) and `X-Sign` (base64 ECDSA-SHA256 over `X-Time [+ X-Request-Id] + URL`),
 * computed afresh for every attempt.
 *
 * **Experimental (Java callers too):** the request signing follows the docs and community
 * clients but has not been confirmed against the live service yet, so this API may change in
 * a minor release. [CorporateConfig] has switches for the details the docs leave open.
 *
 * Thread-safe; create one per company key and reuse it.
 *
 * ```kotlin
 * val mono = MonobankCorporate(CorporateConfig.builder(File("priv.key").readText()).build())
 * val access = mono.access.request()            // show access.acceptUrl to the client
 * if (mono.access.status(access.tokenRequestId) == AccessRequestStatus.APPROVED) {
 *     val accounts = mono.client(access.tokenRequestId).info().accounts
 * }
 * ```
 */
@ExperimentalMonobankApi
public class MonobankCorporate internal constructor(
    public val config: CorporateConfig,
    private val http: CorporateHttp,
) {
    /** Creates a client from [config]. */
    public constructor(config: CorporateConfig) : this(config, CorporateHttp(config))

    /** Company registration, settings and the company-wide webhook. */
    public val company: CompanyApi = CompanyApi(http)

    /** Requests for access to a client's data, and their state. */
    public val access: AccessApi = AccessApi(http)

    /** monoKEP document signing. */
    public val signatures: SignatureApi = SignatureApi(http)

    /**
     * Data of the client who approved access request [requestId] (its `tokenRequestId`).
     *
     * @throws IllegalArgumentException when [requestId] is blank or not one line.
     */
    public fun client(requestId: String): ClientApi = ClientApi(http, requestId)

    override fun toString(): String = "MonobankCorporate($config)"

    public companion object {
        /**
         * Creates a client with default settings for the company's private key in PEM.
         *
         * @throws IllegalArgumentException when the key cannot be read or is not secp256k1.
         */
        @JvmStatic
        public fun create(privateKeyPem: String): MonobankCorporate =
            MonobankCorporate(CorporateConfig.builder(privateKeyPem).build())
    }
}
