# Contributing

Thanks for helping. Bug reports, fixes for API changes on monobank's side and new endpoints are welcome.

## Build

Requirements: JDK 21 or newer to run Gradle (the wrapper downloads Gradle). The build compiles
for Java 11 and also runs the tests on a Java 11 runtime, which Gradle finds or downloads
(`testJdk11`).

```bash
./gradlew check          # tests (JDK 21 + JDK 11 + Jackson 2.13), detekt, ktlint, API dump check
./gradlew ktlintFormat   # fix formatting
./gradlew apiDump        # after an intentional public API change; commit api/*.api
```

## Rules

- Every endpoint follows monobank's current documentation. Link the docs page in the PR.
- Models declare JSON names explicitly (`@JsonProperty`), use only Jackson 2.13 APIs, and never
  use `Double` for money. Amount fields say their unit in KDoc (kopiyky vs hryvnias).
- Responses tolerate unknown fields and unknown enum values (`UNKNOWN`).
- Never log or `toString()` tokens, keys, card numbers, CVV, card tokens or payment cryptograms.
- Public API changes after 1.0.0 must be backwards compatible (`apiCheck`); breaking changes wait
  for a major version.
- Tests: MockWebServer for every endpoint (request and response), plus edge cases. Crypto code is
  tested with real key pairs and with the documentation's examples.

## Live tests

Tests tagged `live` call the real API and are skipped unless credentials are set. Run them by hand
only; never in CI, never with production credentials you cannot afford to have rate-limited.

| Module | Variables | Notes |
|---|---|---|
| acquiring | `MONOBANK_ACQUIRING_TEST_TOKEN` | test token from https://api.monobank.ua/ (acquiring test mode) |
| personal | `MONOBANK_PERSONAL_TOKEN` | personal token from https://api.monobank.ua/; 1 request / 60 s limits |
| corporate | `MONOBANK_CORP_PRIVATE_KEY_PEM`, `MONOBANK_CORP_REQUEST_ID` | provider key registered with monobank |
| installments | `MONOBANK_CHAST_STORE_ID`, `MONOBANK_CHAST_STORE_SECRET` | sandbox store credentials |

```bash
MONOBANK_ACQUIRING_TEST_TOKEN=... ./gradlew :monobank-acquiring:liveTest
```

## Test fixtures

`tools/fixtures_from_openapi.py` builds request/response fixtures from monobank's OpenAPI
specs (field examples). When monobank changes an API, regenerate, diff, and update models and tests.

## Commits and PRs

Small, focused PRs with tests. CI must be green. By contributing you agree your work is licensed under the MIT License.
