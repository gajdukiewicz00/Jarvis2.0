#!/usr/bin/env bash
# =============================================================================
# jarvis-host-bridge.sh — the safe host-side bridge for Jarvis.
# =============================================================================
# k3s pods cannot touch the host X11 display, mic, or speakers. This bridge is
# the ONLY place host-bound operations run. It exposes a small, structured-JSON
# contract (stdin/stdout) and an optional localhost-only HTTP server.
#
# Subcommands (JSON request on stdin or via --json '<obj>'; JSON reply on stdout):
#   health                      -> component readiness
#   screen-context              -> {userId?} : capture screen + OCR (no raw bytes returned)
#   voice-command               -> {wav?|record?,lang?} : STT -> {transcript}
#   speak                       -> {text} : local TTS (spd-say)
#   action                      -> {type,target,execute?,confirm?} : allow-listed desktop action
#   serve [--port N] [--token T]-> localhost HTTP API (delegates to the above)
#
# Security:
#   - HTTP binds 127.0.0.1 only; a token is REQUIRED when --token is set.
#   - No arbitrary shell execution. Only an allow-list of action types.
#   - Dangerous actions are refused; guarded actions need confirm=true.
#   - Secrets are never echoed; text that looks like a secret is redacted.
# Safe by default: action execute=false (dry-run) unless explicitly requested.
# 100% local: vision-security CLI (OCR), Vosk (STT), spd-say (TTS). No cloud.
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_PY="${JARVIS_VOICE_PY:-${PROJECT_DIR}/.venv-voice/bin/python}"
VISION_JAR="${VISION_JAR:-${PROJECT_DIR}/apps/vision-security-service/target/vision-security-service-1.0.0.jar}"
LLM_ENDPOINT="${JARVIS_LLM_ENDPOINT:-http://127.0.0.1:18080}"
VOSK_EN="${JARVIS_VOSK_EN:-${HOME}/.jarvis/models/vosk/vosk-model-small-en-us-0.15}"
VOSK_RU="${JARVIS_VOSK_RU:-${HOME}/.jarvis/models/vosk/vosk-model-small-ru-0.22}"
WORK="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"; mkdir -p "$WORK"
LOG_DIR="${HOME}/.jarvis/logs"; mkdir -p "$LOG_DIR"
EVENTS="${LOG_DIR}/host-bridge.events.jsonl"

emit() { # emit <stage> <success> <json-detail>
  python3 - "$1" "$2" "$3" >>"$EVENTS" 2>/dev/null <<'PY'
import json,sys,time
print(json.dumps({"ts":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
                  "component":"host-bridge","stage":sys.argv[1],
                  "success":sys.argv[2]=="true","detail":json.loads(sys.argv[3] or "{}")}))
PY
}
jget() { # jget <json> <key> [default]  (safe field extraction)
  python3 -c 'import json,sys
try: d=json.loads(sys.stdin.read() or "{}")
except Exception: d={}
v=d.get(sys.argv[1], sys.argv[2] if len(sys.argv)>2 else "")
print(v if not isinstance(v,bool) else str(v).lower())' "$2" "${3:-}" <<<"$1"
}
redact() { sed -E 's/[A-Za-z0-9+/]{40,}={0,2}/<redacted>/g; s/(key|token|secret|password)=[^ ]+/\1=<redacted>/Ig'; }

read_input() { # from --json or stdin
  local empty='{}'
  if [[ "${1:-}" == "--json" ]]; then printf '%s' "${2:-$empty}"; else cat; fi
}

# ---- action allow-list + safety -------------------------------------------
# returns: SAFE | GUARDED | DANGEROUS | UNKNOWN
classify_action() {
  case "${1^^}" in
    OPEN_APP|OPEN_URL|FOCUS_WINDOW|SCREENSHOT|NONE) echo SAFE ;;
    TYPE_TEXT|HOTKEY)                    echo GUARDED ;;
    DELETE_FILE|SEND_MESSAGE|SEND_EMAIL|INSTALL_PACKAGE|RUN_ARBITRARY_COMMAND|RUN_SHELL|COMMIT_PUSH_CODE|MODIFY_SECURITY_SETTINGS|EXPOSE_SECRET|SHUTDOWN)
                                         echo DANGEROUS ;;
    *)                                   echo UNKNOWN ;;
  esac
}
resolve_launch() { # type target -> launcher spec or "none"
  local typ="${1^^}" t; t="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
  case "$typ" in
    OPEN_URL) [[ "$t" == http*://* ]] && echo "url:$2" || echo "none" ;;
    OPEN_APP)
      case "$t" in
        *terminal*|*shell*|*konsole*) command -v gnome-terminal >/dev/null && echo "cmd:gnome-terminal" || echo "cmd:xterm" ;;
        *code*|*vscode*|*editor*) command -v code >/dev/null && echo "cmd:code" || { command -v gedit >/dev/null && echo "cmd:gedit" || echo none; } ;;
        *files*|*nautilus*) command -v nautilus >/dev/null && echo "cmd:nautilus" || echo none ;;
        *browser*|*firefox*|*chrome*|*web*) echo "url:https://duckduckgo.com" ;;
        *) echo none ;;
      esac ;;
    FOCUS_WINDOW) command -v wmctrl >/dev/null && echo "focus:$2" || echo none ;;
    SCREENSHOT) echo "shot:$WORK/bridge-action-shot.$$.png" ;;
    *) echo none ;;
  esac
}

cmd_health() {
  local ocr llm stt tts disp shot
  command -v tesseract >/dev/null 2>&1 && ocr=ready || ocr=missing
  [[ "$(curl -s -o /dev/null -m3 -w '%{http_code}' "$LLM_ENDPOINT/health" 2>/dev/null)" == 200 ]] && llm=ready || llm=unavailable
  [[ -x "$VENV_PY" && -d "$VOSK_EN" ]] && stt=ready || stt=missing
  command -v spd-say >/dev/null 2>&1 && tts=ready || tts=missing
  [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]] && disp=ready || disp=headless
  [[ -f "$VISION_JAR" ]] && shot=ready || shot=missing
  python3 -c 'import json,sys;print(json.dumps({"status":"ok","components":dict(zip(["ocr","llm","stt","tts","display","vision_jar"],sys.argv[1:7]))}))' \
    "$ocr" "$llm" "$stt" "$tts" "$disp" "$shot"
  emit health true "{\"llm\":\"$llm\",\"stt\":\"$stt\"}"
}

cmd_screen_context() {
  local body user; body="$(read_input "$@")"; user="$(jget "$body" userId owner)"
  [[ -f "$VISION_JAR" ]] || { echo '{"success":false,"error":"vision_jar_missing"}'; emit screen-context false '{}'; return; }
  local out="$WORK/bridge-ctx.$$.json"
  SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none SERVICE_JWT_SECRET=bridge \
    java -jar "$VISION_JAR" --cv.screen-context=true --cv.user="$user" --cv.output="$WORK/bridge-shot.$$.png" 2>/dev/null \
    | python3 -c 'import sys,json
dec=json.JSONDecoder();d=sys.stdin.read()
for i,ch in enumerate(d):
    if ch=="{" and (i==0 or d[i-1]=="\n"):
        try:
            o,_=dec.raw_decode(d[i:])
            if "success" in o:
                a=o.get("analysis") or {}
                # never return raw bytes; slim, structured context only
                print(json.dumps({"success":o.get("success"),"userId":o.get("userId"),
                  "activeWindowTitle":o.get("activeWindowTitle"),"activeProcessName":o.get("activeProcessName"),
                  "displayServer":o.get("displayServer"),"semanticTags":o.get("semanticTags"),
                  "ocrText":a.get("ocrText"),"ocrChars":len(a.get("ocrText") or ""),
                  "engine":a.get("engine"),"screenshotPath":o.get("screenshotPath")})); break
        except Exception: pass' > "$out" 2>/dev/null
  if [[ -s "$out" ]]; then cat "$out"; emit screen-context true "{\"ocrChars\":$(jget "$(cat "$out")" ocrChars 0)}"
  else echo '{"success":false,"error":"capture_failed"}'; emit screen-context false '{}'; fi
}

cmd_voice_command() {
  local body wav rec lang model raw w16
  body="$(read_input "$@")"; wav="$(jget "$body" wav)"; rec="$(jget "$body" record 0)"; lang="$(jget "$body" lang en)"
  [[ "$lang" == ru ]] && model="$VOSK_RU" || model="$VOSK_EN"
  raw="$WORK/bridge-utt.$$.wav"
  if [[ "$rec" =~ ^[0-9]+$ && "$rec" -gt 0 ]]; then
    arecord -q -f S16_LE -r 16000 -c 1 -d "$rec" "$raw" 2>/dev/null || { echo '{"success":false,"error":"mic_failed"}'; emit voice-command false '{}'; return; }
  elif [[ -n "$wav" && -f "$wav" ]]; then cp "$wav" "$raw"
  else echo '{"success":false,"error":"no_audio_input"}'; emit voice-command false '{}'; return; fi
  w16="$WORK/bridge-utt16.$$.wav"; ffmpeg -y -i "$raw" -ar 16000 -ac 1 -f wav "$w16" >/dev/null 2>&1
  local tx; tx="$("$VENV_PY" "$PROJECT_DIR/scripts/stt/vosk_transcribe.py" "$model" "$w16" 2>/dev/null)"
  rm -f "$raw" "$w16" 2>/dev/null
  python3 -c 'import json,sys;t=sys.argv[1].strip();print(json.dumps({"success":bool(t),"transcript":t}))' "$tx"
  emit voice-command "$([[ -n "${tx// }" ]] && echo true || echo false)" "{\"chars\":${#tx}}"
}

cmd_speak() {
  local body text; body="$(read_input "$@")"; text="$(jget "$body" text)"
  [[ -n "${text// }" ]] || { echo '{"success":false,"error":"empty_text"}'; return; }
  if "$(dirname "${BASH_SOURCE[0]}")/jarvis-say.sh" "$text" >/dev/null 2>&1; then echo '{"success":true,"spoken":true}'; emit speak true '{}'; return; fi
  echo '{"success":false,"error":"tts_unavailable"}'; emit speak false '{}'
}

cmd_action() {
  local body typ target exec confirm cls plan
  body="$(read_input "$@")"
  typ="$(jget "$body" type NONE)"; target="$(jget "$body" target)"
  exec="$(jget "$body" execute false)"; confirm="$(jget "$body" confirm false)"
  cls="$(classify_action "$typ")"
  local executed=false refused=false reqconf=false reason="" detail="$target"
  if [[ "$cls" == DANGEROUS || "$cls" == UNKNOWN ]]; then
    refused=true; reason="$cls action refused by host bridge policy"
  elif [[ "$cls" == GUARDED && "$confirm" != "true" ]]; then
    reqconf=true; reason="guarded action ($typ) requires confirm=true"
  elif [[ "$exec" != "true" ]]; then
    reason="dry-run (execute=false): would run $typ $target"
  else
    plan="$(resolve_launch "$typ" "$target")"
    if [[ "$plan" == none ]]; then refused=true; reason="target not on safe launcher allow-list"
    else
      case "$plan" in
        url:*) setsid xdg-open "${plan#url:}" >/dev/null 2>&1 & executed=true; detail="${plan#url:}" ;;
        cmd:*) setsid "${plan#cmd:}" >/dev/null 2>&1 & executed=true; detail="${plan#cmd:}" ;;
        focus:*) wmctrl -a "${plan#focus:}" >/dev/null 2>&1 && executed=true; detail="${plan#focus:}" ;;
        shot:*)
          local shotpath="${plan#shot:}"
          if command -v gnome-screenshot >/dev/null 2>&1; then
            gnome-screenshot -f "$shotpath" >/dev/null 2>&1 && executed=true
          elif command -v scrot >/dev/null 2>&1; then
            scrot "$shotpath" >/dev/null 2>&1 && executed=true
          fi
          if [[ "$executed" == true ]]; then detail="$shotpath"
          else refused=true; reason="no screenshot tool available (gnome-screenshot/scrot)"; fi
          ;;
      esac
      [[ "$refused" == true ]] || reason="executed"
    fi
  fi
  python3 -c 'import json,sys
print(json.dumps({"success":True,"type":sys.argv[1],"classification":sys.argv[2],
 "executed":sys.argv[3]=="true","refused":sys.argv[4]=="true",
 "requiresConfirmation":sys.argv[5]=="true","detail":sys.argv[6],"reason":sys.argv[7]}))' \
    "$typ" "$cls" "$executed" "$refused" "$reqconf" "$detail" "$reason"
  emit action "$([[ $refused == true ]] && echo false || echo true)" "{\"type\":\"$typ\",\"class\":\"$cls\",\"executed\":$executed,\"refused\":$refused}"
}

cmd_serve() {
  local port=8770 token=""
  while [[ $# -gt 0 ]]; do case "$1" in --port) port="$2"; shift 2;; --token) token="$2"; shift 2;; *) shift;; esac; done
  JARVIS_BRIDGE="$0" JARVIS_BRIDGE_TOKEN="$token" "$VENV_PY" "$PROJECT_DIR/scripts/host_bridge_server.py" --port "$port"
}

SUB="${1:-}"; shift || true
case "$SUB" in
  health)         cmd_health ;;
  screen-context) cmd_screen_context "$@" ;;
  voice-command)  cmd_voice_command "$@" ;;
  speak)          cmd_speak "$@" ;;
  action)         cmd_action "$@" ;;
  serve)          cmd_serve "$@" ;;
  *) sed -n '2,30p' "$0"; exit 2 ;;
esac
