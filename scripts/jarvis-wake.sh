#!/usr/bin/env bash
# =============================================================================
# jarvis-wake.sh — local wake-word loop for Jarvis.
# =============================================================================
#   listen for "Jarvis" (Porcupine, offline)  ->  record utterance
#     ->  jarvis-voice-smoke.sh (Vosk STT -> jarvis-loop.sh -> TTS answer)
#
# Engines, in order:
#   1. Porcupine  — used when a VALID PORCUPINE_ACCESS_KEY + keyword .ppn load.
#   2. push-to-talk — fallback when Porcupine can't init (e.g. invalid key).
#                     Wake-word is then NOT_READY; you press ENTER to "wake".
#   --test-wav bypasses wake detection entirely (deterministic; for smoke/CI).
#
# 100% local: Porcupine inference + Vosk STT + local Qwen + spd-say TTS run on
# the host. (Picovoice may validate the AccessKey over the network — license
# check only, never audio.) No cloud STT/TTS/LLM.
#
# SAFE BY DEFAULT: --no-act (dry-run). Real desktop action only with --yes.
#
# Run modes:
#   scripts/jarvis-wake.sh --test-wav assets/voice-test/jarvis-screen-en.wav --once --no-act
#   scripts/jarvis-wake.sh --once --record-seconds 5 --no-act
#   scripts/jarvis-wake.sh --continuous --no-act
# Daemon (systemd --user; never auto-started):
#   scripts/jarvis-wake.sh install|start|stop|status|logs
#
# Flags: --lang en|ru  --record-seconds N  --no-act  --yes  --once  --continuous
#        --device <alsa>  --tts-out <path>  --test-wav <wav>  --sensitivity F
# Exit: 0 ok · 2 bad args · 4 deps missing · 5 loop failed · 7 listen timeout
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_PY="${JARVIS_VOICE_PY:-${PROJECT_DIR}/.venv-voice/bin/python}"
WAKE_PY="${PROJECT_DIR}/scripts/stt/porcupine_wake.py"
VOICE_SMOKE="${PROJECT_DIR}/scripts/jarvis-voice-smoke.sh"
PPN_EN="${JARVIS_WAKE_PPN_EN:-${HOME}/.jarvis/models/wake-word/Jarvis_en.ppn}"
PPN_RU="${JARVIS_WAKE_PPN_RU:-${HOME}/.jarvis/models/wake-word/Jarvis_ru.ppn}"
WORK="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"; mkdir -p "$WORK"
LOG_DIR="${HOME}/.jarvis/logs"; mkdir -p "$LOG_DIR"
UNIT="${HOME}/.config/systemd/user/jarvis-wake.service"

LANG_SEL="en"; REC_SECS=5; ACT=false; MODE="once"; DEVICE=""; TTS_OUT=""; TEST_WAV=""; SENS="0.5"
VAD=false; SILENCE_MS=800; MAX_REC=10; LISTEN_TIMEOUT=30
SUBCMD=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    install|start|stop|status|logs) SUBCMD="$1"; shift ;;
    --lang) LANG_SEL="$2"; shift 2 ;;
    --record-seconds) REC_SECS="$2"; shift 2 ;;
    --vad) VAD=true; shift ;;
    --silence-ms) SILENCE_MS="$2"; shift 2 ;;
    --max-record-seconds) MAX_REC="$2"; shift 2 ;;
    --listen-timeout) LISTEN_TIMEOUT="$2"; shift 2 ;;
    --no-act) ACT=false; shift ;;
    --yes) ACT=true; shift ;;
    --once) MODE="once"; shift ;;
    --continuous) MODE="continuous"; shift ;;
    --device) DEVICE="$2"; shift 2 ;;
    --tts-out) TTS_OUT="$2"; shift 2 ;;
    --test-wav) TEST_WAV="$2"; shift 2 ;;
    --sensitivity) SENS="$2"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
VAD_PY="${PROJECT_DIR}/scripts/stt/vad_record.py"

c(){ printf '\033[%sm%s\033[0m' "$1" "$2"; }
log(){ printf '%s %s\n' "$(c '1;35' '[wake]')" "$1"; }

resolve_key() {
  if [[ -n "${PORCUPINE_ACCESS_KEY:-}" ]]; then printf '%s' "$PORCUPINE_ACCESS_KEY"; return; fi
  local f="${PROJECT_DIR}/secrets/secrets.env"
  [[ -f "$f" ]] && sed -n 's/^PORCUPINE_ACCESS_KEY=//p' "$f" | head -1
}
ppn_for_lang(){ [[ "$1" == "ru" ]] && printf '%s' "$PPN_RU" || printf '%s' "$PPN_EN"; }

# ---------- systemd subcommands ----------
case "$SUBCMD" in
  install)
    mkdir -p "$(dirname "$UNIT")"
    cat > "$UNIT" <<EOF
[Unit]
Description=Jarvis wake-word listener (continuous, SAFE/no-act)
After=default.target

[Service]
Type=simple
# systemd --user does NOT inherit your interactive shell profile, so provide the
# Porcupine key here (optional file; '-' = ok if missing). Put a line:
#   PORCUPINE_ACCESS_KEY=<your valid Picovoice key>
EnvironmentFile=-%h/.jarvis/wake.env
# Continuous + SAFE by default: no real desktop action. Add --yes deliberately
# (edit this unit) only if you accept unattended actions.
ExecStart=${PROJECT_DIR}/scripts/jarvis-wake.sh --continuous --no-act --lang en
Restart=on-failure
RestartSec=5
StandardOutput=append:${LOG_DIR}/jarvis-wake.log
StandardError=append:${LOG_DIR}/jarvis-wake.log

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload 2>/dev/null
    log "installed $UNIT (NOT started/enabled — start explicitly)."
    log "start:  scripts/jarvis-wake.sh start    (begins always-listening)"
    exit 0 ;;
  start)  systemctl --user start jarvis-wake.service && log "started (continuous, no-act)"; exit $? ;;
  stop)   systemctl --user stop jarvis-wake.service && log "stopped"; exit $? ;;
  status) systemctl --user --no-pager --lines=0 status jarvis-wake.service 2>/dev/null | sed -n '1,4p'; exit 0 ;;
  logs)   tail -n 60 "${LOG_DIR}/jarvis-wake.log" 2>/dev/null || echo "(no log yet)"; exit 0 ;;
esac

# ---------- deps ----------
[[ -x "$VOICE_SMOKE" || -f "$VOICE_SMOKE" ]] || { log "$(c '1;31' "missing $VOICE_SMOKE")"; exit 4; }

# ---------- choose engine ----------
ENGINE="push-to-talk"; ENGINE_REASON=""
if [[ -n "$TEST_WAV" ]]; then
  ENGINE="test"
else
  KEY="$(resolve_key)"
  PPN="$(ppn_for_lang "$LANG_SEL")"
  if [[ -z "$KEY" ]]; then
    ENGINE_REASON="no PORCUPINE_ACCESS_KEY (env or secrets/secrets.env)"
  elif [[ ! -f "$PPN" ]]; then
    ENGINE_REASON="keyword file missing: $PPN"
  elif [[ ! -x "$VENV_PY" ]]; then
    ENGINE_REASON="pvporcupine venv missing ($VENV_PY)"
  else
    PROBE="$(PORCUPINE_ACCESS_KEY="$KEY" "$VENV_PY" "$WAKE_PY" --probe --keyword "$PPN" --sensitivity "$SENS" 2>&1)"
    if grep -q '^PROBE_OK' <<<"$PROBE"; then
      ENGINE="porcupine"
    else
      ENGINE_REASON="$(grep -oE 'PORCUPINE_[A-Z_]+: .*' <<<"$PROBE" | head -1)"
      ENGINE_REASON="${ENGINE_REASON:-Porcupine init failed}"
    fi
  fi
fi

log "engine: $(c '1;36' "$ENGINE")  mode=$MODE  lang=$LANG_SEL  action=$([[ $ACT == true ]] && echo REAL || echo SAFE/dry-run)"
[[ "$ENGINE" == "porcupine" ]] && log "$(c '1;32' 'wake-word READY (say "Jarvis")')"
[[ "$ENGINE" == "push-to-talk" ]] && log "$(c '1;33' "wake-word NOT_READY -> push-to-talk fallback. reason: $ENGINE_REASON")"

dispatch() { # dispatch <utterance-wav>
  local wav="$1"; local args=(--wav "$wav" --lang "$LANG_SEL")
  $ACT && args+=(--yes) || args+=(--no-act)
  [[ -n "$TTS_OUT" ]] && args+=(--tts-out "$TTS_OUT")
  bash "$VOICE_SMOKE" "${args[@]}"
}

capture_utterance() { # -> echoes wav path on success (logs to stderr)
  local out="${WORK}/wake-utt.$$.$RANDOM.wav"
  if $VAD; then
    log "capturing utterance with VAD (silence=${SILENCE_MS}ms, max=${MAX_REC}s)…" >&2
    if "$VENV_PY" "$VAD_PY" --out "$out" ${DEVICE:+--device "$DEVICE"} \
         --silence-ms "$SILENCE_MS" --max-seconds "$MAX_REC" --start-timeout 6 >&2 \
       && [[ -s "$out" ]]; then
      printf '%s' "$out"; return 0
    fi
    log "VAD capture got no speech; falling back to fixed ${REC_SECS}s" >&2
  fi
  if command -v arecord >/dev/null 2>&1 && \
     arecord ${DEVICE:+-D "$DEVICE"} -q -d "$REC_SECS" -f S16_LE -r 16000 -c 1 "$out" 2>/dev/null; then
    printf '%s' "$out"
  fi
}

run_once() {
  case "$ENGINE" in
    test)
      log "$(c '1;33' '[test mode] bypassing wake detection')"
      log "$(c '1;32' 'wake detected (simulated)')"
      dispatch "$TEST_WAV"; return $? ;;
    porcupine)
      local to="$1"  # timeout seconds (0 forever)
      local res; res="$(PORCUPINE_ACCESS_KEY="$KEY" "$VENV_PY" "$WAKE_PY" --keyword "$PPN" \
        --sensitivity "$SENS" ${DEVICE:+--device "$DEVICE"} --timeout "$to" 2>/dev/null)"
      if grep -q '^WAKE' <<<"$res"; then
        log "$(c '1;32' 'wake detected!')"; "$(dirname "${BASH_SOURCE[0]}")/jarvis-say.sh" "Yes, sir?" >/dev/null 2>&1 || true
        local utt; utt="$(capture_utterance)"
        [[ -n "$utt" ]] || { log "$(c '1;31' 'utterance capture failed')"; return 6; }
        dispatch "$utt"; return $?
      else
        log "$(c '1;33' 'no wake word detected (timeout) — still listening worked')"; return 7
      fi ;;
    push-to-talk)
      printf '%s' "$(c '1;33' '[push-to-talk] press ENTER to talk (Ctrl-C to quit) … ')"
      read -r _ </dev/tty 2>/dev/null || { log "no TTY for push-to-talk"; return 7; }
      log "$(c '1;32' 'wake (manual)')"
      local utt; utt="$(capture_utterance)"
      [[ -n "$utt" ]] || { log "$(c '1;31' 'utterance capture failed')"; return 6; }
      dispatch "$utt"; return $? ;;
  esac
}

RC=0
if [[ "$MODE" == "once" ]]; then
  run_once "$LISTEN_TIMEOUT"; RC=$?
else
  log "continuous mode — Ctrl-C to stop"
  while true; do run_once 0 || true; done
fi
[[ $RC -eq 0 ]] && log "$(c '1;32' 'wake loop ok')" || log "wake loop exit=$RC"
exit $RC
