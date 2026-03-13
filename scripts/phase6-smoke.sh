#!/bin/bash

# Phase 6 smoke test:
# 1) Start 4-process topology
# 2) Validate process pid files and actuator health
# 3) Stop topology (unless --keep-running)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
START_SCRIPT="$PROJECT_ROOT/scripts/start-phase6.sh"
STOP_SCRIPT="$PROJECT_ROOT/scripts/stop-phase6.sh"

PROFILE="development"
BUS_MODE="AERON_IPC"
BUS_STREAM_ID="9000"
BUS_ENDPOINT="aeron:udp?endpoint=localhost:40200"
AERON_DIR="/tmp/aeron-bedrock-phase6"
OMS_EXCHANGE="simulation"
TIMEOUT_SEC=60
KEEP_RUNNING=false
JAR_FILE=""
SKIP_BUILD=false
SKIP_RECOVERY_CHECK=false

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
  -p, --profile PROFILE      Spring profile [default: development]
  --bus-mode MODE            AERON_IPC | AERON_UDP [default: AERON_IPC]
  --bus-stream-id ID         Unified bus stream id [default: 9000]
  --bus-endpoint URI         Aeron UDP endpoint [default: aeron:udp?endpoint=localhost:40200]
  --aeron-dir DIR            Aeron directory prefix [default: /tmp/aeron-bedrock-phase6]
  --oms-exchange NAME        OMS exchange [default: simulation]
  --jar-file PATH            Use specific executable bedrock-app jar
  --skip-build               Fail if executable jar is missing
  --skip-recovery-check      Skip OMS startup recovery readiness check
  -t, --timeout SEC          Health wait timeout per instance [default: 60]
  -k, --keep-running         Keep topology alive after checks
  -h, --help                 Show this help
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
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-recovery-check)
      SKIP_RECOVERY_CHECK=true
      shift
      ;;
    -t|--timeout)
      TIMEOUT_SEC="$2"
      shift 2
      ;;
    -k|--keep-running)
      KEEP_RUNNING=true
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

if [[ ! -x "$START_SCRIPT" ]]; then
  print_error "Missing start script: $START_SCRIPT"
  exit 1
fi

if [[ ! -x "$STOP_SCRIPT" ]]; then
  print_error "Missing stop script: $STOP_SCRIPT"
  exit 1
fi

# Prefer the curated local Maven tool/repo if present to avoid user ~/.m2 issues.
MVN_BIN_DEFAULT="/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn"
MVN_SETTINGS_DEFAULT="/Users/kaymen/tool/apache-maven-3.9.9/conf/settings.xml"
MVN_REPO_DEFAULT="/Users/kaymen/tool/repository"
if [[ -x "$MVN_BIN_DEFAULT" && -f "$MVN_SETTINGS_DEFAULT" && -d "$MVN_REPO_DEFAULT" ]]; then
  export MVN_BIN="${MVN_BIN:-$MVN_BIN_DEFAULT}"
  export MVN_SETTINGS="${MVN_SETTINGS:-$MVN_SETTINGS_DEFAULT}"
  export MVN_REPO="${MVN_REPO:-$MVN_REPO_DEFAULT}"
  export MVN_OFFLINE="${MVN_OFFLINE:-false}"
fi

cleanup() {
  if [[ "$KEEP_RUNNING" == false ]]; then
    print_info "Stopping Phase 6 topology..."
    "$STOP_SCRIPT" --force || true
  fi
}
trap cleanup EXIT

check_pid_file() {
  local name="$1"
  local file="$PROJECT_ROOT/logs/${name}.pid"
  if [[ ! -f "$file" ]]; then
    print_error "PID file missing: $file"
    return 1
  fi
  local pid
  pid=$(cat "$file")
  if ! kill -0 "$pid" >/dev/null 2>&1; then
    print_error "Process not running for ${name}; pid=${pid}"
    return 1
  fi
  print_info "${name} running with pid=${pid}"
}

is_executable_jar() {
  local jar="$1"
  if [[ ! -f "$jar" ]]; then
    return 1
  fi
  local manifest
  manifest="$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null || true)"
  [[ "$manifest" == *"Main-Class:"* ]] || [[ "$manifest" == *"Start-Class:"* ]]
}

resolve_jar_candidate() {
  find "$PROJECT_ROOT/bedrock-app/target" -maxdepth 1 -type f -name "bedrock-app-*.jar" \
    ! -name "*-sources.jar" ! -name "*-javadoc.jar" ! -name "*.jar.original" \
    -exec ls -1t {} + 2>/dev/null | head -n 1
}

validate_jar_runtime() {
  local jar="$1"
  # Guard against agrona class shadowing from sbe-all packaged in BOOT-INF/lib.
  if jar tf "$jar" 2>/dev/null | grep -q 'BOOT-INF/lib/sbe-all-'; then
    print_error "Jar contains BOOT-INF/lib/sbe-all-*.jar; this can shadow agrona and break Aeron MediaDriver"
    print_error "Fix bedrock-sbe dependency scope or rebuild jar with corrected dependency graph"
    return 1
  fi
}

ensure_executable_jar() {
  if [[ -z "$JAR_FILE" ]]; then
    JAR_FILE="$(resolve_jar_candidate)"
  fi

  if [[ -n "$JAR_FILE" ]] && is_executable_jar "$JAR_FILE"; then
    validate_jar_runtime "$JAR_FILE"
    print_info "Using existing executable jar: $JAR_FILE"
    return 0
  fi

  if [[ "$SKIP_BUILD" == true ]]; then
    print_error "Executable jar not found and --skip-build enabled"
    return 1
  fi

  if [[ -z "${MVN_BIN:-}" ]]; then
    MVN_BIN="mvn"
  fi

  print_info "Building executable bedrock-app jar for smoke test..."
  local mvn_cmd=("$MVN_BIN")
  if [[ -n "${MVN_SETTINGS:-}" ]]; then
    mvn_cmd+=(--settings "$MVN_SETTINGS")
  fi
  if [[ -n "${MVN_REPO:-}" ]]; then
    mvn_cmd+=(-Dmaven.repo.local="$MVN_REPO")
  fi
  if [[ "${MVN_OFFLINE:-false}" == "true" ]]; then
    mvn_cmd+=(-o -nsu)
  fi
  mvn_cmd+=(-pl bedrock-app -am -DskipTests package)

  (
    cd "$PROJECT_ROOT"
    "${mvn_cmd[@]}"
  )

  JAR_FILE="$(resolve_jar_candidate)"
  if [[ -z "$JAR_FILE" ]] || ! is_executable_jar "$JAR_FILE"; then
    print_error "Failed to produce executable bedrock-app jar"
    return 1
  fi

  validate_jar_runtime "$JAR_FILE"
  print_info "Built executable jar: $JAR_FILE"
}

wait_health() {
  local name="$1"
  local port="$2"
  local url="http://127.0.0.1:${port}/actuator/health"
  local pid_file="$PROJECT_ROOT/logs/${name}.pid"
  local log_file="$PROJECT_ROOT/logs/${name}.out"
  local i

  for ((i=1; i<=TIMEOUT_SEC; i++)); do
    if [[ -f "$pid_file" ]]; then
      local pid
      pid="$(cat "$pid_file" 2>/dev/null || true)"
      if [[ -n "$pid" ]] && ! kill -0 "$pid" >/dev/null 2>&1; then
        print_error "${name} process exited before health became UP (pid=${pid})"
        if [[ -f "$log_file" ]]; then
          print_error "Recent ${name} logs:"
          tail -n 40 "$log_file" || true
        fi
        return 1
      fi
    fi

    if [[ -f "$log_file" ]] && grep -Eq "Operation not permitted|Address already in use|Protocol handler start failed|Web server failed to start" "$log_file"; then
      print_error "${name} log indicates startup failure before health is UP"
      print_error "Recent ${name} logs:"
      tail -n 40 "$log_file" || true
      return 1
    fi

    if command -v curl >/dev/null 2>&1; then
      local body
      body="$(curl -fsS "$url" 2>/dev/null || true)"
      if [[ "$body" == *"\"status\":\"UP\""* ]] || [[ "$body" == *"\"status\": \"UP\""* ]]; then
        print_info "${name} health is UP on ${url}"
        return 0
      fi
    fi
    sleep 1
  done

  print_error "Health check timeout for ${name}: ${url}"
  if [[ -f "$log_file" ]]; then
    print_error "Recent ${name} logs:"
    tail -n 20 "$log_file" || true
  fi
  return 1
}

wait_oms_recovery_ready() {
  local url="http://127.0.0.1:19082/api/v1/oms/recovery"
  local i
  local body

  for ((i=1; i<=TIMEOUT_SEC; i++)); do
    if command -v curl >/dev/null 2>&1; then
      body="$(curl -fsS "$url" 2>/dev/null || true)"
      if [[ -n "$body" ]]; then
        if command -v jq >/dev/null 2>&1; then
          if jq -e '
            type == "object"
            and (
              [to_entries[] | select((.value.recoveryReady // false) != true)] | length
            ) == 0
          ' >/dev/null 2>&1 <<< "$body"; then
            print_info "OMS startup recovery is ready on ${url}"
            return 0
          fi
        else
          if [[ "$body" != *"\"recoveryReady\":false"* ]] && [[ "$body" != *"\"recoveryReady\": false"* ]]; then
            print_info "OMS startup recovery appears ready on ${url} (jq not available, fallback check)"
            return 0
          fi
        fi
      fi
    fi
    sleep 1
  done

  print_error "OMS startup recovery readiness timeout: ${url}"
  if [[ -n "${body:-}" ]]; then
    print_error "Last recovery payload: $body"
  fi
  if [[ -f "$PROJECT_ROOT/logs/oms.out" ]]; then
    print_error "Recent oms logs:"
    tail -n 20 "$PROJECT_ROOT/logs/oms.out" || true
  fi
  return 1
}

ensure_executable_jar

print_info "Starting Phase 6 topology for smoke test..."
"$START_SCRIPT" \
  --force-restart \
  --profile "$PROFILE" \
  --bus-mode "$BUS_MODE" \
  --bus-stream-id "$BUS_STREAM_ID" \
  --bus-endpoint "$BUS_ENDPOINT" \
  --aeron-dir "$AERON_DIR" \
  --oms-exchange "$OMS_EXCHANGE" \
  --jar-file "$JAR_FILE"

check_pid_file "mds"
check_pid_file "pricing"
check_pid_file "oms"
check_pid_file "monitor"

wait_health "mds" "19080"
wait_health "pricing" "19081"
wait_health "oms" "19082"
wait_health "monitor" "19083"

if [[ "$SKIP_RECOVERY_CHECK" == false ]]; then
  wait_oms_recovery_ready
else
  print_info "Skipping OMS startup recovery check (--skip-recovery-check)"
fi

print_info "Phase 6 smoke checks passed"
if [[ "$KEEP_RUNNING" == true ]]; then
  print_info "Topology left running by request (--keep-running)"
fi
