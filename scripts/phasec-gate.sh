#!/bin/bash

# Phase C gate checks for OMS production hardening:
# - startup openOrders reconstruction
# - REST reconciliation event synthesis
# - risk parameter snapshot/rollback safety

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

MVN_BIN_DEFAULT="/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn"
MVN_SETTINGS_DEFAULT="/Users/kaymen/tool/apache-maven-3.9.9/conf/settings.xml"
MVN_REPO_DEFAULT="/Users/kaymen/tool/repository"

MVN_BIN="${MVN_BIN:-$MVN_BIN_DEFAULT}"
MVN_SETTINGS="${MVN_SETTINGS:-$MVN_SETTINGS_DEFAULT}"
MVN_REPO="${MVN_REPO:-$MVN_REPO_DEFAULT}"

if [[ ! -x "$MVN_BIN" ]]; then
  echo "[ERROR] Maven binary not found: $MVN_BIN"
  exit 1
fi

cd "$PROJECT_ROOT"

echo "[INFO] Phase C gate: OMS hardening tests"
"$MVN_BIN" --settings "$MVN_SETTINGS" \
  -Dmaven.repo.local="$MVN_REPO" \
  -o -nsu \
  -pl bedrock-oms -am \
  -Dtest=PositionTrackerTest,PositionReconciliationTest,RestReconciliationTest,RiskGuardTest,BinanceExecGatewayTest,OrderStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

echo "[INFO] Phase C gate: App OMS integration tests"
"$MVN_BIN" --settings "$MVN_SETTINGS" \
  -Dmaven.repo.local="$MVN_REPO" \
  -o -nsu \
  -pl bedrock-app -am \
  -Dtest=OmsBusConsumerTest,OmsCoordinatorTest,OmsUnifiedBusOrderingIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

echo "[SUCCESS] Phase C gate passed"
