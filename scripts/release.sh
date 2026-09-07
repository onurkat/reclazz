#!/usr/bin/env bash
# Release X.Y.Z: check what a release needs, then do the steps in order.
#
# The steps were seven commands in docs/publishing.md, two of them with a
# known way to go wrong (the tag's release was forgotten four times; the
# signed zip must be renamed before upload or the release breaks). This runs
# them, refuses early when a precondition is missing, and prints what it
# would do under --dry-run so a test can hold it to the order.
#
#   scripts/release.sh X.Y.Z [--dry-run] [--skip-publish]
#
# --skip-publish leaves the Marketplace upload out (the signed zip is still
# built and attached to the GitHub release).
set -euo pipefail

version="${1:?usage: release.sh X.Y.Z [--dry-run] [--skip-publish]}"
version="${version#v}"
shift
dry_run=false
skip_publish=false
for arg in "$@"; do
    case "$arg" in
        --dry-run) dry_run=true ;;
        --skip-publish) skip_publish=true ;;
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

echo "Released $version. Left to do by hand: the site (git worktree add /tmp/reclazz-site gh-pages)"
echo "and hiding the superseded Marketplace version; see docs/publishing.md."
