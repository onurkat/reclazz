#!/usr/bin/env bash
# Prepare all release metadata before replacing files; never publish.
# Usage: scripts/bump-version.sh X.Y.Z (optional v prefix)
set -euo pipefail
exec python3 "$(dirname "$0")/bump-version.py" "$@"
