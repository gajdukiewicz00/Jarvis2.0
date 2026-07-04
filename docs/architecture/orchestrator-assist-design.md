# Design: orchestrator `/assist` — cluster brain + host bridge

- Status: **IMPLEMENTED (code + unit tests); deploy = next step.**
  `POST /api/v1/orchestrator/assist` exists (`AssistController` → `AssistService`)
  with reasoning (`LlmReasonerImpl` → llm-service → host Qwen), graceful memory
  read/write (`AssistMemoryImpl` → memory-service), safety (`ActionSafetyPolicy`),
  secret redaction (`SecretRedactor`), and an honest host-delegating executor
  (`DelegatedHostActionExecutor` — never fakes in-pod desktop control). Covered
  by `AssistServiceTest` (9 tests: dry-run / confirm / execute-safe /
  dangerous-refused / unknown-refused / guarded-needs-token / llm-unavailable /
  secret-redacted / blank-command). Host-bound capture/execute is performed by
  `scripts/jarvis-host-bridge.sh`. Building/rolling the orchestrator image and
  end-to-end-through-gateway (needs the service JWT) are the remaining steps.
- Hard rule: **desktop control is NEVER performed in-cluster.** A k3s pod has no
  access to the workstation X display; faking it is forbidden. The orchestrator
  only *decides*; the host *acts*.

## Why a split

Screen capture and keyboard/mouse/app-launch are physically bound to the
workstation's display. The orchestrator pod cannot reach `DISPLAY`. So the loop
divides into:

- **Cluster (orchestrator):** understand the request, reason over the supplied
  screen context with `llm-service` (Qwen via host-model-daemon), persist memory
  via `memory-service`, and return a decided next action. No side effects on the
  host.
- **Host bridge (`jarvis-loop.sh` / a small host agent):** capture the screen,
  call `/assist`, then execute the returned action (allow-listed, with
  confirmation) and speak the answer.

## Endpoint

```
POST /api/v1/orchestrator/assist
Authorization: Bearer <service or user JWT>     # existing gateway auth
Content-Type: application/json
```

### Request (host bridge → orchestrator)

```json
{
  "userId": "owner",
  "question": "What am I working on? Open the next useful tool.",
  "screenContext": {
    "activeWindowTitle": "…",
    "activeProcessName": "…",
    "semanticTags": ["DEVELOPMENT"],
    "ocrText": "…(already-OCR'd text; orchestrator does NOT capture)…",
    "displayServer": "x11",
    "screenshotPath": "/tmp/jarvis-cv/<run>.screen.png"
  },
  "allowedActions": ["open_app", "open_url"]
}
```

The host captures and OCRs (via `vision-security` CLI) and sends **text +
metadata only**, consistent with ADR-0011 (raw frames stay host-local).

### Response (orchestrator → host bridge)

```json
{
  "answer": "You are working on … (1-2 sentences).",
  "next_action": { "type": "open_app", "target": "terminal", "reason": "…", "dangerous": false },
  "memory": { "persisted": true, "store": "screen_context_observation", "id": "uuid" },
  "llm": { "provider": "host-model-daemon", "model": "qwen2.5-3b", "availability": "READY|UNAVAILABLE" }
}
```

If the LLM is unavailable, `llm.availability=UNAVAILABLE`, `answer=null`,
`next_action.type=none` — **never a fabricated answer.**

## Orchestrator internals (to implement)

1. `AssistController` → `POST /api/v1/orchestrator/assist`.
2. `AssistService`:
   - Calls existing `LlmServiceClient` with a strict-JSON system prompt
     (same contract as `jarvis-loop.sh` stage 2) to get `{summary, next_action}`.
   - Calls `memory-service` (`POST /memory/ingest` or the screen-context path)
     to persist; or publishes `jarvis.cv.screen_context.created` so the existing
     consumer persists it (preferred — reuses the verified pipeline).
   - Validates `next_action.type ∈ allowedActions`; clears it otherwise.
3. Returns the response above. **No `pc-control` desktop call** for display
   actions (pod cannot reach the host display).

## Host bridge contract (what the host MUST provide)

The host side (today: `scripts/jarvis-loop.sh`) is responsible for, and is the
ONLY place that may:

| Capability | Host requirement |
| --- | --- |
| Capture screen + OCR | `vision-security` CLI (`--cv.screen-context`), tesseract, gnome-screenshot |
| Execute action | allow-list mapping (terminal/editor/files/browser/URL) via `xdg-open`/`gtk-launch`; `xdotool` for input |
| Confirmation | dangerous / non-allow-listed actions require explicit `--yes` or `[y/N]` |
| Speak | local TTS (`spd-say`) |

A future dedicated host agent could expose this as a localhost daemon
(`POST /host/execute {action}`) that the bridge calls, but it must remain
host-local and confirmation-gated.

## Implementation + deploy steps (when greenlit)

```bash
# 1. add AssistController + AssistService to apps/orchestrator
# 2. unit test the JSON contract + allowedActions filtering
mvn -pl apps/orchestrator -am test
# 3. build + push image
mvn -pl apps/orchestrator -am -DskipTests -Djib.image.tag=assist-1 jib:build
# 4. deploy
kubectl -n jarvis-prod set image deploy/orchestrator orchestrator=localhost:5000/jarvis/orchestrator:assist-1
kubectl -n jarvis-prod rollout status deploy/orchestrator
# 5. point jarvis-loop.sh stage 2/3 at the gateway /assist (needs SERVICE_JWT_SECRET)
```

## Acceptance (for the future implementation)

- `/assist` returns a real `answer` + `next_action` when the LLM is READY.
- Honest `UNAVAILABLE` when the daemon is down (no fake answer).
- Memory row persisted (reuses the verified `screen_context_observation` path).
- Response contains **no executed desktop action** — only a decision; the host
  bridge executes and the action is confirmation-gated.
