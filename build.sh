#!/bin/bash

# Verifact Build Script
# Usage: ./build.sh [option]
# Options:
#   maven    - Build with Maven only (skip Docker) - requires Java 17+
#   docker   - Build Docker image (includes Maven build inside container)
#   all      - Build with Maven and Docker (default) - requires Java 17+
#   clean    - Clean all build artifacts

# Don't exit on error initially - we want to check prerequisites first

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check Java installation
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH!"
        print_warn "Please install Java 17 or higher, or use Docker build:"
        print_info "  ./build.sh docker"
        return 1
    fi

    # Check Java version
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$java_version" -lt 17 ]; then
        print_error "Java version $java_version found. Java 17 or higher is required!"
        print_warn "Please upgrade Java or use Docker build:"
        print_info "  ./build.sh docker"
        return 1
    fi

    print_info "Java $java_version detected"
    return 0
}

# Function to build with Maven
build_maven() {
    if ! check_java; then
        exit 1
    fi

    print_info "Building with Maven..."
    ./mvnw clean package -DskipTests

    if [ $? -eq 0 ]; then
        print_info "Maven build completed successfully!"
        print_info "JAR location: target/verifact-0.0.1-SNAPSHOT.jar"
    else
        print_error "Maven build failed!"
        exit 1
    fi
}

# Function to run the application
run_application() {
    print_info "Starting application on port 8080..."
    print_warn "Press Ctrl+C to stop the application"
    echo ""
    ./mvnw spring-boot:run
}

# Function to build Docker image
build_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed or not in PATH!"
        print_warn "Please install Docker, or use Maven build if Java 17+ is installed:"
        print_info "  ./build.sh maven"
        exit 1
    fi

    print_info "Building Docker image (includes Maven build inside container)..."
    print_info "This may take a few minutes on first build..."
    docker build -t verifact:latest .

    if [ $? -eq 0 ]; then
        print_info "Docker image built successfully!"
        print_info "Run with: docker-compose up"
        print_info "Or: docker run -p 8080:8080 --env-file .env verifact:latest"
    else
        print_error "Docker build failed!"
        exit 1
    fi
}

# Function to clean build artifacts
clean_build() {
    print_info "Cleaning build artifacts..."
    ./mvnw clean

    if command -v docker &> /dev/null; then
        print_info "Removing Docker images..."
        docker rmi verifact:latest 2>/dev/null || print_warn "No Docker image to remove"
    fi

    print_info "Clean completed!"
}

# Check if .env file exists
check_env() {
    if [ ! -f ".env" ]; then
        print_warn ".env file not found!"
        print_warn "Make sure to create .env with required API keys before running the application."
    fi
}

# Main script logic
print_info "====================================="
print_info "   Verifact Build Script"
print_info "====================================="
echo ""

check_env

case "${1:-docker}" in
    maven)
        print_info "Building with Maven only..."
        build_maven
        echo ""
        run_application
        ;;
    docker)
        print_info "Building Docker image..."
        build_docker
        echo ""
        print_info "Starting application with Docker Compose..."
        print_warn "Press Ctrl+C to stop the application"
        echo ""
        docker-compose up
        ;;
    all)
        print_info "Building with Maven and Docker..."
        build_maven
        echo ""
        build_docker
        echo ""
        print_info "Starting application with Docker Compose..."
        print_warn "Press Ctrl+C to stop the application"
        echo ""
        docker-compose up
        ;;
    clean)
        clean_build
        ;;
    *)
        print_error "Unknown option: $1"
        echo ""
        echo "Usage: ./build.sh [option]"
        echo "Options:"
        echo "  maven    - Build with Maven only (requires Java 17+)"
        echo "  docker   - Build Docker image (default, no Java required)"
        echo "  all      - Build with Maven and Docker (requires Java 17+)"
        echo "  clean    - Clean all build artifacts"
        exit 1
        ;;
esac
