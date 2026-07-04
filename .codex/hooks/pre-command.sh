#!/usr/bin/env bash
set -euo pipefail

if [[ ! -t 0 ]]; then
  cat >/dev/null || true
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"

if [[ -z "${repo_root}" ]]; then
  echo "Jarvis Codex pre-command example: not inside a git repository; skipping." >&2
  exit 0
fi

if [[ "${PWD}" == "/" ]]; then
  echo "Jarvis Codex pre-command example: refusing to operate from /." >&2
  exit 0
fi

if [[ ! -f "${repo_root}/README.md" ]]; then
  echo "Jarvis Codex pre-command example: repo root missing README.md; skipping." >&2
  exit 0
fi

echo "Jarvis Codex pre-command example: repo looks valid (${repo_root})." >&2
