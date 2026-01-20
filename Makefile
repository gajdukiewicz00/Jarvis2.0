# =============================================================================
# Jarvis 2.0 - Makefile (prod-only)
# =============================================================================

.PHONY: help build test clean launch stop logs install tls hosts secrets

.DEFAULT_GOAL := help

help:
	@echo "╔════════════════════════════════════════════════════════════════╗"
	@echo "║                    Jarvis 2.0 Makefile                        ║"
	@echo "╠════════════════════════════════════════════════════════════════╣"
	@echo "║ Build & Test:                                                  ║"
	@echo "║   make build        - Build all modules (skip tests)          ║"
	@echo "║   make test         - Run all tests                           ║"
	@echo "║   make clean        - Clean build artifacts                   ║"
	@echo "║                                                                ║"
	@echo "║ Runtime (prod-only):                                          ║"
	@echo "║   make launch       - Start Kubernetes stack                  ║"
	@echo "║   make stop         - Stop Kubernetes stack                   ║"
	@echo "║   make logs         - Open logs viewer                        ║"
	@echo "║                                                                ║"
	@echo "║ Setup:                                                         ║"
	@echo "║   make install      - Install launcher to ~/.jarvis/app       ║"
	@echo "║   make tls          - Install local CA to trust store         ║"
	@echo "║   make hosts        - Update /etc/hosts                       ║"
	@echo "║   make secrets      - Apply local secrets to Kubernetes       ║"
	@echo "╚════════════════════════════════════════════════════════════════╝"

build:
	@echo "🔨 Building all modules..."
	mvn -q -DskipTests package
	@echo "✅ Build complete"

test:
	@echo "🧪 Running tests..."
	mvn test
	@echo "✅ Tests complete"

clean:
	@echo "🧹 Cleaning build artifacts..."
	mvn clean
	@echo "✅ Clean complete"

launch:
	./jarvis-launch.sh

stop:
	./jarvis-stop.sh

logs:
	./jarvis-logs.sh

install:
	./scripts/product/jarvis-install.sh

tls:
	sudo ./scripts/product/jarvis-install-tls.sh

hosts:
	sudo ./scripts/product/jarvis-setup-hosts.sh

secrets:
	./scripts/product/jarvis-secrets-apply.sh
