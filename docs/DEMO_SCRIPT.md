# Jarvis 2.0 — Demo Script (2026-07-05)

A concrete, runnable demo covering the live cluster plus this wave's "wave-1"
features. Each step lists exactly what to run, what to expect, and whether it
needs real hardware. For the honest status behind every step, see
[`docs/STATUS.md`](STATUS.md). For the older, narrower local-runtime demo, see
[`docs/DEMO.md`](DEMO.md); for the one-page "what's real today" summary, see
[`docs/WHAT_TO_DEMO.md`](WHAT_TO_DEMO.md).

## Setup — once per session

```bash
cd ~/Jarvis/Jarvis2.0

# Never hardcode the cluster IP — it changes with DHCP (see docs/DEPLOYMENT_CANONICAL.md).
NODE_IP="$(sudo k3s kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
GW="https://${NODE_IP}"

TOKEN="$(curl -sk -H 'Host: api.jarvis.local' "${GW}/api/v1/security/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"test1111","password":"test1111"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')

AUTH=(-sk -H 'Host: api.jarvis.local' -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')
```

Hardware: none — this just needs the cluster up (`./jarvis doctor` green).

---

## 1. One-click launch

**Hardware:** none (a GPU speeds up the brain but is not required).

```bash
./scripts/jarvis-oneclick.sh
# or: double-click the "Jarvis" desktop icon (scripts/jarvis.desktop),
# which runs the same script via Exec=
```

**Expected:** a progress splash walks through "checking k3s → brain/voice →
recovering services → waiting for readiness → launching the interface," then
the JavaFX Control Center opens. Verify headlessly instead of watching the GUI:

```bash
./jarvis doctor      # all-green
./jarvis smoke-e2e   # 4/4: login, LLM chat, memory, planner
./jarvis drift-check # 18/18 images match infra/k8s/overlays/prod
```

---

## 2. Voice command

**Hardware:** a microphone + speakers for a live take; `--sample`/`--wav` need neither.

```bash
scripts/jarvis-voice-demo.sh --sample            # bundled clip, no mic needed
scripts/jarvis-voice-demo.sh --lang ru --record 5 # live mic, 5s, Russian
```

**Expected:** printed transcript, then a spoken (Piper) answer from the
Qwen3-14B brain (`host-model-daemon:18080`); the response audio is also saved
to disk so it can be replayed without redoing the take.

---

## 3. Memory recall

**Hardware:** none.

```bash
curl "${AUTH[@]}" -X POST "${GW}/api/v1/memory/search/unified" \
  -d '{"query":"coffee","topK":5}'
```

**Expected:** JSON hits tagged `source=conversation|obsidian|memory` with file
path + score; `noteSearchMode` is `"semantic"` (pgvector) or `"keyword"`
(fallback if the embedding worker is down).

---

## 4. Bank notification draft

**Hardware:** the full on-device path needs a physical Android phone with the
bank app installed and Notification-Listener access granted. The parser
itself is a plain HTTP endpoint and needs no phone.

```bash
curl "${AUTH[@]}" -X POST "${GW}/api/v1/life/finance/parse-notification" \
  -d '{"text":"Purchase 12.50 USD at Starbucks, card *1234","store":false}'
```

**Expected:** a parsed-transaction draft (amount, currency, merchant,
category, confidence). Set `"store":true` and only HIGH-confidence, non-review
drafts get persisted to `life-tracker` — low/medium confidence stays in the
manual inbox by design.

Full hardware path (not exercised by the command above): install the APK,
grant Notification-Listener access, receive a real bank push —
`BankNotificationListenerService` captures it, `OtpGuard` blocks OTP codes
from ever being forwarded, and `NotificationSanitizer` redacts card/PII text
before anything leaves the device.

---

## 5. Agent swarm dry run

**Hardware:** none.

```bash
curl "${AUTH[@]}" -X POST "${GW}/api/v1/agents/swarm" \
  -d '{"goal":"summarize open TODOs in docs/roadmap.md","roles":["RESEARCH","DOCS"],"dryRun":true,"awaitCompletion":true}'
```

**Expected:** a `CombinedReport` with each role's dry-run plan/proposal and no
filesystem writes — the CODER/RESEARCH/DOCS executors only write when
`dryRun=false`, and even then only inside their own git-worktree sandbox,
never the real repo.

---

## 6. Media subtitles

**Hardware:** none for the default (mock) path; real transcription needs
`ffmpeg` plus a Whisper.cpp model file on the host running `media-service`.

```bash
# 1. extract audio (real ffmpeg)
curl "${AUTH[@]}" -X POST "${GW}/api/v1/media/jobs/extract-audio" \
  -d '{"inputFile":"/path/to/clip.mp4"}'
# -> 202 Accepted JobView; poll: GET .../api/v1/media/jobs/{id} until status=DONE,
#    then read the extracted-audio path from outputFiles[]

# 2. transcribe (MockAsrProvider by default)
curl "${AUTH[@]}" -X POST "${GW}/api/v1/media/jobs/transcribe" \
  -d '{"inputFile":"<audio path from step 1>"}'

# 3. generate Russian subtitles (MockTranslationProvider by default)
curl "${AUTH[@]}" -X POST "${GW}/api/v1/media/jobs/russian-subtitles" \
  -d '{"transcriptFile":"<transcript.json path from step 2>"}'
```

**Expected:** the pipeline wiring is real (ffmpeg extraction, Postgres-backed
async jobs, artifact download) and produces a `.srt` file — but the
transcription/translation content is deterministic mock text until the real
`WhisperCppAsrProvider`/`LlmTranslationProvider` are flag-enabled via
`media.*.mode` and the required binaries/models are installed.

---

## 7. Analytics insight

**Hardware:** none.

```bash
curl "${AUTH[@]}" "${GW}/api/v1/analytics/insights"
curl "${AUTH[@]}" "${GW}/api/v1/analytics/insights/forecast"
```

**Expected:** auto-generated insights (budget forecast, correlations,
anomalies) derived from `life-tracker` data — real statistics
(`StatsMath`/`CorrelationService`/`AnomalyDetectionService`), not canned text.

---

## 8. Planner reschedule

**Hardware:** none.

```bash
curl "${AUTH[@]}" -X POST "${GW}/api/v1/planner/energy" -d '{"level":"EXHAUSTED"}'
curl "${AUTH[@]}" -X POST "${GW}/api/v1/planner/reschedule-when-tired?force=true"
```

**Expected:** hard/deep-work tasks get pushed out by a day (urgent/overdue
tasks are never deferred); the response lists which tasks moved and the
reasoning (`EnergyAwareRanker`/`RescheduleService`).

---

## 9. Smart-home scene

**Hardware:** none for the catalog/scene API; real device actuation needs an
MQTT broker / Home Assistant bridge, which is not wired up yet.

```bash
curl "${AUTH[@]}" "${GW}/api/v1/smarthome/catalog"    # pick a real deviceId from here

curl "${AUTH[@]}" -X POST "${GW}/api/v1/smarthome/scenes" \
  -d '{"name":"movie-night","steps":[{"deviceId":"<id from catalog>","action":"turnOff","payload":null}]}'

curl "${AUTH[@]}" -X POST "${GW}/api/v1/smarthome/scenes/movie-night/activate"
curl "${AUTH[@]}" "${GW}/api/v1/smarthome/scenes/history"
```

**Expected:** the scene is created, activation applies every step through the
device-action pipeline, and shows up in scene history — device state changes
are in-memory against the static catalog, not real Zigbee/MQTT hardware.

---

## 10. Panic button

**Hardware:** none.

```bash
curl "${AUTH[@]}" -X POST "${GW}/api/v1/agent/panic" -d '{"actor":"demo","reason":"stop everything"}'
curl "${AUTH[@]}" "${GW}/api/v1/agent/panic"          # {"engaged":true,...}

# any guarded agent action now refuses:
curl "${AUTH[@]}" -X POST "${GW}/api/v1/agents/swarm" -d '{"goal":"x","roles":["RESEARCH"],"dryRun":true}'
# -> HTTP 423 Locked (PanicEngagedException)

curl "${AUTH[@]}" -X POST "${GW}/api/v1/agent/panic/clear" -d '{"actor":"demo"}'
```

**Expected:** engaging panic makes every `AgentActionGuard`-protected action
return `423 Locked` immediately (checked before any role executor runs);
clearing it restores normal operation. This is the same kill-switch the
desktop's Native Desktop Agent contract uses.

---

## Hardware requirements at a glance

| Step | Needs real hardware? |
| --- | --- |
| 1. One-click launch | No |
| 2. Voice command (live take) | Mic + speakers (sample/wav fallback needs none) |
| 3. Memory recall | No |
| 4. Bank notification draft (full path) | Android phone (parser alone needs none) |
| 5. Agent swarm dry run | No |
| 6. Media subtitles (real content) | ffmpeg + Whisper.cpp model (mock path needs none) |
| 7. Analytics insight | No |
| 8. Planner reschedule | No |
| 9. Smart-home scene (real actuation) | MQTT/Home Assistant bridge (catalog demo needs none) |
| 10. Panic button | No |

The JavaFX desktop shell itself needs a graphical `DISPLAY`/`WAYLAND_DISPLAY`
session — it cannot be driven over a headless SSH session. The closest
headless proxy is `scripts/e2e-desktop-dry-run.sh`.
