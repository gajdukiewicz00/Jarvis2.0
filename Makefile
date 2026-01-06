# =============================================================================
# Jarvis 2.0 - Makefile
# =============================================================================
# Usage: make <target>
# Run 'make help' for available commands
# =============================================================================

.PHONY: help build test clean up down logs restart health smoke-test

# Default target
.DEFAULT_GOAL := help

# =============================================================================
# Help
# =============================================================================

help:
	@echo "╔════════════════════════════════════════════════════════════════╗"
	@echo "║                    Jarvis 2.0 Makefile                        ║"
	@echo "╠════════════════════════════════════════════════════════════════╣"
	@echo "║ Build & Test:                                                  ║"
	@echo "║   make build        - Build all modules (skip tests)          ║"
	@echo "║   make test         - Run all tests                           ║"
	@echo "║   make clean        - Clean build artifacts                   ║"
	@echo "║                                                                ║"
	@echo "║ Docker:                                                        ║"
	@echo "║   make up           - Start all Docker services               ║"
	@echo "║   make down         - Stop all Docker services                ║"
	@echo "║   make restart      - Restart all Docker services             ║"
	@echo "║   make logs         - Show logs (all services)                ║"
	@echo "║   make logs-f       - Follow logs (all services)              ║"
	@echo "║                                                                ║"
	@echo "║ Service-specific:                                              ║"
	@echo "║   make logs-<svc>   - Show logs for specific service          ║"
	@echo "║     Examples: logs-api-gateway, logs-life-tracker             ║"
	@echo "║   make restart-<svc>- Restart specific service                ║"
	@echo "║                                                                ║"
	@echo "║ Verification:                                                  ║"
	@echo "║   make health       - Check health of all services            ║"
	@echo "║   make smoke-test   - Run basic smoke tests                   ║"
	@echo "║   make ps           - Show Docker container status            ║"
	@echo "╚════════════════════════════════════════════════════════════════╝"

# =============================================================================
# Build & Test
# =============================================================================

build:
	@echo "🔨 Building all modules..."
	mvn -q -DskipTests package
	@echo "✅ Build complete"

build-verbose:
	mvn package -DskipTests

test:
	@echo "🧪 Running tests..."
	mvn test
	@echo "✅ Tests complete"

test-fast:
	mvn test -DfailIfNoTests=false -Dtest=*Test

clean:
	@echo "🧹 Cleaning build artifacts..."
	mvn clean
	@echo "✅ Clean complete"

# =============================================================================
# Docker Operations
# =============================================================================

up:
	@echo "🚀 Starting Docker services..."
	docker compose up -d
	@echo "✅ Services started"
	@make ps

up-build:
	@echo "🚀 Building and starting Docker services..."
	docker compose up -d --build
	@echo "✅ Services started"

down:
	@echo "🛑 Stopping Docker services..."
	docker compose down
	@echo "✅ Services stopped"

down-v:
	@echo "🛑 Stopping Docker services and removing volumes..."
	docker compose down -v
	@echo "✅ Services and volumes removed"

restart:
	@echo "🔄 Restarting Docker services..."
	docker compose restart
	@echo "✅ Services restarted"

ps:
	@docker compose ps

logs:
	docker compose logs --tail=100

logs-f:
	docker compose logs -f

# Service-specific logs
logs-api-gateway:
	docker compose logs -f api-gateway

logs-life-tracker:
	docker compose logs -f life-tracker

logs-analytics-service:
	docker compose logs -f analytics-service

logs-security-service:
	docker compose logs -f security-service

logs-postgres:
	docker compose logs -f postgres

# Service-specific restart
restart-api-gateway:
	docker compose restart api-gateway

restart-life-tracker:
	docker compose restart life-tracker

restart-analytics-service:
	docker compose restart analytics-service

restart-security-service:
	docker compose restart security-service

# =============================================================================
# Verification
# =============================================================================

health:
	@echo "🏥 Checking service health..."
	@echo ""
	@echo "API Gateway:"
	@curl -s http://localhost:8080/actuator/health | head -c 200 || echo "❌ Not reachable"
	@echo ""
	@echo ""
	@echo "Planner Service:"
	@curl -s http://localhost:8092/actuator/health | head -c 200 || echo "❌ Not reachable"
	@echo ""

smoke-test:
	@echo "🔥 Running smoke tests..."
	@echo ""
	@echo "1. Health check..."
	@curl -s http://localhost:8080/actuator/health | grep -q "UP" && echo "✅ API Gateway healthy" || echo "❌ API Gateway unhealthy"
	@echo ""
	@echo "2. Auth endpoints..."
	@curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/auth/health | grep -q "200" && echo "✅ Auth health OK" || echo "⚠️  Auth health check failed"
	@echo ""
	@echo "3. Life tracker endpoints..."
	@curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/life/finance/expenses | grep -qE "200|401" && echo "✅ Life tracker reachable" || echo "❌ Life tracker unreachable"
	@echo ""
	@echo "4. Analytics endpoints..."
	@curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/analytics/overview | grep -qE "200|401" && echo "✅ Analytics reachable" || echo "❌ Analytics unreachable"
	@echo ""
	@echo "🏁 Smoke tests complete"

# =============================================================================
# Development
# =============================================================================

run-core-dev:
	java -jar apps/assistant-core/target/assistant-core-0.1.0-SNAPSHOT.jar

run-gateway-dev:
	java -jar apps/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar

run-life-tracker-dev:
	java -jar apps/life-tracker/target/life-tracker-0.1.0-SNAPSHOT.jar

# =============================================================================
# Legacy (backward compatibility)
# =============================================================================

run-all:
	./run-all.sh

stop-all:
	./stop-all.sh
