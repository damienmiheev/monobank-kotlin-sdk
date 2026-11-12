## What and why

<!-- Link monobank's docs for API changes. -->

## Checklist

- [ ] Tests cover the request and the response (MockWebServer), plus edge cases
- [ ] `./gradlew check` passes
- [ ] Public API change? `./gradlew apiDump` committed, and it is backwards compatible
- [ ] CHANGELOG.md updated
- [ ] No secrets, tokens or card data in code, tests, logs or `toString()`
