#!/usr/bin/env bash
# =============================================================================
# jarvis-final-check.sh — one-shot end-to-end verification of the live Jarvis
# stack with a single PASS/FAIL summary.
#
# READ-ONLY BY DEFAULT. The only mutation any check performs is the existing
# smoke behaviour (a throwaway Obsidian note), and only if you opt into the
# obsidian smoke. Nothing here patches the cluster unless you pass --repair.
#
# --repair : before the checks, run the documented endpoint repair
#            (scripts/jarvis-host-endpoint-check.sh --fix) to recover from the
#            recurring host-model-daemon 192.0.2.1 placeholder reset. This is
#            the ONLY mutating action and it is opt-in.
#
# Checks:
#   1. ./jarvis health
#   2. ./jarvis doctor            (read-only diagnostics)
#   3. host-model-daemon endpoint (read-only; placeholder guard)
#   4. llm chat                   (14B brain via host-model-daemon)
#   5. voice diagnostics          (STT/TTS/WebSocket readiness)
#   6. voice session intent       ("сделай тише" -> volume_down)
#   7. desktop dry-run            (safe pc-control read + orchestrator intent)
#   8. obsidian unit tests
#   9. android dry-run            (server-side, no phone)
#
# Exit 0 only if every check passes.
#
# Usage:
#   scripts/jarvis-final-check.sh            # read-only verification
#   scripts/jarvis-final-check.sh --repair   # repair endpoint first, then verify
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GW="${JARVIS_API_BASE:-https://10.113.0.176}"
HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"
USER_PASS="${JARVIS_TEST_PASS:-test1111}"

REPAIR=false
[[ "${1:-}" == "--repair" ]] && REPAIR=true

pass=0; fail=0
ok(){ printf '  \033[0;32m[PASS]\033[0m %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  \033[0;31m[FAIL]\033[0m %s\n' "$1"; fail=$((fail+1)); }
hint(){ printf '         \033[0;33m%s\033[0m\n' "$1"; }
step(){ printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

echo "== Jarvis final check (repair=$REPAIR) =="

# --- optional repair (the ONLY mutating path) --------------------------------
if $REPAIR; then
  step "Repair: host-model-daemon endpoint"
  if "$ROOT/scripts/jarvis-host-endpoint-check.sh" --fix; then
    echo "  repair: endpoint re-applied"
  else
    echo "  repair: endpoint fix reported a problem (continuing to checks)"
  fi
fi

# --- token -------------------------------------------------------------------
TOKEN="$(curl -sk -m10 -H "Host: $HOST" "$GW/api/v1/security/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
A=(-sk -m20 -H "Host: $HOST" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
[ -n "$TOKEN" ] && ok "auth token acquired" || no "auth token (login failed)"

# 1. stack health
step "1. jarvis health"
"$ROOT/jarvis" health >/dev/null 2>&1 && ok "jarvis health READY" || no "jarvis health"

# 2. jarvis doctor (read-only)
step "2. jarvis doctor (read-only)"
"$ROOT/jarvis" doctor >/dev/null 2>&1 && ok "jarvis doctor clean" \
  || { no "jarvis doctor reported warnings/errors"; hint "run ./jarvis doctor for detail"; }

# 3. host-model-daemon endpoint (read-only guard)
step "3. host-model-daemon endpoint"
if "$ROOT/scripts/jarvis-host-endpoint-check.sh" >/dev/null 2>&1; then
  ok "host-model-daemon endpoint wired + reachable"
else
  no "host-model-daemon endpoint = PLACEHOLDER (14B brain unreachable)"
  hint "fix: ./scripts/jarvis-final-check.sh --repair   (or ./scripts/jarvis-host-endpoint-check.sh --fix)"
fi

# 4. llm chat (14B brain)
step "4. llm chat (14B brain)"
MODEL="$(curl "${A[@]}" -m45 -X POST "$GW/api/v1/llm/chat" \
  -d '{"sessionId":"final-check","messages":[{"role":"user","content":"ответь одним словом: жив /no_think"}]}' 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("model",""))' 2>/dev/null)"
[ -n "$MODEL" ] && ok "llm chat model=$MODEL" || { no "llm chat (no model in reply)"; hint "likely the endpoint placeholder — run with --repair"; }

# 5. voice diagnostics
step "5. voice diagnostics"
VD="$(curl "${A[@]}" "$GW/api/v1/voice/diagnostics" 2>/dev/null \
  | python3 -c 'import json,sys
d=json.load(sys.stdin)
print(d["stt"]["reasonCode"], d["tts"]["reasonCode"], d["websocket"]["reasonCode"])' 2>/dev/null)"
[ "$VD" = "STT_READY TTS_READY WEBSOCKET_READY" ] && ok "voice diagnostics ($VD)" || no "voice diagnostics ($VD)"

# 6. voice session intent ("сделай тише" -> volume_down)
# NOTE: post-auth-fix the synchronous utterance call proceeds into the orchestrator
# dispatch, where risk=MEDIUM actions wait on the confirmation gate; the gateway
# returns 504 before the body comes back. The resolved intent is therefore verified
# from the voice-gateway log (the reliable signal), not the timing-out HTTP body.
step "6. voice session intent"
# Resolve a kubectl that can actually read the (often root-owned) kubeconfig,
# mirroring ./jarvis resolve_kubectl — bare kubectl may exist but lack access.
if kubectl -n jarvis-prod get ns >/dev/null 2>&1; then KCTL="kubectl"; else KCTL="sudo k3s kubectl"; fi
SID="$(curl "${A[@]}" -X POST "$GW/api/v1/voice/sessions" \
  -d '{"agentId":"final-check","userId":"'"$USER_LOGIN"'"}' 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("sessionId",""))' 2>/dev/null)"
# Fire the utterance; tolerate the expected gateway timeout. The voice-gateway
# only logs "utterance done" AFTER the orchestrator confirmation-gate wait
# (~25s), so poll the log for this session's resolved intent.
curl "${A[@]}" -m30 -X POST "$GW/api/v1/voice/sessions/$SID/utterance" \
  -d '{"transcript":"сделай тише","locale":"ru-RU"}' -o /dev/null 2>/dev/null || true
INTENT=""
for _ in $(seq 1 12); do
  INTENT="$($KCTL -n jarvis-prod logs deploy/voice-gateway --since=120s 2>/dev/null \
    | grep -F "[$SID] utterance done" | grep -oE 'intent=[a-z_]+' | head -1 | cut -d= -f2)"
  [ -n "$INTENT" ] && break
  sleep 3
done
curl "${A[@]}" -X POST "$GW/api/v1/voice/sessions/$SID/end" -o /dev/null 2>/dev/null || true
# Count real HTTP 403s (auth), not the substring "403" inside UUIDs/sizes.
NO403="$($KCTL -n jarvis-prod logs deploy/voice-gateway --since=120s 2>/dev/null | grep -cE '403 :|"status":403|Forbidden' || true)"
if [ "$INTENT" = "volume_down" ]; then
  ok "voice intent: сделай тише -> volume_down (no UNKNOWN_INTENT; 403s in window=$NO403)"
else
  no "voice intent (got='$INTENT' want=volume_down)"
  hint "check: $KCTL -n jarvis-prod logs deploy/voice-gateway | grep 'utterance done'"
fi

# 7. desktop dry-run (safe)
step "7. desktop dry-run"
if env -u JARVIS_SMOKE_TOKEN JARVIS_GATEWAY_URL="$GW" \
     JARVIS_SMOKE_USER="$USER_LOGIN" JARVIS_SMOKE_PASS="$USER_PASS" \
     "$ROOT/scripts/e2e-desktop-dry-run.sh" 2>&1 | grep -q 'E2E scenario PASSED'; then
  ok "desktop dry-run PASSED"
else
  no "desktop dry-run"
fi

# 8. obsidian unit tests
step "8. obsidian unit tests"
python3 "$ROOT/scripts/tests/test_jarvis_obsidian.py" >/dev/null 2>&1 \
  && ok "obsidian unit tests" || no "obsidian unit tests"

# 9. android dry-run (server-side)
step "9. android dry-run"
"$ROOT/scripts/jarvis-android-setup.sh" --dry-run >/dev/null 2>&1 \
  && ok "android dry-run (server-side ready)" || no "android dry-run"

# --- summary -----------------------------------------------------------------
printf '\n\033[1m== final-check result: %d passed, %d failed ==\033[0m\n' "$pass" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "If the only failures are host-model-daemon / llm chat, re-run with --repair."
fi
[ "$fail" -eq 0 ]
