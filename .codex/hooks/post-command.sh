#!/usr/bin/env bash
set -euo pipefail

if [[ ! -t 0 ]]; then
  cat >/dev/null || true
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"

if [[ -z "${repo_root}" ]]; then
  echo "Jarvis Codex post-command example: not inside a git repository; skipping." >&2
  exit 0
fi

echo "Jarvis Codex post-command example: git status summary from ${repo_root}" >&2
git -C "${repo_root}" status --short --untracked-files=all | sed -n '1,20p' >&2 || true
