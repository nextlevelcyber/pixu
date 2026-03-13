#!/bin/bash

# Bedrock Market Making System Test Script

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
    -u, --unit              Run unit tests only
    -i, --integration       Run integration tests only
    -c, --coverage          Generate test coverage report
    -f, --fast              Skip slow tests
    --phase1-gate           Run Phase 1 OMS gate after tests
    --phasec-gate           Run Phase C OMS hardening gate after tests
    --phase-d-pack          Generate Phase D decision package after tests
    --phase1-latency-url U  Latency snapshot URL for Phase 1 threshold checks
    --phase1-latency-file F Latency snapshot JSON file for Phase 1 threshold checks
    --phase1-recovery-url U OMS recovery snapshot URL for Phase 1 recovery checks
    --phase1-recovery-file F OMS recovery snapshot JSON file for Phase 1 recovery checks
    --phase6-smoke          Run Phase 6 4-process smoke test after tests
    -m, --module MODULE     Test specific module (sbe, common, aeron, monitor, md, oms, pricing, strategy, adapter, app)
    -t, --test TEST         Run specific test class or method
    -p, --profile PROFILE   Use specific Maven profile [default: none]
    -v, --verbose           Verbose output
    -h, --help              Show this help message

Examples:
    $0                      # Run all tests
    $0 -u                   # Run unit tests only
    $0 -i                   # Run integration tests only
    $0 -c                   # Run tests with coverage
    $0 -m app               # Test app module only
    $0 -t BedrockApplicationTest  # Run specific test class
    $0 --phase1-gate --phase1-latency-url http://127.0.0.1:8080/api/v1/oms/latency
    $0 --phasec-gate
    $0 --phase-d-pack
    $0 --phase6-smoke

EOF
}

# Default values
UNIT_ONLY=false
INTEGRATION_ONLY=false
COVERAGE=false
FAST=false
PHASE1_GATE=false
PHASEC_GATE=false
PHASED_PACK=false
PHASE1_LATENCY_URL=""
PHASE1_LATENCY_FILE=""
PHASE1_RECOVERY_URL=""
PHASE1_RECOVERY_FILE=""
PHASE6_SMOKE=false
MODULE=""
TEST=""
PROFILE=""
VERBOSE=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -u|--unit)
            UNIT_ONLY=true
            shift
            ;;
        -i|--integration)
            INTEGRATION_ONLY=true
            shift
            ;;
        -c|--coverage)
            COVERAGE=true
            shift
            ;;
        -f|--fast)
            FAST=true
            shift
            ;;
        --phase1-gate)
            PHASE1_GATE=true
            shift
            ;;
        --phasec-gate)
            PHASEC_GATE=true
            shift
            ;;
        --phase-d-pack)
            PHASED_PACK=true
            shift
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
        --phase6-smoke)
            PHASE6_SMOKE=true
            shift
            ;;
        -m|--module)
            MODULE="$2"
            shift 2
            ;;
        -t|--test)
            TEST="$2"
            shift 2
            ;;
        -p|--profile)
            PROFILE="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE=true
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

# Validate module if specified
if [[ -n "$MODULE" ]]; then
    VALID_MODULES=("sbe" "common" "aeron" "monitor" "md" "oms" "pricing" "strategy" "adapter" "app")
    if [[ ! " ${VALID_MODULES[@]} " =~ " ${MODULE} " ]]; then
        print_error "Invalid module: $MODULE. Valid modules: ${VALID_MODULES[*]}"
        exit 1
    fi
fi

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    print_error "Maven is not installed or not in PATH"
    exit 1
fi

# Change to project root
cd "$PROJECT_ROOT"

# Build Maven command
MVN_CMD="mvn"

# Add Maven profile if specified
if [[ -n "$PROFILE" ]]; then
    MVN_CMD="$MVN_CMD -P$PROFILE"
fi

# Add verbose flag if requested
if [[ "$VERBOSE" == true ]]; then
    MVN_CMD="$MVN_CMD -X"
fi

# Add fast flag if requested
if [[ "$FAST" == true ]]; then
    MVN_CMD="$MVN_CMD -Dfast=true"
fi

# Determine test phase
if [[ "$UNIT_ONLY" == true ]]; then
    TEST_PHASE="test"
    print_info "Running unit tests only..."
elif [[ "$INTEGRATION_ONLY" == true ]]; then
    TEST_PHASE="integration-test"
    print_info "Running integration tests only..."
else
    TEST_PHASE="verify"
    print_info "Running all tests..."
fi

# Add coverage if requested
if [[ "$COVERAGE" == true ]]; then
    MVN_CMD="$MVN_CMD jacoco:prepare-agent"
    print_info "Test coverage enabled"
fi

# Add specific module if specified
if [[ -n "$MODULE" ]]; then
    MVN_CMD="$MVN_CMD -pl bedrock-$MODULE"
    print_info "Testing module: $MODULE"
fi

# Add specific test if specified
if [[ -n "$TEST" ]]; then
    MVN_CMD="$MVN_CMD -Dtest=$TEST"
    print_info "Running specific test: $TEST"
fi

# Final Maven command
MVN_CMD="$MVN_CMD $TEST_PHASE"

print_info "Executing: $MVN_CMD"
print_info "Starting tests..."

# Run tests
START_TIME=$(date +%s)

if $MVN_CMD; then
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    print_success "All tests passed! Duration: ${DURATION}s"
    
    # Generate coverage report if requested
    if [[ "$COVERAGE" == true ]]; then
        print_info "Generating coverage report..."
        mvn jacoco:report
        
        # Find and display coverage report location
        COVERAGE_REPORT="$PROJECT_ROOT/target/site/jacoco/index.html"
        if [[ -f "$COVERAGE_REPORT" ]]; then
            print_success "Coverage report generated: $COVERAGE_REPORT"
        fi
        
        # Display coverage summary
        if command -v grep &> /dev/null; then
            COVERAGE_FILES=$(find . -name "jacoco.csv" 2>/dev/null || true)
            if [[ -n "$COVERAGE_FILES" ]]; then
                print_info "Coverage Summary:"
                for file in $COVERAGE_FILES; do
                    MODULE_NAME=$(dirname "$file" | sed 's|.*/||')
                    if [[ -f "$file" ]]; then
                        # Extract coverage percentage (simplified)
                        LINES=$(tail -n +2 "$file" | wc -l)
                        if [[ $LINES -gt 0 ]]; then
                            print_info "  $MODULE_NAME: Coverage data available"
                        fi
                    fi
                done
            fi
        fi
    fi

    # Display test results summary
    print_info "Test Results Summary:"
    
    # Find surefire reports
    SUREFIRE_REPORTS=$(find . -path "*/target/surefire-reports" -type d 2>/dev/null || true)
    if [[ -n "$SUREFIRE_REPORTS" ]]; then
        for report_dir in $SUREFIRE_REPORTS; do
            MODULE_NAME=$(echo "$report_dir" | sed 's|.*/\([^/]*\)/target/surefire-reports|\1|')
            XML_FILES=$(find "$report_dir" -name "TEST-*.xml" 2>/dev/null || true)
            if [[ -n "$XML_FILES" ]]; then
                TOTAL_TESTS=0
                TOTAL_FAILURES=0
                TOTAL_ERRORS=0
                
                for xml_file in $XML_FILES; do
                    if [[ -f "$xml_file" ]]; then
                        TESTS=$(grep -o 'tests="[0-9]*"' "$xml_file" | grep -o '[0-9]*' || echo "0")
                        FAILURES=$(grep -o 'failures="[0-9]*"' "$xml_file" | grep -o '[0-9]*' || echo "0")
                        ERRORS=$(grep -o 'errors="[0-9]*"' "$xml_file" | grep -o '[0-9]*' || echo "0")
                        
                        TOTAL_TESTS=$((TOTAL_TESTS + TESTS))
                        TOTAL_FAILURES=$((TOTAL_FAILURES + FAILURES))
                        TOTAL_ERRORS=$((TOTAL_ERRORS + ERRORS))
                    fi
                done
                
                if [[ $TOTAL_TESTS -gt 0 ]]; then
                    print_info "  $MODULE_NAME: $TOTAL_TESTS tests, $TOTAL_FAILURES failures, $TOTAL_ERRORS errors"
                fi
            fi
        done
    fi

    if [[ "$PHASE1_GATE" == true ]]; then
        print_info "Running Phase 1 OMS gate..."
        GATE_CMD=("$PROJECT_ROOT/scripts/phase1-gate.sh")
        GATE_ENV=()
        if [[ -n "$PHASE1_LATENCY_URL" ]]; then
            print_info "Phase 1 latency source URL: $PHASE1_LATENCY_URL"
            GATE_ENV+=("PHASE1_LATENCY_URL=$PHASE1_LATENCY_URL")
        elif [[ -n "$PHASE1_LATENCY_FILE" ]]; then
            print_info "Phase 1 latency source file: $PHASE1_LATENCY_FILE"
            GATE_ENV+=("PHASE1_LATENCY_FILE=$PHASE1_LATENCY_FILE")
        fi
        if [[ -n "$PHASE1_RECOVERY_URL" ]]; then
            print_info "Phase 1 recovery source URL: $PHASE1_RECOVERY_URL"
            GATE_ENV+=("PHASE1_RECOVERY_URL=$PHASE1_RECOVERY_URL")
        elif [[ -n "$PHASE1_RECOVERY_FILE" ]]; then
            print_info "Phase 1 recovery source file: $PHASE1_RECOVERY_FILE"
            GATE_ENV+=("PHASE1_RECOVERY_FILE=$PHASE1_RECOVERY_FILE")
        fi
        if [[ ${#GATE_ENV[@]} -gt 0 ]]; then
            env "${GATE_ENV[@]}" "${GATE_CMD[@]}"
        else
            "${GATE_CMD[@]}"
        fi
        print_success "Phase 1 OMS gate passed"
    fi

    if [[ "$PHASEC_GATE" == true ]]; then
        print_info "Running Phase C OMS hardening gate..."
        "$PROJECT_ROOT/scripts/phasec-gate.sh"
        print_success "Phase C OMS hardening gate passed"
    fi

    if [[ "$PHASED_PACK" == true ]]; then
        print_info "Generating Phase D decision package..."
        PACK_CMD=("$PROJECT_ROOT/scripts/phase-d-pack.sh")
        if [[ -n "$PHASE1_LATENCY_URL" ]]; then
            PACK_CMD+=(--phase1-latency-url "$PHASE1_LATENCY_URL")
        elif [[ -n "$PHASE1_LATENCY_FILE" ]]; then
            PACK_CMD+=(--phase1-latency-file "$PHASE1_LATENCY_FILE")
        fi
        if [[ -n "$PHASE1_RECOVERY_URL" ]]; then
            PACK_CMD+=(--phase1-recovery-url "$PHASE1_RECOVERY_URL")
        elif [[ -n "$PHASE1_RECOVERY_FILE" ]]; then
            PACK_CMD+=(--phase1-recovery-file "$PHASE1_RECOVERY_FILE")
        fi
        "${PACK_CMD[@]}"
        print_success "Phase D decision package generated"
    fi

    if [[ "$PHASE6_SMOKE" == true ]]; then
        print_info "Running Phase 6 4-process smoke test..."
        "$PROJECT_ROOT/scripts/phase6-smoke.sh" --profile "$PROFILE"
        print_success "Phase 6 smoke test passed"
    fi
    
else
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    print_error "Tests failed! Duration: ${DURATION}s"
    
    # Display failure summary
    print_info "Checking for test failures..."
    
    # Find and display failed tests
    SUREFIRE_REPORTS=$(find . -path "*/target/surefire-reports" -type d 2>/dev/null || true)
    if [[ -n "$SUREFIRE_REPORTS" ]]; then
        for report_dir in $SUREFIRE_REPORTS; do
            TXT_FILES=$(find "$report_dir" -name "*.txt" 2>/dev/null || true)
            for txt_file in $TXT_FILES; do
                if [[ -f "$txt_file" ]] && grep -q "FAILURE\|ERROR" "$txt_file" 2>/dev/null; then
                    print_error "Failed test: $(basename "$txt_file" .txt)"
                    # Show first few lines of failure
                    head -10 "$txt_file" | while read -r line; do
                        print_error "  $line"
                    done
                fi
            done
        done
    fi
    
    exit 1
fi
