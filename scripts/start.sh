#!/bin/bash

# Bedrock Market Making System Startup Script

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
PROFILE="development"
MODE="FULL"
PORT="8080"
MANAGEMENT_PORT="8081"
JVM_OPTS=""
DEBUG_PORT=""
BACKGROUND=false
MONITOR_ENABLED=true
BUS_MODE="IN_PROC"
BUS_STREAM_ID="9000"
BUS_ENDPOINT="aeron:udp?endpoint=localhost:40200"
INSTANCE_NAME="bedrock"
JAR_FILE_OVERRIDE=""
NO_BUILD=false

# Maven build options (used only when app jar is missing)
MVN_BIN="${MVN_BIN:-mvn}"
MVN_SETTINGS="${MVN_SETTINGS:-}"
MVN_REPO="${MVN_REPO:-}"
MVN_OFFLINE="${MVN_OFFLINE:-false}"

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
    -p, --profile PROFILE       Spring profile (development, production, test) [default: development]
    -m, --mode MODE            Application mode (FULL, MARKET_DATA_ONLY, STRATEGY_ONLY, ADAPTER_ONLY, SIMULATION) [default: FULL]
    -P, --port PORT            Server port [default: 8080]
    -M, --management-port PORT Management port [default: 8081]
    -I, --instance NAME        Instance name for pid/log files [default: bedrock]
    -j, --jvm-opts OPTS        Additional JVM options
    -d, --debug PORT           Enable debug mode on specified port
    -b, --background           Run in background
    -x, --disable-monitor      Disable monitoring (avoid Chronicle Map init)
    --jar-file PATH            Use specific application jar (must be executable Spring Boot jar)
    --no-build                 Fail if executable jar is missing (do not trigger Maven build)
    --bus-mode MODE            Unified Event Bus mode (IN_PROC, AERON_IPC, AERON_UDP) [default: IN_PROC]
    --bus-stream-id ID         Unified Event Bus streamId [default: 9000]
    --bus-endpoint URI         Aeron UDP endpoint when mode=AERON_UDP [default: aeron:udp?endpoint=localhost:40200]
    -h, --help                 Show this help message

Examples:
    $0                                          # Start with default settings
    $0 -p production -m FULL                   # Start in production mode
    $0 --bus-mode IN_PROC                      # Use in-process ringbuffer bus
    $0 --bus-mode AERON_IPC                    # Use Aeron IPC bus
    $0 --bus-mode AERON_UDP --bus-endpoint "aeron:udp?endpoint=localhost:40200"  # Use Aeron UDP bus
    $0 -d 5005                                 # Start with debug enabled
    $0 -j "-Xmx4g -Xms2g"                     # Start with custom JVM options
    $0 -I pricing -b                           # Start named instance in background
    $0 -b                                      # Start in background

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -p|--profile)
            PROFILE="$2"
            shift 2
            ;;
        -m|--mode)
            MODE="$2"
            shift 2
            ;;
        -P|--port)
            PORT="$2"
            shift 2
            ;;
        -M|--management-port)
            MANAGEMENT_PORT="$2"
            shift 2
            ;;
        -I|--instance)
            INSTANCE_NAME="$2"
            shift 2
            ;;
        -j|--jvm-opts)
            JVM_OPTS="$2"
            shift 2
            ;;
        -d|--debug)
            DEBUG_PORT="$2"
            shift 2
            ;;
        -b|--background)
            BACKGROUND=true
            shift
            ;;
        -x|--disable-monitor)
            MONITOR_ENABLED=false
            shift
            ;;
        --jar-file)
            JAR_FILE_OVERRIDE="$2"
            shift 2
            ;;
        --no-build)
            NO_BUILD=true
            shift
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

# Validate profile
if [[ ! "$PROFILE" =~ ^(development|production|test)$ ]]; then
    print_error "Invalid profile: $PROFILE. Must be one of: development, production, test"
    exit 1
fi

# Validate mode
if [[ ! "$MODE" =~ ^(FULL|MARKET_DATA_ONLY|STRATEGY_ONLY|ADAPTER_ONLY|SIMULATION)$ ]]; then
    print_error "Invalid mode: $MODE. Must be one of: FULL, MARKET_DATA_ONLY, STRATEGY_ONLY, ADAPTER_ONLY, SIMULATION"
    exit 1
fi

# Validate bus mode
if [[ ! "$BUS_MODE" =~ ^(IN_PROC|AERON_IPC|AERON_UDP)$ ]]; then
    print_error "Invalid bus mode: $BUS_MODE. Must be one of: IN_PROC, AERON_IPC, AERON_UDP"
    exit 1
fi

# Check if Java is installed
if ! command -v java &> /dev/null; then
    print_error "Java is not installed or not in PATH"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
    print_error "Java 17 or higher is required. Current version: $JAVA_VERSION"
    exit 1
fi

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
    local artifact_dir="$PROJECT_ROOT/bedrock-app/target"
    find "$artifact_dir" -maxdepth 1 -type f -name "bedrock-app-*.jar" \
        ! -name "*-sources.jar" ! -name "*-javadoc.jar" \
        ! -name "*.jar.original" -exec ls -1t {} + 2>/dev/null | head -n 1
}

# Resolve application JAR dynamically (supports SNAPSHOT and releases)
ARTIFACT_DIR="$PROJECT_ROOT/bedrock-app/target"
if [[ -n "$JAR_FILE_OVERRIDE" ]]; then
    JAR_FILE="$JAR_FILE_OVERRIDE"
else
    JAR_FILE="$(resolve_jar_candidate)"
fi

if [[ -n "$JAR_FILE" ]] && [[ -f "$JAR_FILE" ]] && ! is_executable_jar "$JAR_FILE"; then
    print_warning "Found non-executable jar: $JAR_FILE"
    if [[ "$NO_BUILD" == true ]]; then
        print_error "Use --jar-file with an executable Spring Boot jar or run without --no-build to rebuild"
        exit 1
    fi
    JAR_FILE=""
fi

# Build the project if jar doesn't exist
if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
    if [[ "$NO_BUILD" == true ]]; then
        print_error "Executable application jar not found and --no-build is enabled"
        exit 1
    fi
    print_info "JAR file not found. Building the project..."
    cd "$PROJECT_ROOT"
    if [[ "$MVN_BIN" == */* ]]; then
        if [[ ! -x "$MVN_BIN" ]]; then
            print_error "Maven binary not executable: $MVN_BIN"
            exit 1
        fi
    elif ! command -v "$MVN_BIN" &> /dev/null; then
        print_error "Maven is not installed or not in PATH: $MVN_BIN"
        exit 1
    fi

    MVN_CMD=("$MVN_BIN")
    if [[ -n "$MVN_SETTINGS" ]]; then
        MVN_CMD+=(--settings "$MVN_SETTINGS")
    fi
    if [[ -n "$MVN_REPO" ]]; then
        MVN_CMD+=(-Dmaven.repo.local="$MVN_REPO")
    fi
    if [[ "$MVN_OFFLINE" == "true" ]]; then
        MVN_CMD+=(-o -nsu)
    fi
    MVN_CMD+=(package -DskipTests -pl bedrock-app -am)
    print_info "Build command: ${MVN_CMD[*]}"
    "${MVN_CMD[@]}"

    # Re-resolve JAR after build
    if [[ -n "$JAR_FILE_OVERRIDE" ]]; then
        JAR_FILE="$JAR_FILE_OVERRIDE"
    else
        JAR_FILE="$(resolve_jar_candidate)"
    fi
fi

# Check if jar file exists after build
if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
    print_error "Failed to build JAR file under: $ARTIFACT_DIR"
    print_error "Check Maven output and ensure the module 'bedrock-app' is built."
    exit 1
fi

if ! is_executable_jar "$JAR_FILE"; then
    print_error "Resolved jar is not executable: $JAR_FILE"
    print_error "Expected a Spring Boot repackaged jar with Main-Class/Start-Class in MANIFEST"
    print_error "Try rebuilding with online dependency access for spring-boot-maven-plugin"
    exit 1
fi

# Prepare JVM options
# Low-latency ZGC configuration for market making hot path
ZGC_OPTS="-XX:+UseZGC -XX:MaxGCPauseMillis=1 -XX:+AlwaysPreTouch -XX:+DisableExplicitGC"
DEFAULT_JVM_OPTS="-Xmx2g -Xms1g $ZGC_OPTS"

# Module opens/exports for Chronicle and reflective access on Java 17+
MODULE_OPENS="\
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/sun.security.util=ALL-UNNAMED \
  --add-opens java.base/sun.net.util=ALL-UNNAMED \
  --add-opens java.base/java.time=ALL-UNNAMED \
  --add-opens java.base/java.text=ALL-UNNAMED \
  --add-opens java.base/java.math=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  --add-opens java.base/java.security.cert=ALL-UNNAMED \
  --add-opens java.base/javax.net.ssl=ALL-UNNAMED \
  --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-exports java.base/sun.security.action=ALL-UNNAMED"

# Add debug options if debug port is specified
if [[ -n "$DEBUG_PORT" ]]; then
    DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$DEBUG_PORT"
    JVM_OPTS="$JVM_OPTS $DEBUG_OPTS"
    print_info "Debug mode enabled on port $DEBUG_PORT"
fi

# Combine JVM options
FINAL_JVM_OPTS="$DEFAULT_JVM_OPTS $MODULE_OPENS $JVM_OPTS"

# Prepare application arguments
APP_ARGS="--spring.profiles.active=$PROFILE"
APP_ARGS="$APP_ARGS --bedrock.mode=$MODE"
APP_ARGS="$APP_ARGS --bedrock.server.port=$PORT"
APP_ARGS="$APP_ARGS --bedrock.server.management-port=$MANAGEMENT_PORT"
APP_ARGS="$APP_ARGS --bedrock.monitor.enabled=$MONITOR_ENABLED"
APP_ARGS="$APP_ARGS --bedrock.bus.mode=$BUS_MODE"
APP_ARGS="$APP_ARGS --bedrock.bus.streamId=$BUS_STREAM_ID"
APP_ARGS="$APP_ARGS --bedrock.bus.endpoint=$BUS_ENDPOINT"
APP_ARGS="$APP_ARGS --logging.file.name=logs/${INSTANCE_NAME}.log"

# Create logs directory
mkdir -p "$PROJECT_ROOT/logs"

# Print startup information
print_info "Starting Bedrock Market Making System..."
print_info "Profile: $PROFILE"
print_info "Mode: $MODE"
print_info "Server Port: $PORT"
print_info "Management Port: $MANAGEMENT_PORT"
print_info "Instance Name: $INSTANCE_NAME"
print_info "Monitor Enabled: $MONITOR_ENABLED"
print_info "Bus Mode: $BUS_MODE"
print_info "Bus StreamId: $BUS_STREAM_ID"
print_info "Bus Endpoint: $BUS_ENDPOINT"
print_info "JVM Options: $FINAL_JVM_OPTS"
if [[ -n "$DEBUG_PORT" ]]; then
    print_info "Debug Port: $DEBUG_PORT"
fi

# Start the application
cd "$PROJECT_ROOT"

if [[ "$BACKGROUND" == true ]]; then
    PID_FILE="logs/${INSTANCE_NAME}.pid"
    OUT_FILE="logs/${INSTANCE_NAME}.out"
    print_info "Starting in background mode..."
    nohup java $FINAL_JVM_OPTS -jar "$JAR_FILE" $APP_ARGS > "$OUT_FILE" 2>&1 &
    PID=$!
    echo $PID > "$PID_FILE"
    print_success "Application started in background with PID: $PID"
    print_info "Logs: tail -f $OUT_FILE"
    print_info "Stop: ./scripts/stop.sh --instance $INSTANCE_NAME"
else
    print_info "Starting in foreground mode..."
    java $FINAL_JVM_OPTS -jar "$JAR_FILE" $APP_ARGS
fi
