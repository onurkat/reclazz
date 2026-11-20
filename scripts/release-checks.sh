#!/usr/bin/env bash
# The release gate (release.sh runs it). No IDE signing, tagging or remote publication.
set -euo pipefail
cd "$(dirname "$0")/.."
# The Orika e2e tests need SAP Commerce's Orika jars and skip without them. Supply them when a
# local install is present (release.sh requires it); without one they skip.
if [ -z "${RECLAZZ_ORIKA_TEST_CLASSPATH:-}" ] && orika="$(scripts/orika-test-classpath.sh)"; then
    export RECLAZZ_ORIKA_TEST_CLASSPATH="$orika"
fi
if [ -n "${RECLAZZ_ORIKA_TEST_CLASSPATH:-}" ]; then echo "Orika e2e tests: run"; else echo "Orika e2e tests: skipped (no local SAP Commerce install)"; fi
./gradlew clean build buildPlugin verifyPlugin :agent:publishToMavenLocal --rerun-tasks --no-daemon
npm --prefix mcp-server/src/test/client ci --ignore-scripts --no-audit --no-fund
npm --prefix mcp-server/src/test/client test
mvn -q -f maven-plugin/pom.xml clean verify
python3 scripts/test-maven-consumer.py
