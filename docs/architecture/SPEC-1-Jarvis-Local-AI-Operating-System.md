# SPEC-1: Jarvis Local AI Operating System

## 1. Purpose

This specification defines the Phase 0 architecture truth for Jarvis 2.0.

Phase 0 is an alignment and deprecation phase. Its job is to make repository documentation, runtime assumptions, namespace defaults, and repository guardrails match the target architecture without deleting working paths or renaming services.

## 2. Current Repository Reality

The repository currently contains:

- Maven backend services under `apps/`
- the current desktop/native implementation under `apps/desktop-javafx`
- local runtime scripts under `scripts/`
- Kubernetes manifests under `k8s/`
- optional Python AI workers under `docker/`
- local Codex/native agent workflow assets under `.agents/skills/` and `.codex/`

Concrete runtime facts from the code, manifests, and smokeable entrypoints:

- `api-gateway`, `voice-gateway`, `nlp-service`, `orchestrator`, `planner-service`, `life-tracker`, `analytics-service`, `security-service`, `user-profile`, and `smart-home-service` are real backend services.
- `pc-control` is a real workstation control path locally and a constrained or stubbed path in Kubernetes.
- `vision-security-service` is a real local service and is not part of `k8s/base`.
- `desktop-javafx` is the current shipped desktop surface and launcher/shell path.
- `llm-service` and `memory-service` are optional service-level AI façades.
- `docker/llm-server` and `docker/embedding-service` are the current Python worker implementations used by the optional AI stack.
- `k8s/overlays/prod-release` is already treated as the digest-pinned release artifact, while `k8s/overlays/prod` remains a mutable workspace/runtime input.

This repo still contains legacy or compatibility-oriented Docker assets and some namespace/runtime wording that predates the target architecture. Phase 0 aligns those areas without removing the working paths yet.

## 3. Target Runtime Model

Jarvis production runtime is split into two official zones:

- Native host zone
- MicroK8s zone

The split is intentional:

- Native host owns machine-local UI, device capture, operating-system integration, and host-only execution.
- MicroK8s owns backend APIs, stateful services, internal service-to-service contracts, and cluster operations.

Critical architectural rule:

- The LLM must not write directly to databases.
- LLM and tool planning must go through official service APIs.
- Real state changes must be executed by domain services such as `planner-service`, `life-tracker`, `smart-home-service`, `security-service`, `user-profile`, `memory-service`, and other bounded services.

## 4. Official Runtime Zones

### Native host

The native host zone is the official home for:

- `apps/desktop-javafx`, the current Native Desktop Agent
- launcher-side runtime helpers such as `scripts/product/jarvis-launcher.sh`
- local OS and desktop integrations
- microphone capture, wake word, and local audio-device handling
- host-level `pc-control` execution
- webcam and computer-vision flows that are currently local-only
- the local llama.cpp-backed inference worker path currently implemented by `docker/llm-server`

Important truth:

- Some payloads and diagnostics still use the string `desktop-client`.
- In Phase 0 that string is treated as a legacy label for the current Native Desktop Agent implementation, not as a separate supported module.

### MicroK8s

The MicroK8s zone is the official home for:

- backend services
- `api-gateway`
- `voice-gateway`
- `nlp-service`
- `orchestrator`
- `planner-service`
- `life-tracker`
- `analytics-service`
- `security-service`
- `user-profile`
- `smart-home-service`
- `llm-service`
- `memory-service`
- PostgreSQL and optional `pgvector`
- observability components
- cluster ingress and release overlays

Target-zone note for not-yet-converged infrastructure:

- Kafka and RabbitMQ belong to the MicroK8s zone when they are part of the supported runtime.
- In current repo reality, they appear in configuration and Docker assets, but they are not yet part of the same evidence-backed committed MicroK8s baseline as PostgreSQL, Mosquitto, and observability.

## 5. Service Ownership Boundaries

- `api-gateway` is the main authenticated HTTP and WebSocket edge.
- `voice-gateway` owns voice transport, STT/TTS orchestration, websocket lifecycle, and voice-facing notification flows.
- `desktop-javafx` owns native desktop UI, wake-word capture, local device state, and user-present workstation integration.
- `pc-control` owns the service contract for desktop actions, but real host execution stays in the native host zone.
- `vision-security-service` owns workstation-local CV and security flows and stays outside the core K8s base runtime.
- `orchestrator` routes intents and downstream actions; it is not a direct database writer on behalf of the LLM.
- `planner-service`, `life-tracker`, `security-service`, `user-profile`, `smart-home-service`, and other domain services own state changes in their domains.
- `llm-service` is an authenticated AI façade, not a domain state owner.
- `memory-service` owns its memory/vector API boundary; direct LLM-to-database writes remain disallowed even when vector storage exists.

## 6. Local AI Rules

- The canonical local AI stack remains host-oriented.
- Host-local inference uses the current llama.cpp-backed worker implementation exposed behind `llm-service`.
- `llm-service` and `memory-service` remain optional and must stay behind service APIs.
- Local or cluster AI services may read from allowed service APIs and tool inputs, but they must not bypass service ownership by writing directly to relational or vector databases.
- Optional AI Kubernetes workloads remain optional until a release overlay explicitly includes immutable digests for them.
- The committed `k8s/overlays/prod-release` artifact excludes the optional LLM ingress stack by default, so public AI in Kubernetes is not the default production claim today.

## 7. Desktop / Native Agent Rules

- `desktop-javafx` is the current official Native Desktop Agent implementation.
- It is not deprecated in Phase 0.
- It may later be renamed or internally refactored, but that is explicitly out of scope for this phase.
- Local OS control, wake word, desktop UX, local capture, and user-present workstation behavior belong here or in companion native host agents.
- Launcher helpers and desktop packaging scripts are supporting surfaces around the same native-host responsibility, not replacements for it.

## 8. Kubernetes / MicroK8s Rules

- `jarvis-prod` is the only production namespace.
- Production manifests, production overlays, production rollout scripts, and production documentation must converge on `jarvis-prod`.
- `k8s/overlays/prod` remains a mutable cluster input, not the final release artifact.
- `k8s/overlays/prod-release` is the release artifact because it is generated from immutable digests.
- Non-production overlays such as `k8s/overlays/staging` and `k8s/overlays/dev-hostpath` may keep separate namespace choices when they are intentionally test or development surfaces.
- Current launcher/runtime helpers may still support overrides for compatibility, but the default production story must be `jarvis-prod`.

## 9. Deprecated Docker Paths

Deprecated runtime path. Kept temporarily for compatibility and migration evidence. Production runtime target is native host + MicroK8s under `jarvis-prod`.

Phase 0 rules for Docker assets:

- Existing Dockerfiles are kept.
- Existing Docker-oriented runtime and image build assets are kept.
- Existing local helper paths that still rely on Docker, such as managed local PostgreSQL, are kept.
- Docker Compose is not the production source of truth.
- New Docker-specific runtime files are rejected by repository guardrails.
- Removal is deferred until native-host and MicroK8s parity is demonstrated with evidence.

## 10. Non-Goals For Phase 0

- Renaming services
- Deleting Dockerfiles or Docker helper assets
- Removing working local runtime paths
- Removing working `voice-gateway`, `api-gateway`, `desktop-javafx`, `pc-control`, or `vision-security-service` paths
- Claiming Kafka or RabbitMQ are already fully converged into the committed MicroK8s baseline when the repo does not yet prove that
- Replacing the current Native Desktop Agent implementation

## 11. Migration Roadmap After Phase 0

1. Converge production namespace and release scripts fully on `jarvis-prod`, with rendered-manifest proof.
2. Harden the native host zone around `desktop-javafx`, host AI, CV, and workstation control contracts.
3. Prove optional AI parity between native host and MicroK8s release overlays.
4. Remove deprecated Docker runtime paths only after parity evidence exists.
5. Consider future module renames or deeper refactors only after runtime ownership boundaries stay stable.

## 12. Acceptance Criteria Mapping

- Canonical architecture spec:
  - This file is the Phase 0 source of truth.
- Baseline evidence:
  - `docs/architecture/phase-0-baseline-evidence.md`
- Runtime-zone ADR:
  - `docs/architecture/ADR/ADR-0001-runtime-zones-native-host-and-microk8s.md`
- Native desktop ADR:
  - `docs/architecture/ADR/ADR-0002-desktop-javafx-native-desktop-agent.md`
- Docker deprecation ADR:
  - `docs/architecture/ADR/ADR-0003-docker-runtime-deprecation-before-removal.md`
- Production namespace ADR:
  - `docs/architecture/ADR/ADR-0004-production-namespace-jarvis-prod.md`
- Desktop implementation alignment:
  - README and service docs identify `desktop-javafx` as the current Native Desktop Agent.
- Docker deprecation:
  - Existing Docker paths are documented as deprecated but retained.
- Production namespace:
  - Production docs and defaults converge on `jarvis-prod`.
- Repository guard:
  - `scripts/guards/reject-new-docker-runtime-files.sh` blocks newly added Docker runtime files while allowing existing ones during Phase 0.
- Compile/runtime preservation:
  - Baseline evidence proves that `api-gateway`, `voice-gateway`, `desktop-javafx`, `pc-control`, and `vision-security-service` currently build and the local smoke path passes.
