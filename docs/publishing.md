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

scripts/release.sh X.Y.Z            # or --dry-run first, to see the steps
```

That runs the sequence below in order, after refusing when anything it
needs is missing: a dirty tree, a branch other than main, a
`gradle.properties` version that is not X.Y.Z, a changelog or plugin.xml
without an entry for it, a tag that already exists, an unset password or
token (`--skip-publish` leaves the Marketplace out), or a
`maven-plugin/pom.xml` whose version does not match. It waits for the tag's
release to appear and attaches the signed zip under the name every release
has used.

After the Marketplace release it runs the distribution phase: it builds the
Maven Central bundles for the agent and the starter, stages the Maven plugin,
and publishes the Gradle plugin to the Portal, each step gated on its own
credential and skipped with a printed note when that credential is absent
(`--skip-distribution` leaves the phase out entirely). The Central bundles
still end in a manual Publish click; see [Maven Central](#maven-central-the-agent-the-starter-and-the-maven-plugin)
and [the Gradle Plugin Portal](#gradle-plugin-portal-the-gradle-plugin) below.
The Marketplace steps, for reference and for doing one by hand:

```bash

./gradlew verifyPlugin                # what the Marketplace runs on submission
./gradlew signPlugin --no-daemon      # produces the signed zip
./gradlew publishPlugin --no-daemon   # uploads it

git tag -a vX.Y.Z -m 'Reclazz X.Y.Z' && git push origin main --follow-tags

# The tag creates the release, with the changelog section as its notes and
# the agent jar attached. This adds the signed plugin zip to it.
cp build/distributions/reclazz-X.Y.Z-signed.zip /tmp/reclazz-X.Y.Z.zip
gh release upload vX.Y.Z /tmp/reclazz-X.Y.Z.zip

git worktree add /tmp/reclazz-site gh-pages    # then edit, commit, push
```

The copy is the point of that first line. Every release since 1.0.0
attaches `reclazz-X.Y.Z.zip`, and `gh` takes the filename from the path
you give it. Its `path#label` syntax sets the label shown beside the
asset, not the name it is stored under, so uploading the signed zip
directly publishes it as `reclazz-X.Y.Z-signed.zip` and breaks the run.

## What the tag does on its own

`.github/workflows/release.yml` fires on `vX.Y.Z` and creates the release:
it checks that the tag and `gradle.properties` agree, takes the notes from
that version's section of `CHANGELOG.md` (`scripts/changelog-section.sh`,
which fails when there is no such section), builds the agent jar, and
attaches it with a `.sha256` beside it.

Signing is not in there and cannot be: the private key and its passphrase
live on your machine and are deliberately not repository secrets. So the
signed zip is still yours to upload, and that is now `gh release upload`
against a release that exists rather than `gh release create` against one
that has to be remembered. Forgetting it leaves a release with the agent
jar and no plugin zip, which is visible; forgetting the old step left no
release at all, which is what happened three times.

The agent jar is the other reason for it. Somebody using Reclazz without
IntelliJ had no download: the README told them to clone the repository and
run Gradle, which is a strange thing to ask of a person evaluating a tool.

The last two steps used to live only in whoever was doing the release.
1.0.8 went to the Marketplace and got its tag, and the GitHub release was
simply forgotten, so for a while the repository's newest release was one
version behind what people were installing. It happened again, and worse:
1.0.24, 1.0.25 and 1.0.26 all shipped without one, and the repository's
newest release read 1.0.23 while users were installing 1.0.26. The backfill
is possible (the tags are the source of truth, and a rebuild from the tag
carries the same code under a fresh signature), but the artifact that
actually shipped is gone once a later build overwrites `build/distributions`.
Do this step in the same sitting as the publish.

## The site is a branch of this repository

reclazz.com is GitHub Pages serving the `gh-pages` branch of this repo, which
is why it is easy to miss: everything else in a release happens on `main`, and
nothing in the build touches that branch or fails without it.

```bash
git worktree add /tmp/reclazz-site gh-pages
```

Three things go stale there, in this order of visibility:

- `"softwareVersion"` in the JSON-LD block of `index.html`, which is the number
  a search engine reads.
- The feature cards, which carry the capability claims and the sentences about
  what still needs a restart. A claim that stopped being true is worse than a
  missing one, so correct those rather than only adding.
- `llms.txt`, which is the same content for anything reading the site as a
  model rather than a page.

`index.html` keeps English inline and Turkish in a `translations` object keyed
by `data-i18n`, so a new card needs both halves. This check catches the half
that is easy to forget:

```bash
python3 - <<'EOF'
import re
s = open("index.html", encoding="utf-8").read()
keys = set(re.findall(r'data-i18n="([^"]+)"', s))
block = re.search(r"const translations = \{(.*?)\n\};", s, re.S).group(1)
missing = sorted(keys - set(re.findall(r"^\s*'([^']+)':", block, re.M)))
print("missing Turkish:", missing or "none")
EOF
```

Do this in the same sitting as the publish, for the reason the GitHub release
is in the same sitting: it depends on somebody remembering. It was forgotten
through 1.0.24 to 1.0.29, and the page advertised 1.0.23 while the Marketplace
shipped 1.0.29. That gap is worst exactly when it matters most: 1.0.29 fixes a
defect in 1.0.28, and a reader of the site would have had no way to know the
version they were installing had one.

## Then hide what it supersedes

```bash
# what the Marketplace currently offers, hidden versions excluded
curl -s "https://plugins.jetbrains.com/api/plugins/33498/updates?size=100" |
  python3 -c "import json,sys;[print(u['version'], u['compatibleVersions']) for u in json.load(sys.stdin)]"
```

Hiding is a UI action: plugins.jetbrains.com > the plugin > Versions > hide.
There is no upload-token endpoint for it, so it cannot be scripted with the
token the publish step uses.

Hide, never delete. Deleting is irreversible and takes the rollback path with
it; hiding keeps the artifact and the history that explains how a defect was
found. Keep the git tag either way, because it is what answers "what was the
source at this version".

A version is safe to hide when the new one covers the same IDE range, which
the `compatibleVersions` above shows directly. When the ranges differ, the
older version is the only thing some users can install, and hiding it strands
them.

## Release notes live in two files

`CHANGELOG.md` is for people reading the repository. The `<change-notes>`
block in `plugin.xml` is what the IDE shows in its "What's new" panel when
it offers the update, and it is the only one most users ever see. Both need
an entry for the version being released.

`checkReleaseNotes` fails the build when either is missing, and `signPlugin`
and `publishPlugin` depend on it, so a release cannot go out without them.
That check exists because 1.0.9 did: its changelog entry was written, its
plugin.xml notes were not, and it reached the Marketplace showing 1.0.8 at
the top of the list. Nobody who updated could see what they were getting.

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
agent jar name, and the zip name. `scripts/bump-version.sh X.Y.Z` makes the
release commit's three edits together: the version, the changelog's
`[Unreleased]` section dated as `[X.Y.Z]` with a fresh `[Unreleased]` above
it, and an `<h3>X.Y.Z</h3>` block at the top of `plugin.xml`'s change-notes
seeded with the changelog's headlines, to be edited into the IDE's own
words. Review, commit, then `scripts/release.sh X.Y.Z`.

What a number means here:

- **Patch** (1.1.x): fixes, and anything that changes no contract: a reload
  that now lands where it did not, a message said better, a cost cut.
- **Minor** (1.x.0): something new that an existing set-up does not have to
  know about: a new kind of reload, a new agent argument, a new socket
  command or JFR event, a new IDE version supported.
- **Major** (x.0.0): a contract broken: an agent argument or a persisted
  setting removed or renamed, a socket field or command changed, a JDK or
  IDE floor raised, a default that changes what a save does.

The contracts are the ones the tests pin: the argument table
(`AgentArgumentsAreDocumentedTest`), the socket document
(`ProtocolContractTest`), the persisted settings (`SettingsContractTest`)
and the JFR events (`ReloadEventsTest`). A change that fails one of them is
a major bump or a migration, never a quiet rename.

## Maven Central: the agent, the starter, and the Maven plugin

Three artifacts go to Maven Central under `com.onurkat.reclazz`, all at the same
version as the plugin:

- `reclazz-agent`: the shadow (fat) jar with the agent manifest, so the Gradle
  plugin and any build can resolve it by version. Wiring in
  `agent/build.gradle.kts`.
- `reclazz-spring-boot-starter`: the startup reporter and actuator endpoint.
  Wiring in `spring-boot-starter/build.gradle.kts`.
- `reclazz-maven-plugin`: the `prepare-agent` Maven plugin. It is a separate
  Maven module (`maven-plugin/pom.xml`), published with `mvn`, not Gradle.

The agent and the starter each publish the main jar alongside a sources jar, a
javadoc jar, a POM and their signatures, through `maven-publish` + `signing` +
a `centralBundle` zip. `scripts/release.sh` builds both bundles in its
distribution phase; the section below is what each does and how to do one by
hand.

One-time account setup (owner):

- A Sonatype Central account at central.sonatype.com with the `com.onurkat`
  namespace verified, and a user token (a username and password pair).
- A GPG key whose public half is on a keyserver.

Credentials live in the owner's `~/.gradle/gradle.properties`, never in the
repository:

```
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
signing.keyId=<last 8 hex of the key id>
signing.password=<gpg passphrase>
signing.secretKeyRingFile=/Users/<you>/.gnupg/secring.gpg
```

Modern GnuPG does not keep `secring.gpg`, so export it once:

```
gpg --export-secret-keys <KEY_ID> > ~/.gnupg/secring.gpg
```

To build the agent and starter bundles:

```
./gradlew :agent:centralBundle :spring-boot-starter:centralBundle
```

That signs the artifacts and writes
`agent/build/reclazz-agent-<version>-central-bundle.zip` and
`spring-boot-starter/build/reclazz-spring-boot-starter-<version>-central-bundle.zip`.
Upload each at central.sonatype.com (Publish, then Upload a bundle), or POST it
to the Publisher API, and click Publish once it validates. The publication is
staged, not auto-released, so nothing goes public until that click.

The Maven plugin stages itself through its own `release` profile
(`maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`, and
`central-publishing-maven-plugin` with `autoPublish=false`):

```
mvn -f maven-plugin/pom.xml -Prelease clean deploy
```

Its signing goes through `gpg` rather than Gradle's own signing, so it prompts
for the GPG passphrase through pinentry and needs a real terminal; it cannot
sign unattended the way the Gradle bundles can. It appears in the same staged
list at central.sonatype.com, waiting for the same Publish click.

## Gradle Plugin Portal: the Gradle plugin

The Gradle plugin is published to the Gradle Plugin Portal as
`com.onurkat.reclazz` (a separate registry from Maven Central), so
`id("com.onurkat.reclazz") version "X.Y.Z"` resolves from a plain `plugins {}`
block. Wiring is in `gradle-plugin/build.gradle.kts` (the
`com.gradle.plugin-publish` plugin and the `gradlePlugin` block).

One-time setup (owner), already done for 1.x:

- A Gradle Plugin Portal account, with its API key and secret in
  `~/.gradle/gradle.properties` as `gradle.publish.key` and
  `gradle.publish.secret`.
- The first publication of a new plugin id needs manual Gradle approval and,
  because the id is under `com.onurkat`, ownership of `onurkat.com` proven with
  a DNS TXT record. Later versions of an approved id publish with no further
  approval.

To publish:

```
./gradlew :gradle-plugin:publishPlugins
```

The version comes from `pluginVersion` in `gradle.properties`, the same source
as the agent, so keeping that one file current keeps the plugin and the agent it
resolves on the same version. A Portal version is immutable: to correct a
published build you bump the version and publish again, never overwrite.

This is why a release is all four registries at once, not the plugin alone. The
plugin defaults the agent it resolves to its own version (`ReclazzPlugin` sets
`agentVersion` to `project.version`), so a plugin published as X.Y.Z resolves
`reclazz-agent:X.Y.Z` from Central. Publishing the plugin without the matching
agent on Central leaves every user of it unable to resolve the agent. The Maven
plugin has the same coupling: its POM depends on `reclazz-agent` at its own
version, so its build needs that agent resolvable. `release.sh` handles the
build-time half by running `:agent:publishToMavenLocal` before `mvn deploy`, so
the Maven plugin resolves the agent from `~/.m2` rather than waiting for the
Central sync; the runtime half is the manual Publish click that puts the agent
on Central for real users.

## Verify what actually shipped

A registry can end up serving an older build than the source. It happened once:
the Gradle plugin's 1.3.0 was published from a build taken before the
`reclazzStatus` task and the `reclazz {}` extension existed, so the Portal
served a plugin the docs described features it did not have. The publish step
does not catch this, because it uploads whatever the last build produced. Check
the served artifact against the source before calling a release done:

```
# the Gradle plugin: the served jar has every class the source has
./gradlew :gradle-plugin:jar
unzip -l gradle-plugin/build/libs/gradle-plugin-<version>.jar | grep -E 'ReclazzStatusTask|ReclazzExtension|AgentStatus'

# the agent on Central resolves and carries the Premain manifest
# (a day or so after Publish, once it syncs to repo1)
curl -sI https://repo1.maven.org/maven2/com/onurkat/reclazz/reclazz-agent/<version>/reclazz-agent-<version>.jar
```

The Gradle plugin also carries a `reclazzStatus`/`reclazz {}` claim in its own
docs, so the quickest end-to-end check is a throwaway project with
`plugins { id("com.onurkat.reclazz") version "<version>" }` and
`./gradlew reclazzStatus`: a "task not found" there means the Portal is serving
a build without it, the exact failure the drift above produced.
