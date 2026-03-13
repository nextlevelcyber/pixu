#!/bin/bash

# Bedrock Market Making System Stop Script

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show usage
show_usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Options:
    -i, --instance NAME  Stop specific instance [default: bedrock]
    -a, --all            Stop all Bedrock instances
    -f, --force     Force kill the process
    -h, --help      Show this help message

Examples:
    $0                       # Stop default instance (bedrock)
    $0 --instance pricing    # Stop named instance
    $0 --all                 # Stop all instances
    $0 -f                    # Force kill the application

EOF
}

# Default values
FORCE=false
INSTANCE_NAME="bedrock"
STOP_ALL=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -i|--instance)
            INSTANCE_NAME="$2"
            shift 2
            ;;
        -a|--all)
            STOP_ALL=true
            shift
            ;;
        -f|--force)
            FORCE=true
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

# PID file location
PID_FILE="$PROJECT_ROOT/logs/${INSTANCE_NAME}.pid"

# Use kill -0 for liveness checks to avoid ps restrictions in constrained environments.
process_exists() {
    local pid="$1"
    kill -0 "$pid" >/dev/null 2>&1
}

# Function to stop by PID file
stop_by_pid_file() {
    if [[ -f "$PID_FILE" ]]; then
        PID=$(cat "$PID_FILE")
        if process_exists "$PID"; then
            print_info "Stopping Bedrock application (PID: $PID)..."
            if [[ "$FORCE" == true ]]; then
                kill -9 "$PID"
                print_success "Application force killed"
            else
                kill -TERM "$PID"
                
                # Wait for graceful shutdown
                for i in {1..30}; do
                    if ! process_exists "$PID"; then
                        print_success "Application stopped gracefully"
                        break
                    fi
                    sleep 1
                done
                
                # Force kill if still running
                if process_exists "$PID"; then
                    print_warning "Graceful shutdown timeout, force killing..."
                    kill -9 "$PID"
                    print_success "Application force killed"
                fi
            fi
            rm -f "$PID_FILE"
        else
            print_warning "Process with PID $PID is not running"
            rm -f "$PID_FILE"
        fi
    else
        print_info "PID file not found for instance '$INSTANCE_NAME'"
        stop_by_instance_name
    fi
}

# Function to stop a specific instance by command-line marker
stop_by_instance_name() {
    local pattern="--logging.file.name=logs/${INSTANCE_NAME}.log"
    local pids
    pids=$(pgrep -f -- "$pattern" 2>/dev/null || true)

    if [[ -z "$pids" ]]; then
        print_info "No running process found for instance '$INSTANCE_NAME'"
        return 0
    fi

    print_info "Found instance '$INSTANCE_NAME' process(es): $pids"

    for pid in $pids; do
        print_info "Stopping process $pid..."
        if [[ "$FORCE" == true ]]; then
            kill -9 "$pid"
        else
            kill -TERM "$pid"
            for i in {1..30}; do
                if ! process_exists "$pid"; then
                    break
                fi
                sleep 1
            done
            if process_exists "$pid"; then
                print_warning "Graceful shutdown timeout for PID $pid, force killing..."
                kill -9 "$pid"
            fi
        fi
    done

    rm -f "$PID_FILE"
    print_success "Instance '$INSTANCE_NAME' stopped"
}

# Function to stop by process name
stop_by_process_name() {
    PIDS=$(pgrep -f "bedrock-app.*\.jar" 2>/dev/null || true)
    
    if [[ -z "$PIDS" ]]; then
        print_info "No Bedrock application processes found"
        return 0
    fi
    
    print_info "Found Bedrock processes: $PIDS"
    
    for PID in $PIDS; do
        print_info "Stopping process $PID..."
        if [[ "$FORCE" == true ]]; then
            kill -9 "$PID"
        else
            kill -TERM "$PID"
            
            # Wait for graceful shutdown
            for i in {1..30}; do
                if ! process_exists "$PID"; then
                    break
                fi
                sleep 1
            done
            
            # Force kill if still running
            if process_exists "$PID"; then
                print_warning "Graceful shutdown timeout for PID $PID, force killing..."
                kill -9 "$PID"
            fi
        fi
    done
    
    print_success "All Bedrock processes stopped"
}

# Function to cleanup resources
cleanup() {
    # Remove PID file(s)
    if [[ "$STOP_ALL" == true ]]; then
        rm -f "$PROJECT_ROOT"/logs/*.pid 2>/dev/null || true
    else
        if [[ -f "$PID_FILE" ]]; then
            rm -f "$PID_FILE"
        fi
    fi
    
    # Clean up any remaining lock files
    find "$PROJECT_ROOT" -name "*.lock" -type f -delete 2>/dev/null || true
}

# Main execution
print_info "Stopping Bedrock Market Making System..."

# Stop the application
if [[ "$STOP_ALL" == true ]]; then
    stop_by_process_name
else
    stop_by_pid_file
fi

# Cleanup
cleanup

print_success "Bedrock application stopped successfully"
