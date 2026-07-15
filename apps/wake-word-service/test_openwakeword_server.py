#!/usr/bin/env python3
"""Tests for the Jarvis openWakeWord sidecar.

Runs headless/CI-safe: everything that would touch real audio hardware is
either mocked (fake stream + fake engine) or guarded with ``requires_input``
so it SKIPS cleanly when no microphone is present. The pure logic — device
filtering, the duplicate-wake dedup, and pause/resume/listening state — is
exercised without any hardware.

Run with the sidecar venv:
    .venv-wakeword/bin/python -m pytest apps/wake-word-service/ -q
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

import openwakeword_server as m


# ---------------------------------------------------------------------------
# Hardware detection + shared fakes
# ---------------------------------------------------------------------------

def _has_input_device() -> bool:
    try:
        import sounddevice as sd

        return any(int(d.get("max_input_channels", 0)) > 0 for d in sd.query_devices())
    except Exception:
        return False


requires_input = pytest.mark.skipif(
    not _has_input_device(),
    reason="no audio input device available (headless/CI)",
)

FAKE_DEVICE = {
    "id": 999,
    "name": "fake-mic",
    "sampleRate": 16000,
    "channels": 1,
    "isInput": True,
    "preferred": True,
}


class FakeStream:
    """Stand-in for a sounddevice.InputStream that yields int16 silence."""

    def __init__(self) -> None:
        self.started = True
        self.stopped = False
        self.closed = False

    def read(self, frames: int):
        return (bytes(frames * 2), False)  # mono int16 silence, no overflow

    def stop(self) -> None:
        self.stopped = True

    def close(self) -> None:
        self.closed = True


class FakeEngine:
    provider = "fakeEngine"
    framework = "onnx"

    def loaded_models(self):
        return ["fake_model"]

    def process(self, frame):  # never triggers a wake
        return 0.0


def _fake_select_device(sel):
    """Stand-in for STATE._select_device: a live preferred device, no probing."""
    return dict(FAKE_DEVICE), [{"name": "fake-mic", "id": 999, "rms": 0.05}], True, False


def _mock_capture(monkeypatch, engine: object | None = None) -> FakeStream:
    """Wire STATE so start() runs with no real hardware. Returns the fake stream.

    Bypasses the real RMS probe by stubbing _select_device (the selection seam),
    so unit tests never touch a microphone. ``engine`` overrides the fake engine.
    """
    stream = FakeStream()
    monkeypatch.setattr(m, "resolve_device", lambda sel: dict(FAKE_DEVICE))
    monkeypatch.setattr(m.STATE, "_select_device", _fake_select_device)
    monkeypatch.setattr(m.STATE, "_open_input_stream", lambda device_id: (stream, 16000, 1))
    monkeypatch.setattr(m.STATE, "_build_engine", lambda eng, model: engine or FakeEngine())
    return stream


@pytest.fixture
def reset_state():
    """Guarantee a clean STATE around each test (isolation)."""
    m.STATE.stop()
    m.STATE.last_error = None
    m.STATE.last_wake_score = None
    m.STATE.last_wake_detected_at = None
    m.STATE.selected_device = None
    m.STATE.loaded_models = []
    m.STATE._last_fire = 0.0
    yield
    m.STATE.stop()


@pytest.fixture
def client(reset_state):
    with TestClient(m.app) as c:
        yield c


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------

def test_health_returns_up_with_paused_and_listening(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "UP"
    assert body["provider"] == "openWakeWord"
    assert isinstance(body["listening"], bool)
    assert isinstance(body["paused"], bool)
    # Nothing started → both false, and honestly so.
    assert body["listening"] is False
    assert body["paused"] is False


# ---------------------------------------------------------------------------
# /devices — filtering logic (mocked, hardware-independent)
# ---------------------------------------------------------------------------

def test_device_filtering_rejects_outputs_prefers_real_mics(monkeypatch):
    fake_devices = [
        {"name": "HDA Intel PCH Speaker", "max_input_channels": 0},   # pure output → skipped
        {"name": "Some Playback Device", "max_input_channels": 2},    # playback → rejected
        {"name": "Monitor of Built-in Audio", "max_input_channels": 2},  # monitor → rejected
        {"name": "USB sink adapter", "max_input_channels": 2},        # sink → rejected
        {"name": "Built-in Output", "max_input_channels": 2},         # output → rejected
        {"name": "Desktop Speaker Array", "max_input_channels": 2},   # speaker → rejected
        {"name": "C4K USB Camera Mic", "max_input_channels": 1},      # preferred (c4k/usb/mic)
        {"name": "T1 USB Audio", "max_input_channels": 1},            # preferred (t1/usb)
        {"name": "plughw CARD", "max_input_channels": 1},             # preferred (plughw)
        {"name": "Generic Line In", "max_input_channels": 2},         # accepted, not preferred
    ]
    import sounddevice as sd

    monkeypatch.setattr(sd, "query_devices", lambda *a, **k: fake_devices)

    listing = m.enumerate_input_devices()
    names = [d["name"] for d in listing["devices"]]
    rejected = [d["name"] for d in listing["rejected"]]

    # playback/output/monitor/sink/speaker rejected and never accepted
    for bad in ("Some Playback Device", "Monitor of Built-in Audio",
                "USB sink adapter", "Built-in Output", "Desktop Speaker Array"):
        assert bad in rejected
        assert bad not in names

    # pure-output device (no input channels) is skipped entirely
    assert "HDA Intel PCH Speaker" not in names
    assert "HDA Intel PCH Speaker" not in rejected

    # preferred real mics accepted
    for good in ("C4K USB Camera Mic", "T1 USB Audio", "plughw CARD"):
        assert good in names
    assert "Generic Line In" in names  # accepted but not preferred

    # preferred devices sorted first
    assert listing["devices"][0]["preferred"] is True
    assert names.index("Generic Line In") > names.index("C4K USB Camera Mic")


def test_devices_endpoint_shape(client):
    r = client.get("/devices")
    assert r.status_code == 200
    body = r.json()
    assert isinstance(body["devices"], list)  # empty list is fine (headless)
    for d in body["devices"]:
        assert d["isInput"] is True
        low = d["name"].lower()
        for token in m.EXCLUDE_TOKENS:
            assert token not in low


# ---------------------------------------------------------------------------
# /start — invalid device is a clear error, not a crash
# ---------------------------------------------------------------------------

def test_start_invalid_device_name_returns_clear_error(client):
    r = client.post("/start", json={"device": "definitely-not-a-real-device-xyz"})
    assert r.status_code == 404
    assert r.json()["error"] == "no_input_device"


def test_start_invalid_device_id_returns_clear_error(client):
    r = client.post("/start", json={"device": "999999"})
    assert r.status_code == 404
    assert r.json()["error"] == "no_input_device"


# ---------------------------------------------------------------------------
# start → pause → resume → stop (mocked hardware, deterministic)
# ---------------------------------------------------------------------------

def test_start_pause_resume_stop_updates_state(monkeypatch, reset_state):
    stream = _mock_capture(monkeypatch)

    result = m.STATE.start("auto", "hey_jarvis", 0.5, "openwakeword")
    assert result["status"] == "STARTED"
    assert m.STATE.is_listening() is True
    assert m.STATE.paused is False

    # pause → detecting stops, but stream stays owned; listening reflects reality
    paused = m.STATE.pause()
    assert paused["paused"] is True
    assert m.STATE.paused is True
    assert m.STATE.is_listening() is False
    assert stream.closed is False  # stream kept alive across pause

    # double pause is idempotent
    assert m.STATE.pause()["paused"] is True
    assert m.STATE.is_listening() is False

    # resume → detecting again
    resumed = m.STATE.resume()
    assert resumed["paused"] is False
    assert m.STATE.paused is False
    assert m.STATE.is_listening() is True

    # double resume is idempotent
    assert m.STATE.resume()["paused"] is False
    assert m.STATE.is_listening() is True

    # stop → released, listening honest-false
    m.STATE.stop()
    assert m.STATE.is_listening() is False
    assert m.STATE.paused is False
    assert stream.closed is True


# ---------------------------------------------------------------------------
# duplicate-wake cooldown (dedup function tested directly)
# ---------------------------------------------------------------------------

def test_cooldown_derives_from_env_default():
    # JARVIS_WAKEWORD_COOLDOWN_MS default 2000 → 2.0 seconds
    assert m.WAKE_COOLDOWN_SEC == 2.0


def test_should_accept_wake_dedups_within_cooldown():
    cooldown = m.WAKE_COOLDOWN_SEC
    first = 1000.0
    assert m.should_accept_wake(first, 0.0, cooldown) is True            # first accepted
    within = first + cooldown / 2.0
    assert m.should_accept_wake(within, first, cooldown) is False        # duplicate
    after = first + cooldown + 0.001
    assert m.should_accept_wake(after, first, cooldown) is True          # after window


def test_two_detections_within_cooldown_collapse_to_one():
    cooldown = m.WAKE_COOLDOWN_SEC
    last_fire = 0.0
    accepted = 0
    for ts in (500.0, 500.0 + cooldown / 4.0):  # two wakes well inside the window
        if m.should_accept_wake(ts, last_fire, cooldown):
            accepted += 1
            last_fire = ts
    assert accepted == 1


# ---------------------------------------------------------------------------
# /diagnostics shape
# ---------------------------------------------------------------------------

def test_diagnostics_shape(client):
    r = client.get("/diagnostics")
    assert r.status_code == 200
    body = r.json()
    for key in (
        "provider", "installed", "models", "selectedDevice", "listening",
        "paused", "lastWakeScore", "lastWakeDetectedAt", "lastError",
        "rejectedDevices",
    ):
        assert key in body, f"diagnostics missing key: {key}"
    assert isinstance(body["listening"], bool)
    assert isinstance(body["paused"], bool)
    assert isinstance(body["rejectedDevices"], list)


# ---------------------------------------------------------------------------
# model-missing path → clear error
# ---------------------------------------------------------------------------

def test_resolve_model_path_missing_raises():
    with pytest.raises(FileNotFoundError):
        m.resolve_model_path("no_such_model_xyzzy", "onnx")


def test_start_missing_model_returns_422(monkeypatch, reset_state):
    # Select a (fake) live device so we reach engine build, but let the REAL
    # engine fail to find the bogus model — a clean 422, not a 500.
    monkeypatch.setattr(m.STATE, "_select_device", _fake_select_device)
    with pytest.raises(m.HTTPException) as excinfo:
        m.STATE.start("auto", "no_such_model_xyzzy", 0.5, "openwakeword")
    assert excinfo.value.status_code == 422
    assert excinfo.value.detail["error"] == "model_not_found"
    # No stream was ever opened.
    assert m.STATE._stream is None
    assert m.STATE.is_listening() is False


# ---------------------------------------------------------------------------
# graceful shutdown releases resources (unit-level)
# ---------------------------------------------------------------------------

def test_stop_releases_stream(monkeypatch, reset_state):
    stream = _mock_capture(monkeypatch)
    m.STATE.start("auto", "hey_jarvis", 0.5, "openwakeword")
    assert m.STATE.is_listening() is True

    m.STATE.stop()
    assert stream.stopped is True
    assert stream.closed is True
    assert m.STATE._stream is None
    assert m.STATE._engine is None
    assert m.STATE.is_listening() is False


def test_release_resources_is_idempotent(monkeypatch, reset_state):
    _mock_capture(monkeypatch)
    m.STATE.start("auto", "hey_jarvis", 0.5, "openwakeword")
    # atexit/signal safety-net must never raise, even called repeatedly.
    m._release_resources()
    m._release_resources()
    assert m.STATE.is_listening() is False


# ---------------------------------------------------------------------------
# Live hardware path (guarded; skips cleanly with no mic)
# ---------------------------------------------------------------------------

@requires_input
def test_live_start_pause_resume_reflects_reality(client):
    r = client.post("/start", json={"device": "auto"})
    # Either it opens the mic, or it returns a clear JSON error — never a crash.
    assert r.status_code in (200, 404, 503)

    if r.status_code != 200:
        assert "error" in r.json()
        return

    assert client.get("/health").json()["listening"] is True

    client.post("/pause")
    diag = client.get("/diagnostics").json()
    assert diag["paused"] is True
    assert diag["listening"] is False

    client.post("/resume")
    diag = client.get("/diagnostics").json()
    assert diag["paused"] is False
    assert diag["listening"] is True

    client.post("/stop")
    assert client.get("/health").json()["listening"] is False


# ---------------------------------------------------------------------------
# RMS-verified device selection (THE fix) — pure logic, injected probe RMS
# ---------------------------------------------------------------------------

def test_pick_usable_device_prefers_live_over_silent():
    # C4K-like preferred mic is HARDWARE-DEAD (rms 0); T1-like preferred mic is
    # LIVE. Selection must skip the dead C4K and pick the live T1, never the
    # first-by-name preferred device.
    devices = [
        {"id": 2, "name": "C4K USB Camera Mic", "preferred": True},   # silent
        {"id": 7, "name": "T1 USB Audio", "preferred": True},         # live
        {"id": 0, "name": "Generic Line In", "preferred": False},     # live
    ]
    probe_rms = {2: 0.0, 7: 0.06, 0: 0.05}
    chosen, results = m.pick_usable_device(devices, lambda i: probe_rms[i], floor=1e-4)

    assert chosen is not None
    assert chosen["id"] == 7  # live PREFERRED, NOT the dead C4K (id 2)
    ids = {r["id"] for r in results}
    # Short-circuit: probing stops at the FIRST live device (id 7), so the trailing
    # id 0 is NOT probed — this keeps /start fast on multi-device hosts.
    assert ids == {2, 7}
    rms_by_id = {r["id"]: r["rms"] for r in results}
    assert rms_by_id[2] == 0.0  # dead mic's silence is on record


def test_pick_usable_device_falls_back_to_nonpreferred_when_preferred_silent():
    devices = [
        {"id": 2, "name": "C4K USB Camera Mic", "preferred": True},  # silent
        {"id": 0, "name": "Generic Line In", "preferred": False},   # live
    ]
    probe_rms = {2: 0.0, 0: 0.05}
    chosen, _ = m.pick_usable_device(devices, lambda i: probe_rms[i], floor=1e-4)
    assert chosen["id"] == 0  # first usable ACCEPTED when no preferred is live


def test_pick_usable_device_none_when_all_silent():
    devices = [
        {"id": 2, "name": "C4K USB Camera Mic", "preferred": True},
        {"id": 7, "name": "PCH Alt Analog", "preferred": True},
    ]
    chosen, results = m.pick_usable_device(devices, lambda i: 0.0, floor=1e-4)
    assert chosen is None  # -> /start returns 503 no_microphone_signal
    assert all(r["rms"] == 0.0 for r in results)


def test_pick_usable_device_treats_probe_error_as_unusable():
    devices = [
        {"id": 2, "name": "C4K USB Camera Mic", "preferred": True},  # raises
        {"id": 7, "name": "T1 USB Audio", "preferred": True},        # live
    ]

    def probe(i):
        if i == 2:
            raise OSError("device busy")
        return 0.06

    chosen, results = m.pick_usable_device(devices, probe, floor=1e-4)
    assert chosen["id"] == 7
    rms_by_id = {r["id"]: r["rms"] for r in results}
    assert rms_by_id[2] is None  # failed probe recorded honestly


def test_start_auto_with_no_signal_returns_503(client, monkeypatch):
    # All input devices present but hardware-dead → /start must NOT open one.
    fake_devices = [
        {"id": 2, "name": "C4K USB Camera Mic", "preferred": True},
        {"id": 7, "name": "T1 USB Audio", "preferred": True},
    ]
    monkeypatch.setattr(m, "enumerate_input_devices",
                        lambda: {"devices": fake_devices, "rejected": []})
    monkeypatch.setattr(m, "probe_device_rms",
                        lambda device_id, seconds=m.PROBE_SECONDS: 0.0)
    r = client.post("/start", json={"device": "auto"})
    assert r.status_code == 503
    body = r.json()
    assert body["error"] == "no_microphone_signal"
    assert isinstance(body["probed"], list) and body["probed"]


def test_start_auto_selects_live_device_over_dead_one(client, monkeypatch):
    # C4K (preferred, first) is dead; T1 (preferred) is live → auto picks T1.
    fake_devices = [
        {"id": 2, "name": "C4K USB Camera Mic", "preferred": True},
        {"id": 7, "name": "T1 USB Audio", "preferred": True},
    ]
    probe_rms = {2: 0.0, 7: 0.06}
    monkeypatch.setattr(m, "enumerate_input_devices",
                        lambda: {"devices": fake_devices, "rejected": []})
    monkeypatch.setattr(m, "probe_device_rms",
                        lambda device_id, seconds=m.PROBE_SECONDS: probe_rms[device_id])
    monkeypatch.setattr(m.STATE, "_open_input_stream",
                        lambda device_id: (FakeStream(), 16000, 1))
    monkeypatch.setattr(m.STATE, "_build_engine", lambda e, mo: FakeEngine())

    r = client.post("/start", json={"device": "auto"})
    assert r.status_code == 200
    assert r.json()["device"]["id"] == 7          # T1, NOT the dead C4K (id 2)
    assert r.json()["device"]["name"] == "T1 USB Audio"
    client.post("/stop")


def test_start_explicit_silent_device_opens_but_flags(client, monkeypatch):
    # User explicitly asked for a device that turns out silent: open it (they
    # asked) but tell the truth — lastError + audioSignalPresent=false.
    monkeypatch.setattr(m, "resolve_device", lambda sel: dict(FAKE_DEVICE))
    monkeypatch.setattr(m, "probe_device_rms",
                        lambda device_id, seconds=m.PROBE_SECONDS: 0.0)
    monkeypatch.setattr(m.STATE, "_open_input_stream",
                        lambda device_id: (FakeStream(), 16000, 1))
    monkeypatch.setattr(m.STATE, "_build_engine", lambda e, mo: FakeEngine())

    r = client.post("/start", json={"device": "fake-mic"})
    assert r.status_code == 200
    assert r.json()["audioSignalPresent"] is False

    diag = client.get("/diagnostics").json()
    assert diag["listening"] is True          # opened, as requested
    assert diag["audioSignalPresent"] is False  # but honestly signal-less
    assert diag["lastError"] == "selected_device_silent"
    assert diag["ready"] is False             # never READY for a silent mic
    client.post("/stop")


# ---------------------------------------------------------------------------
# Observability (Phase-2) — /diagnostics + audioSignalPresent + READY
# ---------------------------------------------------------------------------

def test_diagnostics_includes_signal_and_inference_fields(client):
    body = client.get("/diagnostics").json()
    for key in (
        "sampleRate", "channels", "pcmFormat", "audioFramesReceived",
        "lastAudioFrameAt", "currentRms", "audioSignalPresent", "probedDevices",
        "modelLoaded", "modelName", "expectedWakePhrase", "inferenceCount",
        "lastInferenceAt", "currentScore", "maximumScoreLast30Seconds",
        "threshold", "wakeDetectedCount", "lastWakeDetectedAt", "ready",
    ):
        assert key in body, f"diagnostics missing field: {key}"
    assert body["sampleRate"] == 16000
    assert body["channels"] == 1
    assert body["pcmFormat"] == "int16"


def test_audio_signal_present_reflects_below_floor_rms(reset_state):
    # A below-floor currentRms with no history must read as NO signal.
    m.STATE._rms_history.clear()
    m.STATE.current_rms = 1e-6
    assert m.STATE.signal_present() is False
    # A live reading crosses the floor.
    m.STATE.current_rms = 0.05
    assert m.STATE.signal_present() is True


def test_diagnostics_audio_signal_present_false_when_silent(client):
    m.STATE._rms_history.clear()
    m.STATE.current_rms = 1e-6
    assert client.get("/diagnostics").json()["audioSignalPresent"] is False


def test_ready_false_when_nothing_started(client):
    assert client.get("/health").json()["ready"] is False
    assert client.get("/diagnostics").json()["ready"] is False


def test_ready_false_for_started_but_silent_mic(monkeypatch, reset_state):
    _mock_capture(monkeypatch)  # FakeStream yields int16 silence
    m.STATE.start("auto", "hey_jarvis", 0.5, "openwakeword")
    assert m.STATE.is_listening() is True
    # Listening but silent → NOT ready.
    assert m.STATE.signal_present() is False
    assert m.STATE.provider_ready() is False
    m.STATE.stop()


def test_expected_wake_phrase_derived_not_hardcoded():
    assert m.derive_wake_phrase("hey_jarvis_v0.1") == "hey jarvis"
    assert m.derive_wake_phrase("alexa_v0.1.onnx") == "alexa"
    assert m.derive_wake_phrase("") == "hey jarvis"


# ---------------------------------------------------------------------------
# /calibrate — RMS summary, no model
# ---------------------------------------------------------------------------

def test_calibrate_returns_rms_summary_shape(client, monkeypatch):
    import numpy as np

    monkeypatch.setattr(m, "resolve_device", lambda sel: dict(FAKE_DEVICE))
    # Loud, constant signal so signalDetected is unambiguous + shape is exercised.
    monkeypatch.setattr(
        m.STATE, "_capture_samples",
        lambda device_id, seconds: np.full(m.FRAME_SAMPLES * 3, 1000, dtype=np.int16),
    )
    r = client.post("/calibrate", json={"device": "fake-mic", "seconds": 1})
    assert r.status_code == 200
    body = r.json()
    for key in ("device", "frameCount", "minRms", "avgRms", "maxRms", "signalDetected"):
        assert key in body, f"calibrate missing field: {key}"
    assert body["frameCount"] == 3
    assert body["signalDetected"] is True
    assert body["maxRms"] > 0.0


def test_calibrate_reports_silence_as_no_signal(client, monkeypatch):
    import numpy as np

    monkeypatch.setattr(m, "resolve_device", lambda sel: dict(FAKE_DEVICE))
    monkeypatch.setattr(
        m.STATE, "_capture_samples",
        lambda device_id, seconds: np.zeros(m.FRAME_SAMPLES * 2, dtype=np.int16),
    )
    body = client.post("/calibrate", json={"device": "fake-mic", "seconds": 1}).json()
    assert body["signalDetected"] is False
    assert body["maxRms"] == 0.0


# ---------------------------------------------------------------------------
# /self-test — fixture -> real engine -> WAKE_DETECTED -> SSE
# ---------------------------------------------------------------------------

class _HighScoreEngine:
    """Deterministic engine that always crosses threshold (no hardware/TTS)."""

    provider = "openWakeWord"
    framework = "onnx"
    model_key = "hey_jarvis_v0.1"

    def loaded_models(self):
        return ["hey_jarvis_v0.1"]

    def process(self, frame):
        return 0.95


def _fixture_frames():
    import numpy as np

    return np.zeros(m.FRAME_SAMPLES * 6, dtype=np.int16)


def test_self_test_ok_increments_wake_count(client, monkeypatch):
    monkeypatch.setattr(m.STATE, "_build_engine", lambda e, mo: _HighScoreEngine())
    monkeypatch.setattr(m, "synth_wake_fixture",
                        lambda text=m.SELFTEST_WAKE_PHRASE: _fixture_frames())

    before = client.get("/diagnostics").json()["wakeDetectedCount"]
    r = client.post("/self-test")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["stage"] == "wake_emitted"
    assert body["maxScore"] >= body["threshold"]
    after = client.get("/diagnostics").json()["wakeDetectedCount"]
    assert after == before + 1


def test_self_test_without_tts_is_honest(client, monkeypatch):
    monkeypatch.setattr(m.STATE, "_build_engine", lambda e, mo: _HighScoreEngine())
    monkeypatch.setattr(m, "synth_wake_fixture", lambda text=m.SELFTEST_WAKE_PHRASE: None)
    body = client.post("/self-test").json()
    assert body["ok"] is False
    assert body["stage"] == "fixture"
    assert "no tts fixture" in body["message"].lower()


def test_self_test_below_threshold_reports_honest_failure(client, monkeypatch):
    class LowEngine(_HighScoreEngine):
        def process(self, frame):
            return 0.10

    monkeypatch.setattr(m.STATE, "_build_engine", lambda e, mo: LowEngine())
    monkeypatch.setattr(m, "synth_wake_fixture",
                        lambda text=m.SELFTEST_WAKE_PHRASE: _fixture_frames())
    body = client.post("/self-test").json()
    assert body["ok"] is False
    assert body["stage"] == "fixture_inference"
    assert body["maxScore"] < body["threshold"]


def test_self_test_emits_wake_over_sse(monkeypatch, reset_state):
    """A real SSE subscriber receives the WAKE_DETECTED that /self-test emits.

    Exercises the exact dispatch path /events uses — an asyncio.Queue in
    STATE.subscribers fed via dispatch_event -> call_soon_threadsafe -> _fanout
    — but on a loop we own, so it is deterministic and free of the TestClient
    single-portal streaming deadlock (a second request can't run while a stream
    is held). self_test runs on a worker thread, mirroring run_in_threadpool.
    """
    import asyncio

    monkeypatch.setattr(m.STATE, "_build_engine", lambda e, mo: _HighScoreEngine())
    monkeypatch.setattr(m, "synth_wake_fixture",
                        lambda text=m.SELFTEST_WAKE_PHRASE: _fixture_frames())

    async def scenario():
        m.STATE.loop = asyncio.get_running_loop()
        queue: asyncio.Queue = asyncio.Queue(maxsize=8)
        m.STATE.subscribers.add(queue)  # exactly what GET /events registers
        try:
            result = await asyncio.to_thread(m.STATE.self_test)
            event = await asyncio.wait_for(queue.get(), timeout=5.0)
            return result, event
        finally:
            m.STATE.subscribers.discard(queue)
            m.STATE.loop = None  # don't leave a closed loop dangling

    result, event = asyncio.run(scenario())

    assert result["ok"] is True
    assert result["stage"] == "wake_emitted"
    assert event["type"] == "WAKE_DETECTED"
    assert event["source"] == "self-test"
    assert event["score"] >= result["threshold"]
