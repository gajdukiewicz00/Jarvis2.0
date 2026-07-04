# Jarvis is alive — local end-to-end loop

This is the verified, fully-local assistant loop: a single command makes Jarvis
look at the screen, reason with a local model, remember it, and take a real
desktop action — then speak the answer. **No cloud APIs are used.**

Entry point: [`scripts/jarvis-loop.sh`](../scripts/jarvis-loop.sh).

```bash
# full loop, real action (auto-confirm allow-listed launches) + spoken answer
scripts/jarvis-loop.sh --yes "Look at my screen, tell me what I am working on, remember it, and open the next useful tool."

# safe variants
scripts/jarvis-loop.sh --dry-run        # decide + speak, never act
scripts/jarvis-loop.sh --no-act         # skip the desktop action
scripts/jarvis-loop.sh --no-tts         # text only, no speech
```

## The loop (and which real component runs each stage)

| Stage | Component | Local? |
| --- | --- | --- |
| 1. Capture screen + OCR + screen-context | `vision-security-service` CLI (`--cv.screen-context`) → tesseract + gnome-screenshot | yes |
| 2. Reason / summarize / decide action | **Qwen2.5-3B** via llama.cpp `host-model-daemon` (`:18080`, OpenAI API) — same model the cluster `llm-service` uses | yes |
| 3. Memory write + read-back | JSONL store at `~/.jarvis/memory/screen-context.jsonl` (verifiable read-back) | yes |
| 4. Decide next action | model returns strict JSON `{summary, next_action{type,target,reason,dangerous}}` | yes |
| 5. Execute safe action | `xdg-open` / `gtk-launch` / `gnome-terminal` (allow-listed); `xdotool` for input | yes |
| 6. Respond | text + **local TTS** (`spd-say`) | yes |
| 7. Evidence | structured events JSONL + artifacts under `/tmp/jarvis-cv/` | yes |

## Why the loop runs host-side

Screen capture and desktop actions are physically bound to the workstation's X
display. A k3s pod cannot reach `DISPLAY`, so the loop runner executes on the
host. The **reasoning brain is the same local Qwen daemon** the in-cluster
`llm-service` calls (`host-model-daemon`), so this is consistent with the
cluster architecture, not a parallel one.

## Prerequisites (host)

- `tesseract`, `gnome-screenshot` (or `scrot`/`imagemagick`), `xdotool`, `xdg-open`, `spd-say` — all present on this workstation.
- X11 session (`echo $XDG_SESSION_TYPE` → `x11`).
- The local LLM daemon running (see below). If it is **down**, the loop is honest:
  it prints `NOT_CONFIGURED/UNAVAILABLE` with the exact start command and produces
  **no fabricated summary**.

### Start the local LLM brain (Qwen via llama.cpp)

```bash
/home/kwaqa/llama.cpp/build/bin/llama-server \
  -m ~/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf \
  --host 0.0.0.0 --port 18080 -c 8192 -t 6 --no-webui
# health: curl -s http://127.0.0.1:18080/health   -> 200
```

### Wire the cluster to the host daemon (one-time, for through-cluster LLM)

The `host-model-daemon` k8s Service ships with a placeholder Endpoints IP
(`192.0.2.1`). Patch it to the node IP so in-cluster `llm-service`/`orchestrator`
can reach the host model:

```bash
sudo env KUBECONFIG=/etc/rancher/k3s/k3s.yaml \
  bash infra/scripts/microk8s/apply-host-endpoints.sh --ip=10.113.0.176
# verify from a pod:
sudo k3s kubectl -n jarvis-prod exec deploy/llm-service -- \
  curl -s -o /dev/null -w '%{http_code}\n' \
  http://host-model-daemon.jarvis-prod.svc.cluster.local:18080/health   # -> 200
```

## Safety model

- **Dangerous actions require confirmation.** Any action with `dangerous=true`,
  or a target not on the safe launcher allow-list, is refused/blocked. Without
  `--yes`, every action prompts `[y/N]`.
- Allow-list maps model targets to local launchers: `terminal`→gnome-terminal,
  `editor`→code/gedit, `files`→nautilus, `browser`/URL→`xdg-open`.
- `--dry-run` prints the exact action it *would* take and executes nothing.
- No cloud endpoints are ever contacted (LLM is loopback llama.cpp; CV is local
  tesseract; TTS is local `spd-say`).

## Verified evidence (2026-05-26 run)

```
==> 1/7 capture screen  : window='… Microsoft Teams - Brave' tags=[DEVELOPMENT,COMMUNICATION] ocrChars=4180
==> 2/7 reason (LLM READY): "The user is working on a document … tagged DEVELOPMENT … looking for the next useful tool"
==> 3/7 memory          : WROTE + read-back (records: 2)
==> 4/7 decide          : type=open_app target=terminal dangerous=false
==> 5/7 act             : executed: open_app gnome-terminal   (confirmed: gnome-terminal process running)
==> 6/7 respond         : text + spoken via spd-say
==> 7/7 evidence        : /tmp/jarvis-cv/<run>.{events.jsonl,screen.json,decision.json,screen.png}
```

Honest-degradation check (LLM down):
`LLM NOT_CONFIGURED/UNAVAILABLE (health=000 …)` → no summary fabricated, exact start command printed.

## Status

| Capability | Status |
| --- | --- |
| CLI command → understand → screen → OCR → LLM → memory → action → speak | **READY (verified)** |
| Local LLM reasoning (Qwen/llama.cpp) | **READY** (`:18080`), cluster-reachable after endpoint patch |
| Screen context + OCR | **READY** |
| Memory write/read (local JSONL) | **READY** |
| Real safe desktop action (launch app) | **READY** (gnome-terminal launched) |
| Spoken response (local TTS) | **READY** (`spd-say`) |
| Logs + metrics | **READY** (events JSONL; `jarvis_cv_*` Prometheus when service in web mode) |

## Durable runtime (systemd + smoke)

The proof-of-concept is now a maintained runtime:

```bash
# LLM daemon as a systemd --user service (auto-restart, boot via linger)
scripts/jarvis-llm-daemon.sh install      # one-time: install + enable unit
scripts/jarvis-llm-daemon.sh start|stop|status|health|logs
loginctl enable-linger $USER              # one-time: start on boot w/o login

# cluster endpoint wiring check (fails if still the 192.0.2.1 placeholder)
scripts/jarvis-host-endpoint-check.sh [--fix]

# end-to-end smoke (SAFE: dry-run by default; --yes does a real action)
scripts/jarvis-alive-smoke.sh [--dry-run|--yes]
```

The CV → memory pipeline is deployed in-cluster: `memory-service` consumes
`jarvis.cv.screen_context.created` and persists to `screen_context_observation`
(raw screenshot bytes disabled in cluster; OCR text + tags + pgvector embedding
stored; idempotent).

## Reboot / k3s-restart recovery

1. **LLM daemon** — auto-starts via the systemd user unit + linger. Verify:
   `scripts/jarvis-llm-daemon.sh health` (expect `:18080 HTTP 200`). If down:
   `scripts/jarvis-llm-daemon.sh start`.
2. **host-model-daemon endpoint** — the patch persists in etcd across restarts,
   but the node IP can change (DHCP) and re-applying `k8s/base` resets it to the
   placeholder. Verify + fix: `scripts/jarvis-host-endpoint-check.sh --fix`.
3. **Cluster** — k3s restores pods automatically. Confirm:
   `kubectl -n jarvis-prod get pods`.
4. **Full check** — `scripts/jarvis-alive-smoke.sh`.

## Maintained runtime components

**Host bridge** (`scripts/jarvis-host-bridge.sh`) — the only place host-bound
ops run (k3s pods can't reach the display/mic). Structured-JSON contract on
stdin/stdout, plus an optional localhost-only HTTP server.
```bash
scripts/jarvis-host-bridge.sh health
echo '{"userId":"owner"}'                         | scripts/jarvis-host-bridge.sh screen-context
echo '{"text":"hello"}'                           | scripts/jarvis-host-bridge.sh speak
echo '{"type":"OPEN_APP","target":"terminal"}'    | scripts/jarvis-host-bridge.sh action        # dry-run (safe default)
echo '{"type":"OPEN_APP","target":"terminal","execute":true}' | scripts/jarvis-host-bridge.sh action
scripts/jarvis-host-bridge.sh serve --port 8770 --token "$TOKEN"   # GET /health, POST /screen-context|/voice-command|/speak|/action
```
Safety in code: `OPEN_APP/OPEN_URL/FOCUS_WINDOW/NONE`=SAFE; `TYPE_TEXT/HOTKEY`=GUARDED
(need `confirm`); `DELETE_FILE/RUN_SHELL/SEND_*/INSTALL_PACKAGE/COMMIT_PUSH_CODE/…`=DANGEROUS
(refused); unknown=refused. `execute=false` (dry-run) is the default. HTTP binds
127.0.0.1 and requires `X-Jarvis-Token` when a token is set. Secrets are redacted.

**Orchestrator `/assist`** (cluster brain) — `POST /api/v1/orchestrator/assist`
`{command, mode:dry-run|confirm|execute, useScreen, useMemory, speak,
screenContext, confirmationToken}`. Reasons with local Qwen, reads/writes
memory-service, classifies the proposed action, and **delegates execution to
the host bridge** (never fakes in-pod desktop control). Unit-tested
(`AssistServiceTest`). See [orchestrator-assist-design.md](architecture/orchestrator-assist-design.md).
Deploy: `mvn -pl apps/orchestrator -am -DskipTests -Djib.image.tag=assist-1 jib:build`
then `kubectl -n jarvis-prod set image deploy/orchestrator orchestrator=localhost:5000/jarvis/orchestrator:assist-1`.

**Voice daemon check** — `scripts/jarvis-voice-daemon-check.sh` verifies
Porcupine probe, Vosk, mic, VAD (silence→NO_SPEECH, speech→VAD_OK), Qwen, TTS.

**VAD tuning** — `--vad --silence-ms N --max-record-seconds N
--threshold-multiplier F --pre-roll-ms N` (energy gate, local). `--record-seconds`
is the fixed-window fallback. Noisy room: raise `--threshold-multiplier` (e.g. 4–6).

**Full smoke** — one command, safe by default:
```bash
scripts/jarvis-full-smoke.sh                 # report + READY/PARTIAL/NOT_READY matrix
scripts/jarvis-full-smoke.sh --yes           # also one real safe desktop action
scripts/jarvis-full-smoke.sh --live-wake     # prints the live-human wake instruction
scripts/jarvis-full-smoke.sh --start-wake-daemon
```

## Safety model (enforced in code, not just docs)

| Action class | Examples | Behaviour |
| --- | --- | --- |
| SAFE | OPEN_APP, OPEN_URL, FOCUS_WINDOW, NONE | run only in `execute`/`--yes`; dry-run otherwise |
| GUARDED | TYPE_TEXT, HOTKEY | require explicit confirm/token even in execute |
| DANGEROUS | DELETE_FILE, RUN_SHELL, SEND_*, INSTALL_PACKAGE, COMMIT_PUSH_CODE, MODIFY_SECURITY_SETTINGS, EXPOSE_SECRET, SHUTDOWN | always refused (no auto-execute) |
| UNKNOWN | anything else | refused |

Secret-looking text (keys/tokens/`BEGIN PRIVATE KEY`/long base64) is redacted
before it is logged, returned, or spoken. Raw screenshots/audio are never stored
in cluster mode (`store-raw-screenshot=false` in the prod overlay).

## Known limitations / next steps (honest)

- **Voice input:** DONE (first loop) — `scripts/jarvis-voice-smoke.sh` adds
  local Vosk STT in front of the loop: `audio → Vosk → text → jarvis-loop.sh →
  spoken answer`. `voice-gateway` OOM fixed (limit 2Gi). **Voice input is READY.**

  ```bash
  scripts/jarvis-voice-smoke.sh                         # bundled test wav, dry-run (safe)
  scripts/jarvis-voice-smoke.sh --wav file.wav --yes    # real action
  scripts/jarvis-voice-smoke.sh --record 5 --lang en    # capture 5s from the mic
  scripts/jarvis-voice-smoke.sh --wav file.wav --tts-out /tmp/answer.wav
  ```
  STT engine: Vosk (offline) via `.venv-voice` + `scripts/stt/vosk_transcribe.py`,
  models `~/.jarvis/models/vosk/vosk-model-small-{en-us,ru}`. Test WAV:
  `assets/voice-test/jarvis-screen-en.wav` (regenerate:
  `espeak-ng -w assets/voice-test/jarvis-screen-en.wav "Jarvis, look at my screen and tell me what I am working on."`).
  Manual mic recording: `arecord -d 5 -f S16_LE -r 16000 -c 1 cmd.wav`.

## Wake-word ("Jarvis") — `scripts/jarvis-wake.sh`

Always-listening → detect **"Jarvis"** (Porcupine, offline) → record utterance →
Vosk STT → `jarvis-loop.sh` → spoken answer. Engine selection:
1. **Porcupine** when a *valid* `PORCUPINE_ACCESS_KEY` + `Jarvis_en.ppn` load
   (verified: `PROBE_OK frame_length=512 sample_rate=16000`; mic stream + frame
   processing run — `LISTENING`/`TIMEOUT`). Live detection of a spoken "Jarvis"
   needs a human at the mic.
2. **push-to-talk** fallback when Porcupine can't init (e.g. invalid key) — press
   ENTER to talk; wake-word reported NOT_READY with the exact reason.
3. `--test-wav` bypasses detection (deterministic; CI/smoke).

```bash
# deterministic post-wake path (no human needed), SAFE/dry-run:
scripts/jarvis-wake.sh --test-wav assets/voice-test/jarvis-screen-en.wav --once --no-act
# one real wake+utterance — say "Jarvis", then "look at my screen and tell me what I am working on":
scripts/jarvis-wake.sh --once --record-seconds 5 --no-act
# VAD: record until you stop talking (silence) instead of a fixed window:
scripts/jarvis-wake.sh --once --vad --silence-ms 800 --max-record-seconds 10 --no-act
# allow the real desktop action:
scripts/jarvis-wake.sh --once --vad --yes
# RU keyword/STT (needs Jarvis_ru.ppn): scripts/jarvis-wake.sh --once --lang ru
```

**Key setup (done):** the valid Porcupine key now lives in `~/.jarvis/wake.env`
(mode 600, for the systemd daemon) and in `secrets/secrets.env` (a backup of the
prior malformed value is at `secrets/secrets.env.bak`). The key is never printed;
verify with `scripts/jarvis-wake.sh --once` (engine reports `porcupine` →
"wake-word READY"). To rotate: edit both files, no code change.

**VAD utterance capture:** `--vad` records until `--silence-ms` of silence (or
`--max-record-seconds`), using a local energy gate (`scripts/stt/vad_record.py`,
stdlib only — no cloud). `--record-seconds` remains the fixed-window fallback.

Continuous + daemon (systemd --user; **never auto-started**):
```bash
scripts/jarvis-wake.sh --continuous --no-act      # foreground always-listening
scripts/jarvis-wake.sh install                    # install unit (disabled)
scripts/jarvis-wake.sh start | stop | status | logs
```
The daemon does not inherit your shell profile, so put the key in
`~/.jarvis/wake.env` (`PORCUPINE_ACCESS_KEY=…`) — the unit reads it.

> ⚠ **Privacy:** continuous mode keeps the **microphone always listening
> locally**. Audio is processed on-device (Porcupine + Vosk) and never sent to
> any cloud. Stop anytime with `scripts/jarvis-wake.sh stop` (or Ctrl-C).
> **Safe by default:** `--no-act` — no desktop action runs unless you pass `--yes`.

> **No-cloud note:** STT (Vosk), reasoning (Qwen/llama.cpp) and TTS (`spd-say`)
> are fully local/offline. Porcupine *inference* is local; Picovoice may validate
> the AccessKey against its license server (a license check only — **never
> audio**). Use built-in keywords or an offline-activated key to avoid even that.

> **Key note (resolved):** the valid 56-char `PORCUPINE_ACCESS_KEY` is now in
> both `~/.jarvis/wake.env` and `secrets/secrets.env` (prior malformed 58-char
> value backed up to `secrets/secrets.env.bak`). Porcupine init verified from
> each file with the env var cleared (`PROBE_OK`).
- **Through-cluster orchestration:** the reasoning brain is the cluster's model,
  but the loop *driver* is host-side (pods can't touch the display). A future
  `orchestrator` `assist` endpoint could own stages 2-4 with the host bridging
  only stages 1 and 5; that needs an orchestrator image rebuild + rollout.
- **CV → memory-service DB persistence:** DONE — the screen-context consumer is
  deployed (`memory-service:cv-*` image, Flyway V7 applied, verified: real event
  persisted to `screen_context_observation`, idempotent, raw bytes off). The
  host loop additionally keeps a local JSONL store; wiring the host publisher to
  the in-cluster Kafka (so live loop runs auto-persist to Postgres) is the next
  small step (needs a host-reachable Kafka bootstrap / NodePort).
- **Coding/router channels** (`:18081/:18082`) are not started; only the main
  channel (`:18080`) is up. Start them the same way for full routing.
