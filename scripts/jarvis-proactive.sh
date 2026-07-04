#!/usr/bin/env bash
# =============================================================================
# jarvis-proactive.sh — always-on proactive awareness loop (host-side).
# =============================================================================
# This is the trait that makes J.A.R.V.I.S. feel alive: it periodically observes
# what is on screen and decides — entirely on its own — whether it has something
# genuinely worth saying. It is heavily biased toward SILENCE. A good butler
# speaks only when it helps.
#
#   every N seconds (outside quiet hours, respecting an anti-spam gap):
#     -> capture screen context (window + OCR) via the vision-security CLI
#     -> ask the local LLM, in J.A.R.V.I.S.'s voice: "anything worth a remark?"
#     -> if the model says SILENT (the common case) -> stay quiet
#     -> otherwise speak ONE short line via jarvis-say.sh (Piper neural voice)
#
# 100% local: local llama.cpp brain + local Piper TTS. No cloud, no outside ears.
#
# Subcommands:
#   once       run a single observation tick and print the decision (test)
#   run        run the loop in the foreground
#   install    install a systemd --user unit (never auto-starts on its own)
#   start|stop|status|logs   manage the systemd unit
#
# Key env (safe local defaults):
#   JARVIS_LLM_ENDPOINT        http://127.0.0.1:18080
#   JARVIS_PROACTIVE_INTERVAL  seconds between observations          (default 90)
#   JARVIS_PROACTIVE_MIN_GAP   min seconds between spoken remarks     (default 600)
#   JARVIS_QUIET_START         hour [0-23] quiet period begins        (default 23)
#   JARVIS_QUIET_END           hour [0-23] quiet period ends          (default 8)
#   JARVIS_PROACTIVE_SPEAK     true|false actually speak              (default true)
#   JARVIS_PERSONA_FILE        persona text  (default scripts/jarvis-persona.txt)
#   VISION_JAR                 vision-security CLI jar
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS_DIR="${PROJECT_DIR}/scripts"

JARVIS_LLM_ENDPOINT="${JARVIS_LLM_ENDPOINT:-http://127.0.0.1:18080}"
JARVIS_LLM_MODEL="${JARVIS_LLM_MODEL:-qwen3-14b}"
INTERVAL="${JARVIS_PROACTIVE_INTERVAL:-90}"
MIN_GAP="${JARVIS_PROACTIVE_MIN_GAP:-600}"
QUIET_START="${JARVIS_QUIET_START:-23}"
QUIET_END="${JARVIS_QUIET_END:-8}"
DO_SPEAK="${JARVIS_PROACTIVE_SPEAK:-true}"
JARVIS_USER="${JARVIS_USER:-owner}"
JARVIS_PERSONA_FILE="${JARVIS_PERSONA_FILE:-${SCRIPTS_DIR}/jarvis-persona.txt}"
VISION_JAR="${VISION_JAR:-${PROJECT_DIR}/apps/vision-security-service/target/vision-security-service-1.0.0.jar}"

STATE_DIR="${HOME}/.jarvis/state"; mkdir -p "$STATE_DIR"
LOG_DIR="${HOME}/.jarvis/logs"; mkdir -p "$LOG_DIR"
LAST_FILE="${STATE_DIR}/proactive.last"
REMARKS_FILE="${STATE_DIR}/proactive.remarks"
PLOG="${LOG_DIR}/proactive.jsonl"
WORK="$(mktemp -d /tmp/jarvis-proactive.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

c() { printf '\033[%sm%s\033[0m' "$1" "$2"; }
log() { printf '%s %s\n' "$(c '1;35' '[proactive]')" "$1"; }

now_epoch() { date +%s; }
cur_hour()  { date +%-H; }

in_quiet_hours() {
  local h; h="$(cur_hour)"
  if [ "$QUIET_START" -gt "$QUIET_END" ]; then
    [ "$h" -ge "$QUIET_START" ] || [ "$h" -lt "$QUIET_END" ]
  else
    [ "$h" -ge "$QUIET_START" ] && [ "$h" -lt "$QUIET_END" ]
  fi
}

spoke_recently() {
  local last; last="$(cat "$LAST_FILE" 2>/dev/null || echo 0)"
  local gap=$(( $(now_epoch) - last ))
  [ "$gap" -lt "$MIN_GAP" ]
}

# Capture screen context -> echoes "window<TAB>tags<TAB>ocr(trunc)". Empty on failure.
capture_screen() {
  [ -f "$VISION_JAR" ] || { echo -e "\t\t"; return; }
  local shot="${WORK}/shot.png" sj="${WORK}/screen.json"
  SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  SERVICE_JWT_SECRET="${SERVICE_JWT_SECRET:-loop-local}" \
    java -jar "$VISION_JAR" --cv.screen-context=true --cv.user="$JARVIS_USER" \
         --cv.output="$shot" 2>/dev/null \
    | python3 -c 'import sys,json
dec=json.JSONDecoder(); d=sys.stdin.read()
for i,ch in enumerate(d):
    if ch=="{" and (i==0 or d[i-1]=="\n"):
        try:
            o,_=dec.raw_decode(d[i:])
            if "success" in o: print(json.dumps(o)); break
        except Exception: pass' > "$sj" 2>/dev/null
  [ -s "$sj" ] || { echo -e "\t\t"; return; }
  python3 - "$sj" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
an=d.get("analysis") or {}
win=(d.get("activeWindowTitle") or "")[:120]
tags=",".join(d.get("semanticTags") or [])
ocr=(an.get("ocrText") or "")[:2000].replace("\t"," ").replace("\n"," ")
print(f"{win}\t{tags}\t{ocr}")
PY
}

# Ask the LLM whether to speak. Echoes "SILENT" or a single spoken line.
decide() {
  local window="$1" tags="$2" ocr="$3"
  local health; health="$(curl -s -o /dev/null -m 4 -w '%{http_code}' "${JARVIS_LLM_ENDPOINT}/health" 2>/dev/null)"
  [ "$health" = "200" ] || { echo "SILENT"; return; }
  local recent; recent="$(tail -n 5 "$REMARKS_FILE" 2>/dev/null | tr '\n' '|')"
  JARVIS_PERSONA_FILE="$JARVIS_PERSONA_FILE" \
  python3 - "$JARVIS_LLM_ENDPOINT" "$JARVIS_LLM_MODEL" "$window" "$tags" "$ocr" "$recent" <<'PY'
import json,sys,os,urllib.request,re
endpoint,model,window,tags,ocr,recent=sys.argv[1:7]
persona=""
pf=os.environ.get("JARVIS_PERSONA_FILE","")
if pf and os.path.exists(pf):
    try: persona=open(pf,encoding="utf-8").read().strip()+"\n\n"
    except Exception: persona=""
sysmsg=(persona+
 "MODE: PASSIVE PROACTIVE OBSERVATION. You are quietly watching the principal's screen. "
 "Decide whether there is ONE genuinely useful, timely thing worth saying right now — a real "
 "risk, a forgotten task, an anomaly, or a concretely helpful suggestion grounded in what you "
 "can actually see. Be extremely selective: silence is the default and is correct the vast "
 "majority of the time. Do NOT comment merely to fill the air, narrate the obvious, or repeat "
 "anything similar to your recent remarks. If there is nothing truly worth interrupting for, "
 "reply with exactly the single word: SILENT. Otherwise reply with ONE short spoken sentence in "
 "your own voice (no quotes, no JSON, no preamble). /no_think")
usr=(f"Active window: {window}\nTags: {tags}\nRecent remarks (avoid repeating): {recent}\n"
     f"Screen text (OCR, truncated):\n{ocr}")
body=json.dumps({"model":model,"temperature":0.5,"max_tokens":80,
  "messages":[{"role":"system","content":sysmsg},{"role":"user","content":usr}]}).encode()
try:
    req=urllib.request.Request(endpoint+"/v1/chat/completions",data=body,
        headers={"Content-Type":"application/json"})
    r=json.load(urllib.request.urlopen(req,timeout=60))
    txt=r["choices"][0]["message"]["content"]
except Exception:
    print("SILENT"); sys.exit(0)
txt=re.sub(r"<think>.*?</think>","",txt,flags=re.S).strip()
txt=txt.strip().strip('"').strip()
if not txt or re.fullmatch(r"(?i)silent[.!]?",txt):
    print("SILENT")
else:
    print(txt.splitlines()[0][:300])
PY
}

# crude near-duplicate guard against the last few remarks
is_repeat() {
  local line="$1"
  local norm; norm="$(printf '%s' "$line" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:] ')"
  [ -z "$norm" ] && return 0
  tail -n 5 "$REMARKS_FILE" 2>/dev/null | while IFS= read -r prev; do
    local pn; pn="$(printf '%s' "$prev" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:] ')"
    [ "$pn" = "$norm" ] && exit 0
  done
  return 1
}

tick() {
  local quiet_ok=true
  if in_quiet_hours; then log "quiet hours — observing only"; quiet_ok=false; fi
  IFS=$'\t' read -r window tags ocr < <(capture_screen)
  log "observed: window='${window:0:60}' tags=[${tags}] ocr=${#ocr}ch"
  # Continuous screen-context -> cluster memory (no-op without SERVICE_JWT_SECRET)
  if [ -n "${SERVICE_JWT_SECRET:-}" ] && [ -n "$ocr" ]; then
    "${SCRIPTS_DIR}/jarvis-memory-sync.sh" "screen-context" \
       "[screen] window='${window}' tags=[${tags}]" >/dev/null 2>&1 || true
  fi
  local remark; remark="$(decide "$window" "$tags" "$ocr")"
  if [ "$remark" = "SILENT" ]; then
    log "decision: $(c '1;30' 'SILENT')"
    printf '{"ts":"%s","spoke":false,"window":%s}\n' "$(date -u +%FT%TZ)" "$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$window")" >> "$PLOG"
    return
  fi
  if is_repeat "$remark"; then log "decision: suppressed (near-duplicate)"; return; fi
  log "decision: $(c '1;32' "SPEAK: $remark")"
  printf '%s\n' "$remark" >> "$REMARKS_FILE"
  printf '{"ts":"%s","spoke":true,"remark":%s}\n' "$(date -u +%FT%TZ)" "$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$remark")" >> "$PLOG"
  if [ "$quiet_ok" = true ] && [ "$DO_SPEAK" = true ] && ! spoke_recently; then
    "${SCRIPTS_DIR}/jarvis-say.sh" "$remark" >/dev/null 2>&1 || true
    now_epoch > "$LAST_FILE"
  else
    log "(not spoken: quiet/anti-spam/speak-disabled)"
  fi
}

run_loop() {
  log "starting loop: interval=${INTERVAL}s min-gap=${MIN_GAP}s quiet=${QUIET_START}->${QUIET_END} speak=${DO_SPEAK}"
  while true; do tick; sleep "$INTERVAL"; done
}

UNIT_NAME="jarvis-proactive.service"
UNIT_PATH="${HOME}/.config/systemd/user/${UNIT_NAME}"
install_unit() {
  mkdir -p "$(dirname "$UNIT_PATH")"
  cat > "$UNIT_PATH" <<EOF
[Unit]
Description=J.A.R.V.I.S. proactive awareness loop
After=default.target

[Service]
Type=simple
ExecStart=${SCRIPTS_DIR}/jarvis-proactive.sh run
Restart=on-failure
RestartSec=5
Environment=JARVIS_PROACTIVE_INTERVAL=${INTERVAL}
Environment=JARVIS_PROACTIVE_MIN_GAP=${MIN_GAP}

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  log "installed unit: $UNIT_PATH"
  log "start with: systemctl --user start ${UNIT_NAME}   (never auto-started by this script)"
}

case "${1:-once}" in
  once)    tick ;;
  run)     run_loop ;;
  install) install_unit ;;
  start)   systemctl --user start "$UNIT_NAME" && log "started" ;;
  stop)    systemctl --user stop "$UNIT_NAME" && log "stopped" ;;
  status)  systemctl --user status "$UNIT_NAME" --no-pager 2>&1 | head -15 ;;
  logs)    tail -n 40 "$PLOG" 2>/dev/null || log "no log yet" ;;
  *)       echo "usage: $0 {once|run|install|start|stop|status|logs}"; exit 2 ;;
esac
