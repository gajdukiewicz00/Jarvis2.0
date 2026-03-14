# =============================================================================
# Jarvis 2.0 - Makefile (prod-only)
# =============================================================================

.PHONY: help build test clean launch stop logs install tls hosts secrets verify-ai verify-ai-selftest k8s-preflight k8s-preflight-ci k8s-preflight-staging k8s-preflight-staging-ci verify-prod

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
	@echo "║   make verify-ai    - Verify AI/tooling acceptance contract   ║"
	@echo "║   make verify-ai-selftest - Smoke-test verify-ai.sh           ║"
	@echo "║   make verify-prod  - Verify runtime repo (prod-only rules)   ║"
	@echo "║   make k8s-preflight- Kustomize + dry-run + image/auth checks ║"
	@echo "║   make k8s-preflight-ci - Force toolchain container mode      ║"
	@echo "║   make k8s-preflight-staging - Staging overlay enforce checks ║"
	@echo "║   make k8s-preflight-staging-ci - CI staging enforce mode     ║"
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

verify-ai:
	./scripts/verify-ai.sh

verify-ai-selftest:
	./scripts/ci/test-verify-ai.sh

verify-prod:
	./scripts/verify-prod.sh

k8s-preflight:
	./scripts/ci/k8s-preflight.sh

k8s-preflight-ci:
	CI=true K8S_PREFLIGHT_FORCE_CONTAINER=true ./scripts/ci/k8s-preflight.sh

k8s-preflight-staging:
	./scripts/ci/k8s-preflight-staging.sh

k8s-preflight-staging-ci:
	CI=true K8S_PREFLIGHT_FORCE_CONTAINER=true K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE=enforce ./scripts/ci/k8s-preflight.sh ./k8s/overlays/staging
