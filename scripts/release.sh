#!/usr/bin/env bash
# Release X.Y.Z: prepare and validate everything, then upload checked bytes.
#
# The steps were seven commands in docs/publishing.md, two of them with a
# known way to go wrong (the tag's release was forgotten four times; the
# signed zip must be renamed before upload or the release breaks). This runs
# them, refuses early when a precondition is missing, and prints what it
# would do under --dry-run so a test can hold it to the order.
#
#   scripts/release.sh X.Y.Z [--dry-run] [--skip-publish] [--skip-distribution]
#
# --skip-publish leaves the Marketplace upload out (the signed zip is still
# built and attached to the GitHub release).
# --skip-distribution leaves the Maven Central and Gradle Plugin Portal steps
# out (the Marketplace release still goes ahead).
set -euo pipefail

version="${1:?usage: release.sh X.Y.Z [--dry-run] [--skip-publish] [--skip-distribution]}"
version="${version#v}"
shift
dry_run=false
skip_publish=false
skip_distribution=false
for arg in "$@"; do
    case "$arg" in
        --dry-run) dry_run=true ;;
        --skip-publish) skip_publish=true ;;
        --skip-distribution) skip_distribution=true ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
tag="v$version"
source_commit="$(git rev-parse HEAD)"
gradle_props="$HOME/.gradle/gradle.properties"
problems=()
# Per-invocation outcomes only: never infer public availability from an exit code.
marketplace='awaiting manual action — upload not completed; inspect before retrying'
central='awaiting manual action — staging not completed; inspect before retrying'
portal='awaiting manual action — upload not completed; inspect before retrying'
github='awaiting manual action — draft completion not confirmed'
[ "$skip_publish" = false ] || marketplace='skipped — --skip-publish'
if [ "$skip_distribution" = true ]; then
    central='skipped — --skip-distribution'
    portal='skipped — --skip-distribution'
fi
gate_recorded=false
finish() {
    code=$?
    trap - EXIT
    if [ "$dry_run" = true ]; then
        echo 'Dry run only: no channel was executed or completed.'
    else
        echo
        echo "Channel outcomes for $version (source $source_commit; exit $code):"
        echo "  Marketplace: $marketplace"
        echo "  Maven Central: $central"
        echo "  Gradle Plugin Portal: $portal"
        echo "  GitHub: $github"
        echo '  Site: awaiting manual action — update separately'
        [ "$gate_recorded" = false ] || echo '  Local gate evidence: build/release-gate/receipt.json'
        echo 'Completed means automated commands returned success, not public availability or review approval.'
        echo 'Remote availability remains unverified. See docs/publishing.md#manual-recovery.'
    fi
    exit "$code"
}
trap finish EXIT
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    problems+=("release version must be X.Y.Z")
fi

# ---- preconditions ------------------------------------------------------
if [ -n "$(git status --porcelain)" ]; then
    problems+=("the working tree is not clean; commit or stash first")
fi
branch="$(git rev-parse --abbrev-ref HEAD)"
if [ "$branch" != "main" ]; then
    problems+=("on branch '$branch', releases are cut from main")
fi
declared="$(grep -E '^pluginVersion=' gradle.properties | cut -d= -f2 || true)"
if [ "$declared" != "$version" ]; then
    problems+=("gradle.properties says pluginVersion=$declared, not $version")
fi
# The Maven plugin is a separate POM; its version has to agree, or Central ends
# up a version behind the others. scripts/bump-version.sh keeps them in step.
if [ "$skip_distribution" = false ] && [ -f maven-plugin/pom.xml ]; then
    pom_version="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' maven-plugin/pom.xml | head -1 || true)"
    if [ "$pom_version" != "$version" ]; then
        problems+=("maven-plugin/pom.xml is version $pom_version, not $version (run scripts/bump-version.sh $version)")
    fi
fi
if ! "$root/scripts/changelog-section.sh" "$version" "$root/CHANGELOG.md" >/dev/null 2>&1; then
    problems+=("CHANGELOG.md has no '## [$version]' section")
fi
plugin_xml="src/main/resources/META-INF/plugin.xml"
if ! grep -q "<h3>$version</h3>" "$plugin_xml"; then
    problems+=("$plugin_xml <change-notes> has no <h3>$version</h3> section (what the IDE shows on update)")
fi
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    problems+=("tag $tag already exists")
fi
if [ "$dry_run" = false ]; then
    for tool in python3 npm mvn gh; do
        command -v "$tool" >/dev/null 2>&1 || problems+=("$tool is required for the release gate")
    done
    if [ "$skip_distribution" = false ]; then
        for key in signing.keyId gradle.publish.key gradle.publish.secret; do
            grep -q "^${key}=" "$gradle_props" 2>/dev/null || problems+=("$key missing in ~/.gradle/gradle.properties; or use --skip-distribution")
        done
        if [[ "${RECLAZZ_CENTRAL_TOKEN:-}" =~ [[:space:]] ]]; then
            problems+=("RECLAZZ_CENTRAL_TOKEN must not contain whitespace")
        fi
        [ -n "${RECLAZZ_CENTRAL_TOKEN:-}" ] || problems+=("RECLAZZ_CENTRAL_TOKEN is required to stage the prepared Maven bundle; or use --skip-distribution")
    fi
    if [ -z "${RECLAZZ_SIGNING_PASSWORD:-}" ]; then
        problems+=("RECLAZZ_SIGNING_PASSWORD is not set; the signed zip cannot be built")
    fi
    if [ "$skip_publish" = false ] && [ -z "${RECLAZZ_PUBLISH_TOKEN:-}" ]; then
        problems+=("RECLAZZ_PUBLISH_TOKEN is not set; pass --skip-publish to leave the Marketplace out")
    fi
fi
if [ "${#problems[@]}" -gt 0 ]; then
    echo "Not releasing $version:" >&2
    for p in "${problems[@]}"; do echo "  - $p" >&2; done
    exit 1
fi

# ---- steps ---------------------------------------------------------------
run() {
    if [ "$dry_run" = true ]; then
        echo "would run: $*"
    else
        echo "+ $*"
        "$@"
    fi
}

# All checks, packaging and signing happen before the first uploader or tag.
# Keep secrets out of run() output and let the owner's local keys do the signing.
if [ "$dry_run" = false ]; then
    mvn_gpg_pass="$(grep '^signing.password=' "$gradle_props" 2>/dev/null | cut -d= -f2- || true)"
    [ -z "$mvn_gpg_pass" ] || export MAVEN_GPG_PASSPHRASE="$mvn_gpg_pass"
    unset mvn_gpg_pass
fi
run bash scripts/release-checks.sh
run ./gradlew signPlugin verifyPluginSignature --no-daemon
profile=local
if [ "$skip_distribution" = false ]; then
    profile=distribution
    run ./gradlew :agent:centralBundle :spring-boot-starter:centralBundle --no-daemon
    # Version 0.5.0 creates the signed bundle, then returns before HTTP upload.
    run mvn -q -f maven-plugin/pom.xml -Prelease clean deploy -DskipPublishing=true
    run ./gradlew :gradle-plugin:publishPlugins --validate-only --no-daemon
fi
unset MAVEN_GPG_PASSPHRASE
run python3 scripts/release-evidence.py create "$version" "$source_commit" "$profile"
[ "$dry_run" = true ] || gate_recorded=true

publish() {
    run python3 scripts/release-evidence.py verify
    run "$@"
}
# No producer task may change the validated files inside these invocations.
if [ "$skip_publish" = false ]; then
    publish ./gradlew -I scripts/release-publish.init.gradle :publishPlugin --no-daemon --no-configuration-cache
    marketplace='completed — upload command succeeded; Marketplace review/availability unverified'
fi
if [ "$skip_distribution" = false ]; then
    publish python3 scripts/release-evidence.py stage-maven
    central='awaiting manual action — Maven plugin staged; upload agent/starter bundles and Publish all three in Central'
    publish ./gradlew -I scripts/release-publish.init.gradle :gradle-plugin:publishPlugins --no-daemon --no-configuration-cache
    portal='completed — upload command succeeded; Portal availability unverified'
fi
publish git tag -a "$tag" "$source_commit" -m "Reclazz $version"
# Push only the checked commit and this tag, not other pending local tags.
publish git push origin "$source_commit:refs/heads/main" "refs/tags/$tag"

if [ "$dry_run" = true ]; then
    echo "would wait for: gh release view $tag"
else
    for _ in $(seq 1 60); do
        if gh release view "$tag" >/dev/null 2>&1; then break; fi
        sleep 10
    done
    gh release view "$tag" >/dev/null 2>&1 || { echo "the release for $tag did not appear; check the Release workflow" >&2; exit 1; }
fi
run python3 scripts/release-evidence.py github-check --draft-only
publish gh release upload "$tag" "build/release-gate/assets/reclazz-$version.zip"
run python3 scripts/release-evidence.py github-check
publish gh release edit "$tag" --draft=false --verify-tag
github='completed — required asset metadata checked and publish command succeeded; downloads unverified'

echo
if [ "$dry_run" = false ]; then
    echo "Release commands finished for $version (source $source_commit)."
fi
if [ "$skip_distribution" = false ]; then
    echo "Upload the checked agent/starter Central bundles, then manually Publish all three Central deployments:"
    echo "  agent/build/reclazz-agent-$version-central-bundle.zip"
    echo "  spring-boot-starter/build/reclazz-spring-boot-starter-$version-central-bundle.zip"
fi
echo "Verify registry availability and complete the site separately; see docs/publishing.md."
