# Wake Word — openWakeWord Sidecar

A local, **key-free** wake-word sidecar for Jarvis. It replaces the broken
Picovoice/Porcupine wake word — **no Picovoice account or key is involved**.

It captures audio from a real microphone, feeds 80 ms int16 frames to the
pretrained **`hey_jarvis`** openWakeWord model, and streams `WAKE_DETECTED`
events over Server-Sent Events (SSE).

- Server: `apps/wake-word-service/openwakeword_server.py` (FastAPI + uvicorn)
- Default bind: `127.0.0.1:18095` (env `JARVIS_WAKEWORD_PORT`)
- Primary engine: `openwakeword` (ONNX inference framework, CPU)
- Optional engine: `vosk` phrase-spotter (best-effort Russian/English "Jarvis")

---

## Quick start

```bash
# 1. one-time setup (creates .venv-wakeword, installs deps, self-tests)
bash scripts/setup-wakeword-openwakeword.sh

# 2. run the sidecar (foreground; Ctrl+C to stop)
bash scripts/run-wakeword-openwakeword.sh

# 2b. or run in the background
nohup bash scripts/run-wakeword-openwakeword.sh > /tmp/wakeword.log 2>&1 &

# 3. smoke test (health/devices/diagnostics)
bash scripts/test-wakeword-openwakeword.sh

# 3b. live wake test — start capture + stream events for ~20s, say "Hey Jarvis"
bash scripts/test-wakeword-openwakeword.sh --listen

# 4. stop the sidecar (graceful: POST /stop, then SIGTERM/SIGKILL, confirm port freed)
bash scripts/stop-wakeword-openwakeword.sh
```

The setup script is **idempotent**: it reuses an existing `.venv-wakeword`. The desktop app
also **autostarts** the sidecar when Always Listening begins (`JARVIS_WAKEWORD_AUTOSTART`), so
manual `run`/`stop` is only needed for standalone testing.

> **Security:** the sidecar binds **loopback (`127.0.0.1`) only** by default — it is not exposed
> on the network. Only set `JARVIS_WAKEWORD_HOST` to a non-loopback address if you understand the
> exposure. No raw audio or transcripts are ever persisted or logged.

---

## Endpoint contract

Base URL: `http://127.0.0.1:18095`

| Method | Path           | Description |
|--------|----------------|-------------|
| GET    | `/health`      | Liveness, loaded models, selected device |
| GET    | `/devices`     | Enumerated input devices (preferred first, deduped) |
| POST   | `/start`       | Open device, start capture + detection thread |
| POST   | `/stop`        | Stop capture thread + close stream |
| GET    | `/diagnostics` | Full engine/device/error state + rejected devices |
| GET    | `/events`      | SSE stream of `WAKE_DETECTED` events + keepalives |

### `GET /health`

```json
{
  "status": "UP",
  "provider": "openWakeWord",
  "modelsLoaded": ["hey_jarvis_v0.1"],
  "device": "Creative Live! Cam Sync 4K: USB Audio (hw:1,0)",
  "listening": true
}
```

### `GET /devices`

```json
{
  "devices": [
    {"id": 2, "name": "Creative Live! Cam Sync 4K: USB Audio (hw:1,0)",
     "sampleRate": 16000, "channels": 1, "isInput": true, "preferred": true},
    {"id": 7, "name": "T1: USB Audio (hw:3,0)",
     "sampleRate": 16000, "channels": 1, "isInput": true, "preferred": true}
  ]
}
```

### `POST /start`

Request body (all fields optional; defaults shown):

```json
{"device": "auto", "model": "hey_jarvis", "threshold": 0.5, "engine": "openwakeword"}
```

- `device`: `"auto"` | `"<id>"` | `"<name substring>"`.
  `"auto"` picks the first **preferred** input device (C4K / T1 / USB / mic /
  plughw) and **never** a playback/monitor device.
- `engine`: `"openwakeword"` (default) or `"vosk"`.

Responses:
- `200` `{"status":"STARTED", ...}`
- `404` `{"error":"no_input_device","requested":"..."}` — device not found
- `503` `{"error":"vosk_not_installed"}` — `engine=vosk` but vosk missing
- `503` `{"error":"vosk_model_not_found"}` — vosk installed, no model dir
- `503` `{"error":"openwakeword_not_installed"}` / `{"error":"sounddevice_not_installed"}`

### `POST /stop`

```json
{"status": "STOPPED"}
```

### `POST /pause` / `POST /resume`

Suspend / resume wake detection **without losing sidecar health** — the mic stream stays
owned so resume is instant. Used by the desktop while a command is recording or TTS is playing
so a wake word can't self-trigger mid-command. Idempotent.

```json
{"status": "PAUSED", "paused": true,  "listening": false}
{"status": "RESUMED","paused": false, "listening": true}
```

While paused, `/health` stays `UP`, `listening` is `false`, and `/events` emits only keepalives.

### `GET /diagnostics`

```json
{
  "provider": "openWakeWord",
  "installed": true,
  "engine": "openwakeword",
  "inferenceFramework": "onnx",
  "voskInstalled": true,
  "soundDeviceInstalled": true,
  "models": ["hey_jarvis_v0.1"],
  "selectedDevice": {"id": 2, "name": "...", "sampleRate": 16000, "channels": 1, "isInput": true, "preferred": true},
  "listening": true,
  "paused": false,
  "lastWakeDetectedAt": "2026-07-15T18:41:18.209565+00:00",
  "lastWakeScore": 0.83,
  "lastError": null,
  "rejectedDevices": [{"name": "...monitor...", "reason": "playback/output device"}]
}
```

### `GET /events` (SSE)

`Content-Type: text/event-stream`. On each wake detection:

```
data: {"type":"WAKE_DETECTED","provider":"openWakeWord","model":"hey_jarvis","score":0.83,"device":"C4K","timestamp":"2026-07-15T18:41:18.209565+00:00"}

```

Periodic `: keepalive` comment lines (every 15 s) let clients detect liveness.
An initial `: connected` comment is sent immediately on connect.

Consume with:

```bash
curl -sN http://127.0.0.1:18095/events
```

---

## Detection details

- Audio is captured at 16 kHz mono int16 in 80 ms / 1280-sample frames.
- Each frame is passed to `openwakeword.Model.predict()`; when the `hey_jarvis`
  score `>=` threshold a `WAKE_DETECTED` event is emitted.
- **Debounce:** repeat detections within a 2 s cooldown are ignored.
- The detection thread hands events to the SSE layer through an
  `asyncio.Queue` via `loop.call_soon_threadsafe` (thread-safe).

### Software resampling (important for USB mics)

Raw ALSA `hw:*` devices do **not** resample and frequently refuse 16 kHz mono
(`PaErrorCode -9997 Invalid sample rate`). The sidecar negotiates a working
`(samplerate, channels)` combo, opening at the device's native rate (e.g.
44100 Hz on the C4K) and **downmixing to mono + resampling to 16 kHz in
software** (`scipy.signal.resample_poly`) before inference. This lets `"auto"`
keep selecting the real C4K/T1 microphone while still feeding the model clean
16 kHz mono audio.

---

## Environment variables

| Variable | Default | Meaning |
|----------|---------|---------|
| `JARVIS_WAKEWORD_PORT`      | `18095`       | Listen port |
| `JARVIS_WAKEWORD_HOST`      | `127.0.0.1`   | Bind host |
| `JARVIS_WAKEWORD_DEVICE`    | `auto`        | Default device (`auto`\|`<id>`\|`<name>`) |
| `JARVIS_WAKEWORD_MODEL`     | `hey_jarvis`  | Wake model name (or path to `.onnx`/`.tflite`) |
| `JARVIS_WAKEWORD_THRESHOLD` | `0.5`         | Detection threshold (0.0–1.0) |
| `JARVIS_WAKEWORD_COOLDOWN_MS`| `2000`       | Min gap between accepted wakes (duplicate suppression) |
| `JARVIS_WAKEWORD_ENGINE`    | `openwakeword`| `openwakeword` \| `vosk` |
| `JARVIS_WAKEWORD_FRAMEWORK` | `onnx`        | `onnx` \| `tflite` (extension preference) |
| `JARVIS_WAKEWORD_AUTOSTART` | `0`           | `1` to begin capture on server boot |
| `JARVIS_VOSK_MODEL`         | (auto)        | Path to a vosk model dir |

### Desktop-side (JavaFX) variables

The desktop app reads these to choose and reach a provider (it does not run the sidecar's env):

| Variable | Default | Meaning |
|----------|---------|---------|
| `JARVIS_WAKE_PROVIDER`      | `auto` | `auto`\|`openwakeword`\|`vosk`\|`porcupine`\|`manual` |
| `JARVIS_WAKEWORD_URL`       | `http://127.0.0.1:18095` | Sidecar base URL |
| `JARVIS_WAKEWORD_MODEL`     | `hey_jarvis` | Wake phrase/model |
| `JARVIS_WAKEWORD_THRESHOLD` | `0.5` | Detection threshold |
| `JARVIS_WAKEWORD_DEVICE`    | `auto` | Preferred capture device |
| `JARVIS_WAKEWORD_AUTOSTART` | `true` | Autostart the sidecar when Always Listening begins |

In `AUTO` the desktop tries **openWakeWord → Vosk phrase spotter → Porcupine (only if a valid key
exists) → Manual only**. A missing/invalid `PORCUPINE_ACCESS_KEY` never blocks openWakeWord/Vosk.

---

## Uninstall / cleanup

```bash
bash scripts/stop-wakeword-openwakeword.sh   # stop the sidecar, free the port
rm -rf .venv-wakeword                          # remove the venv (gitignored)
# openWakeWord/vosk pretrained models live inside the venv / ~/.cache and go with it.
```

Removing the venv fully uninstalls the wake-word sidecar; the desktop then falls back to
Porcupine (if a valid key exists) or Manual Talk, and Always Listening reports the fallback.

---

## Device selection on this machine

Real microphones (avoid the PulseAudio/PipeWire playback/monitor/loopback
devices):

- **C4K** — Creative Live! Cam Sync 4K USB cam mic (`hw:1,0`)
- **T1** — USB microphone (`hw:3,0`)

`/devices` marks these `preferred: true` and returns them first. `"auto"`
selects the first preferred one (C4K).

Filtering rule used by `/devices` and `"auto"`:

- **Include** only input-capable devices (`max_input_channels > 0`).
- **Exclude** (case-insensitive) any name containing `playback`, `output`,
  `monitor`, `sink`, or `speaker` — these land in `diagnostics.rejectedDevices`
  with reason `"playback/output device"`.
- **Prefer** (case-insensitive) names containing `c4k`, `t1`, `usb`, `mic`,
  `microphone`, or `plughw`; preferred devices are returned first.
- **Dedupe** by device name.

Pick a specific mic:

```bash
# by name substring
curl -s -X POST http://127.0.0.1:18095/start \
  -H 'Content-Type: application/json' \
  -d '{"device":"T1","model":"hey_jarvis","threshold":0.5}'

# by numeric id (from /devices)
curl -s -X POST http://127.0.0.1:18095/start \
  -H 'Content-Type: application/json' \
  -d '{"device":"7"}'
```

---

## Troubleshooting

### No sound device / capture won't start

- Run `bash scripts/test-wakeword-openwakeword.sh` — `/devices` and
  `/diagnostics` show what was detected and what was rejected.
- `PaErrorCode -9997 Invalid sample rate` on a raw `hw:*` device is handled
  automatically (native-rate capture + software resample). If it still fails,
  point `device` at the PipeWire/`default` device, which resamples natively:
  `-d '{"device":"pipewire"}'` or `-d '{"device":"default"}'`.
- Headless sessions with no audio server: `/health` and `/devices` still work;
  `/start` reports the real error in `diagnostics.lastError`.
- Confirm the OS sees the mics: `arecord -l`.

### Model download / offline

openWakeWord 0.4.x **ships the pretrained models bundled** inside the wheel
(`hey_jarvis_v0.1.onnx` plus the `melspectrogram.onnx` / `embedding_model.onnx`
feature models), so **no network download is required** after `pip install`.
The setup script only *verifies* they are present. If you use a custom model,
drop it in `models/wakeword/` (see below) — no network needed.

### tflite vs onnx

The sidecar runs the **ONNX** inference framework on CPU by default
(`onnxruntime`), which installs cleanly on Python 3.12 without `tflite-runtime`.
The framework is chosen by the model file extension. Set
`JARVIS_WAKEWORD_FRAMEWORK=tflite` only if you provide `.tflite` models and have
`tflite-runtime` installed. The `CUDAExecutionProvider ... not available`
warning from onnxruntime is benign — it falls back to CPU.

### Choosing C4K vs T1

Both are `preferred`. `"auto"` picks C4K (first preferred). Force T1 with
`{"device":"T1"}`. If one mic is unplugged or muted at the hardware level, use
the other by name/id.

### vosk engine returns 503

`engine=vosk` requires the `vosk` package **and** a model directory. The setup
script installs vosk best-effort. Models are auto-discovered from
`~/.jarvis/models/vosk/vosk-model-small-ru-0.22` (Russian) or
`vosk-model-small-en-us-0.15` (English); override with `JARVIS_VOSK_MODEL`.
`{"error":"vosk_not_installed"}` or `{"error":"vosk_model_not_found"}` means one
of those is missing — the openwakeword engine is unaffected.

---

## Adding a custom Russian wake model later

The bundled `hey_jarvis` model is English-trained. To add a native Russian wake
word (e.g. "Джарвис") without any cloud service:

1. **Train a model.** Two local options:
   - **openWakeWord training** — follow the upstream
     [training notebook](https://github.com/dscripka/openWakeWord). Generate
     synthetic "Джарвис" clips (e.g. with **Piper** TTS using a Russian voice)
     plus negative/background audio, train, and export a `.onnx` (or `.tflite`)
     model.
   - **Piper-based / phonetic spotter** — for a quick path, use the optional
     `vosk` engine (`engine=vosk`), which already phrase-spots
     `джарвис / эй джарвис / жарвис / jarvis` against the small Russian model.

2. **Drop the model file** into `models/wakeword/` at the repo root, e.g.
   `models/wakeword/dzharvis_ru.onnx`. The server searches this directory before
   the bundled pretrained models.

3. **Point the sidecar at it** via env or the `/start` body:

   ```bash
   JARVIS_WAKEWORD_MODEL=dzharvis_ru bash scripts/run-wakeword-openwakeword.sh
   # or
   curl -s -X POST http://127.0.0.1:18095/start \
     -H 'Content-Type: application/json' \
     -d '{"device":"auto","model":"dzharvis_ru","threshold":0.5}'
   ```

   `JARVIS_WAKEWORD_MODEL` also accepts an absolute path to a `.onnx`/`.tflite`
   file. Use `.onnx` to stay on the default CPU onnxruntime backend; use
   `.tflite` only if you have `tflite-runtime` installed (set
   `JARVIS_WAKEWORD_FRAMEWORK=tflite`).

4. **Tune the threshold** with `bash scripts/test-wakeword-openwakeword.sh
   --listen`, watching `score` in the emitted events, until real utterances
   fire and background noise does not.
