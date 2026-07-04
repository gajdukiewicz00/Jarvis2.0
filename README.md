# Jarvis 2.0

A **local-first, single-user personal AI assistant**: Java 21 / Spring Boot microservices, JavaFX desktop, real voice loop (vosk STT + espeak-ng TTS), local LLM via `host-model-daemon` (llama.cpp), semantic memory (PostgreSQL + pgvector), and full observability (Prometheus + Loki + Tempo + Grafana + Alloy).

> **Audit date:** 2026-05-09. **Status:** v1.0 in development. **Audience:** university defense + portfolio. See [docs/audit/JARVIS_AUDIT_REPORT.md](docs/audit/JARVIS_AUDIT_REPORT.md) for the current honest status, [docs/architecture/JARVIS_TARGET_STATE.md](docs/architecture/JARVIS_TARGET_STATE.md) for the v1.0 target, and [docs/DEMO.md](docs/DEMO.md) for the 5-minute demo path.

## Quickstart (local, 5 commands)

```bash
# 1. one-time environment check (java, maven, podman, vosk model paths)
./scripts/check-local-env.sh

# 2. one-time setup (downloads STT model, generates secrets, creates ~/.jarvis/)
./scripts/setup-local.sh

# 3. start the local stack (Postgres in podman + 12 Spring Boot services)
./scripts/runtime-up.sh

# 4. open the JavaFX desktop
./scripts/product/jarvis-desktop-launch.sh

# 5. when done
./scripts/runtime-down.sh
```

Voice + LLM + Memory (optional but required for the full v1.0 demo):

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/setup-ai-local.sh   # one-time, ~2 GB download
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

K8s deployment (canonical from 2026-05-09 → `infra/k8s/`):

```bash
./scripts/product/jarvis-deploy-microk8s-prod.sh
./scripts/verify-prod.sh
```

For the full demo flow (login → voice command → LLM answer → memory recall): [docs/DEMO.md](docs/DEMO.md).

## What This Repo Actually Contains

This is the canonical project overview for the repository.

Source of truth is the current code and runtime wiring: `pom.xml`, module `pom.xml` files, `application.yml` and `application*.yml`, controller/service classes, Flyway migrations, runtime scripts, and Kubernetes manifests. Older Markdown is only valid when it matches those artifacts.

## What This Repo Actually Contains

Jarvis is a Maven multi-module workspace for a personal assistant platform with:

- Spring Boot backend services under `apps/`
- JavaFX desktop applications under `apps/`
- optional Python AI workers under `apps/embedding-service-py/` and `apps/llm-server-py/` (built with `Containerfile`, daemonless OCI; `apps/llm-server-py/` is the deprecated Phase-3 wrapper, see [docs/LEGACY_AND_CLEANUP.md](docs/LEGACY_AND_CLEANUP.md))
- local runtime scripts under `scripts/`
- Kubernetes manifests under [`infra/k8s/`](infra/k8s/) (**canonical from 2026-05-09**) and a legacy [`k8s/`](k8s/) tree retained for `jarvis-launch.sh` compatibility — see [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md) and [docs/audit/JARVIS_AUDIT_REPORT.md](docs/audit/JARVIS_AUDIT_REPORT.md) finding P0-004
- a Phase-12 mobile scaffold under `apps/android-app/` (Gradle subproject excluded from the Maven reactor)

The repository contains real code for auth, gateway routing, voice I/O, NLP, orchestration, planning, personal-data tracking, analytics, smart-home actions, desktop control, and a workstation-local vision-security service.

Architecture alignment references:

- [docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md](docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md)
- [docs/architecture/phase-0-baseline-evidence.md](docs/architecture/phase-0-baseline-evidence.md)
- [docs/architecture/ADR/](docs/architecture/ADR/)

## Runtime Entry Points

Local process runtime:

- `./scripts/setup-local.sh`
- `./scripts/setup-voice-local.sh`
- `./scripts/setup-ai-local.sh`
- `./scripts/runtime-up.sh`
- `./scripts/runtime-status.sh`
- `./scripts/runtime-down.sh`

Kubernetes / product runtime:

- `./scripts/product/jarvis-deploy-microk8s-prod.sh` — **canonical** (targets `infra/k8s/overlays/prod`)
- `./scripts/verify-prod.sh` — canonical post-deploy verification
- `./jarvis-launch.sh` — legacy k3s launcher (targets `k8s/overlays/prod`); see [k8s/README.md](k8s/README.md) deprecation banner
- `./jarvis-stop.sh`
- `./jarvis-logs.sh`
- `./scripts/product/jarvis-*.sh`

Desktop run paths:

- `mvn -f apps/desktop-javafx/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run`

## Native Desktop Agent

`desktop-javafx` is the current Native Desktop Agent implementation.

- It is the current official desktop/native module.
- It is not deprecated in Phase 0.
- It owns the JavaFX desktop shell, launcher/runtime UX, local auth bootstrap, desktop WebSocket clients, and host-present UI workflows.
- `scripts/product/jarvis-launcher.sh` is a launcher/helper surface around the same native-host runtime path.
- Some runtime payloads still use the label `desktop-client`; in current repo reality that label points at the `desktop-javafx` implementation rather than a separate supported module.

Current reality notes:

- `vision-security-service` is part of the local runtime, not `k8s/base`.
- `pc-control` is real on a local workstation, but runs in stub mode in Kubernetes.
- optional AI workloads exist in local scripts and `k8s/overlays/prod`, but they are not part of the minimum core runtime.
- the mobile module exists at `apps/android-app/` (Phase 12 scaffold). It is a Gradle subproject excluded from the Maven reactor; build with `./gradlew assembleDebug` from inside that directory after running `gradle wrapper --gradle-version 8.5` once. APK install + device pairing are operator-side validation work, not covered by reactor tests.
- `./jarvis-launch.sh` is the mutable local k3s launcher path. Reproducible release deploys must use a generated digest-pinned `k8s/overlays/prod-release` overlay, either via `./scripts/product/jarvis-deploy-prod.sh` or `./jarvis-launch.sh --release-overlay`.
- Deprecated runtime path. Kept temporarily for compatibility and migration evidence. Production runtime target is native host + MicroK8s under `jarvis-prod`.

## Module And Runtime Catalog

| Component | Type | Runtime | Status | Doc |
| --- | --- | --- | --- | --- |
| `jarvis-common` | shared Java library | build-only | implemented | [docs/services/jarvis-common.md](docs/services/jarvis-common.md) |
| `api-gateway` | backend gateway | local + k8s | implemented | [docs/services/api-gateway.md](docs/services/api-gateway.md) |
| `voice-gateway` | backend voice service | local + k8s | implemented | [docs/services/voice-gateway.md](docs/services/voice-gateway.md) |
| `nlp-service` | backend NLP service | local + k8s | implemented, rule-based | [docs/services/nlp-service.md](docs/services/nlp-service.md) |
| `orchestrator` | orchestration service | local + k8s | partial, bounded intent router | [docs/services/orchestrator.md](docs/services/orchestrator.md) |
| `pc-control` | desktop-control service | local + k8s | implemented locally, stubbed in k8s | [docs/services/pc-control.md](docs/services/pc-control.md) |
| `vision-security-service` | workstation-local CV: owner verification + screen understanding (OCR, screen-context, local VLM ask-screen, Kafka → memory) | local only | implemented, local-only | [docs/services/vision-security-service.md](docs/services/vision-security-service.md) |
| `life-tracker` | personal-data service | local + k8s | implemented | [docs/services/life-tracker.md](docs/services/life-tracker.md) |
| `analytics-service` | analytics/read-model service | local + k8s | implemented | [docs/services/analytics-service.md](docs/services/analytics-service.md) |
| `planner-service` | planning service | local + k8s | partial, planner-owned LLM endpoints return `501` | [docs/services/planner-service.md](docs/services/planner-service.md) |
| `user-profile` | profile/context service | local + k8s | implemented | [docs/services/user-profile.md](docs/services/user-profile.md) |
| `security-service` | auth service | local + k8s | implemented | [docs/services/security-service.md](docs/services/security-service.md) |
| `smart-home-service` | smart-home API | local + k8s | implemented with static catalog | [docs/services/smart-home-service.md](docs/services/smart-home-service.md) |
| `llm-service` | authenticated AI facade | optional local + optional k8s | implemented, optional | [docs/services/llm-service.md](docs/services/llm-service.md) |
| `memory-service` | semantic memory/vector service | optional local + optional k8s | implemented, optional | [docs/services/memory-service.md](docs/services/memory-service.md) |
| `desktop-javafx` | unified desktop launcher + shell | local desktop | implemented | [docs/services/desktop-javafx.md](docs/services/desktop-javafx.md) |
| `apps/android-app` | mobile client | Android device | Phase 12 Pass 1 scaffold (Gradle, not in Maven reactor) | [apps/android-app/README.md](apps/android-app/README.md) |
| `sync-service` | E2E sync inbox for paired Android devices | local + k8s | implemented | (no doc page yet — see [ADR-0013](docs/architecture/ADR/ADR-0013-android-and-cloud-relay.md)) |
| `cloud-relay` | off-prem opaque blob forwarder | off-prem only | implemented | (no doc page yet — see [ADR-0013](docs/architecture/ADR/ADR-0013-android-and-cloud-relay.md)) |
| host model daemon | native llama.cpp inference worker | host only (Phase 3) | scripts/launchers under `infra/scripts/host-model-daemon/`; supersedes the legacy Python worker path | [docs/services/host-model-daemon.md](docs/services/host-model-daemon.md) |
| host embedding daemon | native embedding worker | host only (Phase 9) | supersedes `apps/embedding-service-py/` (the older `docker/embedding-service/` tree was retired) | [docs/services/embedding-service.md](docs/services/embedding-service.md) |
| `postgres` | runtime datastore | local managed container + k8s | implemented infra, optional pgvector overlay | [docs/services/postgres.md](docs/services/postgres.md) |
| `mosquitto` | MQTT broker | k8s only | implemented infra | [docs/services/mosquitto.md](docs/services/mosquitto.md) |
| `observability-stack` | Prometheus/Loki/Tempo/Grafana/Alloy | k8s only | implemented infra | [docs/services/observability-stack.md](docs/services/observability-stack.md) |

## Actual Topology

The repo is centered around `api-gateway`.

- external clients authenticate through gateway-proxied `security-service` endpoints
- `api-gateway` is the main REST and WebSocket edge
- `voice-gateway` owns STT, TTS, voice WebSocket handling, and rule-based voice command dispatch
- `orchestrator` routes normalized intents to `pc-control`, `smart-home-service`, and optional `llm-service`
- `life-tracker` is the main source-of-record service for finance, calendar, and time tracking
- `analytics-service` derives summaries from `life-tracker`
- `planner-service` stores tasks/reminders in PostgreSQL and talks to analytics/profile/voice/gateway paths
- `llm-service` fronts the `host-model-daemon` Service, which routes through manual Endpoints to llama.cpp on the Linux host, and can optionally integrate with `memory-service` and `user-profile`
- `memory-service` uses `embedding-service` plus PostgreSQL/pgvector
- `vision-security-service` is a separate local workstation service with gateway proxying

## Local Runtime Defaults

Default local ports come from `scripts/runtime/common.sh`.

| Component | Port |
| --- | --- |
| `api-gateway` | `8080` |
| `voice-gateway` | `8081` |
| `nlp-service` | `8082` |
| `orchestrator` | `8083` |
| `pc-control` | `8084` |
| `life-tracker` | `8085` |
| `smart-home-service` | `8086` |
| `analytics-service` | `8087` |
| `security-service` | `8088` |
| `user-profile` | `8089` |
| `llm-service` | `8091` |
| `planner-service` | `8092` |
| `memory-service` | `8093` |
| `vision-security-service` | `8094` |
| `host-model-daemon` main channel | `18080` |
| `embedding-service` | `15001` |

Optional local AI flags:

- `ENABLE_LLM=true`
- `ENABLE_MEMORY=true`

The local runtime can auto-start a managed Postgres container if external DB settings are not supplied.

## Kubernetes Reality

`k8s/base` contains:

- core Spring services
- `postgres`
- `mosquitto`
- selectorless `host-model-daemon` Service + manual Endpoints for host llama.cpp
- ingress resources
- observability manifests

`k8s/overlays/prod` adds hardening and optional AI workloads:

- `llm-service`
- `embedding-service`
- `memory-service`
- `postgres-pgvector`

Those optional AI workloads are present in manifests but default to `replicas: 0` in the prod overlay.

Release rule:

- `k8s/overlays/prod` is a workspace/local launcher input and is not the final release artifact because its image refs are mutable.
- the honest release artifact is `k8s/overlays/prod-release/kustomization.yaml`, generated by `./scripts/product/jarvis-promote-images.sh` with immutable digest refs.
- the repo's canonical `k8s/overlays/prod-release` artifact excludes the optional LLM stack by default; public AI in Kubernetes is only part of a release when the generated overlay is regenerated with an immutable `llm-service` digest. Model execution stays on the host via `host-model-daemon`.
- production namespace is `jarvis-prod`
- `k8s/overlays/staging` and `k8s/overlays/dev-hostpath` are non-production overlays and should not be confused with the production namespace contract

## Current Constraints

- the only mobile module is `apps/android-app/`, a Phase 12 Pass 1 Gradle scaffold excluded from the Maven reactor — no reactor build emits an APK and there is no production mobile client today
- `vision-security-service` is not deployed by `k8s/base`
- `pc-control` is intentionally stubbed in Kubernetes (`PC_CONTROL_STUB_MODE=true`)
- `smart-home-service` uses a fixed device catalog and in-memory state
- `nlp-service` is deterministic/rule-based
- `orchestrator` is not a general workflow engine
- `planner-service` core planner works, but planner-owned LLM endpoints return `501 Not Implemented` (see [`LlmEnhancementService.java`](apps/planner-service/src/main/java/org/jarvis/planner/service/LlmEnhancementService.java))
- `apps/llm-server-py` is the deprecated Phase-3 Python wrapper retained for the local AI scripts; the production inference path is the native `host-model-daemon` (Phase 3) routed through a selectorless K8s Service in `k8s/base/host-model-daemon/`
- `k8s/` and `infra/k8s/` are both currently live; the digest-pinned `k8s/overlays/prod-release/` artifact and `jarvis-launch.sh` target `k8s/`, while `scripts/product/jarvis-deploy-microk8s-prod.sh` and `scripts/verify-prod.sh` target `infra/k8s/`. Pick one explicitly per deployment (see [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md))

## Build And Verification

Build or test from the repo root:

```bash
mvn test
```

Default `mvn test` is the Docker-free lane. It runs unit tests and Spring slice tests, and excludes tests tagged `integration` or `microk8s`.

Run container-backed or environment-backed tests explicitly:

```bash
mvn verify -Pintegration
mvn verify -Pmicrok8s
```

The `integration` profile runs tests tagged `@Tag("integration")` through Failsafe during `verify`; these tests may require Docker/Testcontainers or a compatible PostgreSQL environment. The `microk8s` profile is reserved for tests tagged `@Tag("microk8s")` that validate a real MicroK8s/runtime deployment. CI should keep `mvn test` as the default fast lane and schedule profile-backed verification only in jobs where Docker or MicroK8s is available.

Target one module with dependencies:

```bash
mvn -pl apps/<module> -am test
```

Typical verification commands for this repo:

```bash
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh
./scripts/verify-observability.sh
./scripts/guards/reject-new-docker-runtime-files.sh --all
./infra/scripts/microk8s/verify-no-docker-runtime.sh --strict
```

## Security

Jarvis is designed for trusted **local / LAN** use. It is **not** ready for unrestricted public exposure today — see the open hardening items in [docs/security/SECURITY_HARDENING_PLAN.md](docs/security/SECURITY_HARDENING_PLAN.md).

What the platform enforces today:

- User auth via `security-service` (BCrypt, signed JWT access + refresh, server-side refresh rotation with reuse detection).
- Internal service-to-service auth via `X-Service-Token` minted from `SERVICE_JWT_SECRET`; downstream services trust delegated `X-User-*` headers only when the request carries a valid service JWT.
- Spring Method Security on internal-only endpoints (`hasAuthority('SVC_INTERNAL')`).
- TLS-only ingress with `force-ssl-redirect`; only three hostnames are exposed (`api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local`).
- Pod-Security Restricted profile: `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, all capabilities dropped, `RuntimeDefault` seccomp; enforced by Kyverno policies in `k8s/overlays/prod/kyverno/`.
- Secrets sourced from a Kubernetes `Secret/jarvis-secrets`; never embedded in ConfigMaps.

Local-only components — must not be exposed publicly:

- `pc-control` real-control mode — controls keyboard, mouse, file system on the host.
- `vision-security-service` — owner-verification camera; deliberately absent from `k8s/base`.
- Host-model daemon — local LLM runtime that backs `llm-service` in production.

Secrets handling:

- `secrets/secrets.example.env` is the only template tracked in git; real values live in `~/.jarvis/secrets/secrets.env` and `~/.jarvis/tls/`.
- See [docs/security/SECRETS_POLICY.md](docs/security/SECRETS_POLICY.md) for the canonical rules.

Reference documents:

- [docs/security/SECURITY.md](docs/security/SECURITY.md) — security overview
- [docs/security/SECURITY_AUDIT.md](docs/security/SECURITY_AUDIT.md) — last repo-first audit (2026-05-08)
- [docs/security/SECURITY_HARDENING_PLAN.md](docs/security/SECURITY_HARDENING_PLAN.md) — P0/P1/P2 backlog
- [docs/security/SECURITY_COMPONENT_STATUS.md](docs/security/SECURITY_COMPONENT_STATUS.md) — per-component status table
- [docs/security/AUTH_MODEL.md](docs/security/AUTH_MODEL.md) — formal user-auth and service-auth model
- [docs/security/SECRETS_POLICY.md](docs/security/SECRETS_POLICY.md) — secrets handling policy

## Supplementary Docs Kept On Purpose

Local or policy docs that remain intentionally outside the canonical overview:

- [ARCHITECTURE.md](ARCHITECTURE.md) — runtime topology and data flows
- [docs/COMPONENT_STATUS.md](docs/COMPONENT_STATUS.md) — full component inventory with status per module
- [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md) — supported runtime modes and their entry points
- [docs/LEGACY_AND_CLEANUP.md](docs/LEGACY_AND_CLEANUP.md) — deprecated, legacy-candidate, and drift findings (audit dated 2026-05-08)
- [k8s/README.md](k8s/README.md)
- [infra/k8s/README.md](infra/k8s/README.md)
- [docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md](docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md)
- [docs/architecture/milestone-1-architecture-lock.md](docs/architecture/milestone-1-architecture-lock.md)
- [apps/android-app/README.md](apps/android-app/README.md)
- [docs/HTTPS_STANDARD.md](docs/HTTPS_STANDARD.md)
- [docs/security/SECRETS_POLICY.md](docs/security/SECRETS_POLICY.md)

Per-service and per-module documentation lives under [`docs/services/`](docs/services/).

---

## Quick verify & Obsidian (2026-06-06)

Run the whole stack health + memory + Obsidian + brain in one go:

```bash
./jarvis health                      # READY/DEGRADED verdict
./jarvis doctor                      # GPU, k3s, host LLM/TTS, host-model-daemon endpoint
./scripts/jarvis-smoke-verify.sh     # 8 end-to-end checks (brain, memory, unified search, planner, tests)
```

### Obsidian (host vault `~/JarvisVault`)
```bash
./jarvis obsidian status
./jarvis obsidian note "Title" --body "text" --folder memory
./jarvis obsidian daily --text "what I did"
./jarvis obsidian index              # push vault notes into vector memory
python3 scripts/tests/test_jarvis_obsidian.py   # 11 unit tests
```
Notes are written to `~/JarvisVault` (open it as an Obsidian vault). Secret-shaped
content is redacted before write. See `docs/MEMORY_AND_MODELS.md`.

### If the 14B brain goes silent (placeholder regression)
The `host-model-daemon` Endpoints can reset to `192.0.2.1` on a cluster re-apply,
making the GPU 14B unreachable. Detect + fix:
```bash
./scripts/jarvis-host-endpoint-check.sh          # reports host + slice + cluster reachability
./scripts/jarvis-host-endpoint-check.sh --fix    # re-patch to node IP (also run by `jarvis up`)
```
Keep `llm-service` env `JARVIS_HOST_DAEMON_ENABLED=false` and
`LLM_SERVER_URL=http://host-model-daemon...:18080` (true needs the unused
coding/router daemons on :18081/:18082 and fails readiness). Details:
`docs/MEMORY_AND_MODELS.md`, `docs/COMPONENT_STATUS.md`.

### Unified memory search (conversations + screen-context + Obsidian notes)
```bash
curl -sk -H 'Host: api.jarvis.local' -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -X POST \
  https://10.113.0.176/api/v1/memory/search/unified -d '{"query":"coffee","topK":5}'
# returns results tagged source=conversation|obsidian|memory, with file path + score.
# Note search is SEMANTIC-first (pgvector, finds notes by meaning even without the exact
# words) with keyword fallback; the response field `noteSearchMode` is "semantic"|"keyword".
```

> **Android E2E sync** still requires a manual NodePort + phone pairing —
> run `./scripts/jarvis-android-setup.sh` (read-only; prints the exact NodePort +
> NetworkPolicy commands, since the agent guard blocks prod NodePort patches).
