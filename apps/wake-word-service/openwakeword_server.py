#!/usr/bin/env python3
"""
Jarvis wake-word sidecar (openWakeWord).

A local, key-free replacement for the broken Picovoice/Porcupine wake word.
Runs a FastAPI/uvicorn server that captures audio from a real microphone,
feeds 80ms int16 frames to the pretrained "hey_jarvis" openWakeWord model,
and streams WAKE_DETECTED events over Server-Sent Events (SSE).

Primary engine: openwakeword (pretrained "hey_jarvis").
Optional engine: vosk phrase-spotter (best-effort; Russian/English "джарвис").

No API key is ever required.

Endpoints:
  GET  /health        liveness + loaded models + selected device
  GET  /devices       enumerated input devices (preferred first, deduped)
  POST /start         open device, start capture+detection thread
  POST /stop          stop capture thread + close stream
  GET  /diagnostics   full engine/device/error state incl. rejected devices
  GET  /events        SSE stream of WAKE_DETECTED events + keepalives

Env:
  JARVIS_WAKEWORD_PORT       listen port                 (default 18095)
  JARVIS_WAKEWORD_HOST       bind host                   (default 127.0.0.1)
  JARVIS_WAKEWORD_DEVICE     default device for autostart (id|name|auto)
  JARVIS_WAKEWORD_MODEL      wake model name             (default hey_jarvis)
  JARVIS_WAKEWORD_THRESHOLD  detection threshold         (default 0.5)
  JARVIS_WAKEWORD_ENGINE     openwakeword|vosk           (default openwakeword)
  JARVIS_WAKEWORD_FRAMEWORK  onnx|tflite                 (default onnx)
  JARVIS_WAKEWORD_AUTOSTART  1 to auto-start capture on boot (default 0)
  JARVIS_VOSK_MODEL          path to a vosk model dir (optional)
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Configuration & constants
# ---------------------------------------------------------------------------

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

SAMPLE_RATE = 16000
CHANNELS = 1
FRAME_SAMPLES = 1280          # 80 ms @ 16 kHz — openWakeWord's native frame
WAKE_COOLDOWN_SEC = 2.0       # debounce window to ignore repeat triggers
KEEPALIVE_SEC = 15.0          # SSE keepalive comment interval
SUBSCRIBER_QUEUE_MAX = 64

# Case-insensitive tokens that mark a device as an output/loopback (rejected).
EXCLUDE_TOKENS = ("playback", "output", "monitor", "sink", "speaker")
# Case-insensitive tokens that mark a device as a *preferred* real microphone.
PREFERRED_TOKENS = ("c4k", "t1", "usb", "mic", "microphone", "plughw")

DEFAULT_PORT = int(os.getenv("JARVIS_WAKEWORD_PORT", "18095"))
DEFAULT_HOST = os.getenv("JARVIS_WAKEWORD_HOST", "127.0.0.1")
DEFAULT_MODEL = os.getenv("JARVIS_WAKEWORD_MODEL", "hey_jarvis")
DEFAULT_THRESHOLD = float(os.getenv("JARVIS_WAKEWORD_THRESHOLD", "0.5"))
DEFAULT_ENGINE = os.getenv("JARVIS_WAKEWORD_ENGINE", "openwakeword").lower()
DEFAULT_FRAMEWORK = os.getenv("JARVIS_WAKEWORD_FRAMEWORK", "onnx").lower()
DEFAULT_DEVICE = os.getenv("JARVIS_WAKEWORD_DEVICE", "auto")

logging.basicConfig(
    level=getattr(logging, os.getenv("JARVIS_WAKEWORD_LOG", "INFO").upper(), logging.INFO),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("wakeword")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Optional dependency probes (never crash on import failure)
# ---------------------------------------------------------------------------

def _openwakeword_available() -> bool:
    try:
        import openwakeword  # noqa: F401
        return True
    except Exception:
        return False


def _sounddevice_available() -> bool:
    try:
        import sounddevice  # noqa: F401
        return True
    except Exception:
        return False


def _vosk_available() -> bool:
    try:
        import vosk  # noqa: F401
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Device enumeration & filtering
# ---------------------------------------------------------------------------

def enumerate_input_devices() -> Dict[str, List[Dict[str, Any]]]:
    """Return {"devices": [...accepted...], "rejected": [...]} .

    Accepted devices are input-capable (max_input_channels > 0), not an
    output/loopback (name has no EXCLUDE_TOKENS), deduped by name, and
    sorted preferred-first. Rejected devices carry a human reason.
    """
    if not _sounddevice_available():
        return {"devices": [], "rejected": []}

    import sounddevice as sd

    accepted: List[Dict[str, Any]] = []
    rejected: List[Dict[str, Any]] = []
    seen_names: set[str] = set()

    try:
        raw = sd.query_devices()
    except Exception as exc:  # pragma: no cover - host audio failure
        logger.warning("query_devices failed: %s", exc)
        return {"devices": [], "rejected": []}

    for idx, dev in enumerate(raw):
        try:
            max_in = int(dev.get("max_input_channels", 0))
        except Exception:
            max_in = 0
        if max_in <= 0:
            continue

        name = str(dev.get("name", f"device-{idx}"))
        low = name.lower()

        if any(tok in low for tok in EXCLUDE_TOKENS):
            rejected.append({"name": name, "reason": "playback/output device"})
            continue

        if name in seen_names:
            continue
        seen_names.add(name)

        accepted.append({
            "id": idx,
            "name": name,
            "sampleRate": SAMPLE_RATE,
            "channels": CHANNELS,
            "isInput": True,
            "preferred": any(tok in low for tok in PREFERRED_TOKENS),
        })

    # Stable sort: preferred devices first, original discovery order otherwise.
    accepted.sort(key=lambda d: not d["preferred"])
    return {"devices": accepted, "rejected": rejected}


def resolve_device(selector: str) -> Optional[Dict[str, Any]]:
    """Resolve an 'auto'|<id>|<name> selector to an accepted input device.

    "auto" picks the first PREFERRED device and never an output/monitor one.
    """
    listing = enumerate_input_devices()
    devices = listing["devices"]
    if not devices:
        return None

    sel = (selector or "auto").strip()

    if sel.lower() == "auto":
        for dev in devices:
            if dev["preferred"]:
                return dev
        # No preferred device found; fall back to the first accepted input.
        return devices[0]

    # Numeric id match.
    if sel.isdigit():
        wanted = int(sel)
        for dev in devices:
            if dev["id"] == wanted:
                return dev
        return None

    # Name substring match (case-insensitive).
    low = sel.lower()
    for dev in devices:
        if low in dev["name"].lower():
            return dev
    return None


# ---------------------------------------------------------------------------
# Detection engines
# ---------------------------------------------------------------------------

def resolve_model_path(model_name: str, framework: str) -> str:
    """Resolve a wake-model name to a concrete .onnx/.tflite file path.

    openWakeWord 0.4.x auto-detects the inference framework from the file
    extension, so this picker controls which framework runs by choosing the
    extension. Resolution order:
      1. model_name is an existing file path -> use as-is
      2. a matching file in the repo models/ dir (custom models)
      3. a bundled pretrained model matching the name
    """
    import openwakeword

    # 1. explicit path
    if os.path.isfile(model_name):
        return model_name

    ext_order = [".onnx", ".tflite"] if framework != "tflite" else [".tflite", ".onnx"]

    # 2. repo-local custom models directory
    repo_models = os.path.join(REPO_ROOT, "models", "wakeword")
    search_dirs = [repo_models]
    for d in search_dirs:
        if not os.path.isdir(d):
            continue
        for ext in ext_order:
            for fname in sorted(os.listdir(d)):
                if fname.lower().endswith(ext) and model_name.lower() in fname.lower():
                    return os.path.join(d, fname)

    # 3. bundled pretrained models (returns .onnx paths)
    try:
        for path in openwakeword.get_pretrained_model_paths():
            base = os.path.basename(path).lower()
            if model_name.lower() in base:
                # honor a tflite preference if that sibling exists
                if framework == "tflite":
                    tflite = os.path.splitext(path)[0] + ".tflite"
                    if os.path.isfile(tflite):
                        return tflite
                return path
    except Exception as exc:
        logger.warning("pretrained path lookup failed: %s", exc)

    raise FileNotFoundError(
        f"wake model '{model_name}' not found (framework={framework})"
    )


class OpenWakeWordEngine:
    """Wraps openwakeword.Model for the pretrained hey_jarvis wake word."""

    provider = "openWakeWord"

    def __init__(self, model_name: str, framework: str):
        from openwakeword.model import Model

        model_path = resolve_model_path(model_name, framework)
        self.framework = "tflite" if model_path.endswith(".tflite") else "onnx"

        # openWakeWord 0.4.x infers the framework from the file extension;
        # do NOT pass inference_framework (removed in this API version).
        self.model = Model(wakeword_model_paths=[model_path])
        self.requested = model_name
        self.model_key = self._match_key(model_name)
        logger.info(
            "openWakeWord ready (framework=%s, path=%s, models=%s, key=%s)",
            self.framework, model_path, list(self.model.models.keys()), self.model_key,
        )

    def _match_key(self, model_name: str) -> str:
        keys = list(self.model.models.keys())
        for key in keys:
            if key == model_name or model_name.lower() in key.lower():
                return key
        return keys[0] if keys else model_name

    def loaded_models(self) -> List[str]:
        return list(self.model.models.keys())

    def process(self, frame_int16) -> float:
        preds = self.model.predict(frame_int16)
        return float(preds.get(self.model_key, 0.0))


class VoskPhraseSpotter:
    """Best-effort Russian/English 'Jarvis' phrase spotter over vosk."""

    provider = "voskPhraseSpotter"
    PHRASES = ("джарвис", "эй джарвис", "жарвис", "jarvis", "hey jarvis")

    def __init__(self, model_path: str):
        from vosk import Model, KaldiRecognizer

        self.model = Model(model_path)
        self.rec = KaldiRecognizer(self.model, SAMPLE_RATE)
        self.rec.SetWords(False)
        self.model_path = model_path
        logger.info("vosk phrase spotter ready (model=%s)", model_path)

    def loaded_models(self) -> List[str]:
        return [os.path.basename(self.model_path.rstrip("/"))]

    def _hit(self, text: str) -> bool:
        text = (text or "").lower()
        return any(phrase in text for phrase in self.PHRASES)

    def process(self, frame_int16) -> float:
        data = frame_int16.tobytes()
        if self.rec.AcceptWaveform(data):
            text = json.loads(self.rec.Result()).get("text", "")
            if self._hit(text):
                self.rec.Reset()
                return 1.0
            return 0.0
        partial = json.loads(self.rec.PartialResult()).get("partial", "")
        if self._hit(partial):
            self.rec.Reset()
            return 1.0
        return 0.0


def _default_vosk_model_path() -> Optional[str]:
    explicit = os.getenv("JARVIS_VOSK_MODEL")
    candidates = [explicit] if explicit else []
    candidates += [
        os.path.expanduser("~/.jarvis/models/vosk/vosk-model-small-ru-0.22"),
        os.path.expanduser("~/.jarvis/models/vosk/vosk-model-small-en-us-0.15"),
    ]
    for path in candidates:
        if path and os.path.isdir(path):
            return path
    return None


# ---------------------------------------------------------------------------
# Server state & capture loop
# ---------------------------------------------------------------------------

class WakeWordState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.engine_name: str = DEFAULT_ENGINE
        self.model_name: str = DEFAULT_MODEL
        self.threshold: float = DEFAULT_THRESHOLD
        self.selected_device: Optional[Dict[str, Any]] = None
        self.listening: bool = False
        self.loaded_models: List[str] = []
        self.provider: str = "openWakeWord"
        self.inference_framework: str = DEFAULT_FRAMEWORK

        self.last_wake_detected_at: Optional[str] = None
        self.last_wake_score: Optional[float] = None
        self.last_error: Optional[str] = None

        self._engine: Any = None
        self._stream: Any = None
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._last_fire = 0.0

        # SSE plumbing — loop captured at app startup.
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.subscribers: "set[asyncio.Queue]" = set()

    # -- SSE dispatch (called FROM the capture thread) ----------------------
    def dispatch_event(self, event: Dict[str, Any]) -> None:
        loop = self.loop
        if loop is None:
            return
        loop.call_soon_threadsafe(self._fanout, event)

    def _fanout(self, event: Dict[str, Any]) -> None:
        # Runs on the event loop thread.
        for queue in list(self.subscribers):
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                logger.warning("SSE subscriber queue full; dropping event")

    # -- capture control ----------------------------------------------------
    def start(self, device_sel: str, model: str, threshold: float, engine: str) -> Dict[str, Any]:
        engine = (engine or DEFAULT_ENGINE).lower()

        if engine == "vosk" and not _vosk_available():
            raise HTTPException(status_code=503, detail={"error": "vosk_not_installed"})
        if engine == "openwakeword" and not _openwakeword_available():
            raise HTTPException(status_code=503, detail={"error": "openwakeword_not_installed"})
        if not _sounddevice_available():
            raise HTTPException(status_code=503, detail={"error": "sounddevice_not_installed"})

        device = resolve_device(device_sel)
        if device is None:
            raise HTTPException(
                status_code=404,
                detail={"error": "no_input_device", "requested": device_sel},
            )

        with self.lock:
            # Idempotent restart: stop any existing capture first.
            self._stop_locked()

            try:
                built = self._build_engine(engine, model)
            except HTTPException:
                raise
            except Exception as exc:
                self.last_error = f"engine_init_failed: {exc}"
                logger.exception("engine init failed")
                raise HTTPException(status_code=500, detail={"error": str(exc)})

            self._engine = built
            self.engine_name = engine
            self.model_name = model
            self.threshold = threshold
            self.selected_device = device
            self.provider = built.provider
            self.loaded_models = built.loaded_models()
            self.inference_framework = getattr(built, "framework", DEFAULT_FRAMEWORK)
            self.last_error = None

            self._stop.clear()
            self._thread = threading.Thread(
                target=self._capture_loop, name="wakeword-capture", daemon=True,
            )
            self._thread.start()
            self.listening = True

        logger.info(
            "capture started (engine=%s, model=%s, device=%s[%d], threshold=%.2f)",
            engine, model, device["name"], device["id"], threshold,
        )
        return {
            "status": "STARTED",
            "engine": engine,
            "provider": self.provider,
            "device": device,
            "model": model,
            "threshold": threshold,
            "modelsLoaded": self.loaded_models,
        }

    def _build_engine(self, engine: str, model: str) -> Any:
        if engine == "vosk":
            model_path = _default_vosk_model_path()
            if not model_path:
                raise HTTPException(
                    status_code=503,
                    detail={"error": "vosk_model_not_found"},
                )
            return VoskPhraseSpotter(model_path)
        framework = DEFAULT_FRAMEWORK
        return OpenWakeWordEngine(model, framework)

    def stop(self) -> Dict[str, Any]:
        with self.lock:
            self._stop_locked()
        logger.info("capture stopped")
        return {"status": "STOPPED"}

    def _stop_locked(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=3.0)
        self._thread = None
        stream = self._stream
        self._stream = None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception as exc:
                logger.warning("stream close error: %s", exc)
        self._engine = None
        self.listening = False

    def _open_input_stream(self, device_id: int):
        """Negotiate a working (samplerate, channels) combo for a raw device.

        Raw ALSA hw:* devices do not resample and often refuse 16 kHz mono, so
        we fall back to the device's native rate / channel count and convert to
        16 kHz mono in software. Returns (stream, native_rate, open_channels).
        """
        import sounddevice as sd

        try:
            info = sd.query_devices(device_id)
            native_rate = int(round(float(info.get("default_samplerate", SAMPLE_RATE))))
            max_in = max(1, int(info.get("max_input_channels", 1)))
        except Exception:
            native_rate, max_in = SAMPLE_RATE, 1

        # Preference order: exact 16k mono first (cheapest), then native rate,
        # then native channel count.
        combos = [
            (SAMPLE_RATE, 1),
            (SAMPLE_RATE, min(2, max_in)),
            (native_rate, 1),
            (native_rate, min(2, max_in)),
        ]
        last_exc: Optional[Exception] = None
        for rate, chans in combos:
            frame = int(round(rate * FRAME_SAMPLES / SAMPLE_RATE))
            try:
                stream = sd.InputStream(
                    samplerate=rate,
                    channels=chans,
                    dtype="int16",
                    blocksize=frame,
                    device=device_id,
                )
                stream.start()
                logger.info(
                    "opened device %d at %d Hz, %d ch (resample->16k mono: %s)",
                    device_id, rate, chans, rate != SAMPLE_RATE or chans != 1,
                )
                return stream, rate, chans
            except Exception as exc:  # PortAudioError: unsupported combo
                last_exc = exc
                continue
        raise last_exc or RuntimeError("no supported input format")

    def _capture_loop(self) -> None:
        import numpy as np

        device = self.selected_device
        engine = self._engine
        device_id = device["id"] if device else None
        try:
            stream, native_rate, open_channels = self._open_input_stream(device_id)
            self._stream = stream
        except Exception as exc:
            self.last_error = f"device_open_failed: {exc}"
            self.listening = False
            logger.error("failed to open input stream: %s", exc)
            return

        resample = None
        if native_rate != SAMPLE_RATE:
            from scipy.signal import resample_poly
            resample = resample_poly

        native_frame = int(round(native_rate * FRAME_SAMPLES / SAMPLE_RATE))
        pending = np.zeros(0, dtype=np.float32)

        logger.info("capture loop running on device %s (native=%dHz)", device, native_rate)
        while not self._stop.is_set():
            try:
                data, overflowed = stream.read(native_frame)
                if overflowed:
                    logger.debug("input overflow")
                block = np.frombuffer(bytes(data), dtype=np.int16)
                if open_channels > 1:
                    block = block.reshape(-1, open_channels).mean(axis=1)
                pending = np.concatenate([pending, block.astype(np.float32)])

                while pending.size >= native_frame:
                    chunk = pending[:native_frame]
                    pending = pending[native_frame:]
                    if resample is not None:
                        chunk = resample(chunk, SAMPLE_RATE, native_rate)
                    frame16 = np.clip(chunk, -32768, 32767).astype(np.int16)
                    if frame16.size >= FRAME_SAMPLES:
                        frame16 = frame16[:FRAME_SAMPLES]
                    else:
                        frame16 = np.pad(frame16, (0, FRAME_SAMPLES - frame16.size))
                    score = engine.process(frame16)
                    if score >= self.threshold:
                        self._on_wake(score)
            except Exception as exc:
                self.last_error = f"capture_error: {exc}"
                logger.exception("capture loop error")
                break

        logger.info("capture loop exited")

    def _on_wake(self, score: float) -> None:
        now = time.monotonic()
        if now - self._last_fire < WAKE_COOLDOWN_SEC:
            return
        self._last_fire = now

        ts = _now_iso()
        self.last_wake_detected_at = ts
        self.last_wake_score = round(float(score), 4)
        device_name = self.selected_device["name"] if self.selected_device else None

        event = {
            "type": "WAKE_DETECTED",
            "provider": self.provider,
            "model": self.model_name,
            "score": round(float(score), 4),
            "device": device_name,
            "timestamp": ts,
        }
        logger.info("WAKE_DETECTED score=%.3f device=%s", score, device_name)
        self.dispatch_event(event)


STATE = WakeWordState()


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------

class StartRequest(BaseModel):
    device: str = Field(default=DEFAULT_DEVICE, description="auto | <id> | <name>")
    model: str = Field(default=DEFAULT_MODEL)
    threshold: float = Field(default=DEFAULT_THRESHOLD, ge=0.0, le=1.0)
    engine: str = Field(default=DEFAULT_ENGINE, description="openwakeword | vosk")


@asynccontextmanager
async def lifespan(app: FastAPI):
    STATE.loop = asyncio.get_running_loop()
    logger.info(
        "wake-word sidecar up (engine=%s, model=%s, framework=%s, port=%d)",
        DEFAULT_ENGINE, DEFAULT_MODEL, DEFAULT_FRAMEWORK, DEFAULT_PORT,
    )
    if os.getenv("JARVIS_WAKEWORD_AUTOSTART", "0") == "1":
        try:
            STATE.start(DEFAULT_DEVICE, DEFAULT_MODEL, DEFAULT_THRESHOLD, DEFAULT_ENGINE)
        except Exception as exc:
            logger.warning("autostart failed: %s", exc)
    try:
        yield
    finally:
        STATE.stop()
        logger.info("wake-word sidecar shutting down")


app = FastAPI(title="Jarvis Wake-Word Sidecar", version="1.0.0", lifespan=lifespan)


@app.get("/health")
async def health() -> Dict[str, Any]:
    device_name = STATE.selected_device["name"] if STATE.selected_device else None
    return {
        "status": "UP",
        "provider": "openWakeWord",
        "modelsLoaded": STATE.loaded_models,
        "device": device_name,
        "listening": STATE.listening,
    }


@app.get("/devices")
async def devices() -> Dict[str, Any]:
    return {"devices": enumerate_input_devices()["devices"]}


@app.post("/start")
async def start(req: StartRequest) -> Dict[str, Any]:
    return STATE.start(req.device, req.model, req.threshold, req.engine)


@app.post("/stop")
async def stop() -> Dict[str, Any]:
    return STATE.stop()


@app.get("/diagnostics")
async def diagnostics() -> Dict[str, Any]:
    listing = enumerate_input_devices()
    return {
        "provider": STATE.provider,
        "installed": _openwakeword_available(),
        "engine": STATE.engine_name,
        "inferenceFramework": STATE.inference_framework,
        "voskInstalled": _vosk_available(),
        "soundDeviceInstalled": _sounddevice_available(),
        "models": STATE.loaded_models,
        "selectedDevice": STATE.selected_device,
        "listening": STATE.listening,
        "lastWakeDetectedAt": STATE.last_wake_detected_at,
        "lastWakeScore": STATE.last_wake_score,
        "lastError": STATE.last_error,
        "rejectedDevices": listing["rejected"],
    }


@app.get("/events")
async def events() -> StreamingResponse:
    queue: asyncio.Queue = asyncio.Queue(maxsize=SUBSCRIBER_QUEUE_MAX)
    STATE.subscribers.add(queue)
    logger.info("SSE subscriber added (total=%d)", len(STATE.subscribers))

    async def event_stream():
        try:
            # Prime the connection so clients see bytes immediately.
            yield ": connected\n\n"
            while True:
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=KEEPALIVE_SEC)
                    yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
        except asyncio.CancelledError:  # client disconnected
            raise
        finally:
            STATE.subscribers.discard(queue)
            logger.info("SSE subscriber removed (total=%d)", len(STATE.subscribers))

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request, exc: HTTPException):
    detail = exc.detail if isinstance(exc.detail, dict) else {"error": str(exc.detail)}
    return JSONResponse(status_code=exc.status_code, content=detail)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=DEFAULT_HOST, port=DEFAULT_PORT, log_level="info")
