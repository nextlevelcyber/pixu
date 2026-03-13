#!/bin/bash

# Bedrock Market Making System - API Test Example

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# Configuration
BASE_URL="http://localhost:8080/api"
MANAGEMENT_URL="http://localhost:8081"

# Function to test API endpoint
test_endpoint() {
    local method=$1
    local endpoint=$2
    local description=$3
    local expected_status=${4:-200}
    local data=${5:-""}
    
    print_info "Testing: $description"
    echo "  Method: $method"
    echo "  Endpoint: $endpoint"
    
    local curl_cmd="curl -s -w '%{http_code}' -o response.tmp"
    
    if [[ "$method" == "POST" || "$method" == "PUT" ]]; then
        curl_cmd="$curl_cmd -X $method -H 'Content-Type: application/json'"
        if [[ -n "$data" ]]; then
            curl_cmd="$curl_cmd -d '$data'"
        fi
    elif [[ "$method" == "DELETE" ]]; then
        curl_cmd="$curl_cmd -X $method"
    fi
    
    curl_cmd="$curl_cmd '$endpoint'"
    
    local status_code
    status_code=$(eval "$curl_cmd")
    
    if [[ "$status_code" == "$expected_status" ]]; then
        print_success "✓ Status: $status_code (Expected: $expected_status)"
        
        # Pretty print JSON response if possible
        if command -v python3 &> /dev/null && [[ -s response.tmp ]]; then
            echo "  Response:"
            python3 -m json.tool response.tmp 2>/dev/null | head -20 || cat response.tmp | head -10
        else
            echo "  Response:"
            cat response.tmp | head -10
        fi
    else
        print_error "✗ Status: $status_code (Expected: $expected_status)"
        echo "  Response:"
        cat response.tmp
    fi
    
    echo ""
    rm -f response.tmp
}

# Function to check if service is running
check_service() {
    print_info "Checking if Bedrock service is running..."
    
    if curl -s -f "$BASE_URL/v1/health" > /dev/null 2>&1; then
        print_success "Service is running"
        return 0
    else
        print_error "Service is not running or not accessible"
        print_info "Please start the service first using: ./scripts/start.sh"
        return 1
    fi
}

# Function to run comprehensive API tests
run_api_tests() {
    print_info "Running comprehensive API tests..."
    echo ""
    
    # Application Management APIs
    print_info "=== Application Management APIs ==="
    test_endpoint "GET" "$BASE_URL/v1/status" "Get application status"
    test_endpoint "GET" "$BASE_URL/v1/health" "Get health status"
    test_endpoint "POST" "$BASE_URL/v1/restart" "Restart services" 200
    
    # Strategy Management APIs
    print_info "=== Strategy Management APIs ==="
    test_endpoint "GET" "$BASE_URL/v1/strategies" "Get all strategies"
    test_endpoint "GET" "$BASE_URL/v1/strategies/running" "Get running strategies"
    test_endpoint "GET" "$BASE_URL/v1/strategies/stats" "Get strategy statistics"
    test_endpoint "POST" "$BASE_URL/v1/strategies/start" "Start all strategies"
    test_endpoint "POST" "$BASE_URL/v1/strategies/stop" "Stop all strategies"
    test_endpoint "POST" "$BASE_URL/v1/strategies/reset" "Reset strategies"
    
    # Adapter Management APIs
    print_info "=== Adapter Management APIs ==="
    test_endpoint "GET" "$BASE_URL/v1/adapters" "Get all adapters"
    test_endpoint "GET" "$BASE_URL/v1/adapters/stats" "Get adapter statistics"
    test_endpoint "GET" "$BASE_URL/v1/adapters/symbols" "Get supported symbols"
    test_endpoint "GET" "$BASE_URL/v1/adapters/balances" "Get account balances"
    test_endpoint "GET" "$BASE_URL/v1/adapters/positions" "Get positions"
    
    # Market Data APIs (if available)
    print_info "=== Market Data APIs ==="
    test_endpoint "GET" "$BASE_URL/v1/md/status" "Get market data status" 200
    test_endpoint "GET" "$BASE_URL/v1/md/symbols" "Get available symbols" 200
    
    # Actuator endpoints
    print_info "=== Actuator Endpoints ==="
    test_endpoint "GET" "$MANAGEMENT_URL/actuator/health" "Actuator health"
    test_endpoint "GET" "$MANAGEMENT_URL/actuator/info" "Actuator info"
    test_endpoint "GET" "$MANAGEMENT_URL/actuator/metrics" "Actuator metrics"
}

# Function to run performance tests
run_performance_tests() {
    print_info "Running performance tests..."
    echo ""
    
    local endpoint="$BASE_URL/v1/status"
    local requests=100
    local concurrency=10
    
    print_info "Performance test: $requests requests with concurrency $concurrency"
    
    if command -v ab &> /dev/null; then
        ab -n $requests -c $concurrency "$endpoint"
    elif command -v curl &> /dev/null; then
        print_info "Apache Bench not available, running simple curl test..."
        
        local start_time=$(date +%s%N)
        for i in $(seq 1 10); do
            curl -s "$endpoint" > /dev/null
        done
        local end_time=$(date +%s%N)
        
        local duration=$(( (end_time - start_time) / 1000000 ))
        local avg_time=$(( duration / 10 ))
        
        print_info "10 requests completed in ${duration}ms (avg: ${avg_time}ms per request)"
    else
        print_warning "No performance testing tools available"
    fi
}

# Function to run load tests
run_load_tests() {
    print_info "Running load tests..."
    echo ""
    
    local endpoints=(
        "$BASE_URL/v1/status"
        "$BASE_URL/v1/health"
        "$BASE_URL/v1/strategies"
        "$BASE_URL/v1/adapters"
    )
    
    print_info "Testing multiple endpoints concurrently..."
    
    for endpoint in "${endpoints[@]}"; do
        print_info "Load testing: $endpoint"
        
        # Run 5 concurrent requests
        for i in {1..5}; do
            curl -s "$endpoint" > /dev/null &
        done
        
        wait
        print_success "Completed load test for $endpoint"
    done
}

# Function to test error scenarios
test_error_scenarios() {
    print_info "Testing error scenarios..."
    echo ""
    
    # Test non-existent endpoints
    test_endpoint "GET" "$BASE_URL/v1/nonexistent" "Non-existent endpoint" 404
    
    # Test invalid methods
    test_endpoint "DELETE" "$BASE_URL/v1/status" "Invalid method on status endpoint" 405
    
    # Test invalid JSON (if applicable)
    test_endpoint "POST" "$BASE_URL/v1/strategies/start" "Invalid request body" 400 '{"invalid": json}'
}

# Function to show usage
show_usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Options:
    -a, --all           Run all tests (default)
    -b, --basic         Run basic API tests only
    -p, --performance   Run performance tests
    -l, --load          Run load tests
    -e, --errors        Test error scenarios
    -h, --help          Show this help message

Examples:
    $0                  # Run all tests
    $0 -b               # Run basic tests only
    $0 -p               # Run performance tests
    $0 -e               # Test error scenarios

EOF
}

# Parse command line arguments
RUN_BASIC=false
RUN_PERFORMANCE=false
RUN_LOAD=false
RUN_ERRORS=false
RUN_ALL=true

while [[ $# -gt 0 ]]; do
    case $1 in
        -a|--all)
            RUN_ALL=true
            shift
            ;;
        -b|--basic)
            RUN_BASIC=true
            RUN_ALL=false
            shift
            ;;
        -p|--performance)
            RUN_PERFORMANCE=true
            RUN_ALL=false
            shift
            ;;
        -l|--load)
            RUN_LOAD=true
            RUN_ALL=false
            shift
            ;;
        -e|--errors)
            RUN_ERRORS=true
            RUN_ALL=false
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

# Main execution
main() {
    print_info "Bedrock Market Making System - API Test Suite"
    echo ""
    
    # Check if service is running
    if ! check_service; then
        exit 1
    fi
    
    echo ""
    
    # Run tests based on options
    if [[ "$RUN_ALL" == true ]]; then
        run_api_tests
        run_performance_tests
        run_load_tests
        test_error_scenarios
    else
        if [[ "$RUN_BASIC" == true ]]; then
            run_api_tests
        fi
        
        if [[ "$RUN_PERFORMANCE" == true ]]; then
            run_performance_tests
        fi
        
        if [[ "$RUN_LOAD" == true ]]; then
            run_load_tests
        fi
        
        if [[ "$RUN_ERRORS" == true ]]; then
            test_error_scenarios
        fi
    fi
    
    print_success "API testing completed!"
}

# Run main function
main "$@"