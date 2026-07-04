#!/usr/bin/env python3
"""Localhost-only HTTP front-end for jarvis-host-bridge.sh.

Binds 127.0.0.1 only. If JARVIS_BRIDGE_TOKEN is set, every request must carry a
matching `X-Jarvis-Token` header. Each route shells to the bridge subcommand and
streams its JSON back. No arbitrary execution — only the fixed route table.

Routes:  GET /health · POST /screen-context · POST /voice-command
         POST /speak · POST /action
"""
import argparse
import json
import os
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BRIDGE = os.environ.get("JARVIS_BRIDGE", "scripts/jarvis-host-bridge.sh")
TOKEN = os.environ.get("JARVIS_BRIDGE_TOKEN", "")
ROUTES = {"/screen-context", "/voice-command", "/speak", "/action"}


def run_bridge(sub: str, body: bytes) -> bytes:
    args = ["bash", BRIDGE, sub]
    if body:
        args += ["--json", body.decode("utf-8", "replace")]
    out = subprocess.run(args, capture_output=True, timeout=180)
    return out.stdout or b'{"success":false,"error":"bridge_no_output"}'


class Handler(BaseHTTPRequestHandler):
    def _auth_ok(self):
        return not TOKEN or self.headers.get("X-Jarvis-Token", "") == TOKEN

    def _send(self, code, payload: bytes):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *a):  # quiet; never log bodies/tokens
        pass

    def do_GET(self):
        if self.path != "/health":
            return self._send(404, b'{"error":"not_found"}')
        if not self._auth_ok():
            return self._send(401, b'{"error":"unauthorized"}')
        self._send(200, run_bridge("health", b""))

    def do_POST(self):
        if self.path not in ROUTES:
            return self._send(404, b'{"error":"not_found"}')
        if not self._auth_ok():
            return self._send(401, b'{"error":"unauthorized"}')
        n = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(n) if n else b""
        try:
            self._send(200, run_bridge(self.path.lstrip("/"), body))
        except subprocess.TimeoutExpired:
            self._send(504, b'{"success":false,"error":"bridge_timeout"}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8770)
    args = ap.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(json.dumps({"status": "listening", "addr": f"127.0.0.1:{args.port}",
                      "token_required": bool(TOKEN)}), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
