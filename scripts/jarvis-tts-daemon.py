#!/usr/bin/env python3
"""
jarvis-tts-daemon.py — host-side Piper neural TTS HTTP daemon.

Mirrors the host-model-daemon pattern: a small local HTTP service the k3s cluster
reaches via a selectorless Service. voice-gateway POSTs text, gets back a WAV.

Endpoints:
  GET  /health                 -> {"status":"ok"}
  POST /synthesize             body {"text": "...", "language": "ru-RU"|"en-GB"...}
                               -> audio/wav (Piper neural voice; Cyrillic auto-uses RU voice)

100% local. Voices from ~/.jarvis/models/tts (en_GB-alan-medium / ru_RU-dmitri-medium).
Env:
  JARVIS_TTS_PORT       default 18090
  JARVIS_PIPER_BIN      default ~/piper/piper
  JARVIS_PIPER_VOICE_EN default ~/.jarvis/models/tts/en_GB-alan-medium.onnx
  JARVIS_PIPER_VOICE_RU default ~/.jarvis/models/tts/ru_RU-dmitri-medium.onnx
"""
import os, io, json, wave, subprocess, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
PORT = int(os.environ.get("JARVIS_TTS_PORT", "18090"))
PIPER_BIN = os.environ.get("JARVIS_PIPER_BIN", f"{HOME}/piper/piper")
VOICE_EN = os.environ.get("JARVIS_PIPER_VOICE_EN", f"{HOME}/.jarvis/models/tts/en_GB-alan-medium.onnx")
VOICE_RU = os.environ.get("JARVIS_PIPER_VOICE_RU", f"{HOME}/.jarvis/models/tts/ru_RU-dmitri-medium.onnx")
# Speech pace: >1 slower, <1 faster. Overridable per-request via JSON "speed".
LENGTH_SCALE = float(os.environ.get("JARVIS_PIPER_LENGTH_SCALE", "1.0"))
SAMPLE_RATE = 22050  # Piper medium voices

CYRILLIC = re.compile(r"[Ѐ-ӿ]")


def pick_voice(text: str, language: str) -> str:
    lang = (language or "").lower()
    if lang.startswith("ru") or CYRILLIC.search(text or ""):
        if os.path.exists(VOICE_RU):
            return VOICE_RU
    return VOICE_EN


def synthesize(text: str, language: str, length_scale: float = LENGTH_SCALE) -> bytes:
    voice = pick_voice(text, language)
    proc = subprocess.run(
        [PIPER_BIN, "--model", voice, "--length_scale", str(length_scale), "--output-raw"],
        input=text.encode("utf-8"),
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30,
    )
    pcm = proc.stdout
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm)
    return buf.getvalue()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", "/healthz", ""):
            self._send(200, json.dumps({"status": "ok", "engine": "piper"}).encode())
        else:
            self._send(404, b'{"error":"not_found"}')

    def do_POST(self):
        if self.path.rstrip("/") not in ("/synthesize", "/api/tts", "/v1/tts"):
            self._send(404, b'{"error":"not_found"}')
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(n) or b"{}")
            text = (data.get("text") or "").strip()
            language = data.get("language") or data.get("languageCode") or ""
            # Optional per-request pace: "length_scale" (>1 slower) or "speed" (>1 faster).
            length_scale = LENGTH_SCALE
            if data.get("length_scale") is not None:
                length_scale = float(data["length_scale"])
            elif data.get("speed"):
                length_scale = 1.0 / float(data["speed"])
            if not text:
                self._send(400, b'{"error":"empty_text"}')
                return
            wav = synthesize(text, language, length_scale)
            self._send(200, wav, ctype="audio/wav")
        except Exception as e:
            self._send(500, json.dumps({"error": type(e).__name__, "detail": str(e)[:200]}).encode())

    def log_message(self, *a):  # quiet
        pass


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"jarvis-tts-daemon (Piper) listening on 0.0.0.0:{PORT}", flush=True)
    srv.serve_forever()
