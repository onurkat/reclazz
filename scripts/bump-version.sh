#!/usr/bin/env bash
# Prepare the release commit for X.Y.Z: the version, the changelog, the
# plugin's change-notes. The three used to be edited by hand, and the pattern
# was "look at the last release commit"; 1.0.9 shipped with the changelog
# written and the change-notes not.
#
#   scripts/bump-version.sh X.Y.Z
#
# Sets pluginVersion in gradle.properties, turns the changelog's [Unreleased]
# section into [X.Y.Z] dated today (RECLAZZ_RELEASE_DATE overrides the date)
# with a fresh empty [Unreleased] above it, and inserts an <h3>X.Y.Z</h3>
# block at the top of plugin.xml's change-notes carrying the changelog's
# bold headlines as list items, for editing into the IDE's own words.
set -euo pipefail

version="${1:?usage: bump-version.sh X.Y.Z}"
version="${version#v}"
case "$version" in
    *.*.*) ;;
    *) echo "version must be X.Y.Z, got $version" >&2; exit 2 ;;
esac
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
date="${RECLAZZ_RELEASE_DATE:-$(date +%F)}"
changelog="CHANGELOG.md"
plugin_xml="src/main/resources/META-INF/plugin.xml"

if ! grep -q '^## \[Unreleased\]' "$changelog"; then
    echo "$changelog has no [Unreleased] section to release" >&2
    exit 1
fi
if grep -q "^## \[$version\]" "$changelog"; then
    echo "$changelog already has a section for $version" >&2
    exit 1
fi
if grep -q "<h3>$version</h3>" "$plugin_xml"; then
    echo "$plugin_xml already has change-notes for $version" >&2
    exit 1
fi

# 1. the version
sed -i.bak -E "s/^pluginVersion=.*/pluginVersion=$version/" gradle.properties && rm gradle.properties.bak

# 2. the changelog: [Unreleased] becomes [X.Y.Z] - date, with a new empty
#    [Unreleased] above it
awk -v v="$version" -v d="$date" '
    !done && /^## \[Unreleased\]/ { print "## [Unreleased]"; print ""; print "## [" v "] - " d; done = 1; next }
    { print }
' "$changelog" > "$changelog.tmp" && mv "$changelog.tmp" "$changelog"

# 3. the change-notes: the section's bold headlines, one <li> each
headlines=$(scripts/changelog-section.sh "$version" "$changelog" \
    | grep -oE '^\- \*\*[^*]+\*\*' | sed -E 's/^- \*\*(.*)\*\*$/\1/')
{
    echo "        <h3>$version</h3>"
    echo "        <ul>"
    if [ -n "$headlines" ]; then
        while IFS= read -r line; do
            echo "            <li>$line</li>"
        done <<< "$headlines"
    else
        echo "            <li>TODO: say what changed, in the IDE's own words.</li>"
    fi
    echo "        </ul>"
} > /tmp/reclazz-notes.$$
awk -v notes="/tmp/reclazz-notes.$$" '
    { print }
    !done && /<change-notes><!\[CDATA\[/ { while ((getline line < notes) > 0) print line; done = 1 }
' "$plugin_xml" > "$plugin_xml.tmp" && mv "$plugin_xml.tmp" "$plugin_xml"
rm -f /tmp/reclazz-notes.$$

echo "Prepared $version ($date):"
echo "  gradle.properties     pluginVersion=$version"
echo "  $changelog          [Unreleased] -> [$version] - $date, new [Unreleased] above"
echo "  $plugin_xml  <h3>$version</h3> inserted; edit the items into the IDE's words"
echo "Then: review, commit, and scripts/release.sh $version"
