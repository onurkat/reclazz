#!/usr/bin/env bash
#
# Prints one version's section of CHANGELOG.md, for release notes.
#
# The release step asked for "--notes-file <notes from the changelog>", which
# means somebody opening the file, finding the section, and pasting it into a
# temporary file at the end of a release. That is the part of the process that
# was skipped for 1.0.24, 1.0.25 and 1.0.26.
#
#   scripts/changelog-section.sh 1.1.0
#   scripts/changelog-section.sh v1.1.0     # a tag name works too
#
# Exits non-zero when there is no section for that version, which is what makes
# it usable in a release workflow: shipping a version the changelog does not
# mention is a mistake worth stopping for.
set -euo pipefail

version="${1:?usage: changelog-section.sh <version>}"
version="${version#v}"
changelog="${2:-$(dirname "$0")/../CHANGELOG.md}"

section=$(awk -v want="## [$version]" '
    index($0, want) == 1 { printing = 1; next }
    printing && /^## \[/  { exit }
    printing              { print }
' "$changelog")

# Leading and trailing blank lines, so the notes start at the first word.
section=$(printf '%s\n' "$section" | sed -e '/./,$!d' | awk '
    { lines[NR] = $0 }
    END {
        last = NR
        while (last > 0 && lines[last] ~ /^[[:space:]]*$/) last--
        for (i = 1; i <= last; i++) print lines[i]
    }
')

if [ -z "$section" ]; then
    echo "CHANGELOG.md has no section for $version" >&2
    exit 1
fi

printf '%s\n' "$section"
