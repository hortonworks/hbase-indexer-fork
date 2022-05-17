#!/usr/bin/env bash
set -euo pipefail

cat > mvn_settings.xml <<EOF
  <settings>
    <localRepository/>
    <mirrors>
      <mirror>
        <id>public</id>
        <mirrorOf>*</mirrorOf>
        <url>https://nexus-private.hortonworks.com/nexus/content/groups/public</url>
      </mirror>
    </mirrors>
    <profiles/>
  </settings>
EOF

# compile the whole project
mvn -s mvn_settings.xml -Pclover clean install -DskipTests
# execute the tests
mvn -s mvn_settings.xml -Pclover --update-snapshots --batch-mode -Dmaven.test.failure.ignore=true -Dtests.nightly=true --fail-at-end clean test
# collect the results
mvn -s mvn_settings.xml -Pclover clover:aggregate clover:clover
# upload the results to sonar
if [ -z "${SKIP_SONAR:-}" ]; then
  mvn -s mvn_settings.xml -Pclover sonar:sonar -Dsonar.clover.reportPath=./target/clover/clover.xml -Dsonar.projectKey=hbaseindexer_ut_cdpd_master -Dsonar.host.url=https://sonarqube.infra.cloudera.com -Dsonar.projectName="HBase Indexer UT cdpd-master"
fi
