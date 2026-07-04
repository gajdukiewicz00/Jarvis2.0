#!/usr/bin/env bash
# =============================================================================
# jarvis-voice-demo.sh — full spoken demo loop on the host:
#   mic (record) -> Vosk STT -> 14B brain (/llm/chat) -> Piper TTS -> speaker
# Prints the transcript and the spoken response. Needs a microphone + speakers.
#
# Usage:
#   scripts/jarvis-voice-demo.sh                 # record 5s (EN), speak the answer
#   scripts/jarvis-voice-demo.sh --lang ru       # Russian STT
#   scripts/jarvis-voice-demo.sh --record 7      # record 7 seconds
#   scripts/jarvis-voice-demo.sh --wav file.wav  # use a wav instead of the mic
#
# Exit: 0 ok · 2 audio/mic · 3 empty transcript · 4 STT engine · 5 brain · 6 TTS
#
# TROUBLESHOOTING
#   no microphone     : `arecord -l` shows no card → check input device / PipeWire.
#                       Try: scripts/jarvis-voice-demo.sh --wav assets/voice-test/jarvis-screen-en.wav
#   no speaker        : `aplay -l` empty → set default sink; the answer wav is saved anyway.
#   Vosk model missing: ls ~/.jarvis/models/vosk/ ; expected vosk-model-small-{en-us-0.15,ru-0.22}.
#   TTS not speaking  : curl host :18090/health should be 200; `systemctl --user status jarvis-tts`.
#   brain error       : run ./scripts/jarvis-host-endpoint-check.sh --fix (endpoint reset).
#   gateway timeout   : /llm/chat cold-loads on first call; re-run once.
# =============================================================================
set -uo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_PY="${JARVIS_VOICE_PY:-${PROJECT_DIR}/.venv-voice/bin/python}"
STT="${PROJECT_DIR}/scripts/stt/vosk_transcribe.py"
MODEL_EN="${JARVIS_VOSK_EN:-${HOME}/.jarvis/models/vosk/vosk-model-small-en-us-0.15}"
MODEL_RU="${JARVIS_VOSK_RU:-${HOME}/.jarvis/models/vosk/vosk-model-small-ru-0.22}"
GW="${JARVIS_API_BASE:-https://10.113.0.176}"
HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"; USER_PASS="${JARVIS_TEST_PASS:-test1111}"
TTS_URL="${JARVIS_TTS_URL:-http://127.0.0.1:18090}"
WORK="/tmp/jarvis-voice-demo"; mkdir -p "$WORK"

LANG_SEL="en"; RECORD=5; WAV=""
SAMPLE_WAV="${PROJECT_DIR}/assets/voice-test/jarvis-screen-en.wav"
while [[ $# -gt 0 ]]; do case "$1" in
  --lang) LANG_SEL="$2"; shift 2 ;;
  --record) RECORD="$2"; shift 2 ;;
  --wav) WAV="$2"; shift 2 ;;
  --sample) WAV="$SAMPLE_WAV"; shift ;;   # bundled clip — no microphone needed
  -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
  *) echo "unknown arg: $1" >&2; exit 2 ;;
esac; done

c(){ printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
say(){ c '1;36' "[voice-demo] $1"; }

[[ -x "$VENV_PY" && -f "$STT" ]] || { c '1;31' "STT engine missing ($VENV_PY $STT). Run scripts/setup-voice-local.sh"; exit 4; }
case "$LANG_SEL" in en) MODEL="$MODEL_EN"; LANGCODE="en-US" ;; ru) MODEL="$MODEL_RU"; LANGCODE="ru-RU" ;; *) echo "lang must be en|ru"; exit 2 ;; esac
[[ -d "$MODEL" ]] || { c '1;31' "Vosk model not found: $MODEL"; exit 4; }

# 1) get audio -----------------------------------------------------------------
RAW="$WORK/in.$$.wav"
if [[ -n "$WAV" ]]; then
  [[ -f "$WAV" ]] || { c '1;31' "wav not found: $WAV"; exit 2; }
  cp "$WAV" "$RAW"; say "using wav: $WAV"
else
  say "recording ${RECORD}s from mic — speak now…"
  if command -v arecord >/dev/null 2>&1 && arecord -d "$RECORD" -f S16_LE -r 16000 -c 1 "$RAW" 2>/dev/null; then :
  elif command -v pw-record >/dev/null 2>&1; then pw-record --rate 16000 --channels 1 --format s16 "$RAW" & R=$!; sleep "$RECORD"; kill "$R" 2>/dev/null
  else c '1;31' "no mic capture tool (arecord/pw-record). See TROUBLESHOOTING."; exit 2; fi
  [[ -s "$RAW" ]] || { c '1;31' "no audio captured. See TROUBLESHOOTING (no microphone)."; exit 2; }
fi

# 2) normalize + STT -----------------------------------------------------------
WAV16="$WORK/in16.$$.wav"
ffmpeg -y -i "$RAW" -ar 16000 -ac 1 -f wav "$WAV16" >/dev/null 2>&1 || { c '1;31' "ffmpeg failed"; exit 2; }
say "transcribing (Vosk $LANG_SEL, offline)…"
TRANSCRIPT="$("$VENV_PY" "$STT" "$MODEL" "$WAV16" 2>/dev/null)"
[[ -n "${TRANSCRIPT// }" ]] || { c '1;31' "empty transcript (say something louder / closer to the mic)"; exit 3; }
c '1;32' "  you said: \"$TRANSCRIPT\""

# 3) brain ---------------------------------------------------------------------
say "asking the 14B brain…"
TOKEN="$(curl -sk -m10 -H "Host: $HOST" -H 'Content-Type: application/json' "$GW/api/v1/security/auth/login" \
  -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
REPLY="$(curl -sk -m60 -H "Host: $HOST" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -X POST "$GW/api/v1/llm/chat" \
  -d "$(python3 -c 'import json,sys;print(json.dumps({"sessionId":"voice-demo","messages":[{"role":"user","content":sys.argv[1]+" /no_think"}]}))' "$TRANSCRIPT")" 2>/dev/null \
  | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin); print((d.get("reply") or d.get("response") or d.get("content") or "").strip())
except Exception: print("")')"
[[ -n "$REPLY" ]] || { c '1;31' "brain returned no reply. Try ./scripts/jarvis-host-endpoint-check.sh --fix then re-run."; exit 5; }
c '1;35' "  Jarvis: $REPLY"

# 4) TTS + play ----------------------------------------------------------------
OUT="${JARVIS_VOICE_OUT:-/tmp/jarvis-demo.wav}"
say "synthesizing voice (Piper)…"
HTTP="$(curl -s -m30 -o "$OUT" -w '%{http_code}' -X POST "$TTS_URL/synthesize" \
  -H 'Content-Type: application/json' -d "$(python3 -c 'import json,sys;print(json.dumps({"text":sys.argv[1],"language":sys.argv[2]}))' "$REPLY" "$LANGCODE")" 2>/dev/null)"
if [[ "$HTTP" != "200" || ! -s "$OUT" ]]; then
  c '1;31' "TTS failed (HTTP $HTTP). Check curl $TTS_URL/health and systemctl --user status jarvis-tts."; exit 6
fi
say "playing answer ($OUT)…"
if command -v paplay >/dev/null 2>&1; then paplay "$OUT" 2>/dev/null || aplay "$OUT" 2>/dev/null
elif command -v aplay >/dev/null 2>&1; then aplay "$OUT" 2>/dev/null
else c '1;33' "no audio player (paplay/aplay). WAV saved at $OUT"; fi

# save artifacts so the owner can inspect the demo afterwards
printf '%s\n' "$TRANSCRIPT" > "$WORK/transcript.txt" 2>/dev/null
printf '%s\n' "$REPLY"      > "$WORK/response.txt"   2>/dev/null
cp -f "$OUT" "$WORK/answer.wav" 2>/dev/null
rm -f "$RAW" "$WAV16" 2>/dev/null
echo
say "artifacts saved in $WORK :"
echo "    transcript.txt  -> \"$TRANSCRIPT\""
echo "    response.txt    -> \"$REPLY\""
echo "    answer.wav      -> $(ls -la "$WORK/answer.wav" 2>/dev/null | awk '{print $5" bytes"}')"
c '1;32' "voice demo complete."
