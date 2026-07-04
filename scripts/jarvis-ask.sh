#!/usr/bin/env bash
# =============================================================================
# jarvis-ask.sh — talk to the 14B brain from the terminal (with RAG memory).
#   US-039 "analyze old notes", US-102 "months-long context", US-101 persona.
#
#   scripts/jarvis-ask.sh "что ты помнишь про кофе?"
#   scripts/jarvis-ask.sh --speak "напомни, над чем я работаю"
#
# Flags:
#   --speak   synthesize the answer via Piper and play it (needs speakers)
#   --quiet   text only (default)
# Uses the live gateway + /api/v1/llm/chat; RAG memory is injected server-side.
# =============================================================================
set -uo pipefail
GW="${JARVIS_API_BASE:-https://10.113.0.176}"
HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"; USER_PASS="${JARVIS_TEST_PASS:-test1111}"
TTS_URL="${JARVIS_TTS_URL:-http://127.0.0.1:18090}"

SPEAK=false; Q=""
for a in "$@"; do
  case "$a" in
    --speak) SPEAK=true ;;
    --quiet) SPEAK=false ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) Q="${Q:+$Q }$a" ;;
  esac
done
[ -n "${Q// }" ] || { echo "usage: scripts/jarvis-ask.sh [--speak] \"your question\""; exit 2; }

J=(-sk -H "Host: $HOST" -H 'Content-Type: application/json')
TOKEN="$(curl "${J[@]}" -m10 "$GW/api/v1/security/auth/login" \
  -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
[ -n "$TOKEN" ] || { echo "auth failed (gateway reachable?)"; exit 1; }

printf '\033[0;90mthinking…\033[0m\n'
BODY="$(python3 -c 'import json,sys;print(json.dumps({"sessionId":"ask-cli","messages":[{"role":"user","content":sys.argv[1]+" /no_think"}]}))' "$Q")"
REPLY="$(curl "${J[@]}" -m60 -H "Authorization: Bearer $TOKEN" -X POST "$GW/api/v1/llm/chat" -d "$BODY" 2>/dev/null \
  | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin); print((d.get("reply") or d.get("response") or d.get("content") or "").strip())
except Exception: print("")')"
if [ -z "$REPLY" ]; then
  echo "Jarvis did not answer. Try: ./scripts/jarvis-host-endpoint-check.sh --fix"; exit 5
fi
printf '\033[1;35mJarvis:\033[0m %s\n' "$REPLY"

if $SPEAK; then
  OUT="/tmp/jarvis-ask.wav"
  CODE="$(curl -s -m30 -o "$OUT" -w '%{http_code}' -X POST "$TTS_URL/synthesize" \
    -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys;print(json.dumps({"text":sys.argv[1],"language":"ru-RU"}))' "$REPLY")" 2>/dev/null)"
  if [ "$CODE" = "200" ] && [ -s "$OUT" ]; then
    command -v paplay >/dev/null 2>&1 && paplay "$OUT" 2>/dev/null || aplay "$OUT" 2>/dev/null || echo "(WAV saved: $OUT)"
  else
    echo "(TTS unavailable HTTP $CODE; text only)"
  fi
fi
