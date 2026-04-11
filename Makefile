# =============================================================================
# Jarvis 2.0 - Makefile (prod-only)
# =============================================================================

.PHONY: help build test clean launch stop logs obs-status obs-verify obs-idempotency obs-grafana obs-prometheus obs-loki obs-tempo install tls hosts secrets verify-ai verify-ai-selftest k8s-preflight k8s-preflight-ci k8s-preflight-staging k8s-preflight-staging-ci verify-prod

.DEFAULT_GOAL := help

KUBECONFIG_PATH := $(shell if [ -r "$$HOME/.jarvis/kubeconfig" ]; then printf '%s' "$$HOME/.jarvis/kubeconfig"; elif [ -r /etc/rancher/k3s/k3s.yaml ]; then printf '%s' /etc/rancher/k3s/k3s.yaml; fi)
KUBECTL := $(if $(KUBECONFIG_PATH),KUBECONFIG=$(KUBECONFIG_PATH) kubectl,kubectl)

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
	@echo "║ Observability (in K8s):                                        ║"
	@echo "║   make obs-status   - Show observability pod status           ║"
	@echo "║   make obs-verify   - Verify Grafana/Loki/log ingestion       ║"
	@echo "║   make obs-idempotency - Run 3x launcher idempotency matrix   ║"
	@echo "║   make obs-grafana  - Port-forward Grafana to :3000          ║"
	@echo "║   make obs-prometheus - Port-forward Prometheus to :9090     ║"
	@echo "║   make obs-loki     - Port-forward Loki to :3100            ║"
	@echo "║   make obs-tempo    - Port-forward Tempo to :3200           ║"
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

obs-status:
	@echo "Observability pod status:"
	@$(KUBECTL) get pods -n jarvis -l 'app in (prometheus,grafana,loki,tempo,alloy)' -o wide 2>/dev/null || echo "(kubectl not available or cluster not running)"

obs-verify:
	./scripts/verify-observability.sh

obs-idempotency:
	./scripts/launcher-idempotency-matrix.sh

obs-grafana:
	@echo "Fallback helper only. Primary Grafana URL is https://grafana.jarvis.local"
	@echo "Grafana credentials live in ~/.jarvis/secrets/secrets.env"
	@echo "Opening Grafana port-forward on http://localhost:3000"
	$(KUBECTL) port-forward -n jarvis svc/grafana 3000:3000

obs-prometheus:
	@echo "Opening Prometheus port-forward on http://localhost:9090"
	$(KUBECTL) port-forward -n jarvis svc/prometheus 9090:9090

obs-loki:
	@echo "Opening Loki port-forward on http://localhost:3100"
	$(KUBECTL) port-forward -n jarvis svc/loki 3100:3100

obs-tempo:
	@echo "Opening Tempo port-forward on http://localhost:3200"
	$(KUBECTL) port-forward -n jarvis svc/tempo 3200:3200

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
