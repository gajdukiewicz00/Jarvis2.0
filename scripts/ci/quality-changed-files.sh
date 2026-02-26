#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

RUNTIME_PATH_REGEX='^apps/(api-gateway|security-service|planner-service|orchestrator|pc-control)/src/main/java/.*\.java$'
BASE_REF="${QUALITY_BASE_REF:-origin/main}"
RANGE_DIFF=""
SKIP_TESTS="${QUALITY_SKIP_TESTS:-true}"

if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  BASE_REF="HEAD~1"
fi

if git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  RANGE_DIFF="$(git diff --name-only --diff-filter=AM "${BASE_REF}...HEAD" || true)"
fi

mapfile -t CHANGED_RUNTIME_FILES < <(
  {
    printf '%s\n' "$RANGE_DIFF"
    git diff --name-only --diff-filter=AM HEAD || true
    git diff --name-only --cached --diff-filter=AM || true
  } |
    sort -u |
    rg "$RUNTIME_PATH_REGEX" || true
)

if ((${#CHANGED_RUNTIME_FILES[@]} == 0)); then
  INCLUDES='**/__quality_no_files__.java'
  echo "[quality] No changed runtime Java files found in range/worktree"
else
  INCLUDES="$(printf '%s\n' "${CHANGED_RUNTIME_FILES[@]}" |
    sed -E 's#^apps/[^/]+/src/main/java/##' |
    sort -u |
    paste -sd, -)"
  echo "[quality] Runtime files in scope (${#CHANGED_RUNTIME_FILES[@]}):"
  printf ' - %s\n' "${CHANGED_RUNTIME_FILES[@]}"
fi

mvn -q -Pci-quality -DskipTests="$SKIP_TESTS" -Dquality.includes="$INCLUDES" verify
