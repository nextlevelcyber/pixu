#!/bin/bash

# Bedrock Market Making System Build Script

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
    -c, --clean             Clean before build
    -t, --skip-tests        Skip tests during build
    -d, --docker            Build Docker image
    -p, --profile PROFILE   Maven profile (development, production) [default: development]
    -m, --module MODULE     Build specific module only
    -v, --verbose           Verbose output
    -h, --help              Show this help message

Examples:
    $0                      # Standard build
    $0 -c                   # Clean build
    $0 -t                   # Build without tests
    $0 -d                   # Build with Docker image
    $0 -m app               # Build app module only

EOF
}

# Default values
CLEAN=false
SKIP_TESTS=false
DOCKER=false
PROFILE="development"
MODULE=""
VERBOSE=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -c|--clean)
            CLEAN=true
            shift
            ;;
        -t|--skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        -d|--docker)
            DOCKER=true
            shift
            ;;
        -p|--profile)
            PROFILE="$2"
            shift 2
            ;;
        -m|--module)
            MODULE="$2"
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
    VALID_MODULES=("common" "aeron" "sbe" "monitor" "md" "strategy" "adapter" "app")
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

# Check Java version
if ! command -v java &> /dev/null; then
    print_error "Java is not installed or not in PATH"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
    print_error "Java 17 or higher is required. Current version: $JAVA_VERSION"
    exit 1
fi

# Change to project root
cd "$PROJECT_ROOT"

# Build Maven command
MVN_CMD="mvn"

# Add profile
MVN_CMD="$MVN_CMD -P$PROFILE"

# Add verbose flag if requested
if [[ "$VERBOSE" == true ]]; then
    MVN_CMD="$MVN_CMD -X"
fi

# Add clean if requested
if [[ "$CLEAN" == true ]]; then
    MVN_CMD="$MVN_CMD clean"
    print_info "Clean build enabled"
fi

# Add compile phase
MVN_CMD="$MVN_CMD compile"

# Add test phase unless skipped
if [[ "$SKIP_TESTS" == true ]]; then
    MVN_CMD="$MVN_CMD -DskipTests"
    print_info "Skipping tests"
else
    MVN_CMD="$MVN_CMD test"
fi

# Add package phase
MVN_CMD="$MVN_CMD package"

# Add specific module if specified
if [[ -n "$MODULE" ]]; then
    MVN_CMD="$MVN_CMD -pl bedrock-$MODULE -am"
    print_info "Building module: $MODULE (with dependencies)"
fi

print_info "Building Bedrock Market Making System..."
print_info "Profile: $PROFILE"
print_info "Executing: $MVN_CMD"

# Run build
START_TIME=$(date +%s)

if $MVN_CMD; then
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    print_success "Build completed successfully! Duration: ${DURATION}s"
    
    # Display build artifacts
    print_info "Build Artifacts:"
    
    if [[ -n "$MODULE" ]]; then
        # Show artifacts for specific module
        ARTIFACT_DIR="$PROJECT_ROOT/bedrock-$MODULE/target"
        if [[ -d "$ARTIFACT_DIR" ]]; then
            JAR_FILES=$(find "$ARTIFACT_DIR" -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" 2>/dev/null || true)
            for jar in $JAR_FILES; do
                SIZE=$(du -h "$jar" | cut -f1)
                print_info "  $(basename "$jar") ($SIZE)"
            done
        fi
    else
        # Show artifacts for all modules
        for module in common aeron sbe monitor md strategy adapter app; do
            ARTIFACT_DIR="$PROJECT_ROOT/bedrock-$module/target"
            if [[ -d "$ARTIFACT_DIR" ]]; then
                JAR_FILES=$(find "$ARTIFACT_DIR" -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" 2>/dev/null || true)
                for jar in $JAR_FILES; do
                    SIZE=$(du -h "$jar" | cut -f1)
                    print_info "  bedrock-$module: $(basename "$jar") ($SIZE)"
                done
            fi
        done
    fi
    
    # Build Docker image if requested
    if [[ "$DOCKER" == true ]]; then
        print_info "Building Docker image..."
        
        # Check if Docker is installed
        if ! command -v docker &> /dev/null; then
            print_error "Docker is not installed or not in PATH"
            exit 1
        fi
        
        # Check if Dockerfile exists
        DOCKERFILE="$PROJECT_ROOT/Dockerfile"
        if [[ ! -f "$DOCKERFILE" ]]; then
            print_warning "Dockerfile not found, creating a basic one..."
            create_dockerfile
        fi
        
        # Build Docker image
        IMAGE_TAG="bedrock-mm:latest"
        if docker build -t "$IMAGE_TAG" .; then
            print_success "Docker image built: $IMAGE_TAG"
            
            # Show image size
            IMAGE_SIZE=$(docker images "$IMAGE_TAG" --format "table {{.Size}}" | tail -n 1)
            print_info "Image size: $IMAGE_SIZE"
        else
            print_error "Failed to build Docker image"
            exit 1
        fi
    fi
    
else
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    print_error "Build failed! Duration: ${DURATION}s"
    exit 1
fi

# Function to create a basic Dockerfile
create_dockerfile() {
    cat > "$PROJECT_ROOT/Dockerfile" << 'EOF'
# Bedrock Market Making System Dockerfile

FROM openjdk:17-jre-slim

# Set working directory
WORKDIR /app

# Create user for security
RUN groupadd -r bedrock && useradd -r -g bedrock bedrock

# Copy application jar
COPY bedrock-app/target/bedrock-app-*.jar app.jar

# Copy configuration
COPY bedrock-app/src/main/resources/application.yml application.yml

# Create logs directory
RUN mkdir -p logs && chown -R bedrock:bedrock /app

# Switch to non-root user
USER bedrock

# Expose ports
EXPOSE 8080 8081

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF
    
    print_info "Created basic Dockerfile"
}