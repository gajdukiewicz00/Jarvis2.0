# Jarvis

Jarvis is a privacy-first, offline-capable assistant platform built as a Maven multi-module backend with a JavaFX desktop product surface and an optional local LLM stack.

## Canonical Docs

- Architecture: [`docs/architecture.md`](docs/architecture.md)
- LLM runtime: [`RUNBOOK_LLM.md`](RUNBOOK_LLM.md)
- Kubernetes deployment: [`k8s/README.md`](k8s/README.md)

## Current Architecture

Core runtime services:

- `api-gateway`
- `voice-gateway`
- `nlp-service`
- `orchestrator`
- `security-service`
- `user-profile`
- `planner-service`
- `life-tracker`
- `analytics-service`
- `pc-control`
- `vision-security-service`
- `smart-home-service`

Optional runtime services:

- `llm-service`
- `llm-server`
- `memory-service`
- `embedding-service`

Local-only runtime note:

- `vision-security-service` is currently wired for the Ubuntu local runtime and desktop product path, not Kubernetes

Canonical desktop path:

- `launcher-javafx` is the operational entrypoint
- `desktop-app-javafx` is the only supported desktop UI artifact
- `desktop-client-javafx` remains a build-time dependency of `desktop-app-javafx`, not a separate product path

## What Builds

The root Maven build is the source of truth:

```bash
mvn -DskipTests package
```

The active reactor includes:

- shared library: `jarvis-common`
- backend services listed above
- desktop modules: `desktop-client-javafx`, `desktop-app-javafx`, `launcher-javafx`

## Runtime Paths

Local runtime:

```bash
./scripts/runtime-up.sh
./scripts/runtime-status.sh
./scripts/runtime-down.sh
```

Optional AI path:

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
./scripts/ai-local-smoke.sh
```

Product launcher path:

```bash
./scripts/product/jarvis-install.sh
~/.jarvis/app/bin/jarvis-launcher.sh
```

Kubernetes path:

```bash
kubectl apply -k k8s/base
kubectl apply -k k8s/overlays/prod
```

## Repo Map

```text
apps/
  api-gateway/
  voice-gateway/
  nlp-service/
  orchestrator/
  security-service/
  user-profile/
  planner-service/
  life-tracker/
  analytics-service/
  pc-control/
  vision-security-service/
  smart-home-service/
  llm-service/
  memory-service/
  jarvis-common/
  desktop-client-javafx/
  desktop-app-javafx/
  launcher-javafx/
scripts/
  runtime-up.sh
  runtime-down.sh
  runtime-status.sh
  product/
k8s/
  base/
  overlays/prod/
```

## Non-Goals In This Repo

- no standalone mobile product path
- no archived legacy module tree
