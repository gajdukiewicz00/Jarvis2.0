# Jarvis 2.0 — Project Report

_Snapshot: 2026-06-05. Single-user, local-first AI assistant._

## 1. What it is

Jarvis 2.0 is a **fully local, microservice-based AI assistant** styled after the
film J.A.R.V.I.S. — you talk to it by voice, it has a personality, it watches your
screen, remembers context, runs your computer and smart home, tracks your life, and
proactively speaks up when something matters. **Nothing leaves the machine**: the LLM,
speech-to-text, text-to-speech, vision and embeddings all run on local hardware
(`LocalOnlyEnforcer` actively refuses cloud LLM endpoints).

It is not a toy script — it is a **production-style distributed system**: 22 service
modules + 3 shared libs, ~944 Java + ~166 Kotlin files, running as **26 pods across 22
deployments + 4 StatefulSets** on a live **k3s** cluster (`jarvis-prod`), with full
observability, dual-plane auth, TLS, and a phased delivery history (Phases 0–12).

## 2. Architecture & runtime

```
        Voice / Desktop / Android / API
                     │
              nginx ingress (api/voice/grafana.jarvis.local, TLS)
                     │
              ┌──────────────┐
              │ api-gateway  │  JWT (dual-plane), CORS, rate-limit, security headers,
              └──────┬───────┘  /status/report, proxies to all services
                     │
   ┌─────────┬───────┼───────────┬──────────┬───────────┐
 nlp-     orchestr-  llm-      memory-   life-      pc-control /
 service   ator      service   service   tracker    smart-home
   │         │         │          │         │
   │   RabbitMQ cmd   │      pgvector    Postgres
   │   pipeline +     │      (RAG)       (Flyway)
   │   risk/confirm   ▼
   │            host-model-daemon  ──►  llama.cpp  Qwen3-14B  (RTX 5070 GPU)
   │            host-tts-daemon    ──►  Piper neural TTS (host :18090)
   └─ Kafka audit/event bus ──► memory-service projection
```

- **Runtime:** k3s namespace `jarvis-prod`. Canonical manifests `infra/k8s/overlays/prod`
  (~137 objects). Images built with **Jib** (no Dockerfiles) → local registry `localhost:5000`.
- **Datastores:** PostgreSQL + PostgreSQL-pgvector + Kafka + RabbitMQ (StatefulSets) + Mosquitto (MQTT).
- **Host-bound parts** (need the GPU/X display): the LLM daemon, Piper TTS daemon, and
  vision/screen capture run on the host and are reached by the cluster via selectorless
  Services with Endpoints patched to the node IP, plus scoped NetworkPolicies.
- **Networking:** default-deny NetworkPolicy + per-service allow-lists; internal mTLS; nginx ingress.

## 3. Core capabilities (what it actually does today)

### 🧠 Brain
- Local **Qwen3-14B-Instruct (Q4_K_M)** on the RTX 5070 (full GPU offload, flash-attn,
  quantized KV cache, ~24 tok/s). OpenAI-compatible API. The **whole cluster** reasons
  through it (`llm-service` → `host-model-daemon`); orchestrator and planner LLM are enabled.
- Honest `NOT_CONFIGURED`/disabled fallback when the model is down; CPU-3B legacy fallback in-cluster.
- Risk-gated tool use: `ToolSchemaRegistry` + `ToolCallValidator` + `RiskClassification`/
  `IntentRiskCatalog` separate reasoning from dangerous actions.

### 🎭 Personality
- A single persona source (`scripts/jarvis-persona.txt` + cluster `PersonalizedPromptBuilder`):
  refined British majordomo — addresses you as "sir", dry wit, concise, candid about limits,
  **language-adaptive** (replies in your language). User-profile drives name/goals/style.

### 🗣️ Voice
- **STT:** Vosk (en/ru), Whisper fallback. **Wake-word:** Porcupine ("Jarvis"), push-to-talk fallback, VAD.
- **TTS:** **Piper neural voice** (British `alan` + Russian `dmitri`), on host *and* in the
  cluster (`voice-gateway` Piper provider → host-tts-daemon); speed/voice selectable; spd-say fallback.
- WebSocket streaming voice loop; `/internal/voice/notify` push so the cluster can speak unprompted.

### 🔔 Proactivity
- Host `jarvis-proactive.sh` watches the screen and decides — heavily biased to silence —
  whether to say anything (quiet hours, anti-spam). Cluster `ProactiveWarningScheduler`
  evaluates life-map warnings (time-waste / overspend / low-sleep) and **speaks them via Piper**.

### 👁️ Vision
- `vision-security-service` (host): screen capture + OCR + semantic tags, "ask the screen",
  owner verification by face, incident capture, frame-retention scheduler. (VLM optional, off by default.)

### 🧩 Memory / continuity
- `memory-service` with **pgvector** RAG injected into the 14B prompt; conversation history,
  session summaries, audit projection. Typed Obsidian-style notes with categories, search,
  **edit / delete ("forget") / export**. Screen-context → cluster memory via `jarvis-memory-sync.sh`.

### 🎮 Environment control
- `pc-control`: real Linux control — launch apps, open URLs/files, volume (pactl), media via
  **MPRIS/playerctl**, window focus/list/min/max, keyboard (xdotool, whitelisted) + bounded mouse,
  scenarios, hotkeys, NOTIFY; allow-list blocks shell/RCE; every action audited.
- `smart-home-service`: lights/thermostat/locks via **MQTT** (mosquitto) or mock transport.

### 📊 Life tracking & analytics
- `life-tracker` (Postgres-persisted): finance (expenses/budgets/recurring/goals), time-by-category,
  calendar, life-map with proactive warnings, **CSV bank import + data export**, and **wellness
  trackers** (habit/weight/mood/steps/workout/notes).
- `analytics-service`: expense/time/sleep aggregates **plus an insight layer** — anomalies,
  day-score, sleep/overtime signals, and a daily report.

### 📱 Mobile & sync
- `android-app` (builds to APK): pairing + **E2E-encrypted sync** (X25519/ChaCha20/Ed25519) via
  `sync-service` + opaque `cloud-relay`; manual finance entry, commands, and **Health Connect**
  (sleep/steps) read on-device, queued for sync.

### 🖥️ Desktop
- `desktop-javafx`: JavaFX shell + one-click launcher, health aggregator, life-map panel,
  settings, diagnostics, STT/TTS status pills.

## 4. Security & privacy
- Dual-plane JWT (user + service), **fail-closed by default**, distinct service secret, `SVC_INTERNAL`
  on `/internal/*`. Refresh-token rotation + revocation. Narrow CORS allow-list. **Security headers**
  (nosniff/DENY/Referrer/Permissions/COOP) at the gateway. Limited actuator exposure. Internal mTLS.
  Rate limiting. `LogSanitizer` redaction. **No hardcoded secrets** (k8s Secrets). **No cloud calls.**
- CI: `gitleaks` + `trivy` gates. (SBOM, per-jti access-token revocation, OWNER/GUEST roles = roadmap.)

## 5. Observability & ops
- **Prometheus + Grafana + Loki + Tempo + Alloy** deployed; per-service `/actuator/prometheus`;
  correlation IDs + trace propagation; `/api/v1/status/report` aggregator.
- Unified **`jarvis` CLI**: `up / status / health / doctor / logs / backup / restore / update / stop`.

## 6. Tech stack
Java 21 / Spring Boot (services) · Kotlin (desktop + Android) · Python (llm/embedding/vision/tts daemons) ·
llama.cpp + Qwen3-14B · Piper · Vosk · Porcupine · tesseract · PostgreSQL + pgvector · Kafka · RabbitMQ ·
Mosquitto · k3s · Jib · nginx ingress · Prometheus/Grafana/Loki/Tempo.

## 7. Maturity (against the 701-story product backlog)
~**41% done, ~20% partial, ~40% to-do** (see `docs/USER_STORIES_STATUS.md`). Strongest:
architecture, infra/observability, PC-control, brain, voice, security. Largest remaining:
smart-home/wearables breadth, desktop UX screens, deeper analytics/insight & memory-UI features,
voice DSP (noise/echo/barge-in), and the deeper auth model (roles/scopes/panic).

## 8. How to run
```bash
./jarvis doctor          # self-check (GPU, k3s, host LLM/TTS, ingress, pods)
./jarvis status          # pods + deployments + brain/voice health
scripts/jarvis-loop.sh --dry-run "Что я делаю на экране?"   # full local loop
scripts/jarvis-proactive.sh once                            # one proactive observation
scripts/jarvis-say.sh "Good evening, sir."                  # neural voice
```

## 9. Honest residuals / needs operator or device
- Enable the `jarvis-tts` / `jarvis-proactive` systemd units for reboot-durable always-on.
- Set `SERVICE_JWT_SECRET` to activate host→cluster memory sync.
- Install the APK on a phone to test pairing / Health Connect; wire server-side `HEALTH_ENTRY` consumption.
- The working tree carries a large operator-approved uncommitted change set ("treat as truth, do not commit").

---
_See also: `MOVIE_JARVIS.md` (the cinematic upgrade), `USER_STORIES_STATUS.md` (701-story status),
`JARVIS_ALIVE.md` (the end-to-end loop), `architecture/` (ADRs + phase evidence)._
