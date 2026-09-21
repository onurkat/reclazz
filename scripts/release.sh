#!/usr/bin/env bash
# Release X.Y.Z: check what a release needs, then do the steps in order.
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
problems=()

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

run ./gradlew verifyPlugin
run ./gradlew signPlugin --no-daemon
if [ "$skip_publish" = false ]; then
    run ./gradlew publishPlugin --no-daemon
fi
run git tag -a "$tag" -m "Reclazz $version"
run git push origin main --follow-tags

# The tag's workflow creates the release and attaches the agent jar; the
# signed zip is added here, under the name every release has used. gh takes
# the stored name from the path, so the copy is the rename.
signed="build/distributions/reclazz-$version-signed.zip"
asset="/tmp/reclazz-$version.zip"
if [ "$dry_run" = true ]; then
    echo "would wait for: gh release view $tag"
else
    for _ in $(seq 1 60); do
        if gh release view "$tag" >/dev/null 2>&1; then break; fi
        sleep 10
    done
    gh release view "$tag" >/dev/null 2>&1 || { echo "the release for $tag did not appear; check the Release workflow" >&2; exit 1; }
fi
run cp "$signed" "$asset"
run gh release upload "$tag" "$asset"

# ---- distribution: Maven Central + Gradle Plugin Portal ------------------
# The Marketplace is one of four registries a release goes to. The others are
# Maven Central (the agent, the Spring Boot starter, the Maven plugin) and the
# Gradle Plugin Portal (the Gradle plugin). Those were published by hand once,
# which is how the Portal ended up a build behind the source. Folding them in
# here keeps every registry on one version. Each step is gated on its own
# credential and prints what is left to do rather than failing the release,
# because the Central publish ends in a manual click either way.
gradle_props="$HOME/.gradle/gradle.properties"
if [ "$skip_distribution" = false ]; then
    echo
    echo "== distribution: Maven Central + Gradle Plugin Portal =="

    # Central bundles: signed local Maven layouts, zipped for upload. Building
    # them needs signing.keyId/signing.password in ~/.gradle/gradle.properties.
    # publishToMavenLocal puts this version's agent in ~/.m2 as well, because
    # the Maven plugin below depends on reclazz-agent at this same version and
    # Central has not served it yet (the bundle is staged, not published), so
    # its build would otherwise fail to resolve the dependency.
    if [ "$dry_run" = true ] || grep -q '^signing.keyId=' "$gradle_props" 2>/dev/null; then
        run ./gradlew :agent:centralBundle :agent:publishToMavenLocal :spring-boot-starter:centralBundle --no-daemon
        echo "Central bundles built (staged upload, then a manual Publish click):"
        echo "  agent/build/reclazz-agent-$version-central-bundle.zip"
        echo "  spring-boot-starter/build/reclazz-spring-boot-starter-$version-central-bundle.zip"
    else
        echo "! signing.keyId not in ~/.gradle/gradle.properties; build the Central bundles by hand:"
        echo "    ./gradlew :agent:centralBundle :agent:publishToMavenLocal :spring-boot-starter:centralBundle"
    fi

    # The Maven plugin is its own POM; -Prelease signs and stages it to the
    # Central Portal (autoPublish=false, so it waits for the same manual click).
    # It resolves reclazz-agent from ~/.m2 (published just above), not from the
    # not-yet-synced Central. maven-gpg-plugin signs through gpg, which may
    # prompt for the passphrase.
    if command -v mvn >/dev/null 2>&1; then
        run mvn -q -f maven-plugin/pom.xml -Prelease clean deploy
    else
        echo "! mvn not found; stage the Maven plugin by hand:"
        echo "    mvn -f maven-plugin/pom.xml -Prelease clean deploy"
    fi

    # The Gradle plugin publishes outright, given the Portal key in
    # ~/.gradle/gradle.properties (gradle.publish.key / gradle.publish.secret).
    if [ "$dry_run" = true ] || grep -q '^gradle.publish.key=' "$gradle_props" 2>/dev/null; then
        run ./gradlew :gradle-plugin:publishPlugins --no-daemon
    else
        echo "! gradle.publish.key not in ~/.gradle/gradle.properties; publish the Gradle plugin by hand:"
        echo "    ./gradlew :gradle-plugin:publishPlugins"
    fi

    echo "Then at https://central.sonatype.com: Publish > Upload a bundle for the"
    echo "agent and starter, and click Publish on all three staged deployments."
    echo "Verify each registry served $version; see docs/publishing.md."
fi

echo
echo "Released $version. Left to do by hand: the site (git worktree add /tmp/reclazz-site gh-pages),"
echo "the Central Publish click, and hiding the superseded Marketplace version; see docs/publishing.md."
