# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased] — 1.0.0

First release under `io.github.damienmiheev`. It replaces `app.spilki:monobank-acquiring-sdk` 0.0.x;
see the README's migration section.

### Added
- Modules: `monobank-core`, `monobank-acquiring`, `monobank-personal`, `monobank-corporate`
  (experimental), `monobank-installments`, `monobank-testkit` (webhook signer and a fake monobank
  acquiring server with fault injection and per-merchant fiscalization/split rules seen on monobank's
  test merchant, for integration tests), and `monobank-bom`.
- Acquiring: every endpoint of monobank's current acquiring documentation (v2412), including
  sync payments, split receivers, POS refunds and Tap-to-Phone status; webhook verification with
  key rotation; statement lookup by order reference for reconciling timed-out payments.
- Personal API, corporate (providers) API with request signing, and the "Pokupka Chastynamy"
  installments API with HMAC signing and callback verification.
- `RequestOutcome` on every exception: whether a failed request may have been performed.
- Retries that are safe for payments, `Retry-After` support, timeouts, custom `HttpClient`.
- `MinorUnits` money conversion without rounding.
- `merchant.statementEntries(...)`: reads the statement entry by entry and returns unreadable
  entries raw; `findByReference` uses it and never reports an unreadable matching payment as missing.
- `MonobankClientOptions.maxResponseBytes` (default 20 MB).
- `invoices.status(invoiceId, statementFrom)`: when monobank's `invoice/status` fails with 500
  `INTERNAL_ERROR` (seen live for USD/EUR payments after a refund), the state is rebuilt from the
  merchant statement (`isReconstructedFromStatement`).
- `MonobankClientOptions.allowPlainHttp` (tests only): plain http to a non-loopback host such as a
  fake monobank reached from a container; `FakeMonobankAcquiring.containerClientOptionsBuilder()`.

### Notes (verified on monobank's test merchant, 12.11.2026)
- Use UAH with merchant acquiring. USD/EUR payments are converted to UAH; saved-card charges in
  USD/EUR cannot be fully refunded (monobank bug, reported).
- On fiscal or split merchants every refund of a saved-card payment needs `items` matching the paid
  basket exactly; a basket line priced per hryvnia (`sum` 100, `qty` with two decimals) or per kopiyka
  (`sum` 1, whole `qty`) allows refunds of any amount.
  README and `InvoiceApi.cancel` describe the rules.

### Changed (from app.spilki 0.0.x)
- Packages renamed from `app.spilki.monobank.acquiring` to `io.github.damienmiheev.monobank.acquiring`
  and restructured; see the README's migration table for every changed call.
- `invoices.receipt(id)` returns the decoded PDF bytes (0.0.x returned the raw JSON).
- Requests are built with builders; responses are read-only and tolerate unknown fields and enum values.
- Exceptions: `errCode`/`errText` → `errorCode`/`errorText`; new subclasses for 404, 5xx, JSON and signature errors.
- Subscriptions use `subscriptionId`.

### Fixed (from app.spilki 0.0.x)
- List endpoints (statement, QR, wallet, submerchants, split receivers, fiscal checks, subscriptions,
  monopay keys, receipts) failed to parse monobank's wrapped responses.
- JSON errors escaped the exception hierarchy; unknown enum values crashed parsing.
- The API token and card data could appear in `toString()` and logs.
- The webhook key was cached forever; rotation broke verification.
