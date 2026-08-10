# Publishing Reclazz to the JetBrains Marketplace

One-time setup, then three commands per release.

## One-time: signing certificate

The Marketplace requires uploads to be signed. You generate your own
certificate; there is no authority to apply to.

```bash
./scripts/generate-signing-certificate.sh
```

It asks for a passphrase twice and writes two files into `certificate/`,
which is git-ignored:

| File | What it is |
|---|---|
| `private.pem` | Encrypted RSA-4096 private key. Signs releases as you. |
| `chain.crt` | Self-signed X.509 certificate, valid 10 years. |

Put the passphrase in your password manager immediately. It cannot be
recovered, and losing it means future releases must be signed under a
new identity.

Back up both files somewhere private (password manager attachment,
encrypted drive). Losing the key has the same effect as losing the
passphrase.

## One-time: Marketplace token

Create a permanent token at
[plugins.jetbrains.com](https://plugins.jetbrains.com) under your
profile, Marketplace, API tokens. It authorises uploads to plugins you
own.

## Per release

```bash
export RECLAZZ_SIGNING_PASSWORD='the passphrase you chose'
export RECLAZZ_PUBLISH_TOKEN='the marketplace token'

./gradlew verifyPlugin     # what the Marketplace will run on submission
./gradlew signPlugin       # produces the signed zip
./gradlew publishPlugin    # uploads it
```

`verifyPlugin` is also part of CI, so a compatibility break surfaces on
push rather than in a rejection email.

If the signing material or the passphrase is missing, the build stops
with a message naming what is absent. It does not quietly skip signing,
which the platform's own task would otherwise do.

## Before the first publication

- [ ] Screenshots for the listing (the tool window and a reload in
      progress carry the most weight)
- [ ] Read the change-notes in `src/main/resources/META-INF/plugin.xml`
      as a user would: it is the "What's new" tab
- [ ] Confirm `sinceBuild` / `untilBuild` in `build.gradle.kts` still
      match the IDEs you intend to support
- [ ] Known gaps worth stating openly in the listing rather than being
      reported as bugs: attach mode does not yet discover Spring Boot
      embedded-Tomcat contexts, and Windows is untested

## Version numbering

`pluginVersion` in `gradle.properties` drives the plugin version, the
agent jar name, and the zip name. Bump it and add a CHANGELOG entry in
the same commit; the release commits in `git log` show the pattern.
