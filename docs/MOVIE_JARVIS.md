# Movie-J.A.R.V.I.S. — capability upgrade (2026-06)

This document records the "make it feel like the film" upgrade applied on top of the
v1.0 stack. Everything here is **local-first** (no cloud LLM/TTS). Working-tree changes
are **not committed** by design (operator rule).

## Brain — Qwen3-14B on GPU
- Host `llama.cpp` daemon on `:18080` now serves **Qwen3-14B-Instruct Q4_K_M** fully on
  the RTX 5070 (`-ngl 99 -fa on -ctk q8_0 -ctv q8_0`), ~24 tok/s, ~10 GB VRAM.
- Managed by `scripts/jarvis-llm-daemon.sh` (systemd `jarvis-llm@18080`, EXTRA args + model
  in `~/.jarvis/llm-18080.env`). The **whole cluster** reaches it: `llm-service` is wired
  to `host-model-daemon` (Endpoints patched to the node IP; `JARVIS_HOST_DAEMON_ENABLED=true`).
- `orchestrator` LLM reasoning enabled (`JARVIS_LLM_ENABLED=true`).
- `planner-service` LLM endpoints (`/api/v1/planner/llm/{generate-document,parse-task,recommend}`)
  now delegate to `llm-service` (host 14B) instead of returning `NOT_IMPLEMENTED`.

## Voice — Piper neural TTS
- Host Piper (`~/piper/piper`) with British `en_GB-alan-medium` + Russian `ru_RU-dmitri-medium`
  voices in `~/.jarvis/models/tts/`.
- `scripts/jarvis-say.sh` — unified TTS (Piper → spd-say fallback, auto EN/RU by script).
  Wired into `jarvis-loop.sh`, `jarvis-wake.sh`, `jarvis-host-bridge.sh`.
- **Cluster voice** also speaks via Piper: `voice-gateway` gained a `piper` TTS provider
  (`TtsService`) that calls a host Piper HTTP daemon (`scripts/jarvis-tts-daemon.py`, `:18090`)
  exposed to the cluster as Service `host-tts-daemon` (`infra/k8s/base/host-tts-daemon/`,
  selectorless + Endpoints to node IP + a scoped egress NetworkPolicy). Set via
  `TTS_PROVIDER=piper`, `TTS_PIPER_URL=http://host-tts-daemon...:18090`.

## Persona — single source of truth
- `scripts/jarvis-persona.txt` — refined British majordomo: addresses you as "sir", dry
  understatement, concise, candid about limits, **language-adaptive** (replies in your language).
- Injected into the host loop and aligned in the cluster (`llm-service`
  `PersonalizedPromptBuilder` + `LlmService` dialog prompt) — no more Russian-only lock.

## Proactivity — speaks on its own
- Host: `scripts/jarvis-proactive.sh` (systemd `jarvis-proactive`) watches the screen and
  decides whether to say anything — heavily biased to silence, quiet hours, anti-spam.
- Cluster: `life-tracker` `ProactiveWarningScheduler` evaluates life-map warnings and pushes
  them to `voice-gateway /internal/voice/notify` (service JWT) → Piper → your session.
  Config under `jarvis.proactive.*` (set `JARVIS_PROACTIVE_USERS` to your user id).

## Memory / continuity
- `llm-service` RAG (pgvector) is enabled (`MEMORY_ENABLED=true`) — past context is
  retrieved and injected into the 14B prompt.
- `scripts/jarvis-memory-sync.sh` pushes screen-context observations to the cluster
  memory-service (host → ingress `api.jarvis.local` → `/api/v1/memory/ingest`, service JWT
  from `$SERVICE_JWT_SECRET`). Wired into the loop + proactive tick. No-op without the secret.

## Always-on (durability)
- `jarvis-llm@18080` is systemd-enabled and linger is on.
- The Piper TTS daemon and proactive loop ship systemd units
  (`scripts/jarvis-tts-daemon.service`, `jarvis-proactive.sh install`). Enabling persistent
  units is operator-gated — see the header of `scripts/jarvis-tts-daemon.service`.

## How to try it
```bash
scripts/jarvis-loop.sh --dry-run "Что я делаю на экране?"   # full loop, no actions
scripts/jarvis-proactive.sh once                            # one proactive observation
scripts/jarvis-say.sh "Good evening, sir."                  # neural voice
```

See also: `docs/JARVIS_ALIVE.md`, `docs/USER_STORIES.md` (220-item backlog).
