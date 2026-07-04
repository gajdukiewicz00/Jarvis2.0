# Jarvis 2.0 — 5-Minute Demo

This is the canonical demo path for Jarvis v1.0. It is written for: a recruiter clicking through the project, a thesis examiner reviewing a defense, a friend who wants to see what Jarvis does.

If anything below fails, the answer is in [docs/services/](services/) (per-service troubleshooting) or [docs/audit/JARVIS_AUDIT_REPORT.md](audit/JARVIS_AUDIT_REPORT.md).

## What you will see

1. Login from the JavaFX desktop to the local Jarvis backend.
2. Voice command → STT → LLM → TTS round trip in Russian.
3. A planner task created by voice and visible in the Planner tab.
4. A semantic memory recall: persist a note, then ask Jarvis a related question.
5. The Grafana dashboard showing live request volume.

Total time on a clean machine: ~30 minutes for first-time setup (model downloads), then ~5 minutes per demo run after that.

## Prerequisites

- Linux x86_64 (Ubuntu 22.04+ tested).
- Java 21, Maven 3.8+, podman.
- `espeak-ng` for TTS: `sudo apt install espeak-ng`.
- ~2 GB free disk for model downloads.
- Optional: NVIDIA GPU for faster LLM inference. CPU mode also works.

Quick check: `./scripts/check-local-env.sh` reports OK.

## Step 1 — One-time setup (do once)

```bash
cd /path/to/Jarvis2.0

# 1.1. Validate environment.
./scripts/check-local-env.sh

# 1.2. Generate ~/.jarvis/, secrets, vosk Russian STT model (~50 MB).
./scripts/setup-local.sh

# 1.3. Download GGUF LLM model + embedding model (~2 GB total).
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/setup-ai-local.sh
```

After this you have:

- `~/.jarvis/secrets/secrets.env` (mode 0600)
- `~/.jarvis/models/vosk-model-small-ru-0.22/` (Russian STT)
- `~/.jarvis/models/llm/Qwen2.5-3B-Instruct-Q4_K_M.gguf` (~1.8 GB)
- `~/.jarvis/venvs/llm-server` and `~/.jarvis/venvs/embedding-service` (Python 3.10+)

## Step 2 — Start the stack

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

This brings up, in order:

| # | Service | Port | Purpose |
| --- | --- | --- | --- |
| 0 | Postgres (managed podman container `jarvis-local-postgres`) | 5432 | datastore |
| 1 | security-service | 8088 | JWT issuer |
| 2 | user-profile | 8089 | user context |
| 3 | nlp-service | 8082 | rule-based intent |
| 4 | orchestrator | 8083 | intent → action router |
| 5 | voice-gateway | 8081 | WebSocket + STT + TTS |
| 6 | pc-control | 8084 | desktop control |
| 7 | vision-security-service | 8094 | local CV (optional) |
| 8 | smart-home-service | 8086 | static device catalog |
| 9 | life-tracker | 8085 | finance / calendar |
| 10 | analytics-service | 8087 | summaries |
| 11 | api-gateway | 8080 | edge REST + WS |
| 12 | planner-service | 8092 | tasks / reminders |
| AI | llm-server (Python) | 15000 | llama.cpp wrapper |
| AI | embedding-service (Python) | 15001 | multilingual-e5-small |
| AI | llm-service | 8091 | authenticated AI facade |
| AI | memory-service | 8093 | pgvector store |

Verify: `./scripts/runtime-status.sh` — every line should show "UP".

If any service stays DOWN: `tail -200 ~/.jarvis/logs/local-runtime/<service>.log`.

## Step 3 — Open the desktop

```bash
./scripts/product/jarvis-desktop-launch.sh
```

You should see the JavaFX shell with tabs: Home, Planner, Life, Analytics, PC Control, Smart Home, Vision Security, Voice, Diagnostics, Settings, AI Runtime.

In the Settings tab → Endpoints, the API Gateway pill should be green ("ONLINE"). If it shows "OFFLINE" or "UNAUTHORIZED": double-check `./scripts/runtime-status.sh` and that the API gateway is at `http://localhost:8080`.

## Step 4 — Login

In the desktop:

1. Click "Sign in" (or use the default user pre-seeded by `setup-local.sh`).
2. Username: `demo` (created by `setup-local.sh`); password: shown in `~/.jarvis/secrets/local-bootstrap.txt`.
3. After login, all tabs become interactive.

## Step 5 — Voice round trip

Open the Voice tab.

1. Hold the microphone button.
2. Say (in Russian): **"Привет, Джарвис. Сколько сейчас времени?"** (Hi Jarvis, what time is it?)
3. Release the button.

Expected:

- A "Listening…" pill turns blue.
- The "Transcript" section shows the recognized Russian text.
- Jarvis replies in text and audio (espeak-ng): something like "Сейчас 14:32" — the time of day.

Round-trip latency on CPU-only: 4–8 seconds. On GPU: 1–3 seconds.

If audio doesn't play: check the TTS pill in the Voice tab. If it shows DEGRADED, install `sudo apt install espeak-ng`.

## Step 6 — Voice → Planner

In the Voice tab, say (in Russian): **"Создай задачу: купить хлеб завтра в 18:00."** (Create a task: buy bread tomorrow at 6 PM.)

Expected:

- Voice loop transcribes and dispatches the intent.
- Switch to the Planner tab → the new task appears with the parsed time.

## Step 7 — Memory recall (LLM + memory + embeddings together)

In the AI Runtime tab:

1. Type into "Persist note": **"Мой любимый язык программирования — Java 21."**
2. Click Save.
3. Then type into "Ask Jarvis": **"На каком языке я предпочитаю программировать?"**
4. Click Ask.

Expected: the model answer references Java 21. This proves: embedding worker is running, pgvector has the note, memory-service retrieved it, llm-service injected it as context for the LLM call.

## Step 8 — Observability (optional, if you brought up K8s)

For pure local mode, observability runs only in K8s. Skip this step or jump to the K8s flow:

```bash
./scripts/product/jarvis-deploy-microk8s-prod.sh   # canonical, infra/k8s/overlays/prod
./scripts/verify-observability.sh
# Open https://grafana.jarvis.local — admin password in ~/.jarvis/secrets/secrets.env
```

You should see Grafana with the Prometheus, Loki, and Tempo datasources auto-provisioned. The "Jarvis overview" dashboard shows request volume, voice loop latency, login attempts, and LLM call success rate.

## Step 9 — Stop

```bash
./scripts/runtime-down.sh
# or for K8s:
./jarvis-stop.sh
```

`runtime-down.sh` keeps the local Postgres container alive by default for fast restarts. Pass `--purge` (or set `JARVIS_KEEP_LOCAL_POSTGRES=false`) to also tear down the container.

## What this demo proves

- Java 21 multi-module Maven build works.
- Spring Boot service mesh starts in correct dependency order.
- JWT user auth + service-to-service auth work end-to-end.
- WebSocket voice loop with Russian STT → NLP → orchestrator → action → TTS.
- Local LLM via `host-model-daemon` / `llm-server` with no cloud dependency.
- pgvector semantic memory with embedding worker.
- Full observability stack in K8s mode.

## What this demo does NOT prove

- Multi-user isolation (Jarvis v1.0 is single-user).
- Public-internet hardening (v1.0 is LAN/host-only).
- Real smart-home device control (the catalog is static).
- Mobile app / Android pairing (Phase 12 scaffold only).

For the honest "what works / what doesn't" matrix: [docs/CAPABILITIES.md](CAPABILITIES.md).

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `runtime-up.sh` exits with "podman: command not found" | podman missing | `sudo apt install podman` |
| Postgres container fails to start | port 5432 in use | `JARVIS_LOCAL_POSTGRES_PORT=5433 ./scripts/runtime-up.sh` |
| Voice tab pill shows "STT: DOWN" | vosk model missing | Re-run `./scripts/setup-local.sh` |
| Voice tab pill shows "TTS: DEGRADED" | `espeak-ng` not installed | `sudo apt install espeak-ng` |
| "AI Runtime: model loading" never goes green | GGUF download incomplete | `du -sh ~/.jarvis/models/llm/` should be ~1.8 GB; if smaller, re-run `setup-ai-local.sh` |
| Login returns 401 with valid creds | `JWT_SECRET` rotated since last login | Reset preferences: `rm -rf ~/.java/.userPrefs/org/jarvis/desktop` |
| Desktop can't reach API gateway | endpoint pinned to wrong URL | In Settings → Endpoints, choose "Auto-detect"; or pin `http://localhost:8080` |

For deeper issues: each service has a doc page under [docs/services/](services/) with config envs, health endpoints, and known failure modes.
