#!/usr/bin/env bash
# =============================================================================
# jarvis-proactive-demo.sh — demonstrate Jarvis' proactive awareness safely.
#
# The host proactive loop periodically captures the screen, OCRs it, and asks the
# 14B brain whether anything is worth saying (usually SILENT). This wrapper runs a
# SINGLE observation tick so the owner can see the behaviour on demand.
#
# Modes (default = --dry-run, NO audio):
#   --dry-run    observe + reason + print the decision; never speak  (DEFAULT)
#   --quiet      alias for --dry-run
#   --speak-once observe and, if the brain decides to, speak ONE short line via TTS
#
# It never spams: --speak-once disables the anti-spam gap for this one tick only.
#
# To disable the always-on background loop entirely:
#   systemctl --user disable --now jarvis-proactive.service
# To (re)enable it:
#   systemctl --user enable --now jarvis-proactive.service
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MODE="dry-run"
case "${1:-}" in
  --speak-once) MODE="speak-once" ;;
  --dry-run|--quiet|"") MODE="dry-run" ;;
  -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
  *) echo "unknown arg: $1 (use --dry-run | --quiet | --speak-once)"; exit 2 ;;
esac

c(){ printf '\033[%sm%s\033[0m\n' "$1" "$2"; }

c '1;36' "== Jarvis proactive awareness demo (mode: $MODE) =="
echo "-- background loop service --"
ACT="$(systemctl --user is-active jarvis-proactive.service 2>/dev/null || echo unknown)"
ENB="$(systemctl --user is-enabled jarvis-proactive.service 2>/dev/null || echo unknown)"
echo "   jarvis-proactive.service: active=$ACT enabled=$ENB"
echo

if [ "$MODE" = "speak-once" ]; then
  c '1;33' "Running ONE observation tick (may speak a single short line)…"
  JARVIS_PROACTIVE_SPEAK=true JARVIS_PROACTIVE_MIN_GAP=0 bash "$ROOT/scripts/jarvis-proactive.sh" once
else
  c '1;33' "Running ONE observation tick (dry-run — it will NOT speak)…"
  JARVIS_PROACTIVE_SPEAK=false bash "$ROOT/scripts/jarvis-proactive.sh" once
fi

echo
c '1;32' "Done. The line after 'decision:' is what Jarvis observed and chose to say (or SILENT)."
[ "$MODE" = "dry-run" ] && echo "Tip: run with --speak-once to actually hear one line (needs speakers)."
