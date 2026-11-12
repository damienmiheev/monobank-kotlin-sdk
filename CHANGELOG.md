# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased] — 1.0.0

First release under `io.github.damienmiheev`. It replaces `app.spilki:monobank-acquiring-sdk` 0.0.x;
see the README's migration section.

### Added
- Modules: `monobank-core`, `monobank-acquiring`, `monobank-personal`, `monobank-corporate`
  (experimental), `monobank-installments`, and `monobank-bom`.
- Acquiring: every endpoint of monobank's current acquiring documentation (v2412), including
  sync payments, split receivers, POS refunds and Tap-to-Phone status; webhook verification with
  key rotation; statement lookup by order reference for reconciling timed-out payments.
- Personal API, corporate (providers) API with request signing, and the "Pokupka Chastynamy"
  installments API with HMAC signing and callback verification.
- `RequestOutcome` on every exception: whether a failed request may have been performed.
- Retries that are safe for payments, `Retry-After` support, timeouts, custom `HttpClient`.
- `MinorUnits` money conversion without rounding.

### Changed (from app.spilki 0.0.x)
- Packages renamed from `app.spilki.monobank.acquiring` to `io.github.damienmiheev.monobank.acquiring`.
- Requests are built with builders; responses are immutable and tolerate unknown fields and enum values.
- Exceptions: `errCode`/`errText` → `errorCode`/`errorText`; new subclasses for 404, 5xx, JSON and signature errors.
- Subscriptions use `subscriptionId`.

### Fixed (from app.spilki 0.0.x)
- List endpoints (statement, QR, wallet, submerchants, split receivers, fiscal checks, subscriptions,
  monopay keys, receipts) failed to parse monobank's wrapped responses.
- JSON errors escaped the exception hierarchy; unknown enum values crashed parsing.
- The API token and card data could appear in `toString()` and logs.
- The webhook key was cached forever; rotation broke verification.
