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


def _mock_capture(monkeypatch) -> FakeStream:
    """Wire STATE so start() runs with no real hardware. Returns the fake stream."""
    stream = FakeStream()
    monkeypatch.setattr(m, "resolve_device", lambda sel: dict(FAKE_DEVICE))
    monkeypatch.setattr(m.STATE, "_open_input_stream", lambda device_id: (stream, 16000, 1))
    monkeypatch.setattr(m.STATE, "_build_engine", lambda engine, model: FakeEngine())
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
    # Resolve a (fake) device so we reach engine build, but let the REAL engine
    # fail to find the bogus model — that must surface as a clean 422, not 500.
    monkeypatch.setattr(m, "resolve_device", lambda sel: dict(FAKE_DEVICE))
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
