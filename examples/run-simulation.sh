#!/bin/bash

# Bedrock Market Making System - Simulation Example

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

print_banner() {
    cat << 'EOF'

╔══════════════════════════════════════════════════════════════╗
║                    BEDROCK SIMULATION DEMO                  ║
║                                                              ║
║  This example demonstrates the Bedrock Market Making        ║
║  System running in simulation mode with:                    ║
║  • Simulated market data for BTCUSDT, ETHUSDT, ADAUSDT     ║
║  • Simple market making strategy                            ║
║  • Paper trading adapter                                    ║
║  • Real-time monitoring and metrics                         ║
╚══════════════════════════════════════════════════════════════╝

EOF
}

# Function to wait for service to be ready
wait_for_service() {
    local url=$1
    local service_name=$2
    local max_attempts=30
    local attempt=1
    
    print_info "Waiting for $service_name to be ready..."
    
    while [[ $attempt -le $max_attempts ]]; do
        if curl -s -f "$url" > /dev/null 2>&1; then
            print_success "$service_name is ready!"
            return 0
        fi
        
        print_info "Attempt $attempt/$max_attempts - $service_name not ready yet..."
        sleep 2
        ((attempt++))
    done
    
    print_error "$service_name failed to start within timeout"
    return 1
}

# Function to display API endpoints
show_endpoints() {
    local base_url="http://localhost:8080/api"
    
    print_info "Available API Endpoints:"
    echo "  Application Status:    $base_url/v1/status"
    echo "  Health Check:          $base_url/v1/health"
    echo "  Strategy Management:   $base_url/v1/strategies"
    echo "  Adapter Management:    $base_url/v1/adapters"
    echo "  Market Data:           $base_url/v1/md"
    echo "  Monitoring:            http://localhost:8081/actuator"
    echo ""
}

# Function to demonstrate API calls
demo_api_calls() {
    local base_url="http://localhost:8080/api"
    
    print_info "Demonstrating API calls..."
    
    # Application status
    print_info "1. Getting application status..."
    curl -s "$base_url/v1/status" | python3 -m json.tool 2>/dev/null || curl -s "$base_url/v1/status"
    echo ""
    
    # Health check
    print_info "2. Checking application health..."
    curl -s "$base_url/v1/health" | python3 -m json.tool 2>/dev/null || curl -s "$base_url/v1/health"
    echo ""
    
    # Strategy status
    print_info "3. Getting strategy status..."
    curl -s "$base_url/v1/strategies" | python3 -m json.tool 2>/dev/null || curl -s "$base_url/v1/strategies"
    echo ""
    
    # Adapter status
    print_info "4. Getting adapter status..."
    curl -s "$base_url/v1/adapters" | python3 -m json.tool 2>/dev/null || curl -s "$base_url/v1/adapters"
    echo ""
    
    # Account balances
    print_info "5. Getting account balances..."
    curl -s "$base_url/v1/adapters/balances" | python3 -m json.tool 2>/dev/null || curl -s "$base_url/v1/adapters/balances"
    echo ""
    
    # Positions
    print_info "6. Getting positions..."
    curl -s "$base_url/v1/adapters/positions" | python3 -m json.tool 2>/dev/null || curl -s "$base_url/v1/adapters/positions"
    echo ""
}

# Function to monitor system
monitor_system() {
    print_info "Monitoring system for 60 seconds..."
    print_info "Press Ctrl+C to stop monitoring early"
    
    local count=0
    local max_count=12  # 60 seconds / 5 seconds
    
    while [[ $count -lt $max_count ]]; do
        echo ""
        print_info "=== Monitoring Update $(($count + 1))/$max_count ==="
        
        # Get strategy stats
        local strategy_stats=$(curl -s "http://localhost:8080/api/v1/strategies/stats" 2>/dev/null || echo "{}")
        print_info "Strategy Stats: $strategy_stats"
        
        # Get adapter stats
        local adapter_stats=$(curl -s "http://localhost:8080/api/v1/adapters/stats" 2>/dev/null || echo "{}")
        print_info "Adapter Stats: $adapter_stats"
        
        sleep 5
        ((count++))
    done
}

# Main execution
main() {
    print_banner
    
    # Check if application is already running
    if curl -s -f "http://localhost:8080/api/v1/status" > /dev/null 2>&1; then
        print_warning "Application appears to be already running"
        read -p "Do you want to continue with the demo? (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 0
        fi
    else
        # Start the application
        print_info "Starting Bedrock Market Making System in simulation mode..."
        
        # Build if necessary
        if [[ ! -f "$PROJECT_ROOT/bedrock-app/target/bedrock-app-1.0.0.jar" ]]; then
            print_info "Building application..."
            "$PROJECT_ROOT/scripts/build.sh" -t
        fi
        
        # Start in background
        "$PROJECT_ROOT/scripts/start.sh" -p development -m SIMULATION -b
        
        # Wait for service to be ready
        if ! wait_for_service "http://localhost:8080/api/v1/health" "Bedrock Application"; then
            print_error "Failed to start application"
            exit 1
        fi
    fi
    
    # Show endpoints
    show_endpoints
    
    # Demo API calls
    demo_api_calls
    
    # Monitor system
    monitor_system
    
    print_success "Simulation demo completed!"
    print_info "The application is still running in the background."
    print_info "Use './scripts/stop.sh' to stop the application."
    print_info "Or visit the endpoints above to continue exploring."
}

# Trap Ctrl+C
trap 'print_info "Demo interrupted by user"; exit 0' INT

# Run main function
main "$@"