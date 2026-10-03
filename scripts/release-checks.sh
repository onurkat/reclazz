#!/usr/bin/env bash
# Shared local/tag-workflow gate. No IDE signing, tagging or remote publication.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew clean build buildPlugin verifyPlugin :agent:publishToMavenLocal --rerun-tasks --no-daemon
npm --prefix mcp-server/src/test/client ci --ignore-scripts --no-audit --no-fund
npm --prefix mcp-server/src/test/client test
mvn -q -f maven-plugin/pom.xml clean verify
python3 scripts/test-maven-consumer.py
