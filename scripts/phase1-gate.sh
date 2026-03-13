#!/bin/bash

# Phase 1 gate checks for OMS determinism and quote/exec wiring.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

MVN_BIN_DEFAULT="/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn"
MVN_SETTINGS_DEFAULT="/Users/kaymen/tool/apache-maven-3.9.9/conf/settings.xml"
MVN_REPO_DEFAULT="/Users/kaymen/tool/repository"

MVN_BIN="${MVN_BIN:-$MVN_BIN_DEFAULT}"
MVN_SETTINGS="${MVN_SETTINGS:-$MVN_SETTINGS_DEFAULT}"
MVN_REPO="${MVN_REPO:-$MVN_REPO_DEFAULT}"

PHASE1_LATENCY_URL="${PHASE1_LATENCY_URL:-}"
PHASE1_LATENCY_FILE="${PHASE1_LATENCY_FILE:-}"
PHASE1_RECOVERY_URL="${PHASE1_RECOVERY_URL:-}"
PHASE1_RECOVERY_FILE="${PHASE1_RECOVERY_FILE:-}"
PHASE1_MAX_EXEC_TO_STATE_P99_NS="${PHASE1_MAX_EXEC_TO_STATE_P99_NS:-200000}"
PHASE1_MAX_EXEC_TO_STATE_P999_NS="${PHASE1_MAX_EXEC_TO_STATE_P999_NS:-500000}"
PHASE1_MAX_ACK_TO_STATE_P99_NS="${PHASE1_MAX_ACK_TO_STATE_P99_NS:-200000}"
PHASE1_MAX_ACK_TO_STATE_P999_NS="${PHASE1_MAX_ACK_TO_STATE_P999_NS:-500000}"
PHASE1_MAX_QUOTE_TO_ACK_P99_NS="${PHASE1_MAX_QUOTE_TO_ACK_P99_NS:-5000000}"
PHASE1_MAX_QUOTE_TO_ACK_P999_NS="${PHASE1_MAX_QUOTE_TO_ACK_P999_NS:-10000000}"

if [[ ! -x "$MVN_BIN" ]]; then
  echo "[ERROR] Maven binary not found: $MVN_BIN"
  exit 1
fi

cd "$PROJECT_ROOT"

echo "[INFO] Phase 1 gate: OMS module tests"
"$MVN_BIN" --settings "$MVN_SETTINGS" \
  -Dmaven.repo.local="$MVN_REPO" \
  -o -nsu \
  -pl bedrock-oms -am \
  -Dtest=BinanceExecGatewayTest,RestReconciliationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

echo "[INFO] Phase 1 gate: App OMS bus tests"
"$MVN_BIN" --settings "$MVN_SETTINGS" \
  -Dmaven.repo.local="$MVN_REPO" \
  -o -nsu \
  -pl bedrock-app -am \
  -Dtest=OmsBusConsumerTest,OmsCoordinatorTest,OmsUnifiedBusOrderingIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

latency_json=""
cleanup_latency_file=false
recovery_json=""
cleanup_recovery_file=false

if [[ -n "$PHASE1_LATENCY_URL" ]]; then
  latency_json="$(mktemp /tmp/phase1-latency.XXXXXX.json)"
  cleanup_latency_file=true
  echo "[INFO] Phase 1 gate: fetching latency snapshot from $PHASE1_LATENCY_URL"
  curl -fsS "$PHASE1_LATENCY_URL" > "$latency_json"
elif [[ -n "$PHASE1_LATENCY_FILE" ]]; then
  latency_json="$PHASE1_LATENCY_FILE"
fi

if [[ -n "$latency_json" ]]; then
  if [[ ! -f "$latency_json" ]]; then
    echo "[ERROR] Latency snapshot not found: $latency_json"
    exit 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "[ERROR] jq is required for latency threshold checks"
    exit 1
  fi

  echo "[INFO] Phase 1 gate: enforcing latency thresholds"
  violations_json="$(
    jq -c \
      --argjson exec_p99 "$PHASE1_MAX_EXEC_TO_STATE_P99_NS" \
      --argjson exec_p999 "$PHASE1_MAX_EXEC_TO_STATE_P999_NS" \
      --argjson ack_p99 "$PHASE1_MAX_ACK_TO_STATE_P99_NS" \
      --argjson ack_p999 "$PHASE1_MAX_ACK_TO_STATE_P999_NS" \
      --argjson qta_p99 "$PHASE1_MAX_QUOTE_TO_ACK_P99_NS" \
      --argjson qta_p999 "$PHASE1_MAX_QUOTE_TO_ACK_P999_NS" \
      '
      [
        to_entries[]
        | .key as $symbol
        | .value as $m
        | [
            {symbol:$symbol, metric:"execToStateP99Ns", value:($m.execToStateP99Ns // 0), threshold:$exec_p99, count:($m.execToStateCount // 0)},
            {symbol:$symbol, metric:"execToStateP999Ns", value:($m.execToStateP999Ns // 0), threshold:$exec_p999, count:($m.execToStateCount // 0)},
            {symbol:$symbol, metric:"ackToStateP99Ns", value:($m.ackToStateP99Ns // 0), threshold:$ack_p99, count:($m.ackToStateCount // 0)},
            {symbol:$symbol, metric:"ackToStateP999Ns", value:($m.ackToStateP999Ns // 0), threshold:$ack_p999, count:($m.ackToStateCount // 0)},
            {symbol:$symbol, metric:"quoteToAckP99Ns", value:($m.quoteToAckP99Ns // 0), threshold:$qta_p99, count:($m.quoteToAckCount // 0)},
            {symbol:$symbol, metric:"quoteToAckP999Ns", value:($m.quoteToAckP999Ns // 0), threshold:$qta_p999, count:($m.quoteToAckCount // 0)}
          ][]
        | select(.count > 0 and (.value > .threshold))
      ]
      ' "$latency_json"
  )"

  violations_count="$(jq -r 'length' <<< "$violations_json")"
  if [[ "$violations_count" -gt 0 ]]; then
    echo "[ERROR] Phase 1 latency threshold violations detected:"
    jq -r '.[] | "- \(.symbol): \(.metric)=\(.value)ns > \(.threshold)ns (count=\(.count))"' <<< "$violations_json"
    echo "[ERROR] Violation summary by symbol:"
    jq -r '
      sort_by(.symbol)
      | group_by(.symbol)[]
      | "  - \(. [0].symbol): \(length) violation(s)"
    ' <<< "$violations_json"
    exit 1
  fi

  echo "[INFO] Phase 1 latency thresholds passed"
else
  echo "[INFO] Phase 1 gate: latency threshold check skipped (set PHASE1_LATENCY_URL or PHASE1_LATENCY_FILE)"
fi

if [[ "$cleanup_latency_file" == true ]]; then
  rm -f "$latency_json"
fi

if [[ -n "$PHASE1_RECOVERY_URL" ]]; then
  recovery_json="$(mktemp /tmp/phase1-recovery.XXXXXX.json)"
  cleanup_recovery_file=true
  echo "[INFO] Phase 1 gate: fetching recovery snapshot from $PHASE1_RECOVERY_URL"
  curl -fsS "$PHASE1_RECOVERY_URL" > "$recovery_json"
elif [[ -n "$PHASE1_RECOVERY_FILE" ]]; then
  recovery_json="$PHASE1_RECOVERY_FILE"
fi

if [[ -n "$recovery_json" ]]; then
  if [[ ! -f "$recovery_json" ]]; then
    echo "[ERROR] Recovery snapshot not found: $recovery_json"
    exit 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "[ERROR] jq is required for recovery readiness checks"
    exit 1
  fi

  echo "[INFO] Phase 1 gate: enforcing startup recovery readiness"
  not_ready_json="$(
    jq -c '
      [
        to_entries[]
        | {
            symbol: .key,
            recoveryReady: (.value.recoveryReady // false),
            recoveryInProgress: (.value.recoveryInProgress // false),
            lastRecoveryError: (.value.lastRecoveryError // "")
          }
        | select(.recoveryReady != true)
      ]
    ' "$recovery_json"
  )"

  not_ready_count="$(jq -r 'length' <<< "$not_ready_json")"
  if [[ "$not_ready_count" -gt 0 ]]; then
    echo "[ERROR] Phase 1 recovery readiness check failed:"
    jq -r '.[] | "- \(.symbol): recoveryReady=\(.recoveryReady), inProgress=\(.recoveryInProgress), error=\(.lastRecoveryError)"' <<< "$not_ready_json"
    exit 1
  fi

  echo "[INFO] Phase 1 recovery readiness passed"
else
  echo "[INFO] Phase 1 gate: recovery readiness check skipped (set PHASE1_RECOVERY_URL or PHASE1_RECOVERY_FILE)"
fi

if [[ "$cleanup_recovery_file" == true ]]; then
  rm -f "$recovery_json"
fi

echo "[SUCCESS] Phase 1 gate passed"
