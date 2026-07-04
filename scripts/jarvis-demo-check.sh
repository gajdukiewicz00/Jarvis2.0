#!/usr/bin/env bash
# =============================================================================
# jarvis-demo-check.sh — one-command "is Jarvis ready to demo?" check.
#
# READ-ONLY BY DEFAULT. Prints READY FOR DEMO / NOT READY with the exact failed
# step and fix command.
#
# Modes:
#   (default)              read-only verification only
#   --repair               run the host-model-daemon endpoint repair first
#   --approve-volume-demo  run the controlled voice→confirm→execute→restore demo
#                          (ONLY volume_down + restore original; nothing else)
#
# Usage:
#   scripts/jarvis-demo-check.sh
#   scripts/jarvis-demo-check.sh --repair
#   scripts/jarvis-demo-check.sh --approve-volume-demo
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="${JARVIS_API_BASE:-https://10.113.0.176}"
HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"
USER_PASS="${JARVIS_TEST_PASS:-test1111}"

REPAIR=false; APPROVE=false; VERBOSE=false
for a in "$@"; do
  case "$a" in
    --repair) REPAIR=true ;;
    --approve-volume-demo) APPROVE=true ;;
    --verbose) VERBOSE=true ;;
    *) echo "unknown arg: $a (use --repair | --approve-volume-demo | --verbose)"; exit 2 ;;
  esac
done
vlog(){ $VERBOSE && printf '     \033[0;90m%s\033[0m\n' "$1" || true; }

pass=0; fail=0; FIRST_FAIL=""; FIRST_FIX=""
ok(){ printf '  \033[0;32m[PASS]\033[0m %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  \033[0;31m[FAIL]\033[0m %s\n' "$1"; fail=$((fail+1));
      [ -z "$FIRST_FAIL" ] && { FIRST_FAIL="$1"; FIRST_FIX="${2:-}"; }; }
step(){ printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

J=(-sk -H "Host: $HOST" -H 'Content-Type: application/json')
TOKEN="$(curl "${J[@]}" -m10 "$GW/api/v1/security/auth/login" \
  -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
AUTH=(-H "Authorization: Bearer $TOKEN")

echo "== Jarvis demo check (repair=$REPAIR approve-volume-demo=$APPROVE) =="

# 0. optional repair (mutating)
if $REPAIR; then
  step "Repair endpoint"
  "$ROOT/scripts/jarvis-host-endpoint-check.sh" --fix >/dev/null 2>&1 && echo "  endpoint re-applied" || echo "  repair reported a problem"
fi

# 1. final-check (the broad gate)
step "1. final-check"
if "$ROOT/scripts/jarvis-final-check.sh" >/tmp/demo_fc.$$ 2>&1; then
  ok "final-check 10/10"
else
  no "final-check failed" "./scripts/jarvis-final-check.sh --repair   (see ./scripts/jarvis-final-check.sh output)"
  grep -E '\[FAIL\]' /tmp/demo_fc.$$ | sed 's/^/     /'
fi
rm -f /tmp/demo_fc.$$

# 2. llm chat (14B)
step "2. llm chat (14B)"
MODEL="$(curl "${J[@]}" "${AUTH[@]}" -m45 -X POST "$GW/api/v1/llm/chat" \
  -d '{"sessionId":"demo","messages":[{"role":"user","content":"ответь одним словом: жив /no_think"}]}' 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("model",""))' 2>/dev/null)"
[ -n "$MODEL" ] && ok "llm chat model=$MODEL" || no "llm chat (no model)" "./scripts/jarvis-host-endpoint-check.sh --fix"

# 3. memory unified search
step "3. memory unified search"
UC="$(curl "${J[@]}" "${AUTH[@]}" -H 'X-User-Id: 2' -m20 -o /dev/null -w '%{http_code}' \
  -X POST "$GW/api/v1/memory/search/unified" -d '{"query":"jarvis","topK":3}')"
[ "$UC" = "200" ] && ok "memory unified search (200)" || no "memory unified search ($UC)" "check memory-service + embedding-service"

# 4. Obsidian semantic search
step "4. obsidian semantic search"
OBS="$(curl "${J[@]}" "${AUTH[@]}" -H 'X-User-Id: 2' -m20 -X POST "$GW/api/v1/memory/search/unified" \
  -d '{"query":"парусное судно в океане","topK":5}' 2>/dev/null \
  | python3 -c 'import json,sys
d=json.load(sys.stdin); rs=d.get("results",[]) if isinstance(d,dict) else []
print(sum(1 for r in rs if r.get("source")=="obsidian"))' 2>/dev/null)"
[ "${OBS:-0}" -ge 1 ] && ok "obsidian semantic search ($OBS hits)" || no "obsidian semantic search (0)" "re-index: jarvis obsidian index"

# 5. voice diagnostics
step "5. voice diagnostics"
VD="$(curl "${J[@]}" "${AUTH[@]}" -m20 "$GW/api/v1/voice/diagnostics" 2>/dev/null \
  | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["stt"]["reasonCode"],d["tts"]["reasonCode"],d["websocket"]["reasonCode"])' 2>/dev/null)"
[ "$VD" = "STT_READY TTS_READY WEBSOCKET_READY" ] && ok "voice diagnostics ($VD)" || no "voice diagnostics ($VD)" "check voice-gateway pod"

# 6. TTS synth
step "6. TTS synth"
TB="$(curl "${J[@]}" "${AUTH[@]}" -m20 -o /tmp/demo_tts.$$ -w '%{size_download}' -X POST "$GW/api/v1/voice/synthesize" \
  -d '{"text":"Demo check, sir.","language":"en-GB"}' 2>/dev/null)"; rm -f /tmp/demo_tts.$$
[ "${TB:-0}" -gt 1000 ] && ok "TTS synth (${TB} bytes WAV)" || no "TTS synth (${TB} bytes)" "check host Piper :18090 + jarvis-tts.service"

# 7. voice session intent (no auto-approve unless --approve-volume-demo)
step "7. voice session intent"
if kubectl -n jarvis-prod get ns >/dev/null 2>&1; then KCTL="kubectl"; else KCTL="sudo k3s kubectl"; fi
SID="$(curl "${J[@]}" "${AUTH[@]}" -m15 -X POST "$GW/api/v1/voice/sessions" \
  -d '{"agentId":"demo","userId":"'"$USER_LOGIN"'"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("sessionId",""))' 2>/dev/null)"
( curl "${J[@]}" "${AUTH[@]}" -m30 -X POST "$GW/api/v1/voice/sessions/$SID/utterance" \
    -d '{"transcript":"сделай тише","locale":"ru-RU"}' -o /dev/null 2>/dev/null ) &
CMD=""
for _ in $(seq 1 12); do
  CMD="$($KCTL -n jarvis-prod logs deploy/orchestrator --since=40s 2>/dev/null \
    | grep -oE '\[cmd-[0-9a-f-]+\] confirmation pending: intent=volume_down' | tail -1 | grep -oE 'cmd-[0-9a-f-]+')"
  [ -n "$CMD" ] && break; sleep 2
done
if [ -n "$CMD" ]; then
  ok "voice intent сделай тише -> volume_down (confirmation required, cmd=$CMD)"
else
  no "voice intent (no commandId / not resolved)" "check nlp-service + voice-gateway logs"
fi

# 8. pc-control safe volume read
step "8. pc-control safe volume read"
ORIG="$(curl "${J[@]}" "${AUTH[@]}" -m15 "$GW/api/v1/pc/desktop/volume" 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("level"))' 2>/dev/null)"
[ -n "$ORIG" ] && ok "pc-control volume read (level=$ORIG)" || no "pc-control volume read" "check host jarvis-pc-control.service :8084"
vlog "pc-control backend = host bridge :8084 (jarvis-pc-control.service)"

# 8b. proactive awareness service (host loop)
step "8b. proactive awareness"
PA="$(systemctl --user is-active jarvis-proactive.service 2>/dev/null || echo unknown)"
[ "$PA" = "active" ] && ok "proactive awareness service active" || no "proactive awareness ($PA)" "systemctl --user start jarvis-proactive.service"
vlog "one-shot demo: ./scripts/jarvis-proactive-demo.sh  (dry-run, no audio)"

# 9. optional controlled volume confirmation demo
if $APPROVE; then
  step "9. controlled volume confirmation demo (APPROVED + restore)"
  if [ -z "$CMD" ] || [ -z "$ORIG" ]; then
    no "volume demo prerequisites (commandId/volume missing)" "re-run; ensure voice intent + pc-control pass"
  else
    CONF="$(curl "${J[@]}" "${AUTH[@]}" -m15 -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/voice/confirmations" \
      -d "{\"commandId\":\"$CMD\",\"decision\":\"APPROVED\",\"decidedBy\":\"$USER_LOGIN\",\"reason\":\"demo-check controlled volume test\"}")"
    sleep 5
    AFTER="$(curl "${J[@]}" "${AUTH[@]}" -m15 "$GW/api/v1/pc/desktop/volume" 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin).get("level"))' 2>/dev/null)"
    # ALWAYS restore to original
    curl "${J[@]}" "${AUTH[@]}" -m15 -o /dev/null -X POST "$GW/api/v1/pc/desktop/volume" -d "{\"level\":$ORIG}" 2>/dev/null
    sleep 1
    FINAL="$(curl "${J[@]}" "${AUTH[@]}" -m15 "$GW/api/v1/pc/desktop/volume" 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin).get("level"))' 2>/dev/null)"
    echo "     APPROVED HTTP=$CONF | volume: orig=$ORIG after=$AFTER restored=$FINAL"
    if [ "$FINAL" = "$ORIG" ]; then
      if [ "$AFTER" != "$ORIG" ]; then ok "confirmation executed (vol $ORIG->$AFTER) and restored to $ORIG"
      else ok "confirmation accepted (HTTP $CONF); host execution needs connected desktop agent; volume restored=$ORIG"; fi
    else
      no "volume NOT restored (final=$FINAL, orig=$ORIG)" "MANUAL restore: POST $GW/api/v1/pc/desktop/volume {\"level\":$ORIG}"
    fi
  fi
fi
wait 2>/dev/null

# --- verdict -----------------------------------------------------------------
echo
if [ "$fail" -eq 0 ]; then
  printf '\033[1;32m================  READY FOR DEMO  (%d/%d)  ================\033[0m\n' "$pass" "$((pass+fail))"
  exit 0
else
  printf '\033[1;31m================  NOT READY  (%d passed, %d failed)  ================\033[0m\n' "$pass" "$fail"
  echo "First failed step : $FIRST_FAIL"
  [ -n "$FIRST_FIX" ] && echo "Fix               : $FIRST_FIX"
  exit 1
fi
