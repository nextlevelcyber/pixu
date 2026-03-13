#!/bin/bash

# Stop Phase 6 topology instances (MDS/Pricing/OMS/Monitor).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
STOP_SCRIPT="$PROJECT_ROOT/scripts/stop.sh"

FORCE=false

show_usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Options:
  -f, --force    Force kill processes
  -h, --help     Show this help

Examples:
  $0
  $0 --force
EOF
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -f|--force)
      FORCE=true
      shift
      ;;
    -h|--help)
      show_usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown option: $1"
      show_usage
      exit 1
      ;;
  esac
done

if [[ ! -x "$STOP_SCRIPT" ]]; then
  echo "[ERROR] Missing stop script: $STOP_SCRIPT"
  exit 1
fi

stop_one() {
  local name="$1"
  if [[ "$FORCE" == true ]]; then
    "$STOP_SCRIPT" --instance "$name" --force || true
  else
    "$STOP_SCRIPT" --instance "$name" || true
  fi
}

stop_one "monitor"
stop_one "oms"
stop_one "pricing"
stop_one "mds"

echo "[INFO] Phase 6 topology stopped."
