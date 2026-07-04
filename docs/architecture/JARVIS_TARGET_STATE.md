# Jarvis 2.0 — Target State (v1.0)

Audit date: 2026-05-09
Source: [docs/audit/JARVIS_AUDIT_REPORT.md](../audit/JARVIS_AUDIT_REPORT.md)
Cross-reference: [docs/RUNTIME_MODES.md](../RUNTIME_MODES.md), [docs/COMPONENT_STATUS.md](../COMPONENT_STATUS.md), [docs/security/SECURITY.md](../security/SECURITY.md)

This document defines the **honest target state** for Jarvis v1.0. It is *not* a wish-list. Anything in this document either works today or has a clear, scheduled path to working before v1.0.

Audience for v1.0: **university defense + portfolio reviewers**. The product narrative therefore is: "a local-first, voice-driven personal assistant that runs on a single workstation, with a clear migration path to K8s and a real local LLM."

---

## 1. What Jarvis Is (v1.0)

A **local-first, single-user personal AI assistant** that:

1. Runs as a Spring Boot microservice mesh on the user's own workstation (Mode 1) or as a k3s/MicroK8s deployment in a home LAN (Mode 2).
2. Provides a JavaFX desktop shell as the primary user interface.
3. Has a real, working voice loop: WebSocket → STT (vosk, RU/EN) → NLP intent → orchestrator → action (pc-control, smart-home, planner, life-tracker) → response → TTS (espeak-ng).
4. Provides a real local LLM facade (`llm-service`) backed by the native `host-model-daemon` (llama.cpp) running on the host. CPU mode supported; GPU optional.
5. Provides semantic memory (`memory-service`) backed by PostgreSQL + pgvector + an embedding worker (`embedding-service-py`, multilingual-e5-small, 384 dims).
6. Tracks personal data (life-tracker: finance, calendar, time tracking; planner-service: tasks, reminders, recommendations).
7. Exposes itself only on the host machine and the trusted LAN, with TLS at the ingress, HMAC-SHA JWT for users, distinct HMAC-SHA service JWT for service-to-service, and per-deployment hardening (Pod Security Restricted, Kyverno enforce in prod).
8. Ships full observability (Prometheus + Loki + Tempo + Grafana + Alloy) with provisioned datasources and a documented dashboard.

## 2. What Jarvis Is Not (v1.0)

- **Not a multi-tenant SaaS.** No OIDC, no per-user isolation in storage, no public sign-up.
- **Not internet-facing.** Public ingress is intentionally not part of v1.0. Reviewers should not point this at port 443 on a VPS.
- **Not a generic AI agent framework.** The orchestrator is a bounded intent router, not a general workflow engine.
- **Not a phone-home product.** `LocalOnlyEnforcer` blocks cloud LLM URLs in non-test profiles.
- **Not a mobile app.** `apps/android-app/` is a Phase-12 Pass-1 scaffold and is explicitly out of v1.0 scope.
- **Not a smart-home backend in the SmartThings sense.** `smart-home-service` ships with a fixed device catalog and in-memory state. Real-world device wiring is a Phase-13+ direction.
- **Not a Tauri / Electron / web-front-end.** The desktop UI is JavaFX; no browser front-end is in v1.0.

## 3. Core Modules (must work for v1.0)

| Module | Purpose | Default port (local) | Health |
| --- | --- | --- | --- |
| `api-gateway` | Edge REST + WS, Spring Cloud Gateway, JWT validation | 8080 | `/actuator/health` |
| `security-service` | JWT issuer (user plane), refresh-token store, password mgmt | 8088 | `/actuator/health` |
| `voice-gateway` | STT (vosk), TTS (espeak-ng), voice WebSocket dispatch | 8081 | `/actuator/health/readiness` (per-component: stt, tts, assets, ws) |
| `nlp-service` | Rule-based intent extraction | 8082 | `/actuator/health` |
| `orchestrator` | Bounded intent router; RabbitMQ topology + Kafka audit | 8083 | `/actuator/health` |
| `pc-control` | Workstation desktop control (real on host, stub in K8s via `PC_CONTROL_STUB_MODE=true`) | 8084 | `/actuator/health` |
| `life-tracker` | Finance / calendar / time tracking | 8085 | `/actuator/health` |
| `analytics-service` | Read-model summaries over life-tracker | 8087 | `/actuator/health` |
| `planner-service` | Tasks, reminders, recommendations | 8092 | `/actuator/health` |
| `user-profile` | Profile / context | 8089 | `/actuator/health` |
| `smart-home-service` | Static-catalog smart-home API | 8086 | `/actuator/health` |
| `desktop-javafx` | JavaFX shell, launcher, login bootstrap | (no HTTP) | desktop process liveness |

These twelve modules are the **v1.0 core**. They run in Mode 1 (native host processes) and Mode 2 (k3s / MicroK8s).

## 4. AI Modules (must work for v1.0 — operator decision)

Operator decision (2026-05-09): AI **is required** for v1.0 demo.

| Module | Purpose | Default port (local) | Notes |
| --- | --- | --- | --- |
| `llm-service` | Authenticated AI facade; gates all LLM access | 8091 | gated by `ENABLE_LLM=true`; `LocalOnlyEnforcer` active |
| `memory-service` | Semantic memory: pgvector store, embedding fan-out | 8093 | gated by `ENABLE_MEMORY=true`; needs pgvector extension on Postgres |
| `host-model-daemon` (native, Phase 3) | llama.cpp on host CPU/GPU | 18080/18081/18082 | canonical inference path; routed in cluster via selectorless Service |
| `embedding-service-py` | FastAPI embedding worker; multilingual-e5-small (384 dims) | 15001 | local: virtualenv; cluster: Containerfile-built image |
| `llm-server-py` (legacy local path) | FastAPI llama.cpp wrapper | 15000 | retained for local AI scripts until Phase 7 migration |

### AI lifecycle expectations for v1.0

- A fresh local clone runs `./scripts/setup-ai-local.sh` once to create the venv, download the default GGUF model (Qwen2.5-3B-Instruct-GGUF, 1.8 GB) and create the embedding model cache.
- After setup, `ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh` brings up the full stack with AI.
- `host-model-daemon` is not started by `scripts/ai-up.sh` today on the host — only the legacy Python `llm-server` path is. Closing this gap is Phase-7 work and is documented in [`apps/llm-server-py/README.md`](../../apps/llm-server-py/README.md).

## 5. Local-Only Modules (never in cluster)

| Module | Why local only |
| --- | --- |
| `vision-security-service` (port 8094) | Owner verification using local camera + screen capture; cluster runtime would be a privacy violation. Deliberately absent from `infra/k8s/base/kustomization.yaml`. |
| `pc-control` real mode | Cluster runtime forces `PC_CONTROL_STUB_MODE=true` and `GatewayCapabilityService.requireDirectPcControlSupport()` returns 503 in K8S `RuntimeMode`. |

## 6. Optional / Future Modules

| Module | Status | Plan |
| --- | --- | --- |
| `sync-service` | Active; in `infra/k8s/base/sync-service/`. Pairs with desktop or future android-app for E2E sync. | v1.1 stretch — not gated by v1.0. |
| `cloud-relay` | Off-prem opaque-blob forwarder; in `k8s/cloud/cloud-relay/`. Not in core base. | v1.1+. |
| `apps/android-app` | Phase-12 Pass-1 Gradle scaffold. APK not built by Maven. | v1.2+. |
| Internal-TLS slice overlays (20×) | Experimental studies of pairwise mTLS. | Move to `k8s/overlays/internal-tls-experiments/<pair>/` for v1.0. Not part of canonical release. |

## 7. Runtime Modes (canonical paths)

### Mode 1 — Local native processes (canonical for v1.0 desktop demo)

```bash
# one-time setup (downloads STT models, sets defaults, validates env)
./scripts/setup-local.sh

# optional one-time AI setup (downloads GGUF + embedding model, ~2 GB)
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/setup-ai-local.sh

# start the stack
./scripts/runtime-up.sh

# verify
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh

# launch desktop
./scripts/product/jarvis-desktop-launch.sh

# stop
./scripts/runtime-down.sh
```

State lives under `~/.jarvis/run/local-runtime/` and `~/.jarvis/logs/local-runtime/`.

### Mode 2 — Kubernetes (k3s, mutable launcher)

```bash
# brings up k3s + ingress + jarvis-prod namespace + observability + core services
./jarvis-launch.sh

# AI overlay
./jarvis-launch.sh --enable-llm --enable-memory

# stop / purge
./jarvis-stop.sh
./jarvis-stop.sh --purge --yes
```

Targets `k8s/overlays/prod`. **Status under operator decision (2026-05-09): legacy.** Move to Mode 4 (`infra/k8s/`) for new work.

### Mode 3 — Kubernetes (digest-pinned release overlay)

```bash
./jarvis-launch.sh --release-overlay
# or
./scripts/product/jarvis-deploy-prod.sh
```

Targets `k8s/overlays/prod-release/kustomization.yaml`, generated by `./scripts/product/jarvis-promote-images.sh`. **Status: legacy** — same migration plan applies.

### Mode 4 — Kubernetes (MicroK8s production foundation, **canonical for v1.0+**)

```bash
./scripts/product/jarvis-deploy-microk8s-prod.sh
./scripts/verify-prod.sh
```

Targets `infra/k8s/overlays/prod`. **Canonical from 2026-05-09** per operator decision. Includes Kafka and RabbitMQ StatefulSets that `k8s/base/` lacks.

### Mode 5 — AI overlay

Activated by `ENABLE_LLM=true` + `ENABLE_MEMORY=true` (or `--enable-llm` / `--enable-memory` flags). Brings up `llm-server`, `embedding-service`, `memory-service`, `llm-service`, `postgres-pgvector`. Required for v1.0 demo.

### Mode 6 — Desktop launcher

```bash
mvn -f apps/desktop-javafx/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
# or
./scripts/product/jarvis-desktop-launch.sh
```

`apps/desktop-javafx` is the only desktop module per [ADR-0002](ADR/ADR-0002-desktop-javafx-native-desktop-agent.md).

### Mode 7 — Observability

Brought up before workloads by `jarvis-launch.sh ensure_observability_stack`. Verify with `./scripts/verify-observability.sh`. Grafana exposed via ingress only; Prometheus / Loki / Tempo / Alloy cluster-internal.

## 8. Canonical Commands

| Action | Command |
| --- | --- |
| Build all (no tests) | `mvn -DskipTests package` |
| Run unit tests | `mvn test` |
| Run integration tests (Docker / podman required) | `mvn verify -Pintegration` |
| Run microk8s tests (real cluster required) | `mvn verify -Pmicrok8s` |
| Local up | `./scripts/runtime-up.sh` |
| Local down | `./scripts/runtime-down.sh` |
| Local smoke | `./scripts/runtime-smoke.sh` |
| Local status | `./scripts/runtime-status.sh` |
| K8s up (canonical, target state) | `./scripts/product/jarvis-deploy-microk8s-prod.sh` |
| K8s verify (canonical, target state) | `./scripts/verify-prod.sh` |
| Observability verify | `./scripts/verify-observability.sh` |
| AI up | `./scripts/ai-up.sh` |
| AI down | `./scripts/ai-down.sh` |
| AI smoke | `./scripts/ai-local-smoke.sh` |
| Voice smoke | `./scripts/voice-local-smoke.sh` |

## 9. Auth Model (target state)

User plane:
- Issuer: `security-service`
- Algorithm: HMAC-SHA via jjwt
- Issuer claim: `jarvis`
- Token types: `access` (TTL **5 minutes** target — currently 60 min, see [P1-006](../audit/JARVIS_AUDIT_REPORT.md)) and `refresh` (TTL 30 days, rotated on use, reuse-detected)
- Storage: refresh tokens in PostgreSQL; access tokens stateless

Service plane:
- Issuer: each service via `apps/jarvis-common` `ServiceJwtProvider`
- Issuer claim: `jarvis-internal`, audience: `jarvis-services`
- Required: `svc=<service>`, `roles` includes `SVC_INTERNAL`, `token_type=service`
- Secret: `SERVICE_JWT_SECRET` — **must be distinct from `JWT_SECRET`** in non-test profiles (target state — currently falls back, see [P0-003](../audit/JARVIS_AUDIT_REPORT.md))

Gateway:
- `JwtAuthFilter` defaults to **enabled in code** (target state — currently defaults to disabled, see [P0-002](../audit/JARVIS_AUDIT_REPORT.md))
- Public paths: `/auth/login`, `/auth/register`, `/auth/logout`, `/auth/refresh`, `/api/v1/security/auth/*`
- `/actuator/**` paths: **internal-only** in target state (currently `/actuator/health` and `/actuator/prometheus` are public, see [P1-007](../audit/JARVIS_AUDIT_REPORT.md))

Desktop:
- Token storage: encrypted at rest with OS keystore where available, else AES-encrypted file with `0600` perms (target state — currently plaintext Java Preferences, see [P1-008](../audit/JARVIS_AUDIT_REPORT.md))

## 10. Voice Flow (target state)

```
desktop voice button
    → wss://voice.jarvis.local (or ws://localhost:8081 in Mode 1)
    → voice-gateway: STT (vosk, ru-RU default, en-US fallback)
    → text → nlp-service rule-based intent
    → orchestrator
    → pc-control / smart-home / planner / llm-service
    → response text
    → voice-gateway: TTS (espeak-ng)
    → WAV binary frame back to desktop
```

Error frames the server sends back: `ERROR{code, message}` for: protocol violations, NO_AUDIO_RECEIVED, STT_UNAVAILABLE, TTS_UNAVAILABLE, ORCHESTRATOR_TIMEOUT.

Desktop expectations: shows separate STT and TTS readiness pills. Shows install hint inline if a backend is missing (e.g. `sudo apt install espeak-ng`).

## 11. LLM Flow (target state)

```
desktop / voice / direct REST
    → api-gateway with user JWT
    → llm-service (port 8091, ENABLE_LLM=true required)
        → LocalOnlyEnforcer asserts target URL is LAN/host-only
        → POST /v1/chat/completions to host-model-daemon (port 18080 main / 18081 coding / 18082 router)
            → llama.cpp on host (CPU or GPU)
        → response
    → JWT-tagged response
```

Failure modes:
- LLM disabled → 503 with `service.unavailable.llm-disabled`.
- Daemon down → 503 with `service.unavailable.llm-daemon-down` and a structured error frame.
- Memory disabled → memory-service returns 503; llm-service degrades to no-context mode (still answers).

## 12. Memory Flow (target state)

```
api-gateway → memory-service (port 8093)
    → embedding-service-py /embed/single (port 15001) → 384-dim vector
    → PostgreSQL + pgvector ANN search (IVFFlat index)
    → top-k matches → returned to caller
```

Schema: V1 `conversation_message`, V2 `memory_chunk(embedding vector(384))`, V3 `session_summary`, V4 `audit_events`, V5 `memory_notes`.

What Jarvis stores: user-authored notes, conversation summaries, planner state, life-tracker entries the user explicitly persists. **What Jarvis never stores**: raw camera frames, raw screen captures (vision-security-service holds them only in-process), raw passwords, third-party API tokens.

## 13. Desktop Endpoint Resolution (target state)

1. `LocalRuntimeEndpointDetector::detectActive` checks `~/.jarvis/run/last-run.json` for an active local runtime.
2. If found: use `https://api.jarvis.local` (TLS) or `http://localhost:8080` (no TLS) automatically.
3. If not found: fall back to user's manual override in `Preferences.userRoot().node("/org/jarvis/desktop/settings")` (`api_gateway_base_url`).
4. **Target state (P1-011 fix):** before saving a manual override, the desktop performs a `GET <url>/actuator/health` test and refuses to save if non-2xx unless the user explicitly confirms.

Voice WebSocket scheme is environment-driven via `runtime_ws_scheme()` in `scripts/runtime/common.sh`.

## 14. Observability Flow (target state)

- Every service emits Micrometer + Prometheus metrics on `/actuator/prometheus` (cluster-internal scrape).
- All services log JSON via Logback (`logstash-logback-encoder`) to stdout; Alloy ships to Loki.
- Trace IDs propagated via Spring Cloud Sleuth; spans shipped to Tempo via OTLP (4317/4318).
- Grafana datasources auto-provisioned via ConfigMap (Prometheus, Loki, Tempo).
- Dashboards under `config/grafana-dashboards/` provisioned by `scripts/provision-grafana-dashboards.sh`.
- Grafana admin sync via `scripts/sync-grafana-admin.sh`.
- Public ingress: only Grafana under admin login. Prometheus / Loki / Tempo / Alloy stay cluster-internal (target state — currently `/actuator/prometheus` is public via gateway, see [P1-007](../audit/JARVIS_AUDIT_REPORT.md)).

## 15. Secrets / Config Policy (target state)

- All secrets in K8s `Secret` named `jarvis-secrets`, applied via `scripts/product/jarvis-secrets-apply.sh`.
- All deployments load env via `envFrom: secretRef: jarvis-secrets` (api-gateway pattern) or per-key `secretKeyRef` (Postgres, Grafana).
- Local: `~/.jarvis/secrets/secrets.env`, `0600`. Template: `secrets/secrets.example.env` (committed; only placeholders).
- TLS: `~/.jarvis/tls/`, `0600` per file. Generation: `scripts/product/jarvis-generate-certs.sh`.
- `.gitignore` covers: `*.pem`, `*.key`, `*.crt`, `*.jks`, `*.p12`, `*.pfx`, `secrets/`, `logs/`, `models/*`, `.env*` (except `.env.example`), `~/.jarvis/`, `last-run.json`.
- Secret rotation: documented in [docs/security/SECRETS_POLICY.md](../security/SECRETS_POLICY.md). For v1.0, manual rotation is acceptable.

## 16. v1.0 Scope Summary (gate criteria)

A reviewer can declare v1.0 done when **all** of the following are true:

- [ ] `git status --short` on `main` returns ≤5 lines (operator commit-plan executed).
- [ ] `mvn -DskipTests package` succeeds from a fresh clone in <10 min.
- [ ] `mvn test` succeeds from a fresh clone in <15 min.
- [ ] `./scripts/runtime-up.sh && ./scripts/runtime-smoke.sh && ./scripts/runtime-down.sh` succeeds end-to-end on a Linux box with podman.
- [ ] `./scripts/product/jarvis-desktop-launch.sh` opens the JavaFX shell and login works against the local runtime.
- [ ] Voice tab transcribes a Russian phrase and Jarvis answers (TTS audio frame returned).
- [ ] LLM tab: a free-text question returns a model answer from `host-model-daemon`.
- [ ] Memory: persisting a note then asking a related question produces a context-aware answer.
- [ ] Grafana dashboard "Jarvis overview" shows non-empty panels.
- [ ] All P0 audit findings are either fixed or documented as accepted-risk in [SECURITY.md](../security/SECURITY.md).
- [ ] [DEMO.md](../DEMO.md) (to be added) reads as a single 30-line demo path.

## 17. Experimental / Future Scope (post-v1.0)

- **OIDC / multi-tenant auth.** v1.1+.
- **Real smart-home device integration** (MQTT bridges to Zigbee2MQTT, Home Assistant). v1.1+.
- **Generic workflow engine in orchestrator.** v1.2+.
- **`apps/android-app` APK build + pairing flow.** v1.2+.
- **Image signing (cosign), SBOM (cdxgen), SAST (CodeQL), trivy CVE scan in CI.** v1.1.
- **Public-internet hardening profile** (CORS lockdown, rate limiting, OWASP top-10 validation). v1.1+.
- **Multi-key JWT rotation with `kid` claim.** v1.1.
- **Per-jti access-token deny-list cache** for immediate revocation. v1.1.
- **Remove `apps/llm-server-py`** once all local AI scripts call host-model-daemon directly. Phase-7.
- **Consolidate K8s trees**: retire `k8s/` once `infra/k8s/` covers all canonical paths. Phase-13.
