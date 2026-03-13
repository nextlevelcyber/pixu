#!/bin/bash

# Phase 6 launcher: start 4-process topology (MDS/Pricing/OMS/Monitor).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
START_SCRIPT="$PROJECT_ROOT/scripts/start.sh"

PROFILE="development"
BUS_MODE="AERON_IPC"
BUS_STREAM_ID="9000"
BUS_ENDPOINT="aeron:udp?endpoint=localhost:40200"
AERON_DIR="/tmp/aeron-bedrock-phase6"
OMS_EXCHANGE="simulation"
FORCE_RESTART=false
JAR_FILE=""

print_info() {
  echo "[INFO] $1"
}

print_error() {
  echo "[ERROR] $1"
}

show_usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Options:
  -p, --profile PROFILE       Spring profile [default: development]
  --bus-mode MODE             AERON_IPC | AERON_UDP [default: AERON_IPC]
  --bus-stream-id ID          Unified bus stream id [default: 9000]
  --bus-endpoint URI          Aeron UDP endpoint [default: aeron:udp?endpoint=localhost:40200]
  --aeron-dir DIR             Aeron directory prefix [default: /tmp/aeron-bedrock-phase6]
  --oms-exchange NAME         OMS exchange (simulation|binance) [default: simulation]
  --jar-file PATH             Use specific executable bedrock-app jar for all instances
  -r, --force-restart         Stop existing phase6 instances before start
  -h, --help                  Show this help

Examples:
  $0
  $0 --bus-mode AERON_IPC --aeron-dir /tmp/aeron-mm
  $0 --bus-mode AERON_UDP --bus-endpoint "aeron:udp?endpoint=127.0.0.1:40200"
EOF
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -p|--profile)
      PROFILE="$2"
      shift 2
      ;;
    --bus-mode)
      BUS_MODE="$2"
      shift 2
      ;;
    --bus-stream-id)
      BUS_STREAM_ID="$2"
      shift 2
      ;;
    --bus-endpoint)
      BUS_ENDPOINT="$2"
      shift 2
      ;;
    --aeron-dir)
      AERON_DIR="$2"
      shift 2
      ;;
    --oms-exchange)
      OMS_EXCHANGE="$2"
      shift 2
      ;;
    --jar-file)
      JAR_FILE="$2"
      shift 2
      ;;
    -r|--force-restart)
      FORCE_RESTART=true
      shift
      ;;
    -h|--help)
      show_usage
      exit 0
      ;;
    *)
      print_error "Unknown option: $1"
      show_usage
      exit 1
      ;;
  esac
done

if [[ ! "$BUS_MODE" =~ ^(AERON_IPC|AERON_UDP)$ ]]; then
  print_error "Invalid bus mode: $BUS_MODE (supported: AERON_IPC, AERON_UDP)"
  exit 1
fi

if [[ ! -x "$START_SCRIPT" ]]; then
  print_error "Missing start script: $START_SCRIPT"
  exit 1
fi

if [[ "$FORCE_RESTART" == true ]]; then
  print_info "Force restart requested. Stopping existing phase6 instances..."
  "$PROJECT_ROOT/scripts/stop-phase6.sh" --force || true
fi

start_instance() {
  local name="$1"
  local mode="$2"
  local port="$3"
  local mgmt_port="$4"
  local disable_monitor="$5"
  local role_jvm="$6"
  local role_aeron_dir="$7"
  local embedded_md="$8"
  local delete_dir_on_start="$9"

  local jvm_opts
  jvm_opts="-Dbedrock.bus.aeronDir=${role_aeron_dir}"
  jvm_opts="$jvm_opts -Dbedrock.bus.embeddedMediaDriver=${embedded_md}"
  jvm_opts="$jvm_opts -Dbedrock.bus.deleteAeronDirOnStart=${delete_dir_on_start}"
  jvm_opts="$jvm_opts ${role_jvm}"

  local cmd=(
    "$START_SCRIPT"
    -p "$PROFILE"
    -m "$mode"
    -P "$port"
    -M "$mgmt_port"
    -I "$name"
    --bus-mode "$BUS_MODE"
    --bus-stream-id "$BUS_STREAM_ID"
    --bus-endpoint "$BUS_ENDPOINT"
    -b
    -j "$jvm_opts"
  )

  if [[ -n "$JAR_FILE" ]]; then
    cmd+=(--jar-file "$JAR_FILE" --no-build)
  fi

  if [[ "$disable_monitor" == "true" ]]; then
    cmd+=(-x)
  fi

  print_info "Starting instance '$name'..."
  "${cmd[@]}"
}

role_aeron_dir() {
  local name="$1"
  if [[ "$BUS_MODE" == "AERON_UDP" ]]; then
    echo "${AERON_DIR}-${name}"
  else
    echo "$AERON_DIR"
  fi
}

embedded_md_for() {
  local name="$1"
  if [[ "$BUS_MODE" == "AERON_UDP" ]]; then
    echo "true"
    return
  fi
  if [[ "$name" == "mds" ]]; then
    echo "true"
  else
    echo "false"
  fi
}

delete_dir_for() {
  local name="$1"
  if [[ "$BUS_MODE" == "AERON_IPC" && "$name" == "mds" ]]; then
    echo "true"
  else
    echo "false"
  fi
}

COMMON_OFF="-Dbedrock.strategy.enabled=false -Dbedrock.adapter.enabled=false"
# MDS role defaults to offline simulation for deterministic local smoke/startup.
MDS_JVM="-Dbedrock.md.enabled=true -Dbedrock.md.binance.enabled=false -Dbedrock.md.bitget.enabled=false -Dbedrock.md.binance.private.enabled=false -Dbedrock.md.simulation.enabled=true -Dbedrock.pricing.enabled=false -Dbedrock.oms.enabled=false ${COMMON_OFF}"
PRICING_JVM="-Dbedrock.md.enabled=false -Dbedrock.pricing.enabled=true -Dbedrock.oms.enabled=false ${COMMON_OFF}"
OMS_JVM="-Dbedrock.md.enabled=false -Dbedrock.pricing.enabled=false -Dbedrock.oms.enabled=true -Dbedrock.oms.exchange=${OMS_EXCHANGE} ${COMMON_OFF}"
MONITOR_JVM="-Dbedrock.md.enabled=false -Dbedrock.pricing.enabled=false -Dbedrock.oms.enabled=false ${COMMON_OFF}"

print_info "Launching Phase 6 topology with bus mode=$BUS_MODE streamId=$BUS_STREAM_ID"

start_instance "mds" "MARKET_DATA_ONLY" "18080" "19080" "true" "$MDS_JVM" \
  "$(role_aeron_dir mds)" "$(embedded_md_for mds)" "$(delete_dir_for mds)"

start_instance "pricing" "FULL" "18081" "19081" "true" "$PRICING_JVM" \
  "$(role_aeron_dir pricing)" "$(embedded_md_for pricing)" "$(delete_dir_for pricing)"

start_instance "oms" "FULL" "18082" "19082" "true" "$OMS_JVM" \
  "$(role_aeron_dir oms)" "$(embedded_md_for oms)" "$(delete_dir_for oms)"

start_instance "monitor" "FULL" "18083" "19083" "false" "$MONITOR_JVM" \
  "$(role_aeron_dir monitor)" "$(embedded_md_for monitor)" "$(delete_dir_for monitor)"

print_info "Phase 6 topology started."
print_info "PIDs: logs/mds.pid logs/pricing.pid logs/oms.pid logs/monitor.pid"
print_info "Logs: tail -f logs/mds.out logs/pricing.out logs/oms.out logs/monitor.out"
