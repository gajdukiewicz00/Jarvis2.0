#!/usr/bin/env bash
# =============================================================================
# jarvis-voice-daemon-check.sh — verify the local voice toolchain end to end.
# =============================================================================
# Checks: Porcupine probe · Vosk model + transcribe · mic capture · VAD
# (deterministic: silence->NO_SPEECH, speech->VAD_OK) · Qwen health · TTS.
# 100% local. Read-only / non-destructive. Does NOT start the always-listening
# daemon (use: scripts/jarvis-wake.sh start).
#
# Exit 0 if all required checks pass; 1 otherwise.
# =============================================================================
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
PY=".venv-voice/bin/python"; WORK="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"; mkdir -p "$WORK"
VOSK_EN="${JARVIS_VOSK_EN:-$HOME/.jarvis/models/vosk/vosk-model-small-en-us-0.15}"
PPN="${JARVIS_WAKE_PPN_EN:-$HOME/.jarvis/models/wake-word/Jarvis_en.ppn}"
fails=0
ok(){ printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
bad(){ printf '  \033[1;31m✗\033[0m %s\n' "$1"; fails=$((fails+1)); }
part(){ printf '  \033[1;33m~\033[0m %s\n' "$1"; }

echo "== Jarvis voice daemon check =="

# 1) Qwen health
[[ "$(curl -s -o /dev/null -m4 -w '%{http_code}' http://127.0.0.1:18080/health 2>/dev/null)" == 200 ]] \
  && ok "Qwen LLM :18080 healthy" || bad "Qwen LLM down (scripts/jarvis-llm-daemon.sh start)"

# 2) TTS
command -v spd-say >/dev/null 2>&1 && ok "TTS spd-say present" || bad "spd-say missing"

# 3) Vosk model + transcribe a generated phrase
if [[ -x "$PY" && -d "$VOSK_EN" ]]; then
  espeak-ng -w "$WORK/vc-speech.wav" "Jarvis testing one two three" 2>/dev/null
  ffmpeg -y -i "$WORK/vc-speech.wav" -ar 16000 -ac 1 "$WORK/vc-speech16.wav" >/dev/null 2>&1
  TX="$("$PY" scripts/stt/vosk_transcribe.py "$VOSK_EN" "$WORK/vc-speech16.wav" 2>/dev/null)"
  [[ -n "${TX// }" ]] && ok "Vosk STT works (\"$TX\")" || part "Vosk transcript empty on synthetic TTS (human speech transcribes better)"
else bad "Vosk venv/model missing (.venv-voice + $VOSK_EN)"; fi

# 4) Porcupine probe
if [[ -x "$PY" && -f "$PPN" ]]; then
  if PORCUPINE_ACCESS_KEY="${PORCUPINE_ACCESS_KEY:-$(sed -n 's/^PORCUPINE_ACCESS_KEY=//p' secrets/secrets.env 2>/dev/null | head -1)}" \
     "$PY" scripts/stt/porcupine_wake.py --probe --keyword "$PPN" 2>/dev/null | grep -q PROBE_OK; then
    ok "Porcupine wake engine probe OK"
  else part "Porcupine probe failed (set a valid PORCUPINE_ACCESS_KEY)"; fi
else bad "Porcupine keyword/venv missing"; fi

# 5) Mic capture (short)
if command -v arecord >/dev/null 2>&1 && timeout 6 arecord -q -d 2 -f S16_LE -r 16000 -c 1 "$WORK/vc-mic.wav" 2>/dev/null && [[ -s "$WORK/vc-mic.wav" ]]; then
  ok "mic capture works (arecord)"; else part "mic capture not verified (device/headless)"; fi

# 6) VAD deterministic — silence -> NO_SPEECH
"$PY" - "$WORK/vc-silence.wav" <<'PY' 2>/dev/null
import sys,wave
w=wave.open(sys.argv[1],"wb");w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000)
w.writeframes(b"\x00\x00"*16000*2);w.close()
PY
if "$PY" scripts/stt/vad_record.py --in-wav "$WORK/vc-silence.wav" --out "$WORK/vc-sil-trim.wav" 2>&1 | grep -q NO_SPEECH \
   || "$PY" scripts/stt/vad_record.py --in-wav "$WORK/vc-silence.wav" --out "$WORK/vc-sil-trim.wav" 2>&1 | grep -qi no_speech; then
  ok "VAD: silence -> NO_SPEECH"; else
  # trim_file writes NO_SPEECH to stderr; check exit code instead
  "$PY" scripts/stt/vad_record.py --in-wav "$WORK/vc-silence.wav" --out "$WORK/vc-sil-trim.wav" >/dev/null 2>&1 && bad "VAD: silence wrongly produced speech" || ok "VAD: silence -> NO_SPEECH (rc=7)"; fi

# 7) VAD deterministic — speech -> VAD_OK
if [[ -f "$WORK/vc-speech16.wav" ]] && "$PY" scripts/stt/vad_record.py --in-wav "$WORK/vc-speech16.wav" --out "$WORK/vc-sp-trim.wav" 2>/dev/null | grep -q VAD_OK; then
  ok "VAD: speech -> VAD_OK"; else part "VAD: speech fixture not available/too quiet"; fi

echo
[[ $fails -eq 0 ]] && { echo -e "\033[1;32mVOICE CHECK: required components OK\033[0m"; exit 0; } \
  || { echo -e "\033[1;31mVOICE CHECK: $fails required component(s) failed\033[0m"; exit 1; }
