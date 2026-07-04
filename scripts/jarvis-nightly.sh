#!/usr/bin/env bash
# =============================================================================
# jarvis-nightly.sh — unattended nightly maintenance for the Jarvis stack.
# Designed to run from SYSTEM cron (no Claude / no human needed):
#   7 4 * * *  /usr/bin/env bash /home/kwaqa/Jarvis/Jarvis2.0/scripts/jarvis-nightly.sh
#
# Deterministic + safe: heals + verifies, restores feature images only if they
# drifted to :local. Never rebuilds, never touches git/DB/secrets, never enables
# HOST_DAEMON or ports 18081/18082. Writes a dated report you can read in the morning.
# =============================================================================
set -uo pipefail
ROOT="/home/kwaqa/Jarvis/Jarvis2.0"; cd "$ROOT" || exit 1
K="sudo k3s kubectl -n jarvis-prod"
DAY="$(date +%Y%m%d)"
LOG="$HOME/.jarvis/logs/nightly-$DAY.log"; mkdir -p "$(dirname "$LOG")"
exec >>"$LOG" 2>&1
echo "==================== nightly $(date '+%F %T') ===================="

# 1. heal brain endpoint + llm env, and run the authoritative 10-check
echo "-- final-check --repair --"
bash scripts/jarvis-final-check.sh --repair 2>&1 | sed -r 's/\x1B\[[0-9;]*[mK]//g' | grep -E 'result:|\[FAIL\]'

# 2. restore feature images if they drifted back to :local
VG="$($K get deploy voice-gateway -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
if printf '%s' "$VG" | grep -q ':local$'; then
  echo "-- image drift ($VG) -> restore-deploy --"
  bash scripts/jarvis-restore-deploy.sh 2>&1 | sed -r 's/\x1B\[[0-9;]*[mK]//g' | grep -E 'result|->|brain model'
else
  echo "-- images OK ($VG) --"
fi

# 3. orchestrator LLM reasoning flag
OE="$($K get deploy orchestrator -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value} {end}' 2>/dev/null)"
printf '%s' "$OE" | grep -q 'JARVIS_LLM_ENABLED=true' || { echo "-- enabling orchestrator LLM --"; $K set env deploy/orchestrator JARVIS_LLM_ENABLED=true; }

# 4. final verdict
echo "-- smoke + demo --"
bash scripts/jarvis-smoke-verify.sh 2>&1 | sed -r 's/\x1B\[[0-9;]*[mK]//g' | grep -E 'result'
bash scripts/jarvis-demo-check.sh 2>&1 | sed -r 's/\x1B\[[0-9;]*[mK]//g' | grep -iE 'READY FOR DEMO|NOT READY' | head -1
echo "==================== nightly done $(date '+%F %T') ===================="

# keep only the last 14 nightly reports
ls -1t "$HOME/.jarvis/logs"/nightly-*.log 2>/dev/null | tail -n +15 | xargs -r rm -f
