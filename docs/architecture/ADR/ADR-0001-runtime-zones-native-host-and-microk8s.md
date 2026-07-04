# ADR-0001: Runtime Zones - Native Host And MicroK8s

- Status: Accepted
- Date: `2026-04-28`

## Decision

Jarvis production runtime is split into two official zones:

- native host
- MicroK8s

## Context

The repo already contains two materially different kinds of runtime responsibility:

- host-bound desktop and device behavior
- backend services and cluster-managed infrastructure

The codebase also proves that some paths cannot honestly be treated as equivalent:

- `desktop-javafx` depends on local UI, desktop session state, wake-word capture, and workstation-side config.
- `pc-control` has a real host execution path locally and a reduced/stubbed posture in Kubernetes.
- `vision-security-service` is local-only in current repo reality.
- `llm-service` depends on a local or internal inference worker that is closer to host-managed compute than to a public edge service.

## Why this decision

### Why `desktop-javafx` is native

- It is the current desktop UI and launcher implementation.
- It owns local UX, local capture, and host-side runtime awareness.
- It depends on resources that are not honest cluster responsibilities.

### Why llama.cpp server is native

- The current local inference worker is a host-oriented model-serving path.
- It depends on local model files, optional GPU access, and workstation-level compute assumptions.
- Treating that path as host-native keeps model/runtime expectations explicit.

### Why backend services run in MicroK8s

- Service APIs, gateway routing, auth, orchestration, planning, analytics, and domain state all fit the cluster/service model.
- Internal TLS, rollout validation, release overlays, and observability are already modeled as Kubernetes concerns in the repo.

### Why PostgreSQL, pgvector, Kafka, RabbitMQ, and observability belong in MicroK8s

- They are infrastructure services, not host-user interactions.
- Their lifecycle, networking, and service ownership boundaries fit the cluster zone.
- In current repo reality, PostgreSQL and observability already have committed Kubernetes manifests.
- Kafka and RabbitMQ are treated as zone ownership targets even where committed MicroK8s deployment parity is not yet fully proven.

## Consequences

- Runtime claims must distinguish host-native behavior from cluster behavior.
- The current Native Desktop Agent remains a first-class runtime component.
- AI access stays behind service APIs, not direct database access.
- Production documentation must describe native host + MicroK8s, not Docker Compose as the authoritative production runtime.
- Removal of compatibility Docker paths becomes a later migration step instead of an immediate cleanup.

## Alternatives Considered

### All Docker Compose

Rejected because it collapses cluster, host, and desktop concerns into one runtime story that the current repo does not honestly support as production truth.

### All Kubernetes, including desktop

Rejected because the desktop, wake-word, device, and workstation-control surfaces are fundamentally host-bound today.

### All native host

Rejected because gateway, auth, orchestration, stateful services, and observability benefit from explicit service boundaries and cluster lifecycle management.

### Cloud LLM

Rejected as the default architecture because the target model is local-first and because Phase 0 is aligning the repo to native host + MicroK8s, not outsourcing the AI runtime.
