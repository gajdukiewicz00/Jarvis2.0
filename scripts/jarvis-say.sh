#!/usr/bin/env bash
# jarvis-say.sh — unified Text-To-Speech for Jarvis.
#
# Primary engine: Piper neural TTS (natural British voice, the "movie J.A.R.V.I.S." sound).
# Fallback:       spd-say (Festival) so the loop never goes silent if Piper is absent.
#
# Usage:
#   jarvis-say.sh "Good evening, sir."
#   echo "All systems nominal." | jarvis-say.sh
#   jarvis-say.sh --save /tmp/out.wav "Saved to a file instead of played."
#
# Env overrides:
#   JARVIS_PIPER_BIN          path to piper binary           (default ~/piper/piper)
#   JARVIS_PIPER_VOICE        path to .onnx voice model      (default en_GB-alan-medium)
#   JARVIS_PIPER_LENGTH_SCALE speech pace, >1 slower         (default 1.0)
#   JARVIS_TTS_ENGINE         force "piper" | "spd" | "auto" (default auto)
set -uo pipefail

PIPER_BIN="${JARVIS_PIPER_BIN:-$HOME/piper/piper}"
PIPER_VOICE="${JARVIS_PIPER_VOICE:-$HOME/.jarvis/models/tts/en_GB-alan-medium.onnx}"
LENGTH_SCALE="${JARVIS_PIPER_LENGTH_SCALE:-1.0}"
ENGINE="${JARVIS_TTS_ENGINE:-auto}"

SAVE_PATH=""
if [ "${1:-}" = "--save" ]; then SAVE_PATH="${2:-}"; shift 2; fi

# Text from args or stdin
if [ "$#" -gt 0 ]; then
  TEXT="$*"
else
  TEXT="$(cat)"
fi
# Trim; nothing to say -> succeed silently
TEXT="$(printf '%s' "$TEXT" | tr -s '[:space:]' ' ' | sed 's/^ *//;s/ *$//')"
[ -z "$TEXT" ] && exit 0

# Auto-select voice by script: Cyrillic text -> Russian voice when available.
PIPER_VOICE_RU="${JARVIS_PIPER_VOICE_RU:-$HOME/.jarvis/models/tts/ru_RU-dmitri-medium.onnx}"
if printf '%s' "$TEXT" | grep -qP '[\x{0400}-\x{04FF}]' 2>/dev/null && [ -f "$PIPER_VOICE_RU" ]; then
  PIPER_VOICE="$PIPER_VOICE_RU"
fi

pick_player() {
  for p in paplay pw-play aplay ffplay; do command -v "$p" >/dev/null 2>&1 && { echo "$p"; return; }; done
  echo ""
}

play_raw() { # reads raw s16le 22050 mono on stdin
  local player; player="$(pick_player)"
  case "$player" in
    paplay)  paplay --raw --rate=22050 --format=s16le --channels=1 ;;
    pw-play) pw-play --rate=22050 --format=s16 --channels=1 - ;;
    aplay)   aplay -q -t raw -f S16_LE -r 22050 -c 1 - ;;
    ffplay)  ffplay -loglevel quiet -autoexit -f s16le -ar 22050 -ac 1 - ;;
    *)       cat >/dev/null; return 1 ;;
  esac
}

piper_ok() { [ "$ENGINE" != "spd" ] && [ -x "$PIPER_BIN" ] && [ -f "$PIPER_VOICE" ]; }

speak_piper() {
  if [ -n "$SAVE_PATH" ]; then
    printf '%s' "$TEXT" | "$PIPER_BIN" --model "$PIPER_VOICE" \
      --length_scale "$LENGTH_SCALE" --output_file "$SAVE_PATH" >/dev/null 2>&1
    return $?
  fi
  # Stream raw PCM straight to the speakers for low latency.
  printf '%s' "$TEXT" | "$PIPER_BIN" --model "$PIPER_VOICE" \
    --length_scale "$LENGTH_SCALE" --output-raw 2>/dev/null | play_raw
}

speak_spd() {
  command -v spd-say >/dev/null 2>&1 || return 1
  if [ -n "$SAVE_PATH" ]; then
    command -v espeak-ng >/dev/null 2>&1 && espeak-ng -w "$SAVE_PATH" "$TEXT" 2>/dev/null
    return $?
  fi
  spd-say -w "$TEXT" >/dev/null 2>&1
}

if piper_ok && speak_piper; then
  exit 0
fi
speak_spd
