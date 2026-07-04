#!/usr/bin/env bash
# =============================================================================
# jarvis-human-layer-check.sh — one-command "is Jarvis ready for a human-layer
# demo?" aggregator. Rolls up Track A (local runtime) and Track B (k3s
# jarvis-prod) checks into a single PASS/FAIL/SKIP tally.
#
# READ-ONLY: every step this script runs is itself read-only/non-destructive
# by its own contract. This script never applies, patches, deletes, restarts,
# or starts anything — it only observes and reports. See
# docs/HUMAN_LAYER_DEMO_RUNBOOK.md for how to actually bring a track up.
#
# Runs:
#   1. scripts/runtime-status.sh (Track A, if a local runtime env exists)
#      or ./jarvis health (Track B convenience verdict) otherwise.
#   2. scripts/jarvis-voice-daemon-check.sh — host voice toolchain
#      (Qwen health, TTS, Vosk STT, Porcupine wake, mic, VAD).
#   3. scripts/e2e-desktop-dry-run.sh — safe pc-control read + benign
#      orchestrator intent (no window opens; exits 0 on PASS or on SKIP).
#   4. scripts/jarvis-final-check.sh — ONLY if the k3s cluster actually
#      answers `get ns jarvis-prod`; otherwise reported as SKIPPED (a down
#      cluster on this machine is an expected, non-fatal state for Track A).
#
# Usage: scripts/jarvis-human-layer-check.sh
# Exit: 0 if every step that RAN passed (skips do not count as failures)
#       1 if any step that ran failed
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

pass=0; fail=0; skip=0
ok()      { printf '  \033[0;32m[PASS]\033[0m %s\n' "$1"; pass=$((pass + 1)); }
no()      { printf '  \033[0;31m[FAIL]\033[0m %s\n' "$1"; fail=$((fail + 1)); }
skipped() { printf '  \033[1;33m[SKIP]\033[0m %s\n' "$1"; skip=$((skip + 1)); }
step()    { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

echo "== Jarvis human-layer readiness check (read-only) =="

# --- helper: is the k3s cluster reachable? (short timeout, never hangs on a
# sudo password prompt or a dead apiserver) ----------------------------------
k3s_reachable() {
  local kctl
  for kctl in "kubectl" "sudo -n k3s kubectl"; do
    if timeout 5 $kctl -n jarvis-prod get ns >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

# --- 1. runtime status snapshot (Track A or Track B, whichever applies) ----
step "1. Runtime status snapshot"
if [[ -f "${HOME}/.jarvis/run/local-runtime/local.env" ]]; then
  echo "  Track A (local runtime) detected — scripts/runtime-status.sh"
  OUT="$("${ROOT}/scripts/runtime-status.sh" 2>&1)"; RC=$?
  echo "${OUT}" | sed 's/^/         /'
  READY_COUNT="$(grep -c 'health=ready' <<<"${OUT}" || true)"
  if [[ ${RC} -eq 0 ]]; then
    ok "runtime-status.sh ran (${READY_COUNT} service(s) health=ready)"
  else
    no "runtime-status.sh exited ${RC}"
  fi
else
  echo "  No local runtime env found — falling back to Track B: ./jarvis health"
  OUT="$("${ROOT}/jarvis" health 2>&1)"
  echo "${OUT}" | sed 's/^/         /'
  if grep -q 'READY' <<<"${OUT}"; then
    ok "jarvis health: READY"
  elif grep -q 'DEGRADED' <<<"${OUT}"; then
    no "jarvis health: DEGRADED"
    echo "         fix: ./scripts/jarvis-host-endpoint-check.sh --fix"
  else
    no "jarvis health: FAILED / cluster unreachable"
    echo "         fix (Track A, no cluster needed): ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh"
  fi
fi

# --- 2. voice toolchain ------------------------------------------------------
step "2. Voice toolchain (scripts/jarvis-voice-daemon-check.sh)"
if "${ROOT}/scripts/jarvis-voice-daemon-check.sh"; then
  ok "voice daemon check: required components OK"
else
  no "voice daemon check: required component(s) failed (see output above)"
fi

# --- 3. desktop safe-action dry run -----------------------------------------
step "3. Desktop safe-action dry run (scripts/e2e-desktop-dry-run.sh)"
DESKTOP_OUT="$("${ROOT}/scripts/e2e-desktop-dry-run.sh" 2>&1)"; DESKTOP_RC=$?
echo "${DESKTOP_OUT}" | sed 's/^/         /'
if [[ ${DESKTOP_RC} -ne 0 ]]; then
  no "desktop dry-run FAILED"
elif grep -q 'E2E scenario PASSED' <<<"${DESKTOP_OUT}"; then
  ok "desktop dry-run PASSED"
else
  skipped "desktop dry-run completed with skips (likely no gateway/token reachable)"
fi

# --- 4. Track B full verification (only if the cluster is actually up) -----
step "4. Track B full verification (scripts/jarvis-final-check.sh)"
if k3s_reachable; then
  if "${ROOT}/scripts/jarvis-final-check.sh"; then
    ok "jarvis-final-check.sh: all checks passed"
  else
    no "jarvis-final-check.sh: one or more checks failed (see FAIL lines above)"
  fi
else
  skipped "k3s jarvis-prod unreachable — Track B checks skipped (cluster is down or this host has no cluster access; recover with scripts/product/jarvis-recover-after-reboot.sh)"
fi

# --- tally -------------------------------------------------------------------
printf '\n\033[1m== human-layer check: %d passed, %d failed, %d skipped ==\033[0m\n' "${pass}" "${fail}" "${skip}"
if [[ ${fail} -ne 0 ]]; then
  echo "See docs/HUMAN_LAYER_DEMO_RUNBOOK.md §4 (Troubleshooting) for fixes matching the FAIL lines above."
fi
[[ ${fail} -eq 0 ]]
