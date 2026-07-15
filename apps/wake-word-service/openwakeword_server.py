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
  GET  /health        liveness + loaded models + selected device + paused
  GET  /devices       enumerated input devices (preferred first, deduped)
  POST /start         open device, start capture+detection thread
  POST /pause         suspend detection (keep stream owned, /health stays UP)
  POST /resume        resume detection after a pause
  POST /stop          stop capture thread + close stream
  POST /calibrate     record a device (no model) + report an RMS summary
  POST /self-test     run a synthesized wake fixture through the real model
  GET  /diagnostics   full engine/device/signal/error state incl. rejected devices
  GET  /events        SSE stream of WAKE_DETECTED events + keepalives

Env:
  JARVIS_WAKEWORD_PORT        listen port                 (default 18095)
  JARVIS_WAKEWORD_HOST        bind host (LOOPBACK ONLY)   (default 127.0.0.1)
  JARVIS_WAKEWORD_DEVICE      default device for autostart (id|name|auto)
  JARVIS_WAKEWORD_MODEL       wake model name             (default hey_jarvis)
  JARVIS_WAKEWORD_THRESHOLD   detection threshold         (default 0.5)
  JARVIS_WAKEWORD_COOLDOWN_MS duplicate-wake debounce ms  (default 2000)
  JARVIS_WAKEWORD_ENGINE      openwakeword|vosk           (default openwakeword)
  JARVIS_WAKEWORD_FRAMEWORK   onnx|tflite                 (default onnx)
  JARVIS_WAKEWORD_AUTOSTART   1 to auto-start capture on boot (default 0)
  JARVIS_VOSK_MODEL           path to a vosk model dir (optional)

Privacy: no raw audio is ever persisted, and no transcript/audio content is
logged. Only wake scores, device names, and error strings are recorded.
"""
from __future__ import annotations

import asyncio
import atexit
import io
import json
import logging
import os
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid
import wave
from collections import deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, Deque, Dict, List, Optional, Tuple

from fastapi import FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Configuration & constants
# ---------------------------------------------------------------------------

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

SAMPLE_RATE = 16000
CHANNELS = 1
FRAME_SAMPLES = 1280          # 80 ms @ 16 kHz — openWakeWord's native frame
# Duplicate-wake debounce window (JARVIS_WAKEWORD_COOLDOWN_MS, default 2000ms).
WAKE_COOLDOWN_SEC = max(0.0, int(os.getenv("JARVIS_WAKEWORD_COOLDOWN_MS", "2000")) / 1000.0)
KEEPALIVE_SEC = 15.0          # SSE keepalive comment interval
SUBSCRIBER_QUEUE_MAX = 64
STREAM_OPEN_TIMEOUT = 6.0     # max seconds /start waits for the mic to open
MAX_CONSECUTIVE_READ_ERRORS = 10  # transient device errors tolerated before giving up
# A raw USB/ALSA hw device just released by the RMS probe can report "Device
# unavailable" (PaErrorCode -9985) for a few hundred ms until the kernel frees
# it. Retry the open a few times with a short settle instead of failing /start.
STREAM_OPEN_ATTEMPTS = 4
STREAM_OPEN_RETRY_SLEEP = 0.35
DEVICE_SETTLE_SEC = 0.15      # pause after a probe closes a device, before reuse

# --- Signal / RMS gating -----------------------------------------------------
# A device whose probe RMS (int16 normalized to [-1, 1]) is at/below this floor
# is treated as SILENT (hardware-dead mic). Auto-selection skips silent devices;
# an explicitly requested silent device is still opened but flagged honest-false.
RMS_SILENCE_FLOOR = float(os.getenv("JARVIS_WAKEWORD_RMS_FLOOR", "1e-4"))
PROBE_SECONDS = float(os.getenv("JARVIS_WAKEWORD_PROBE_SEC", "0.5"))  # per-device probe duration
SIGNAL_WINDOW_SEC = 2.0       # window over which audioSignalPresent is evaluated
MAX_SCORE_WINDOW_SEC = 30.0   # window for maximumScoreLast30Seconds
INFERENCE_LOG_INTERVAL_SEC = 1.0  # rate-limit for wake.inference log lines
CALIBRATE_DEFAULT_SEC = 5      # default /calibrate recording duration
CALIBRATE_MAX_SEC = 30         # hard cap so a bad request can't record forever
RMS_HISTORY_MAX = 64           # ~5s of 80ms frames; caps memory even in tight loops
SCORE_HISTORY_MAX = 512        # ~40s of 80ms frames
SELFTEST_WAKE_PHRASE = "hey jarvis"  # phrase synthesized for /self-test fixtures
PIPER_URL = os.getenv("JARVIS_PIPER_URL", "http://127.0.0.1:18090/synthesize")
PIPER_TIMEOUT_SEC = float(os.getenv("JARVIS_PIPER_TIMEOUT_SEC", "6.0"))

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


def should_accept_wake(now: float, last_fire: float, cooldown_sec: float) -> bool:
    """Duplicate-wake dedup: accept a detection only if the cooldown elapsed.

    Pure function so the debounce policy can be unit-tested without hardware.
    ``now`` / ``last_fire`` are monotonic seconds. Two detections closer than
    ``cooldown_sec`` collapse to a single accepted event.
    """
    return (now - last_fire) >= cooldown_sec


def rms_int16(samples) -> float:
    """Root-mean-square of int16 PCM samples, normalized to [-1, 1].

    A dead/muted microphone yields digital silence (RMS ~0); a live mic picking
    up even ambient room noise reads well above ``RMS_SILENCE_FLOOR``. Pure and
    hardware-free so signal gating can be unit-tested. Empty input -> 0.0.
    """
    import numpy as np

    if samples is None:
        return 0.0
    arr = np.asarray(samples, dtype=np.float64)
    if arr.size == 0:
        return 0.0
    arr = arr / 32768.0
    return float(np.sqrt(np.mean(arr * arr)))


def derive_wake_phrase(model_key_or_name: str) -> str:
    """Derive the human wake phrase from a model key/name (never hardcoded).

    ``hey_jarvis_v0.1`` -> ``hey jarvis``; ``alexa`` -> ``alexa``. Strips a
    trailing version suffix and turns separators into spaces.
    """
    base = os.path.basename(str(model_key_or_name or "")).lower()
    base = re.sub(r"\.(onnx|tflite)$", "", base)
    base = re.sub(r"[_-]?v?\d+([._]\d+)*$", "", base)  # drop _v0.1 / -v2 / _1
    phrase = re.sub(r"[_\-]+", " ", base).strip()
    return phrase or "hey jarvis"


def model_log_label(model_key_or_name: str) -> str:
    """Short model label for structured logs: ``hey_jarvis_v0.1`` -> ``hey_jarvis``."""
    base = os.path.basename(str(model_key_or_name or "")).lower()
    base = re.sub(r"\.(onnx|tflite)$", "", base)
    base = re.sub(r"[_-]?v?\d+([._]\d+)*$", "", base)
    return base.strip("_- ") or "wakeword"


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


def pick_usable_device(
    devices: List[Dict[str, Any]],
    probe_fn,
    floor: float = RMS_SILENCE_FLOOR,
) -> Tuple[Optional[Dict[str, Any]], List[Dict[str, Any]]]:
    """Pure device-selection logic: probe each accepted device, pick a LIVE one.

    ``devices`` must already be preferred-first (as ``enumerate_input_devices``
    returns them). ``probe_fn(device_id) -> float`` returns that device's RMS;
    it may raise (treated as unusable). Selection order:
      1. first PREFERRED device whose probe RMS exceeds ``floor``
      2. else first accepted device whose probe RMS exceeds ``floor``
      3. else ``None`` (no microphone has signal)

    Returns ``(chosen_device_or_None, probe_results)`` where each probe result is
    ``{"name", "id", "rms"}`` (rms ``None`` if the probe raised). Pure over
    ``probe_fn`` so it can be unit-tested with injected RMS values — no hardware.
    """
    probe_results: List[Dict[str, Any]] = []
    usable_preferred: Optional[Dict[str, Any]] = None
    usable_any: Optional[Dict[str, Any]] = None

    for dev in devices:
        try:
            rms = float(probe_fn(dev["id"]))
        except Exception as exc:  # device refused to open / read
            logger.warning("probe failed for %s: %s", dev.get("name"), exc)
            probe_results.append({"name": dev["name"], "id": dev["id"], "rms": None})
            continue

        probe_results.append({"name": dev["name"], "id": dev["id"], "rms": round(rms, 6)})
        if rms > floor:
            if usable_any is None:
                usable_any = dev
            if dev.get("preferred") and usable_preferred is None:
                usable_preferred = dev

    return (usable_preferred or usable_any), probe_results


def probe_device_rms(device_id: int, seconds: float = PROBE_SECONDS) -> float:
    """Open ``device_id`` briefly, read audio, return its RMS (normalized [-1,1]).

    Hardware-touching companion to the pure ``pick_usable_device``. Reuses the
    same open/downmix path as capture, so what we probe is what we would record.
    Raises on any open/read failure so callers treat the device as unusable.
    """
    samples = STATE._capture_samples(device_id, seconds)
    return rms_int16(samples)


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
# Wake-phrase fixture synthesis (for /self-test — never committed audio)
# ---------------------------------------------------------------------------

def _wav_bytes_to_frames16(wav_bytes: bytes):
    """Decode WAV bytes to 16 kHz mono int16 using the SAME path as capture.

    Downmix to mono, resample to 16 kHz with scipy (matching the capture loop),
    then clip to int16. Returns a numpy int16 array, or None on decode failure.
    """
    import numpy as np

    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as w:
            rate = w.getframerate()
            channels = w.getnchannels()
            sampwidth = w.getsampwidth()
            raw = w.readframes(w.getnframes())
    except Exception as exc:
        logger.warning("wav decode failed: %s", exc)
        return None

    if sampwidth != 2:  # only 16-bit PCM fixtures are supported
        logger.warning("unexpected wav sample width: %d bytes", sampwidth)
        return None

    block = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    if channels > 1:
        block = block.reshape(-1, channels).mean(axis=1)
    if rate != SAMPLE_RATE:
        from scipy.signal import resample_poly

        block = resample_poly(block, SAMPLE_RATE, rate)
    return np.clip(block, -32768, 32767).astype(np.int16)


def _synth_piper(text: str) -> Optional[bytes]:
    """Synthesize ``text`` to WAV bytes via the host Piper daemon; None on failure."""
    import urllib.request

    payload = json.dumps({"text": text}).encode("utf-8")
    req = urllib.request.Request(
        PIPER_URL, data=payload, headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=PIPER_TIMEOUT_SEC) as resp:
            if resp.status != 200:
                return None
            data = resp.read()
        return data if data[:4] == b"RIFF" else None
    except Exception as exc:
        logger.info("piper fixture synth unavailable: %s", exc)
        return None


def _synth_espeak(text: str) -> Optional[bytes]:
    """Synthesize ``text`` to WAV bytes via espeak-ng; None if unavailable."""
    if not shutil.which("espeak-ng"):
        return None
    tmp = tempfile.NamedTemporaryFile(prefix="jarvis-wake-", suffix=".wav", delete=False)
    tmp.close()
    try:
        subprocess.run(
            ["espeak-ng", "-v", "en-us", "-w", tmp.name, text],
            check=True, capture_output=True, timeout=10,
        )
        with open(tmp.name, "rb") as fh:
            return fh.read()
    except Exception as exc:
        logger.info("espeak-ng fixture synth failed: %s", exc)
        return None
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass


def synth_wake_fixture(text: str = SELFTEST_WAKE_PHRASE):
    """Return a 16 kHz mono int16 wake-phrase clip, or None if no TTS is available.

    Prefers the host Piper daemon (natural voice, scores ~0.99 on the real
    model); falls back to espeak-ng. Generated at call time — no audio is ever
    committed to the repo. Decoded through the identical resample path as live
    capture so the score proves the real capture->inference chain.
    """
    wav_bytes = _synth_piper(text) or _synth_espeak(text)
    if not wav_bytes:
        return None
    return _wav_bytes_to_frames16(wav_bytes)


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
        self.paused: bool = False
        self.loaded_models: List[str] = []
        self.provider: str = "openWakeWord"
        self.inference_framework: str = DEFAULT_FRAMEWORK

        self.last_wake_detected_at: Optional[str] = None
        self.last_wake_score: Optional[float] = None
        self.last_error: Optional[str] = None

        self._engine: Any = None
        self._stream: Any = None
        self._native_rate: int = SAMPLE_RATE
        self._open_channels: int = CHANNELS
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._ready = threading.Event()   # capture loop signals stream open/failed
        self._last_fire = 0.0

        # -- observability / signal metrics (Phase-2) ----------------------
        # Guarded by _metrics_lock (NOT self.lock) so /diagnostics never blocks
        # behind a slow /start, and the capture thread never blocks control ops.
        self._metrics_lock = threading.Lock()
        self.model_name_full: Optional[str] = None   # engine's real model key
        self.expected_wake_phrase: Optional[str] = None
        self.model_label: str = model_log_label(DEFAULT_MODEL)
        self.audio_frames_received: int = 0
        self.last_audio_frame_at: Optional[str] = None
        self.current_rms: float = 0.0
        self.inference_count: int = 0
        self.last_inference_at: Optional[str] = None
        self.current_score: float = 0.0
        self.wake_detected_count: int = 0
        self._selected_device_silent: bool = False
        self._probe_results: List[Dict[str, Any]] = []
        self._rms_history: Deque[Tuple[float, float]] = deque(maxlen=RMS_HISTORY_MAX)
        self._score_history: Deque[Tuple[float, float]] = deque(maxlen=SCORE_HISTORY_MAX)

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

    # -- honest state reporting ---------------------------------------------
    def is_listening(self) -> bool:
        """Return the REAL detection state, never an optimistic flag.

        Listening is true only when the capture thread is alive, the input
        stream is actually open, we are not paused, and no stop was requested.
        If the mic stream died, this returns False even if a stale flag says
        otherwise — /health and /diagnostics must not lie.
        """
        thread = self._thread
        stream = self._stream
        thread_alive = thread is not None and thread.is_alive()
        stream_active = stream is not None
        return thread_alive and stream_active and not self.paused and not self._stop.is_set()

    # -- observability / signal metrics -------------------------------------
    def _reset_metrics(self) -> None:
        """Zero per-session counters/windows at the start of a fresh capture."""
        with self._metrics_lock:
            self.audio_frames_received = 0
            self.last_audio_frame_at = None
            self.current_rms = 0.0
            self.inference_count = 0
            self.last_inference_at = None
            self.current_score = 0.0
            self.wake_detected_count = 0
            self._rms_history.clear()
            self._score_history.clear()

    def _record_audio_frame(self, frame_rms: float, ts_iso: str) -> None:
        now = time.monotonic()
        with self._metrics_lock:
            self.audio_frames_received += 1
            self.last_audio_frame_at = ts_iso
            self.current_rms = frame_rms
            self._rms_history.append((now, frame_rms))

    def _record_inference(self, score: float, ts_iso: str) -> None:
        now = time.monotonic()
        with self._metrics_lock:
            self.inference_count += 1
            self.last_inference_at = ts_iso
            self.current_score = score
            self._score_history.append((now, score))

    def signal_present(self) -> bool:
        """True only if real audio energy was seen recently (last ~2s).

        A silent/dead mic keeps ``listening`` true but this false — /diagnostics
        must tell the truth. Falls back to the latest ``current_rms`` when no
        windowed history exists yet (e.g. unit tests that set it directly).
        """
        now = time.monotonic()
        cutoff = now - SIGNAL_WINDOW_SEC
        with self._metrics_lock:
            recent = [rms for (ts, rms) in self._rms_history if ts >= cutoff]
            current = self.current_rms
        if not recent:
            return current > RMS_SILENCE_FLOOR
        return max(recent) > RMS_SILENCE_FLOOR

    def max_score_last_30s(self) -> float:
        now = time.monotonic()
        cutoff = now - MAX_SCORE_WINDOW_SEC
        with self._metrics_lock:
            recent = [s for (ts, s) in self._score_history if ts >= cutoff]
        return round(max(recent), 4) if recent else 0.0

    def provider_ready(self) -> bool:
        """READY only when the model is loaded, a device is live-listening with
        real signal, and the capture/inference loop is alive. Never READY for a
        started-but-silent mic.
        """
        return (
            self._engine is not None
            and self.is_listening()
            and self.signal_present()
        )

    # -- capture control ----------------------------------------------------
    def start(self, device_sel: str, model: str, threshold: float, engine: str) -> Dict[str, Any]:
        engine = (engine or DEFAULT_ENGINE).lower()

        if engine == "vosk" and not _vosk_available():
            raise HTTPException(status_code=503, detail={"error": "vosk_not_installed"})
        if engine == "openwakeword" and not _openwakeword_available():
            raise HTTPException(status_code=503, detail={"error": "openwakeword_not_installed"})
        if not _sounddevice_available():
            raise HTTPException(status_code=503, detail={"error": "sounddevice_not_installed"})

        with self.lock:
            # Idempotent restart: stop any existing capture first, so probing is
            # not fighting a still-open stream for the same device.
            self._stop_locked()

            # RMS-verified device selection (THE fix): never blindly open the
            # first name-preferred device — probe for real signal first so a
            # hardware-dead mic (e.g. the C4K webcam) is skipped, not selected.
            device, probe_results, signal_present, explicit_silent = self._select_device(device_sel)

            try:
                built = self._build_engine(engine, model)
            except HTTPException:
                raise
            except FileNotFoundError as exc:
                # Model file absent — a client-fixable request error, not a 500.
                self.last_error = f"model_not_found: {exc}"
                logger.error("wake model not found: %s", exc)
                raise HTTPException(
                    status_code=422,
                    detail={"error": "model_not_found", "model": model, "detail": str(exc)},
                )
            except Exception as exc:
                self.last_error = f"engine_init_failed: {exc}"
                logger.exception("engine init failed")
                raise HTTPException(status_code=500, detail={"error": "engine_init_failed", "detail": str(exc)})

            self._engine = built
            self.engine_name = engine
            self.model_name = model
            self.threshold = threshold
            self.selected_device = device
            self.provider = built.provider
            self.loaded_models = built.loaded_models()
            self.inference_framework = getattr(built, "framework", DEFAULT_FRAMEWORK)
            self.model_name_full = getattr(built, "model_key", model)
            self.expected_wake_phrase = derive_wake_phrase(self.model_name_full)
            self.model_label = model_log_label(self.model_name_full)
            self._probe_results = probe_results
            self._selected_device_silent = explicit_silent
            # An explicitly-requested silent device is still opened (the user
            # asked) but diagnostics tell the truth about it.
            self.last_error = "selected_device_silent" if explicit_silent else None
            self.paused = False
            self._reset_metrics()

            self._stop.clear()
            self._ready.clear()
            self._thread = threading.Thread(
                target=self._capture_loop, name="wakeword-capture", daemon=True,
            )
            self._thread.start()

            # Wait for the capture thread to actually open the mic (or fail), so
            # /start reports the truth instead of an optimistic "STARTED".
            opened = self._ready.wait(timeout=STREAM_OPEN_TIMEOUT)
            if not opened or self._stream is None:
                err = self.last_error or "device_open_timeout"
                self._stop_locked()
                raise HTTPException(
                    status_code=503,
                    detail={
                        "error": "device_open_failed",
                        "device": device.get("name"),
                        "detail": err,
                    },
                )
            self.listening = True

        logger.info(
            "capture started (engine=%s, model=%s, device=%s[%d], threshold=%.2f, "
            "signalPresent=%s)",
            engine, model, device["name"], device["id"], threshold, signal_present,
        )
        return {
            "status": "STARTED",
            "engine": engine,
            "provider": self.provider,
            "device": device,
            "model": model,
            "threshold": threshold,
            "modelsLoaded": self.loaded_models,
            "audioSignalPresent": signal_present,
            "probed": probe_results,
        }

    def _select_device(self, device_sel: str) -> Tuple[Dict[str, Any], List[Dict[str, Any]], bool, bool]:
        """Resolve + RMS-probe a device selector into a device to open.

        Returns ``(device, probe_results, signal_present, explicit_silent)``.

        - ``auto``: probe accepted devices (preferred-first) and pick the first
          LIVE one. Raises 404 if there are no input devices at all, or 503
          ``no_microphone_signal`` (with probe results) if devices exist but none
          have signal — we never silently open a dead mic.
        - explicit ``<id>|<name>``: resolve, then probe. Opens even if silent
          (the user asked), but returns ``explicit_silent=True`` and
          ``signal_present=False`` so diagnostics stay honest.
        """
        sel = (device_sel or "auto").strip()

        if sel.lower() == "auto":
            devices = enumerate_input_devices()["devices"]
            if not devices:
                raise HTTPException(
                    status_code=404,
                    detail={"error": "no_input_device", "requested": device_sel},
                )
            device, probe_results = pick_usable_device(devices, probe_device_rms, RMS_SILENCE_FLOOR)
            if device is None:
                raise HTTPException(
                    status_code=503,
                    detail={"error": "no_microphone_signal", "probed": probe_results},
                )
            return device, probe_results, True, False

        device = resolve_device(sel)
        if device is None:
            raise HTTPException(
                status_code=404,
                detail={"error": "no_input_device", "requested": device_sel},
            )
        try:
            rms = float(probe_device_rms(device["id"]))
        except Exception as exc:
            logger.warning("probe failed for requested device %s: %s", device.get("name"), exc)
            rms = 0.0
        probe_results = [{"name": device["name"], "id": device["id"], "rms": round(rms, 6)}]
        signal_present = rms > RMS_SILENCE_FLOOR
        return device, probe_results, signal_present, (not signal_present)

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

    def pause(self) -> Dict[str, Any]:
        """Suspend detection without tearing the mic down (fast resume).

        While paused the capture thread keeps draining the stream so it stays
        owned and healthy, but no frames reach the model and no WAKE_DETECTED
        is emitted. /health stays UP. Idempotent (double pause is safe).
        """
        with self.lock:
            was_paused = self.paused
            self.paused = True
        if not was_paused:
            logger.info("capture paused")
        return {"status": "PAUSED", "paused": True, "listening": self.is_listening()}

    def resume(self) -> Dict[str, Any]:
        """Resume detection after a pause. Idempotent (double resume is safe)."""
        with self.lock:
            was_paused = self.paused
            self.paused = False
        if was_paused:
            logger.info("capture resumed")
        return {"status": "RESUMED", "paused": False, "listening": self.is_listening()}

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
        self.paused = False
        self._ready.clear()
        # Signal readings are only meaningful while a stream is live; clear them
        # so /diagnostics can't report a stale currentRms / audioSignalPresent.
        self._selected_device_silent = False
        with self._metrics_lock:
            self.current_rms = 0.0
            self.current_score = 0.0
            self._rms_history.clear()
            self._score_history.clear()

    def _capture_samples(self, device_id: int, seconds: float):
        """Open ``device_id``, collect ~``seconds`` of 16 kHz mono int16 samples.

        Shared low-level recorder used by device probing (``probe_device_rms``)
        and ``/calibrate``. Uses the same open/downmix/resample path as capture
        so the signal we measure is the signal we would detect on. Raises on any
        open/read failure; always closes the stream. Returns a numpy int16 array.
        """
        import numpy as np

        stream, native_rate, open_channels = self._open_input_stream(device_id)
        resample = None
        if native_rate != SAMPLE_RATE:
            from scipy.signal import resample_poly

            resample = resample_poly
        native_frame = int(round(native_rate * FRAME_SAMPLES / SAMPLE_RATE))
        collected: List[Any] = []
        deadline = time.monotonic() + max(0.05, float(seconds))
        try:
            while time.monotonic() < deadline:
                data, _ = stream.read(native_frame)
                block = np.frombuffer(bytes(data), dtype=np.int16).astype(np.float32)
                if open_channels > 1:
                    block = block.reshape(-1, open_channels).mean(axis=1)
                if resample is not None:
                    block = resample(block, SAMPLE_RATE, native_rate)
                collected.append(np.clip(block, -32768, 32767).astype(np.int16))
        finally:
            try:
                stream.stop()
                stream.close()
            except Exception as exc:
                logger.warning("probe stream close error: %s", exc)
            # Give the kernel a moment to release a raw USB/ALSA device so an
            # immediate reopen (capture) does not hit "Device unavailable".
            time.sleep(DEVICE_SETTLE_SEC)
        if not collected:
            return np.zeros(0, dtype=np.int16)
        return np.concatenate(collected)

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
        # Outer retry: a device just released by the RMS probe can be briefly
        # "unavailable" (-9985) until the kernel frees it. Settle and retry
        # rather than fail /start on a transient busy state.
        for attempt in range(STREAM_OPEN_ATTEMPTS):
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
                except Exception as exc:  # PortAudioError: unsupported combo / busy
                    last_exc = exc
                    continue
            if attempt < STREAM_OPEN_ATTEMPTS - 1:
                logger.info(
                    "device %d not ready (attempt %d/%d), settling %.2fs: %s",
                    device_id, attempt + 1, STREAM_OPEN_ATTEMPTS, STREAM_OPEN_RETRY_SLEEP, last_exc,
                )
                time.sleep(STREAM_OPEN_RETRY_SLEEP)
        raise last_exc or RuntimeError("no supported input format")

    def _capture_loop(self) -> None:
        import numpy as np

        device = self.selected_device
        engine = self._engine
        device_id = device["id"] if device else None
        try:
            stream, native_rate, open_channels = self._open_input_stream(device_id)
            self._stream = stream
            self._native_rate = native_rate
            self._open_channels = open_channels
        except Exception as exc:
            self.last_error = f"device_open_failed: {exc}"
            self.listening = False
            logger.error("failed to open input stream: %s", exc)
            self._ready.set()   # unblock start() so it can report the failure
            return
        # Signal start() that the mic is open and detection is live.
        self._ready.set()

        resample = None
        if native_rate != SAMPLE_RATE:
            from scipy.signal import resample_poly
            resample = resample_poly

        native_frame = int(round(native_rate * FRAME_SAMPLES / SAMPLE_RATE))
        pending = np.zeros(0, dtype=np.float32)

        logger.info("capture loop running on device %s (native=%dHz)", device, native_rate)
        consecutive_errors = 0
        last_inference_log = 0.0
        while not self._stop.is_set():
            try:
                data, overflowed = stream.read(native_frame)
                consecutive_errors = 0
                if self._stop.is_set():
                    break
                # Paused: keep the stream drained (owned + healthy) but feed no
                # frames to the model and emit nothing. Resume is instant.
                if self.paused:
                    pending = np.zeros(0, dtype=np.float32)
                    continue
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

                    # Observe the REAL signal energy before inference so a dead
                    # mic surfaces as audioSignalPresent=false despite listening.
                    frame_rms = rms_int16(frame16)
                    ts = _now_iso()
                    self._record_audio_frame(frame_rms, ts)
                    score = engine.process(frame16)
                    self._record_inference(score, ts)

                    # Rate-limited (~1/s) structured inference log. Never raw PCM.
                    now = time.monotonic()
                    if now - last_inference_log >= INFERENCE_LOG_INTERVAL_SEC:
                        last_inference_log = now
                        logger.info(
                            "wake.inference provider=%s model=%s score=%.3f "
                            "threshold=%.2f rms=%.4f frames=%d",
                            self.engine_name, self.model_label, score,
                            self.threshold, frame_rms, self.audio_frames_received,
                        )

                    if score >= self.threshold:
                        self._on_wake(score)
            except Exception as exc:
                # Tolerate transient device hiccups; give up only if they persist.
                consecutive_errors += 1
                self.last_error = f"capture_error: {exc}"
                if consecutive_errors >= MAX_CONSECUTIVE_READ_ERRORS:
                    logger.error(
                        "capture loop giving up after %d consecutive errors: %s",
                        consecutive_errors, exc,
                    )
                    break
                logger.warning(
                    "transient capture error (%d/%d): %s",
                    consecutive_errors, MAX_CONSECUTIVE_READ_ERRORS, exc,
                )
                self._stop.wait(0.1)

        self.listening = False
        logger.info("capture loop exited")

    def _on_wake(self, score: float) -> None:
        """Live-capture wake: debounced against the cooldown window."""
        self._emit_wake(score, source=self.engine_name)

    def _emit_wake(
        self,
        score: float,
        source: str = "openwakeword",
        bypass_cooldown: bool = False,
        event_id: Optional[str] = None,
    ) -> Optional[str]:
        """Record + dispatch a WAKE_DETECTED event; returns its eventId (or None
        if collapsed by the cooldown). ``bypass_cooldown`` is used by /self-test,
        which must deterministically emit exactly one event.
        """
        now = time.monotonic()
        if not bypass_cooldown and not should_accept_wake(now, self._last_fire, WAKE_COOLDOWN_SEC):
            return None
        self._last_fire = now

        ts = _now_iso()
        eid = event_id or uuid.uuid4().hex
        score = round(float(score), 4)
        with self._metrics_lock:
            self.wake_detected_count += 1
            self.last_wake_detected_at = ts
            self.last_wake_score = score
        device_name = self.selected_device["name"] if self.selected_device else None

        event = {
            "type": "WAKE_DETECTED",
            "provider": self.provider,
            "model": self.model_name,
            "score": score,
            "device": device_name,
            "eventId": eid,
            "source": source,
            "timestamp": ts,
        }
        logger.info(
            "wake.detected provider=%s model=%s score=%.3f eventId=%s",
            self.engine_name, self.model_label, score, eid,
        )
        self.dispatch_event(event)
        return eid

    def calibrate(self, device_sel: str, seconds: float) -> Dict[str, Any]:
        """Record from a device WITHOUT running the model; return an RMS summary.

        Backs the desktop "Calibrate" flow. Never touches the wake model — pure
        signal measurement so the operator can see whether a mic is live before
        arming detection. Guarded for headless (clear errors, never a crash).
        """
        import numpy as np

        if not _sounddevice_available():
            raise HTTPException(status_code=503, detail={"error": "sounddevice_not_installed"})

        seconds = max(0.1, min(float(seconds or CALIBRATE_DEFAULT_SEC), CALIBRATE_MAX_SEC))
        sel = (device_sel or "auto").strip()
        if sel.lower() == "auto":
            devices = enumerate_input_devices()["devices"]
            device = devices[0] if devices else None
        else:
            device = resolve_device(sel)
        if device is None:
            raise HTTPException(
                status_code=404,
                detail={"error": "no_input_device", "requested": device_sel},
            )

        try:
            samples = self._capture_samples(device["id"], seconds)
        except Exception as exc:
            logger.error("calibrate capture failed: %s", exc)
            raise HTTPException(
                status_code=503,
                detail={"error": "device_open_failed", "device": device.get("name"), "detail": str(exc)},
            )

        frames = [
            samples[i:i + FRAME_SAMPLES]
            for i in range(0, len(samples), FRAME_SAMPLES)
            if len(samples[i:i + FRAME_SAMPLES]) > 0
        ]
        rmss = [rms_int16(f) for f in frames]
        if rmss:
            min_rms, avg_rms, max_rms = min(rmss), float(np.mean(rmss)), max(rmss)
        else:
            min_rms = avg_rms = max_rms = 0.0
        signal_detected = max_rms > RMS_SILENCE_FLOOR
        logger.info(
            "calibrate device=%s frames=%d min=%.5f avg=%.5f max=%.5f signal=%s",
            device["name"], len(frames), min_rms, avg_rms, max_rms, signal_detected,
        )
        return {
            "device": device,
            "frameCount": len(frames),
            "minRms": round(min_rms, 6),
            "avgRms": round(avg_rms, 6),
            "maxRms": round(max_rms, 6),
            "signalDetected": signal_detected,
        }

    def _inference_engine(self) -> Any:
        """Engine used for /self-test fixture scoring.

        Reuses the started engine only when idle; otherwise builds a fresh
        instance of the SAME real model so a concurrent capture loop's streaming
        state is never corrupted and the score stays deterministic.
        """
        if self._engine is not None and not self.is_listening():
            return self._engine
        return self._build_engine(self.engine_name, self.model_name)

    def self_test(self) -> Dict[str, Any]:
        """Reproducible end-to-end detection proof (no human voice needed).

        Feeds a synthesized "hey jarvis" fixture through the REAL model frame by
        frame and reports whether it crosses threshold; on success emits ONE real
        WAKE_DETECTED over the existing SSE so a subscriber sees it. Stages are
        checked in order and the first failure short-circuits with an honest
        message. Returns ``{stage, ok, maxScore, threshold, message}``.
        """
        import numpy as np

        threshold = self.threshold

        # Stage 1: provider/model loaded (or buildable).
        if not _openwakeword_available() and self.engine_name == "openwakeword":
            return {"stage": "provider_loaded", "ok": False, "maxScore": 0.0,
                    "threshold": threshold, "message": "openwakeword not installed"}
        try:
            engine = self._inference_engine()
        except HTTPException as exc:
            detail = exc.detail if isinstance(exc.detail, dict) else {"error": str(exc.detail)}
            return {"stage": "provider_loaded", "ok": False, "maxScore": 0.0,
                    "threshold": threshold, "message": f"model unavailable: {detail}"}
        except Exception as exc:
            return {"stage": "provider_loaded", "ok": False, "maxScore": 0.0,
                    "threshold": threshold, "message": f"model unavailable: {exc}"}

        # Stage 2: if a device is started, it must actually have signal.
        if self.is_listening() and not self.signal_present():
            return {"stage": "audio_signal", "ok": False, "maxScore": 0.0,
                    "threshold": threshold,
                    "message": "microphone is started but silent (audioSignalPresent=false)"}

        # Stage 3: synthesize + run the fixture through the real engine.
        fixture = synth_wake_fixture()
        if fixture is None or len(fixture) < FRAME_SAMPLES:
            return {"stage": "fixture", "ok": False, "maxScore": 0.0,
                    "threshold": threshold, "message": "no tts fixture available"}

        max_score = 0.0
        for i in range(0, len(fixture) - FRAME_SAMPLES + 1, FRAME_SAMPLES):
            frame = np.asarray(fixture[i:i + FRAME_SAMPLES], dtype=np.int16)
            score = float(engine.process(frame))
            if score > max_score:
                max_score = score
        max_score = round(max_score, 4)

        if max_score < threshold:
            return {"stage": "fixture_inference", "ok": False, "maxScore": max_score,
                    "threshold": threshold,
                    "message": f"fixture max score {max_score} below threshold {threshold}"}

        # Stage 4: emit exactly one real WAKE_DETECTED over the SSE.
        eid = self._emit_wake(max_score, source="self-test", bypass_cooldown=True)
        return {"stage": "wake_emitted", "ok": True, "maxScore": max_score,
                "threshold": threshold,
                "message": f"fixture crossed threshold; WAKE_DETECTED emitted (eventId={eid})"}


STATE = WakeWordState()


def _release_resources() -> None:
    """Release the microphone/stream on interpreter exit (belt-and-suspenders).

    stop() is idempotent, so this composes safely with the lifespan shutdown
    and the signal handlers below.
    """
    try:
        STATE.stop()
    except Exception:  # never raise from an atexit/signal path
        pass


# Guarantee the mic is freed even on an ungraceful teardown.
atexit.register(_release_resources)


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------

class StartRequest(BaseModel):
    device: str = Field(default=DEFAULT_DEVICE, description="auto | <id> | <name>")
    model: str = Field(default=DEFAULT_MODEL)
    threshold: float = Field(default=DEFAULT_THRESHOLD, ge=0.0, le=1.0)
    engine: str = Field(default=DEFAULT_ENGINE, description="openwakeword | vosk")


class CalibrateRequest(BaseModel):
    device: str = Field(default=DEFAULT_DEVICE, description="auto | <id> | <name>")
    seconds: float = Field(default=CALIBRATE_DEFAULT_SEC, gt=0.0, le=CALIBRATE_MAX_SEC)


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
        # Honest: reflects the REAL stream state, not an optimistic flag.
        "listening": STATE.is_listening(),
        "paused": STATE.paused,
        # READY only when model loaded AND a live device with real signal AND the
        # loop is alive — never READY for a started-but-silent mic.
        "ready": STATE.provider_ready(),
        "modelLoaded": STATE._engine is not None,
        "modelName": STATE.model_name,
        "expectedWakePhrase": STATE.expected_wake_phrase,
        "currentRms": round(STATE.current_rms, 6),
        "audioSignalPresent": STATE.signal_present(),
        "sampleRate": SAMPLE_RATE,
        "threshold": STATE.threshold,
    }


@app.get("/devices")
async def devices() -> Dict[str, Any]:
    return {"devices": enumerate_input_devices()["devices"]}


# /start, /pause, /resume and /stop all take STATE.lock and can block on it (start
# also does thread.join + _ready.wait; stop does thread.join). Running them inline
# would freeze the single asyncio event loop for up to seconds, so /health and
# /diagnostics would hang behind a slow /start. Offload each to a worker thread via
# run_in_threadpool so the loop stays responsive. The JSON response shape is unchanged;
# an HTTPException raised in the worker still propagates to the exception handler.
@app.post("/start")
async def start(req: StartRequest) -> Dict[str, Any]:
    return await run_in_threadpool(STATE.start, req.device, req.model, req.threshold, req.engine)


@app.post("/pause")
async def pause() -> Dict[str, Any]:
    return await run_in_threadpool(STATE.pause)


@app.post("/resume")
async def resume() -> Dict[str, Any]:
    return await run_in_threadpool(STATE.resume)


@app.post("/stop")
async def stop() -> Dict[str, Any]:
    return await run_in_threadpool(STATE.stop)


@app.post("/calibrate")
async def calibrate(req: CalibrateRequest) -> Dict[str, Any]:
    # Records from the device (no model) — offloaded so the loop stays responsive.
    return await run_in_threadpool(STATE.calibrate, req.device, req.seconds)


@app.post("/self-test")
async def self_test() -> Dict[str, Any]:
    # Synthesizes a fixture and runs it through the real model — TTS + inference
    # are blocking, so keep them off the event loop.
    return await run_in_threadpool(STATE.self_test)


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
        # Honest: computed from the live stream/thread, plus the pause flag.
        "listening": STATE.is_listening(),
        "paused": STATE.paused,
        "ready": STATE.provider_ready(),
        # -- audio pipeline (Phase-2 observability) ------------------------
        "sampleRate": SAMPLE_RATE,
        "channels": CHANNELS,
        "pcmFormat": "int16",
        "audioFramesReceived": STATE.audio_frames_received,
        "lastAudioFrameAt": STATE.last_audio_frame_at,
        "currentRms": round(STATE.current_rms, 6),
        "audioSignalPresent": STATE.signal_present(),
        "probedDevices": STATE._probe_results,
        # -- model / inference --------------------------------------------
        "modelLoaded": STATE._engine is not None,
        "modelName": STATE.model_name,
        "expectedWakePhrase": STATE.expected_wake_phrase,
        "inferenceCount": STATE.inference_count,
        "lastInferenceAt": STATE.last_inference_at,
        "currentScore": round(STATE.current_score, 4),
        "maximumScoreLast30Seconds": STATE.max_score_last_30s(),
        "threshold": STATE.threshold,
        # -- wake events ---------------------------------------------------
        "wakeDetectedCount": STATE.wake_detected_count,
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


def _install_signal_handlers() -> None:
    """Release the mic on SIGTERM/SIGINT before the process exits.

    When run under uvicorn, uvicorn's own handlers drive the lifespan shutdown
    (which also calls STATE.stop()); these handlers are a best-effort safety net
    for the standalone/embedded case. Only installable from the main thread.
    """
    def _graceful(signum, _frame):  # pragma: no cover - signal path
        logger.info("received signal %s; releasing microphone", signum)
        _release_resources()
        # Restore default disposition and re-raise so the process still exits.
        try:
            signal.signal(signum, signal.SIG_DFL)
            os.kill(os.getpid(), signum)
        except Exception:
            raise SystemExit(0)

    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            signal.signal(sig, _graceful)
        except (ValueError, OSError):  # not main thread / unsupported platform
            logger.debug("could not install handler for signal %s", sig)


if __name__ == "__main__":
    import uvicorn

    _install_signal_handlers()
    # Loopback-only unless the operator explicitly overrides the bind host.
    uvicorn.run(app, host=DEFAULT_HOST, port=DEFAULT_PORT, log_level="info")
