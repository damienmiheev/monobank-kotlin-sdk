# AGENTS.md — monobank Kotlin SDK

Instructions for coding agents and contributors working in this repository.

## What this is

Open-source Kotlin/Java SDK for monobank's public APIs, published to Maven Central as
`io.github.damienmiheev:monobank-*`. Main consumers: payment plugins (a Kill Bill plugin on JDK 11)
and backend services. The first official release is 1.0.0; releases must not need a follow-up fix
release — see RELEASING.md (every check is done twice before publishing; only the owner publishes).

## Layout

| Module | Package | Notes |
|---|---|---|
| `monobank-core` | `io.github.damienmiheev.monobank.core` | `MonobankHttp` transport, `MonobankJson`, sealed exceptions with `RequestOutcome`, `RetryPolicy`, `MinorUnits`, `Redaction` |
| `monobank-acquiring` | `...monobank.acquiring` | merchant acquiring API + webhooks; `src/test/resources/acquiring-operations.txt` lists every documented operation |
| `monobank-personal` | `...monobank.personal` | personal API, statement paging, optional rate limiter |
| `monobank-corporate` | `...monobank.corporate` | providers API, secp256k1 signing (BouncyCastle lightweight API), `@ExperimentalMonobankApi` |
| `monobank-installments` | `...monobank.installments` | "Покупка частинами", HMAC signing, BigDecimal hryvnias, snake_case |
| `monobank-testkit` | `...monobank.testkit` | webhook signer + fake monobank acquiring server (JDK HttpServer) |
| `monobank-bom` | — | version alignment |
| `relocation` | — | one-off relocation POM for the old `app.spilki:monobank-acquiring-sdk` |
| `consumer-tests/killbill-maven` | — | Maven project on Kill Bill's parent POM (Jackson 2.13, JDK 11), run in CI |
| `tools/fixtures_from_openapi.py` | — | builds test fixtures from monobank's OpenAPI specs |

## Hard rules

1. **Runtime compatibility:** Java 11 bytecode and JDK 11 API only (`-Xjdk-release=11`); Jackson
   databind APIs that exist in 2.13; SLF4J 1.7 API; no `jackson-module-kotlin`, no JSR-310 module,
   no `kotlin-reflect`, no `ServiceLoader`. `check` runs the tests on JDK 11 and against Jackson 2.13.
2. **Models:** explicit `@JsonProperty` names. Responses: `@ConsistentCopyVisibility data class` with an
   `internal` `@JsonCreator` constructor; only ids/status/amount/currency non-null; enums from
   responses have `UNKNOWN` (and keep the raw value where it matters). Requests: builders.
3. **Money:** minor units as `Long` (acquiring/personal/corporate), `BigDecimal` hryvnias (installments).
   Never `Double`. KDoc states the unit on every amount.
4. **Retries:** mark each operation `idempotent` honestly. Payments, refunds, creates, deletes and
   anything that sends something are not idempotent. The transport never retries those after an
   `UNKNOWN` outcome.
5. **Secrets:** tokens, keys, secrets, card tokens, PAN/CVV/exp, wallet tokens and cryptograms never
   appear in `toString()`, logs or exception messages. Logs carry method + path (no query), status,
   error code.
6. **Public API:** `explicitApi()`; after changing it run `./gradlew apiDump` and commit `api/*.api`.
   After 1.0.0 only additive changes.
7. **Tests:** MockWebServer per endpoint (request and response), fixtures from the docs, edge cases,
   a Java interop test per module, real key pairs for crypto. Live tests are tagged `live` and only
   run by hand (`./gradlew liveTest` with env vars, see CONTRIBUTING.md) or via the manual
   `live.yml` workflow. Never commit credentials.

## Commands

```bash
./gradlew check            # everything CI runs for the build (needs JDK 21+ to run Gradle)
./gradlew ktlintFormat
./gradlew apiDump
./gradlew publishToMavenLocal -x :relocation:publishToMavenLocal
```

## API reference

- Acquiring: https://monobank.ua/api-docs/acquiring (spec version at the time of 1.0.0: v2412)
- Personal: https://api.monobank.ua/docs/ (v250818)
- Corporate/providers: https://api.monobank.ua/docs/corporate.html (v260831)
- Installments: https://monobank.ua/api-docs/chast

## How to add or change an endpoint

1. Model request/response in the module's domain package, following the rules above.
2. Add the method to the domain API class with KDoc (HTTP method + path, idempotency, units).
3. Tests: request + response + error case; add the operation to the coverage list if the module has one.
4. `./gradlew check apiDump`, update CHANGELOG.md.
