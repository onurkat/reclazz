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

./gradlew verifyPlugin                # what the Marketplace runs on submission
./gradlew signPlugin --no-daemon      # produces the signed zip
./gradlew publishPlugin --no-daemon   # uploads it

git tag -a vX.Y.Z -m 'Reclazz X.Y.Z' && git push origin main --follow-tags

cp build/distributions/reclazz-X.Y.Z-signed.zip /tmp/reclazz-X.Y.Z.zip
gh release create vX.Y.Z /tmp/reclazz-X.Y.Z.zip \
  --title 'Reclazz X.Y.Z' --notes-file <notes from the changelog>
```

The copy is the point of that first line. Every release since 1.0.0
attaches `reclazz-X.Y.Z.zip`, and `gh` takes the filename from the path
you give it. Its `path#label` syntax sets the label shown beside the
asset, not the name it is stored under, so uploading the signed zip
directly publishes it as `reclazz-X.Y.Z-signed.zip` and breaks the run.

The last two steps used to live only in whoever was doing the release.
1.0.8 went to the Marketplace and got its tag, and the GitHub release was
simply forgotten, so for a while the repository's newest release was one
version behind what people were installing.

## The asset must be the signed zip

Attach `reclazz-X.Y.Z-signed.zip`, renamed on upload to
`reclazz-X.Y.Z.zip` by the `#` suffix above. Two reasons, and the second
one has already caused a near miss:

- The signed zip is the artifact that went to the Marketplace. Release
  notes claim the download is signed, and it should be true.
- `build/distributions/reclazz-X.Y.Z.zip`, the unsigned one, is rewritten
  by any later build. Running `verifyPlugin` after starting the next
  version's work, before bumping `pluginVersion`, leaves newer code
  sitting under the old version's filename. `signPlugin` does not re-run
  on its own, so the signed zip stays pinned to what was released while
  the unsigned one silently does not.

Check before uploading rather than trusting the filename:

```bash
unzip -p build/distributions/reclazz-X.Y.Z-signed.zip \
  reclazz/lib/reclazz-X.Y.Z.jar > /tmp/check.jar
unzip -l /tmp/check.jar | grep SomethingAddedAfterThatRelease   # expect nothing

java -jar <marketplace-zip-signer-cli.jar> verify \
  -in build/distributions/reclazz-X.Y.Z-signed.zip -cert certificate/chain.crt
```

The signer prints `Provided zip archive is not signed` when it is not,
and says nothing at all when the signature is good. Silence is the pass.

`--no-daemon` on the two tasks that read secrets is not optional. A
running Gradle daemon keeps the environment it was started with, so a
variable you export in your shell afterwards is invisible to the build
and signing fails claiming the passphrase is missing even though it is
right there in your shell.

On macOS, keep the passphrase in the login keychain instead of a shell
history entry:

```bash
security add-generic-password -s reclazz-signing -a reclazz -w   # once
security add-generic-password -s reclazz-publish -a reclazz -w   # once, the Marketplace token

RECLAZZ_SIGNING_PASSWORD="$(security find-generic-password -s reclazz-signing -a reclazz -w)" \
RECLAZZ_PUBLISH_TOKEN="$(security find-generic-password -s reclazz-publish -a reclazz -w)" \
  ./gradlew publishPlugin --no-daemon
```

Those commands prompt and need a real terminal. Run one through anything
that is not a TTY and it stores an empty value without complaining,
which then surfaces later as a missing-credential error.

Never paste either secret into a chat, an issue or a commit. A
Marketplace token can upload a release under your name, so treat one
that has been seen anywhere as spent: revoke it and issue another. The
keychain exists so that the value only ever travels from the prompt to
the build.

`verifyPlugin` is also part of CI, so a compatibility break surfaces on
push rather than in a rejection email.

If the signing material or the passphrase is missing, the build stops
with a message naming what is absent. It does not quietly skip signing,
which the platform's own task would otherwise do.

## About buildSearchableOptions

The task starts a whole IDE to index this plugin's settings, and that IDE
dies during startup roughly one run in five. Its own log, under
`build/idea-sandbox/<version>/log/idea.log`, says why:

```
Start Failed
Internal error. Please refer to https://jb.gg/ide/critical-startup-errors
java.util.ConcurrentModificationException
```

The stack is obfuscated third-party code called straight from
`MainImpl.start`, so it happens before this plugin is loaded and is not
ours. Building against a newer platform is worse rather than better: 2025.1
failed six times out of six.

The build retries it, so this should never reach you as a failure. The
crash happens before the IDE has done anything, so a second attempt costs
about twenty seconds and works. You will see:

```
buildSearchableOptions produced nothing; attempt 2 of 4
buildSearchableOptions succeeded on attempt 2
```

The retry decides on whether the index was produced rather than on the
exit code, because the exit code is the thing that lies here. After four
attempts with no index it fails loudly, naming the sandbox log.

Measured over ten clean builds: ten successes, one of which needed the
retry, and the index it produced was byte-identical to the others.

The heap is also capped at 768m, down from the platform default of 2GB,
which is more than indexing one configurable needs. That is not what
causes the crash, but it is worth not reserving on a machine already
running a SAP Commerce server.

Turning the task off entirely is possible with
`intellijPlatform { buildSearchableOptions = false }`, and costs something
concrete: the index really does contain this plugin's settings page, so
disabling it stops Settings > Tools > Reclazz appearing in the Settings
search box.

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
