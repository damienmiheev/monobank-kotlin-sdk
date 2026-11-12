package io.github.damienmiheev.monobank.core

import java.util.Properties

/** Version information of the SDK. */
public object SdkInfo {
    /** The SDK version, e.g. `1.0.0`. */
    @JvmStatic
    public val version: String by lazy {
        val props = Properties()
        SdkInfo::class.java
            .getResourceAsStream(
                "/io/github/damienmiheev/monobank/core/sdk.properties",
            )?.use(props::load)
        props.getProperty("version") ?: "unknown"
    }

    @InternalMonobankApi
    internal fun userAgent(module: String): String =
        "monobank-kotlin-sdk/$version ($module; Java ${System.getProperty("java.version")})"
}
