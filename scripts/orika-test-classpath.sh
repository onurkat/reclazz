#!/usr/bin/env bash
# Prints RECLAZZ_ORIKA_TEST_CLASSPATH for the Orika e2e tests from a local SAP Commerce
# install (RECLAZZ_SAP_HOME, default ~/Projects/reclazz-sap). Without it those nine
# tests skip themselves. Exits 1, printing nothing, when a jar is missing.
set -euo pipefail
home="${RECLAZZ_SAP_HOME:-$HOME/Projects/reclazz-sap}/hybris"
jars=(
  bin/platform/ext/core/lib/orika-core-sap-commerce-2.5.8-jdk21.jar
  bin/platform/ext/core/lib/slf4j-api-2.0.17.jar
  bin/platform/ext/core/lib/paranamer-2.8.3.jar
  bin/platform/ext/core/lib/guava-33.4.8-jre.jar
  bin/platform/ext/validation/lib/javassist-3.30.2-GA.jar
  bin/modules/search-and-navigation/solrserver/resources/solr/9.10/server/modules/sql/lib/janino-3.1.11.jar
  bin/modules/search-and-navigation/solrserver/resources/solr/9.10/server/modules/sql/lib/commons-compiler-3.1.11.jar
  bin/platform/ext/core/lib/jackson-core-2.19.0.jar
  bin/platform/ext/core/lib/jackson-annotations-2.19.0.jar
  bin/platform/ext/core/lib/jackson-databind-2.18.2.jar
)
path=()
for jar in "${jars[@]}"; do
  [ -f "$home/$jar" ] || exit 1
  path+=("$home/$jar")
done
(IFS=:; printf '%s\n' "${path[*]}")
