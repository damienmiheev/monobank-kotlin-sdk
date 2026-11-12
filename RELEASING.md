# Releasing

Releases go to Maven Central under `io.github.damienmiheev`. Only the maintainer releases.
A release must not need a follow-up fix release, so **every step below is done twice**:
the first pass by the maintainer, the second pass independently (a second person or a fresh
review), on the exact commit that gets tagged. Nothing is published until both passes are clean.

## One-time setup

1. Central Portal (https://central.sonatype.com): the namespace `io.github.damienmiheev` is verified
   (Namespaces → Add namespace → create the temporary public repo GitHub asks for, then verify).
   The old namespace `app.spilki` stays verified on the same account (needed once, for the relocation POM).
2. Central Portal → Account → Generate User Token.
3. GitHub repository → Settings → Environments → create `maven-central` (optionally with required
   reviewers), and add these environment secrets:
   - `OSSRH_USERNAME`, `OSSRH_PASSWORD` — the Central Portal user token (username / password parts).
   - `GPG_SIGNING_KEY` — ASCII-armored private key (`gpg --armor --export-secret-keys <KEY_ID>`).
   - `GPG_SIGNING_PASSWORD` — its passphrase.
   The public key must be on a keyserver (`gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>`).

## Verification pass (run it twice)

On a clean checkout of the release commit:

1. `./gradlew clean check publishToMavenLocal -x :relocation:publishToMavenLocal --console=plain` passes. `check` includes the unit
   tests on JDK 21 and JDK 11 (`testJdk11`), the tests against Jackson 2.13 (`testJackson213`),
   detekt, ktlint and the public API dump (`apiCheck`).
2. CI is green on the commit: `check`, tests on the newest JDK, the Kill Bill Maven consumer, CodeQL.
3. Live tests against monobank: Actions → "Live tests (monobank test mode)" (secrets live in the
   `monobank-test` environment), or by hand with test credentials (never committed):
   ```bash
   MONOBANK_ACQUIRING_TEST_TOKEN=... ./gradlew :monobank-acquiring:liveTest
   MONOBANK_PERSONAL_TOKEN=... ./gradlew :monobank-personal:liveTest
   ```
   and, when credentials exist, `:monobank-corporate:liveTest` and `:monobank-installments:liveTest`
   (see CONTRIBUTING.md for the variables). Any API marked `@ExperimentalMonobankApi` stays marked
   until its live test has passed.
4. A payment end to end with the acquiring test token: create an invoice with card saving, pay it on
   the test page with a test card, receive and verify the webhook, charge the saved card, refund,
   hold and finalize. Note the invoice ids in the release notes draft.
5. Inspect what will be published: `~/.m2/repository/io/github/damienmiheev/*/<version>/` has for every
   module the jar, `-sources.jar`, `-javadoc.jar`, `.module` and a `.pom` with name, description,
   URL, license, developer and SCM; dependencies are `jackson-databind`/`slf4j-api`/`kotlin-stdlib`
   (and BouncyCastle only for `monobank-corporate`); no test classes in the jars.
6. `api/*.api` diff against the previous release contains only additions (after 1.0.0).
7. README examples compile (they mirror `src/test` code) and the CHANGELOG entry is complete.
8. A fresh-eyes review of the full diff since the previous release found nothing to fix.

## Release

1. Set `VERSION_NAME=X.Y.Z` in `gradle.properties`, move the CHANGELOG `Unreleased` section under
   `X.Y.Z`, commit "Release X.Y.Z", tag `vX.Y.Z`, push the tag.
2. Actions → "Publish to Maven Central" → run on tag `vX.Y.Z`, target `sdk`. It re-runs `check`,
   uploads, and stops: the deployment waits in the Central Portal.
3. Central Portal → Deployments: check the file list once more, then **Publish**.
4. After it appears on https://repo1.maven.org/maven2/io/github/damienmiheev/ (can take ~30 min),
   create the GitHub release from the tag with the CHANGELOG entry.
5. Set `VERSION_NAME` to the next `-SNAPSHOT` and commit.

### Once: relocation of the old coordinates

After 1.0.0 is public, publish `app.spilki:monobank-acquiring-sdk:0.1.0`, a POM that only relocates
to `io.github.damienmiheev:monobank-acquiring:1.0.0`: run the workflow on the same tag with target
`relocation`, then publish the deployment in the portal. Check the uploaded POM's
`<distributionManagement><relocation>` before publishing.
