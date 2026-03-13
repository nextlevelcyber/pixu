#!/bin/bash

# Phase D decision package generator:
# - runs selected gate checks
# - collects execution logs
# - generates a Go/No-Go markdown report

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
REPORT_DIR="$PROJECT_ROOT/docs/reports"
TIMESTAMP="$(date +"%Y%m%d-%H%M%S")"
OUTPUT_FILE="$REPORT_DIR/phase-d-decision-${TIMESTAMP}.md"

RUN_PHASE1=true
RUN_PHASEC=true
RUN_PHASE6=false
STRICT=false
PROFILE="development"
PHASE1_LATENCY_URL=""
PHASE1_LATENCY_FILE=""
PHASE1_RECOVERY_URL=""
PHASE1_RECOVERY_FILE=""

PHASE1_STATUS="SKIPPED"
PHASE1_NOTE="not requested"
PHASE1_LOG=""

PHASEC_STATUS="SKIPPED"
PHASEC_NOTE="not requested"
PHASEC_LOG=""

PHASE6_STATUS="SKIPPED"
PHASE6_NOTE="not requested"
PHASE6_LOG=""

LATENCY_SUMMARY_FILE=""
RECOVERY_SUMMARY_FILE=""
CLEANUP_LATENCY_SUMMARY=false
CLEANUP_RECOVERY_SUMMARY=false

usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Options:
  --skip-phase1                 Skip Phase 1 gate
  --skip-phasec                 Skip Phase C gate
  --with-phase6                 Include Phase 6 smoke gate
  --profile PROFILE             Spring profile for Phase 6 smoke [default: development]
  --phase1-latency-url URL      Latency endpoint for Phase 1 gate
  --phase1-latency-file FILE    Latency snapshot file for Phase 1 gate
  --phase1-recovery-url URL     OMS recovery endpoint for Phase 1 gate
  --phase1-recovery-file FILE   OMS recovery snapshot file for Phase 1 gate
  -o, --output FILE             Output markdown report path
  --strict                      Exit non-zero if final decision is not GO_CANDIDATE
  -h, --help                    Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-phase1)
      RUN_PHASE1=false
      shift
      ;;
    --skip-phasec)
      RUN_PHASEC=false
      shift
      ;;
    --with-phase6)
      RUN_PHASE6=true
      shift
      ;;
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --phase1-latency-url)
      PHASE1_LATENCY_URL="$2"
      shift 2
      ;;
    --phase1-latency-file)
      PHASE1_LATENCY_FILE="$2"
      shift 2
      ;;
    --phase1-recovery-url)
      PHASE1_RECOVERY_URL="$2"
      shift 2
      ;;
    --phase1-recovery-file)
      PHASE1_RECOVERY_FILE="$2"
      shift 2
      ;;
    -o|--output)
      OUTPUT_FILE="$2"
      shift 2
      ;;
    --strict)
      STRICT=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

mkdir -p "$REPORT_DIR"

run_check() {
  local name="$1"
  local logfile="$REPORT_DIR/${TIMESTAMP}-${name}.log"
  shift
  "$@" >"$logfile" 2>&1
  local rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS|$logfile"
  else
    echo "FAIL|$logfile"
  fi
}

append_failure_excerpt() {
  local label="$1"
  local status="$2"
  local logfile="$3"
  if [[ "$status" != "FAIL" || -z "$logfile" || ! -f "$logfile" ]]; then
    return 0
  fi
  echo "### ${label}"
  echo
  echo '```text'
  tail -n 40 "$logfile"
  echo '```'
  echo
}

if [[ "$RUN_PHASE1" == true ]]; then
  phase1_env=()
  if [[ -n "$PHASE1_LATENCY_URL" ]]; then
    phase1_env+=("PHASE1_LATENCY_URL=$PHASE1_LATENCY_URL")
  elif [[ -n "$PHASE1_LATENCY_FILE" ]]; then
    phase1_env+=("PHASE1_LATENCY_FILE=$PHASE1_LATENCY_FILE")
  fi
  if [[ -n "$PHASE1_RECOVERY_URL" ]]; then
    phase1_env+=("PHASE1_RECOVERY_URL=$PHASE1_RECOVERY_URL")
  elif [[ -n "$PHASE1_RECOVERY_FILE" ]]; then
    phase1_env+=("PHASE1_RECOVERY_FILE=$PHASE1_RECOVERY_FILE")
  fi

  if [[ "${#phase1_env[@]}" -gt 0 ]]; then
    result="$(run_check phase1 env "${phase1_env[@]}" "$PROJECT_ROOT/scripts/phase1-gate.sh")"
  else
    result="$(run_check phase1 "$PROJECT_ROOT/scripts/phase1-gate.sh")"
  fi
  PHASE1_STATUS="${result%%|*}"
  PHASE1_LOG="${result#*|}"
  if [[ "$PHASE1_STATUS" == "PASS" ]]; then
    PHASE1_NOTE="phase1 gate passed"
  else
    PHASE1_NOTE="phase1 gate failed"
  fi
fi

if [[ "$RUN_PHASEC" == true ]]; then
  result="$(run_check phasec "$PROJECT_ROOT/scripts/phasec-gate.sh")"
  PHASEC_STATUS="${result%%|*}"
  PHASEC_LOG="${result#*|}"
  if [[ "$PHASEC_STATUS" == "PASS" ]]; then
    PHASEC_NOTE="phasec gate passed"
  else
    PHASEC_NOTE="phasec gate failed"
  fi
fi

if [[ "$RUN_PHASE6" == true ]]; then
  result="$(run_check phase6 "$PROJECT_ROOT/scripts/phase6-smoke.sh" --profile "$PROFILE")"
  PHASE6_STATUS="${result%%|*}"
  PHASE6_LOG="${result#*|}"
  if [[ "$PHASE6_STATUS" == "PASS" ]]; then
    PHASE6_NOTE="phase6 smoke passed"
  else
    PHASE6_NOTE="phase6 smoke failed"
  fi
fi

GIT_SHA="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
GIT_BRANCH="$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
DIRTY_COUNT="$(git -C "$PROJECT_ROOT" status --short 2>/dev/null | wc -l | tr -d ' ')"

FINAL_DECISION="GO_CANDIDATE"
DECISION_REASON="all selected checks passed"

if [[ "$PHASE1_STATUS" == "FAIL" || "$PHASEC_STATUS" == "FAIL" || "$PHASE6_STATUS" == "FAIL" ]]; then
  FINAL_DECISION="NO_GO"
  DECISION_REASON="at least one mandatory check failed"
elif [[ "$RUN_PHASE6" == false ]]; then
  FINAL_DECISION="HOLD"
  DECISION_REASON="phase6 smoke not executed in this package"
fi

if [[ -n "$PHASE1_LATENCY_FILE" && -f "$PHASE1_LATENCY_FILE" ]]; then
  LATENCY_SUMMARY_FILE="$PHASE1_LATENCY_FILE"
elif [[ -n "$PHASE1_LATENCY_URL" ]]; then
  tmp_latency_file="$(mktemp /tmp/phase-d-latency.XXXXXX.json)"
  if curl -fsS "$PHASE1_LATENCY_URL" > "$tmp_latency_file"; then
    LATENCY_SUMMARY_FILE="$tmp_latency_file"
    CLEANUP_LATENCY_SUMMARY=true
  else
    echo "[WARN] Failed to fetch latency summary from URL: $PHASE1_LATENCY_URL" >&2
    rm -f "$tmp_latency_file"
  fi
fi

if [[ -n "$PHASE1_RECOVERY_FILE" && -f "$PHASE1_RECOVERY_FILE" ]]; then
  RECOVERY_SUMMARY_FILE="$PHASE1_RECOVERY_FILE"
elif [[ -n "$PHASE1_RECOVERY_URL" ]]; then
  tmp_recovery_file="$(mktemp /tmp/phase-d-recovery.XXXXXX.json)"
  if curl -fsS "$PHASE1_RECOVERY_URL" > "$tmp_recovery_file"; then
    RECOVERY_SUMMARY_FILE="$tmp_recovery_file"
    CLEANUP_RECOVERY_SUMMARY=true
  else
    echo "[WARN] Failed to fetch recovery summary from URL: $PHASE1_RECOVERY_URL" >&2
    rm -f "$tmp_recovery_file"
  fi
fi

{
  echo "# Phase D Decision Package"
  echo
  echo "- GeneratedAt: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "- GitBranch: \`$GIT_BRANCH\`"
  echo "- GitSha: \`$GIT_SHA\`"
  echo "- WorkingTreeDirtyFiles: \`$DIRTY_COUNT\`"
  echo
  echo "## Final Decision"
  echo
  echo "- Decision: **$FINAL_DECISION**"
  echo "- Reason: $DECISION_REASON"
  echo
  echo "## Gate Results"
  echo
  echo "| Check | Status | Note | Log |"
  echo "|---|---|---|---|"
  echo "| Phase1 Gate | $PHASE1_STATUS | $PHASE1_NOTE | ${PHASE1_LOG:-N/A} |"
  echo "| PhaseC Gate | $PHASEC_STATUS | $PHASEC_NOTE | ${PHASEC_LOG:-N/A} |"
  echo "| Phase6 Smoke | $PHASE6_STATUS | $PHASE6_NOTE | ${PHASE6_LOG:-N/A} |"
  echo

  if [[ -n "$LATENCY_SUMMARY_FILE" && -f "$LATENCY_SUMMARY_FILE" && -x "$(command -v jq)" ]]; then
    echo "## Latency Snapshot Summary"
    echo
    echo "| Symbol | execToStateP99Ns | execToStateP999Ns | ackToStateP99Ns | quoteToAckP99Ns |"
    echo "|---|---:|---:|---:|---:|"
    jq -r '
      to_entries[]
      | "| \(.key) | \(.value.execToStateP99Ns // 0) | \(.value.execToStateP999Ns // 0) | \(.value.ackToStateP99Ns // 0) | \(.value.quoteToAckP99Ns // 0) |"
    ' "$LATENCY_SUMMARY_FILE"
    echo
  fi

  if [[ -n "$RECOVERY_SUMMARY_FILE" && -f "$RECOVERY_SUMMARY_FILE" && -x "$(command -v jq)" ]]; then
    echo "## Startup Recovery Snapshot Summary"
    echo
    echo "| Symbol | recoveryReady | inProgress | attempts | success | failure | quoteBlocked | lastError |"
    echo "|---|---|---|---:|---:|---:|---:|---|"
    jq -r '
      to_entries[]
      | .key as $symbol
      | .value as $r
      | "| \($symbol) | \($r.recoveryReady // false) | \($r.recoveryInProgress // false) | \($r.recoveryAttempts // 0) | \($r.recoverySuccess // 0) | \($r.recoveryFailure // 0) | \($r.quoteTargetsBlocked // 0) | \((($r.lastRecoveryError // "") | gsub("\\|"; "\\\\|"))) |"
    ' "$RECOVERY_SUMMARY_FILE"
    echo
  fi

  if [[ "$PHASE1_STATUS" == "FAIL" || "$PHASEC_STATUS" == "FAIL" || "$PHASE6_STATUS" == "FAIL" ]]; then
    echo "## Failure Excerpts"
    echo
    append_failure_excerpt "Phase1 Gate" "$PHASE1_STATUS" "${PHASE1_LOG:-}"
    append_failure_excerpt "PhaseC Gate" "$PHASEC_STATUS" "${PHASEC_LOG:-}"
    append_failure_excerpt "Phase6 Smoke" "$PHASE6_STATUS" "${PHASE6_LOG:-}"
  fi

  echo "## Known Risks To Review Before Real-Money Trading"
  echo
  echo "- ExecGateway REST/WS integration still needs long-duration (24h+) chaos validation on real network conditions."
  echo "- If Phase6 smoke is skipped, cross-process reliability is not yet release-grade."
  echo "- Working tree is dirty; ensure final release artifacts are built from a reviewed and tagged commit."
  echo
  echo "## Recommended Next Actions"
  echo
  echo "1. Run this package with \`--with-phase6\` on a host where local port binding is allowed."
  echo "2. Attach latency/recovery JSON and re-run with \`--phase1-latency-file\` + \`--phase1-recovery-file\` to include readiness evidence."
  echo "3. Freeze release candidate commit and archive this report with its gate logs."
} >"$OUTPUT_FILE"

echo "[INFO] Decision package generated: $OUTPUT_FILE"
echo "[INFO] Final decision: $FINAL_DECISION ($DECISION_REASON)"

if [[ "$CLEANUP_LATENCY_SUMMARY" == true ]]; then
  rm -f "$LATENCY_SUMMARY_FILE"
fi
if [[ "$CLEANUP_RECOVERY_SUMMARY" == true ]]; then
  rm -f "$RECOVERY_SUMMARY_FILE"
fi

if [[ "$STRICT" == true && "$FINAL_DECISION" != "GO_CANDIDATE" ]]; then
  exit 1
fi

exit 0
