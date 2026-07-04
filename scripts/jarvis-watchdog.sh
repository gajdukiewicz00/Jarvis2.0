#!/usr/bin/env bash
# =============================================================================
# jarvis-watchdog.sh — unattended self-heal for the Jarvis k3s stack.
# Run by the jarvis-watchdog.timer (systemd --user) every few minutes.
#
# Heals the three things a cluster re-apply / drift can break, idempotently:
#   1. host-model-daemon endpoint reset (brain unreachable)        -> endpoint --fix
#   2. feature images drifting back to :local                      -> restore-deploy
#   3. orchestrator LLM reasoning flag flipped off                 -> set true
# Safe by design: only touches jarvis-prod, only known-good remediations, never
# git/DB/secrets, never enables HOST_DAEMON or ports 18081/18082.
# =============================================================================
set -uo pipefail
ROOT="/home/kwaqa/Jarvis/Jarvis2.0"; cd "$ROOT" || exit 0
K="sudo k3s kubectl -n jarvis-prod"
NODE_IP="10.113.0.176"
LOG="$HOME/.jarvis/logs/watchdog.log"; mkdir -p "$(dirname "$LOG")"
log(){ echo "$(date '+%F %T') $*" >>"$LOG"; }

# keep the log from growing unbounded
[ -f "$LOG" ] && [ "$(wc -l <"$LOG" 2>/dev/null || echo 0)" -gt 2000 ] && tail -800 "$LOG" >"$LOG.tmp" && mv "$LOG.tmp" "$LOG"

healed=0

# 1. brain endpoint (the recurring 192.0.2.1 reset)
EP="$($K get endpoints host-model-daemon -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)"
if [ "$EP" != "$NODE_IP" ]; then
  log "endpoint drift (got '$EP') -> jarvis-host-endpoint-check.sh --fix"
  bash scripts/jarvis-host-endpoint-check.sh --fix >>"$LOG" 2>&1 && healed=1
fi

# 2. feature image drift -> ALERT ONLY (do NOT auto-mass-redeploy unattended).
# A mass restore is a deliberate, human-reviewed action: run `jarvis-restore-deploy.sh`
# manually, or let the nightly maintenance job handle it. The watchdog only flags it.
VG="$($K get deploy voice-gateway -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
if printf '%s' "$VG" | grep -q ':local$'; then
  log "ALERT image drift ($VG) — run scripts/jarvis-restore-deploy.sh to restore features"
  command -v notify-send >/dev/null 2>&1 && notify-send "Jarvis watchdog" "Feature images drifted to :local — run jarvis-restore-deploy.sh" || true
fi

# 3. orchestrator LLM reasoning flag
OE="$($K get deploy orchestrator -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value} {end}' 2>/dev/null)"
if ! printf '%s' "$OE" | grep -q 'JARVIS_LLM_ENABLED=true'; then
  log "orchestrator JARVIS_LLM_ENABLED not true -> set true"
  $K set env deploy/orchestrator JARVIS_LLM_ENABLED=true >>"$LOG" 2>&1 && healed=1
fi

[ "$healed" = 1 ] && log "watchdog: healed something (ep=$EP vg=$VG)" || log "watchdog: ok (ep=$EP)"
exit 0
