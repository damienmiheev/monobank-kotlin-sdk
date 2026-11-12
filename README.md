# monobank Kotlin SDK

[![CI](https://github.com/damienmiheev/monobank-kotlin-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/damienmiheev/monobank-kotlin-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.damienmiheev/monobank-acquiring)](https://central.sonatype.com/namespace/io.github.damienmiheev)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/damienmiheev/monobank-kotlin-sdk/badge)](https://scorecard.dev/viewer/?uri=github.com/damienmiheev/monobank-kotlin-sdk)

Kotlin and Java clients for monobank's public APIs:

| Module | API | Docs |
|---|---|---|
| `monobank-acquiring` | Merchant acquiring: invoices, holds, refunds, saved cards, subscriptions, QR, split payments, receipts, POS refunds, Tap-to-Phone, webhooks | [monobank.ua/api-docs/acquiring](https://monobank.ua/api-docs/acquiring) |
| `monobank-personal` | Personal API: client info, statements, currency rates, webhooks | [api.monobank.ua/docs](https://api.monobank.ua/docs/) |
| `monobank-corporate` | Open API for providers (corporate): company setup, client access, statements, monoKEP signing — **experimental** | [api.monobank.ua/docs/corporate.html](https://api.monobank.ua/docs/corporate.html) |
| `monobank-installments` | "Покупка частинами" (pay in parts) partner API | [monobank.ua/api-docs/chast](https://monobank.ua/api-docs/chast) |
| `monobank-testkit` | Test helpers: monobank-style webhook signer and a fake monobank acquiring server | — |
| `monobank-core` | Shared transport, errors, retries, money helpers (pulled in automatically) | — |

Built for payment code: every error says whether the request may have been performed, payments
are never retried blindly, webhooks are verified over raw bytes with key rotation, and secrets
never reach logs or `toString()`.

This is an independent open-source project, not affiliated with or endorsed by monobank (Universal Bank).

## Requirements

- Java 11 or newer (tested on 11, 21 and 25). Works from Kotlin and Java.
- Kotlin consumers: Kotlin 2.1 or newer.
- Runtime dependencies: `jackson-databind` 2.13+ (no Jackson modules needed), `slf4j-api` 1.7+,
  `kotlin-stdlib`; `monobank-corporate` adds BouncyCastle (`bcprov-jdk18on`). No HTTP library: it
  uses `java.net.http.HttpClient`. Runs inside Kill Bill 0.24 plugins (CI checks it against Kill Bill's parent POM).

## Installation

Use the BOM so all modules have the same version.

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("io.github.damienmiheev:monobank-bom:1.0.0"))
    implementation("io.github.damienmiheev:monobank-acquiring")
    testImplementation("io.github.damienmiheev:monobank-testkit")
}
```

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.damienmiheev</groupId>
      <artifactId>monobank-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.damienmiheev</groupId>
    <artifactId>monobank-acquiring</artifactId>
  </dependency>
</dependencies>
```

## Acquiring quick start

```kotlin
val mono = MonobankAcquiring.create(System.getenv("MONOBANK_TOKEN"))

val invoice =
    mono.invoices.create(
        CreateInvoiceRequest
            .builder(4200) // 42.00 UAH, in kopiyky
            .ccy(CurrencyCodes.UAH)
            .merchantPaymInfo(MerchantPaymInfo.builder().reference("order-1001").destination("Order 1001").build())
            .redirectUrl("https://shop.example/orders/1001")
            .webHookUrl("https://shop.example/monobank/webhook")
            .build(),
    )
redirectCustomerTo(invoice.pageUrl)

val status = mono.invoices.status(invoice.invoiceId)
if (status.status == InvoiceStatus.SUCCESS) markPaid(status.reference)
```

Java:

```java
MonobankAcquiring mono = MonobankAcquiring.create(System.getenv("MONOBANK_TOKEN"));
CreateInvoiceResponse invoice = mono.getInvoices().create(
        CreateInvoiceRequest.builder(4200)
                .ccy(CurrencyCodes.UAH)
                .webHookUrl("https://shop.example/monobank/webhook")
                .build());
```

Create one client per merchant token and reuse it; it is thread-safe.

If your merchant has fiscal receipts (Checkbox) or split payments enabled, monobank rejects invoices
without `merchantPaymInfo.basketOrder` (`INVALID_MERCHANT_PAYM_INFO`), and with split payments every
`BasketItem` needs a `splitReceiverId` (from `mono.split.receivers()`). Such merchants must also send `items` with a
partial `finalize` (and, likely, a partial refund); otherwise monobank answers 400 `1001`.

## Money

The acquiring, personal and corporate APIs use **minor units** (kopiyky, cents) for every amount:
`4200` is 42.00 UAH. Every amount field's documentation says so. The installments API uses decimal
hryvnias (`BigDecimal`). Convert without rounding:

```kotlin
val minor: Long = MinorUnits.toMinor(BigDecimal("499.00"), CurrencyCodes.UAH)   // 49900
val major: BigDecimal = MinorUnits.toMajor(49_900, CurrencyCodes.UAH)           // 499.00
MinorUnits.toMinor(BigDecimal("1.001"), CurrencyCodes.UAH)                       // throws ArithmeticException
```

## Errors and request outcome

Every exception extends the sealed `MonobankException`. For payment code the key field is
`outcome`:

| `outcome` | Meaning | What to do |
|---|---|---|
| `NOT_SENT` | Never reached monobank (DNS, connect, TLS) | Safe to retry |
| `REJECTED` | monobank refused it (4xx, 429, or a documented definite 5xx code) | Nothing happened; fix or retry later |
| `UNKNOWN` | Maybe performed (read timeout, connection reset, 5xx) | **Reconcile before retrying** |

```kotlin
try {
    mono.wallet.payment(request)
} catch (e: MonobankApiException) {
    log.warn("monobank {} {} trace={}", e.statusCode, e.errorCode, e.traceId) // errorCode: AcquiringErrorCodes.*
    if (e.outcome == RequestOutcome.UNKNOWN) reconcileLater(reference)
} catch (e: MonobankException) {
    if (e.outcome == RequestOutcome.UNKNOWN) reconcileLater(reference)
}
```

Subclasses: `MonobankBadRequestException`, `MonobankAuthException` (401/403), `MonobankNotFoundException`,
`MonobankRateLimitException` (with `retryAfter`), `MonobankServerException`, `MonobankNetworkException`,
`MonobankSerializationException`, `MonobankSignatureException`. Payment failure codes inside a status
(`errCode`) are described by `PaymentErrorCodes.describe(code)`.

## Retries and timeouts

```kotlin
val options =
    MonobankClientOptions
        .builder()
        .connectTimeout(Duration.ofSeconds(5))
        .requestTimeout(Duration.ofSeconds(20))
        .retryPolicy(RetryPolicy.builder().maxRetries(2).build()) // or RetryPolicy.NONE
        .httpClient(myHttpClient) // optional: proxy, TLS; must not follow redirects
        .build()
val mono = MonobankAcquiring(AcquiringConfig.builder(token).options(options).build())
```

The SDK retries only when it is safe: requests that were never sent, 429 answers (honouring
`Retry-After`), and reads after an unknown outcome. Payments, refunds, finalize and other changes
are **never** retried after an unknown outcome.

## Building a payment integration (acquiring)

1. **First payment and save the card**: create the invoice with
   `saveCardData(SaveCardData.saveTo(walletId))` (one stable `walletId` per customer) and a unique,
   deterministic `merchantPaymInfo.reference` per payment attempt. To save a card without charging,
   use `CreateInvoiceRequest.verification(walletId)`.
2. **Webhooks**: verify the raw body, then parse it. Answer 4xx for an invalid signature, 5xx when
   the key is unavailable (monobank then delivers again):
   ```kotlin
   when (mono.webhooks.check(request.header("X-Sign"), rawBody)) {
       VerificationResult.VALID -> handle(WebhookParser.parse(rawBody))
       VerificationResult.INVALID_SIGNATURE -> respond(400)
       VerificationResult.KEY_UNAVAILABLE -> respond(503)
   }
   ```
   Webhooks can arrive out of order: keep the state with the latest `modifiedDate`
   (`newState.isNewerThan(stored)`). The card token arrives in `walletData` (status `created`),
   possibly in a separate webhook. For several merchants, build one verifier per merchant with
   `WebhookVerifier(WebhookKeyProvider { ... })` or `WebhookVerifier.ofPublicKey(storedKey)`;
   `currentPublicKey()` returns the key in use.
3. **Charge a saved card**: `mono.wallet.payment(WalletPaymentRequest.builder(cardToken, amount, CurrencyCodes.UAH, InitiationKind.MERCHANT).build())`.
   If `isThreeDsRequired`, send the customer to `tdsUrl`; the result comes by webhook or status.
4. **Holds**: `paymentType(PaymentType.HOLD)`, then `invoices.finalize(FinalizeInvoiceRequest.of(invoiceId, amount))`
   within 9 days, or `invoices.cancel(...)` to release.
5. **Refunds**: `invoices.cancel(CancelInvoiceRequest.builder(invoiceId).amount(partial).extRef(refundId).build())`.
   Match the result in `cancelList` by `extRef` (`status.cancelFor(refundId)`).
6. **Missed webhooks**: poll `invoices.status(invoiceId)`.
7. **Unknown outcome on create or payment**: you may not have an `invoiceId`. Look the payment up by
   its reference before doing anything else: `mono.merchant.findByReference(reference, from)`.

### Testing with the fake monobank

`monobank-testkit` runs an in-memory monobank acquiring server with its own signing key, signed
webhooks and fault injection (delays, timeouts, dropped connections, duplicate and reordered
webhooks), so integrations can be tested without network access. See the `FakeMonobankAcquiring` KDoc.
It approximates monobank's documented behaviour; also run your own tests against monobank's test token.

## Personal API

```kotlin
val mono = MonobankPersonal.create(System.getenv("MONOBANK_PERSONAL_TOKEN"))
val me = mono.client.info()
val items = mono.statements.listAll(me.accounts.orEmpty().first().id, Instant.now().minus(Duration.ofDays(90)), Instant.now())
val rates = MonobankPersonal.publicOnly().bank.currency()
```

monobank allows one `client-info` and one `statement` call per 60 seconds. `listAll` splits long
ranges and pages through 500-item answers; enable the client-side limiter with
`PersonalConfig.builder(token).rateLimiter(...)` to wait instead of getting 429s.

## Corporate (providers) API — experimental

```kotlin
val corp = MonobankCorporate.create(privateKeyPem) // secp256k1, SEC1 or PKCS#8 PEM
val request = corp.access.request(callbackUrl = "https://example.com/monobank/callback")
// show request.acceptUrl to the client, then:
val info = corp.client(request.tokenRequestId).info()
```

Requests are signed with ECDSA secp256k1 (via BouncyCastle). The signing follows monobank's docs and
community implementations but has not yet been confirmed against the live service, so the module is
marked `@ExperimentalMonobankApi` and may change in a minor release. If monobank rejects signatures,
`CorporateConfig` can switch the signature encoding and query-string handling.

## Installments ("Покупка частинами")

```kotlin
val chast =
    MonobankInstallments(
        InstallmentsConfig.builder(storeId, storeSecret).environment(InstallmentsEnvironment.SANDBOX).build(),
    )
val order = chast.orders.create(createOrderRequest)
// Callbacks: chast.callbacks.verifyAndParse(signatureHeader, rawBody)
```

Amounts are `BigDecimal` hryvnias. Requests and callbacks are signed with HMAC-SHA256.

## Migrating from app.spilki

The SDK moved to the owner's account and namespace. `app.spilki:monobank-acquiring-sdk:0.1.0` is a
relocation POM that points Maven and Gradle to `io.github.damienmiheev:monobank-acquiring:1.0.0`,
but the **packages were restructured and the API changed**, so code needs these edits.

Packages: `app.spilki.monobank.acquiring` → `io.github.damienmiheev.monobank.acquiring`; the `model.`
and `api.` levels are gone (e.g. `model.invoice.CreateInvoiceRequest` → `invoice.CreateInvoiceRequest`,
`model.common.InvoiceStatus` → `common.InvoiceStatus`, `webhook.WebhookParser` stays `webhook.WebhookParser`),
and exceptions moved to `io.github.damienmiheev.monobank.core`.

| 0.0.x | 1.0.0 |
|---|---|
| `implementation("app.spilki:monobank-acquiring-sdk:0.0.3")` | `implementation("io.github.damienmiheev:monobank-acquiring:1.0.0")` |
| `MonobankAcquiring(MonobankAcquiringConfig(token = t))` | `MonobankAcquiring(AcquiringConfig.builder(t).build())` or `MonobankAcquiring.create(t)` |
| `MonobankAcquiringConfig(baseUrl, connectTimeoutMs, readTimeoutMs, cmsName, cmsVersion)` | `AcquiringConfig.builder(t).cmsName(..).cmsVersion(..).options(MonobankClientOptions.builder().baseUrl(..).connectTimeout(Duration).requestTimeout(Duration).build())` |
| `CreateInvoiceRequest(amount = 4200, ccy = 980, ...)` | `CreateInvoiceRequest.builder(4200).ccy(980)...build()` |
| `WalletPaymentRequest(cardToken, amount, ccy, ...)` | `WalletPaymentRequest.builder(cardToken, amount, ccy, InitiationKind.MERCHANT)...build()` (answer: `PaymentResponse`) |
| `CancelInvoiceRequest(invoiceId, amount = x)` | `CancelInvoiceRequest.builder(invoiceId).amount(x).build()` |
| `FinalizeInvoiceRequest(invoiceId, amount)` | `FinalizeInvoiceRequest.of(invoiceId, amount)` (amount is now required) |
| `invoices.receipt(id)` returned the raw JSON bytes | returns the decoded **PDF** bytes (same signature: check your callers) |
| `merchant.statement(fromEpochSeconds, toEpochSeconds)` | `merchant.statement(Instant, Instant?)`; also `statementEntries`, `findByReference` |
| `monopay.importPubKey / deletePubKey / listPubKeys` | `monopay.importKey / deleteKey / keys` |
| `qr.employees()` | `employees.list()` (deprecated: legacy docs only) |
| `submerchants.list()` | `merchant.submerchants()` |
| `terminals.list()` | `terminals.list()` (returns `T2pTerminal`) |
| `wallet.deleteCard(cardToken, walletId)` | `wallet.deleteCard(cardToken)` |
| `subscriptions.status(subId)` | `subscriptions.status(subscriptionId)` |
| `subscriptions.payments(subId)` / `list(walletId)` | `payments(SubscriptionPaymentsQuery)` / `list(SubscriptionListQuery)`, returning a `Page` |
| `subscriptions.edit(EditSubscriptionRequest(...))` | `edit(EditSubscriptionRequest.cancel(id[, refund]))` (cancel is the only documented edit) |
| `webhooks.verify(sign, body)` | same; or `check(...)` to tell an invalid signature from an unavailable key |
| `e.errCode` / `e.errText` | `e.errorCode` / `e.errorText`, plus `e.outcome` and `e.traceId` |

0.0.x could not read list answers (statement, QR, wallet cards, submerchants, split receivers,
fiscal checks, subscriptions, monopay keys) or receipts; 1.0.0 fixes them. See [CHANGELOG.md](CHANGELOG.md).

## Versioning

[Semantic Versioning](https://semver.org/). The public API is checked on every build
(`api/*.api`). APIs marked `@ExperimentalMonobankApi` and declarations marked `@InternalMonobankApi`
are excluded from the compatibility promise. New exception subclasses are only added in major versions.
Constructors of response and request models are `internal` in Kotlin but visible to Java; they are
not part of the API and can change in a minor release. Build test data from JSON
(`WebhookParser.parse(...)`) or use `monobank-testkit` instead of calling them.

## Contributing, security, license

- [CONTRIBUTING.md](CONTRIBUTING.md): build, tests, live tests.
- [SECURITY.md](SECURITY.md): report vulnerabilities privately.
- [RELEASING.md](RELEASING.md): the release checklist.
- [MIT License](LICENSE).
