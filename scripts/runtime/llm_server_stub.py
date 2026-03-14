#!/usr/bin/env python3

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def build_handler(capture_path: Path):
    class Handler(BaseHTTPRequestHandler):
        def _read_body(self) -> bytes:
            transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
            if "chunked" in transfer_encoding:
                chunks = []
                while True:
                    size_line = self.rfile.readline().strip()
                    if not size_line:
                        continue
                    size = int(size_line.split(b";", 1)[0], 16)
                    if size == 0:
                        while True:
                            trailer = self.rfile.readline()
                            if trailer in (b"", b"\r\n", b"\n"):
                                break
                        break
                    chunks.append(self.rfile.read(size))
                    self.rfile.read(2)
                return b"".join(chunks)

            content_length = int(self.headers.get("Content-Length", "0"))
            if content_length <= 0:
                return b""
            return self.rfile.read(content_length)

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            if self.path == "/health":
                self._send(200, {"status": "healthy", "model_loaded": True})
                return
            self._send(404, {"error": "not_found"})

        def do_POST(self) -> None:
            if self.path != "/api/v1/llm/chat":
                self._send(404, {"error": "not_found"})
                return

            raw = self._read_body()
            if not raw:
                self._send(400, {"error": "empty_body"})
                return

            try:
                payload = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                self._send(400, {"error": "invalid_json"})
                return

            capture_path.parent.mkdir(parents=True, exist_ok=True)
            with capture_path.open("a", encoding="utf-8") as capture:
                capture.write(json.dumps(payload, ensure_ascii=False) + "\n")

            self._send(
                200,
                {
                    "reply": "Стаб LLM подтвердил локальный запуск.",
                    "tokens": {"prompt": 1, "completion": 1, "total": 2},
                    "model": "runtime-smoke-stub",
                    "processingTimeMs": 1,
                    "emotion": "NEUTRAL",
                },
            )

        def log_message(self, format: str, *args) -> None:
            return

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="Local stub for Jarvis LLM server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--capture", required=True)
    args = parser.parse_args()

    capture_path = Path(args.capture)
    server = ThreadingHTTPServer((args.host, args.port), build_handler(capture_path))
    server.serve_forever()


if __name__ == "__main__":
    main()
