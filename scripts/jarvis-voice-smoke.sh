#!/usr/bin/env bash
# =============================================================================
# jarvis-voice-smoke.sh — local voice input loop for Jarvis.
# =============================================================================
#   audio (wav or mic) -> local STT (Vosk, offline) -> text command
#     -> scripts/jarvis-loop.sh  -> screen/LLM/memory/action + TTS response
#
# 100% local: Vosk STT (no cloud), local Qwen reasoning, local spd-say TTS.
# SAFE BY DEFAULT: the loop runs dry-run / --no-act unless --yes is given.
#
# Usage:
#   scripts/jarvis-voice-smoke.sh                       # use bundled test wav, no real action
#   scripts/jarvis-voice-smoke.sh --wav /path/to.wav
#   scripts/jarvis-voice-smoke.sh --record 5            # record 5s from the mic
#   scripts/jarvis-voice-smoke.sh --lang ru --wav ru.wav
#   scripts/jarvis-voice-smoke.sh --wav x.wav --yes     # allow the real desktop action
#   scripts/jarvis-voice-smoke.sh --wav x.wav --tts-out /tmp/answer.wav   # also save spoken answer
#
# Exit: 0 ok · 2 no audio · 3 STT empty · 4 STT engine missing · 5 loop failed
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_PY="${JARVIS_VOICE_PY:-${PROJECT_DIR}/.venv-voice/bin/python}"
STT="${PROJECT_DIR}/scripts/stt/vosk_transcribe.py"
MODEL_DIR_EN="${JARVIS_VOSK_EN:-${HOME}/.jarvis/models/vosk/vosk-model-small-en-us-0.15}"
MODEL_DIR_RU="${JARVIS_VOSK_RU:-${HOME}/.jarvis/models/vosk/vosk-model-small-ru-0.22}"
DEFAULT_WAV="${PROJECT_DIR}/assets/voice-test/jarvis-screen-en.wav"
WORK="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"; mkdir -p "$WORK"

WAV=""; RECORD=0; LANG_SEL="en"; ACT=false; NO_ACT_FLAG=true; TTS_OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --wav)     WAV="$2"; shift 2 ;;
    --record)  RECORD="$2"; shift 2 ;;
    --lang)    LANG_SEL="$2"; shift 2 ;;
    --no-act)  ACT=false; NO_ACT_FLAG=true; shift ;;
    --yes)     ACT=true; NO_ACT_FLAG=false; shift ;;
    --tts-out) TTS_OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

c(){ printf '\033[%sm%s\033[0m' "$1" "$2"; }
say(){ printf '%s %s\n' "$(c '1;36' '[voice]')" "$1"; }

[[ -f "$STT" && -x "$VENV_PY" ]] || { say "$(c '1;31' "STT engine missing: $VENV_PY $STT")"; \
  say "  next: python3 -m venv .venv-voice && .venv-voice/bin/pip install vosk"; exit 4; }
case "$LANG_SEL" in en) MODEL="$MODEL_DIR_EN" ;; ru) MODEL="$MODEL_DIR_RU" ;; *) say "lang must be en|ru"; exit 2 ;; esac
[[ -d "$MODEL" ]] || { say "$(c '1;31' "Vosk model not found: $MODEL")"; exit 4; }

# --- 1) obtain audio ---------------------------------------------------------
RAW="${WORK}/voice-in.$$.wav"
if [[ "$RECORD" -gt 0 ]]; then
  say "recording ${RECORD}s from mic (16k mono)…"
  if command -v arecord >/dev/null 2>&1 && arecord -d "$RECORD" -f S16_LE -r 16000 -c 1 "$RAW" 2>/dev/null; then
    say "recorded via arecord"
  elif command -v pw-record >/dev/null 2>&1 && pw-record --rate 16000 --channels 1 --format s16 "$RAW" & REC=$!; sleep "$RECORD" 2>/dev/null; kill "$REC" 2>/dev/null; [[ -s "$RAW" ]]; then
    say "recorded via pw-record"
  else
    say "$(c '1;31' 'mic capture failed — no usable input device')"; exit 2
  fi
else
  WAV="${WAV:-$DEFAULT_WAV}"
  [[ -f "$WAV" ]] || { say "$(c '1;31' "wav not found: $WAV")"; exit 2; }
  say "input wav: $WAV"
  cp "$WAV" "$RAW"
fi

# --- 2) normalize to 16k mono s16le ------------------------------------------
WAV16="${WORK}/voice-16k.$$.wav"
ffmpeg -y -i "$RAW" -ar 16000 -ac 1 -f wav "$WAV16" >/dev/null 2>&1 \
  || { say "$(c '1;31' 'ffmpeg conversion failed')"; exit 2; }

# --- 3) local STT (Vosk) -----------------------------------------------------
say "transcribing with Vosk ($LANG_SEL, offline)…"
TRANSCRIPT="$("$VENV_PY" "$STT" "$MODEL" "$WAV16" 2>/dev/null)"
if [[ -z "${TRANSCRIPT// }" ]]; then
  say "$(c '1;31' 'STT produced an empty transcript')"; exit 3
fi
say "$(c '1;32' "transcript: \"$TRANSCRIPT\"")"

# --- 4) hand the command to the alive loop -----------------------------------
say "dispatching to jarvis-loop.sh (action: $([[ $ACT == true ]] && echo REAL || echo dry-run))…"
LOOP_ARGS=()
$ACT && LOOP_ARGS+=(--yes) || LOOP_ARGS+=(--dry-run --no-act)
LOOP_OUT="${WORK}/voice-loop.$$.out"
bash "${PROJECT_DIR}/scripts/jarvis-loop.sh" "${LOOP_ARGS[@]}" "$TRANSCRIPT" > "$LOOP_OUT" 2>&1
LOOP_RC=$?
CLEAN="$(sed -r 's/\x1B\[[0-9;]*[mK]//g' "$LOOP_OUT")"
ANSWER="$(printf '%s\n' "$CLEAN" | sed -n 's/^\[jarvis answer\] //p' | tail -1)"
printf '%s\n' "$CLEAN" | sed -n '/==>/p' | sed 's/^/    /'
say "loop exit=$LOOP_RC"
[[ -n "$ANSWER" ]] && say "$(c '1;36' "Jarvis: $ANSWER")"

# --- 5) optionally save the spoken answer to a wav ---------------------------
if [[ -n "$TTS_OUT" && -n "$ANSWER" ]] && command -v espeak-ng >/dev/null 2>&1; then
  espeak-ng -w "$TTS_OUT" "$ANSWER" 2>/dev/null && say "answer audio saved: $TTS_OUT"
fi

rm -f "$RAW" "$WAV16" 2>/dev/null
[[ $LOOP_RC -eq 0 ]] || exit 5
say "$(c '1;32' 'voice smoke complete')"
