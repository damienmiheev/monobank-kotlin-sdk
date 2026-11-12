package io.github.damienmiheev.monobank.installments

/**
 * monobank "Покупка частинами" (installments) environment.
 *
 * `MonobankClientOptions.baseUrl` overrides the host of any environment (for tests or a proxy).
 *
 * @property baseUrl Host of the environment.
 */
public enum class InstallmentsEnvironment(
    public val baseUrl: String,
) {
    /** Production: `https://u2.monobank.com.ua`, with the store id and secret issued by monobank. */
    PRODUCTION("https://u2.monobank.com.ua"),

    /**
     * Sandbox: `https://u2-demo-ext.mono.st4g3.com`, with the shared test store published in monobank's docs.
     * The last digit of the client phone selects the scenario: `…1` approved, `…2` waiting for the client,
     * `…3` insufficient limit, `…4` approved and waiting for the store to confirm.
     */
    SANDBOX("https://u2-demo-ext.mono.st4g3.com"),

    /**
     * Stage (pre-production): `https://u2-ext.mono.st4g3.com`, with the shared stage store and the real
     * monobank app. Used to test the QR flow with monobank's test QR ids.
     */
    STAGE("https://u2-ext.mono.st4g3.com"),
}
