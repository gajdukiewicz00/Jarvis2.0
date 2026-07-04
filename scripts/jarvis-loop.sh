#!/usr/bin/env bash
# =============================================================================
# jarvis-loop.sh — the end-to-end "Jarvis is alive" loop (host-side).
# =============================================================================
# Runs the full local assistant loop on the workstation, because screen capture
# and desktop actions are physically host-bound (a k3s pod cannot reach the X
# display). The reasoning brain is the SAME local Qwen llama.cpp daemon the
# cluster's llm-service uses (host-model-daemon, :18080) — 100% local, no cloud.
#
#   user command
#     -> capture screen + OCR + screen-context (vision-security CLI)
#     -> reason with local LLM (Qwen via llama.cpp) — honest NOT_CONFIGURED if down
#     -> read/write memory (local JSONL store; verifiable read-back)
#     -> decide a next desktop action (structured JSON from the model)
#     -> confirm if invasive, then execute a safe action (xdg-open / xdotool)
#     -> speak the answer (local TTS, spd-say) + print text
#     -> log structured events + point at metrics
#     -> print evidence
#
# Principles: fully local, no cloud APIs, no fake summaries, no fake readiness,
# dangerous actions require confirmation.
#
# Usage:
#   scripts/jarvis-loop.sh ["your command"]
#   scripts/jarvis-loop.sh --dry-run            # decide + speak, never act
#   scripts/jarvis-loop.sh --yes                # auto-confirm allow-listed actions
#   scripts/jarvis-loop.sh --no-act             # skip the desktop action stage
#   scripts/jarvis-loop.sh --no-tts             # skip spoken output
#
# Key env (all have safe local defaults):
#   JARVIS_LLM_ENDPOINT   default http://127.0.0.1:18080  (llama.cpp OpenAI API)
#   JARVIS_LLM_MODEL      default qwen2.5-3b-instruct
#   VISION_JAR            default apps/vision-security-service/target/vision-security-service-1.0.0.jar
#   JARVIS_USER           default owner
#   JARVIS_MEMORY_DIR     default ~/.jarvis/memory
#   JARVIS_LOOP_DIR       default /tmp/jarvis-cv  (artifacts + logs)
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

JARVIS_LLM_ENDPOINT="${JARVIS_LLM_ENDPOINT:-http://127.0.0.1:18080}"
JARVIS_LLM_MODEL="${JARVIS_LLM_MODEL:-qwen3-14b}"
VISION_JAR="${VISION_JAR:-${PROJECT_DIR}/apps/vision-security-service/target/vision-security-service-1.0.0.jar}"
JARVIS_USER="${JARVIS_USER:-owner}"
JARVIS_MEMORY_DIR="${JARVIS_MEMORY_DIR:-${HOME}/.jarvis/memory}"
JARVIS_LOOP_DIR="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"
JARVIS_PERSONA_FILE="${JARVIS_PERSONA_FILE:-${PROJECT_DIR}/scripts/jarvis-persona.txt}"
export JARVIS_PERSONA_FILE
SERVICE_JWT_SECRET="${SERVICE_JWT_SECRET:-}"   # optional: enables cluster memory write

DRY_RUN=false; AUTO_YES=false; DO_ACT=true; DO_TTS=true
COMMAND_TEXT=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --yes|-y)  AUTO_YES=true ;;
    --no-act)  DO_ACT=false ;;
    --no-tts)  DO_TTS=false ;;
    --help|-h) sed -n '2,46p' "$0"; exit 0 ;;
    *)         COMMAND_TEXT="$arg" ;;
  esac
done
COMMAND_TEXT="${COMMAND_TEXT:-Look at my screen, tell me what I am working on, remember it, and open the next useful tool.}"

mkdir -p "$JARVIS_MEMORY_DIR" "$JARVIS_LOOP_DIR"
RUN_ID="loop-$(date +%Y%m%d-%H%M%S)"
EVENTS_LOG="${JARVIS_LOOP_DIR}/${RUN_ID}.events.jsonl"
SCREEN_JSON="${JARVIS_LOOP_DIR}/${RUN_ID}.screen.json"
SHOT_PNG="${JARVIS_LOOP_DIR}/${RUN_ID}.screen.png"
MEMORY_STORE="${JARVIS_MEMORY_DIR}/screen-context.jsonl"

c() { printf '\033[%sm%s\033[0m' "$1" "$2"; }
say() { printf '%s %s\n' "$(c '1;36' '[jarvis]')" "$1"; }
stage() { printf '\n%s %s\n' "$(c '1;35' '==>')" "$(c '1;37' "$1")"; }
event() { # event <stage> <status> <detail-json-fragment>
  python3 - "$1" "$2" "$3" >>"$EVENTS_LOG" <<'PY'
import json,sys,time
print(json.dumps({"ts":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
                  "run":__import__("os").environ.get("RUN_ID"),
                  "stage":sys.argv[1],"status":sys.argv[2],
                  "detail":json.loads(sys.argv[3] or "{}")}))
PY
}
export RUN_ID

say "run=$RUN_ID"
say "command: $COMMAND_TEXT"
event start ok "{\"command\": $(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$COMMAND_TEXT")}"

# --- Stage 1: screen capture + OCR + screen-context ---------------------------
stage "1/7 capture screen + OCR (vision-security)"
if [[ ! -f "$VISION_JAR" ]]; then
  say "$(c '1;31' "VISION JAR missing: $VISION_JAR")"
  say "  next: mvn -pl apps/vision-security-service -am -DskipTests package"
  event screen blocked '{"reason":"vision_jar_missing"}'
  exit 4
fi
SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none \
SERVICE_JWT_SECRET="${SERVICE_JWT_SECRET:-loop-local}" \
  java -jar "$VISION_JAR" --cv.screen-context=true --cv.user="$JARVIS_USER" \
       --cv.output="$SHOT_PNG" 2>/dev/null \
  | python3 -c 'import sys,json
dec=json.JSONDecoder()
d=sys.stdin.read()
for i,ch in enumerate(d):
    if ch=="{" and (i==0 or d[i-1]=="\n"):
        try:
            o,_=dec.raw_decode(d[i:])
            if "success" in o: print(json.dumps(o)); break
        except Exception: pass' > "$SCREEN_JSON" 2>/dev/null

if [[ ! -s "$SCREEN_JSON" ]]; then
  say "$(c '1;31' 'screen capture failed')"; event screen error '{}'; exit 4
fi
OCR_CHARS=$(python3 -c 'import json;d=json.load(open("'"$SCREEN_JSON"'"));print(len((d.get("analysis") or {}).get("ocrText") or ""))')
WINDOW=$(python3 -c 'import json;d=json.load(open("'"$SCREEN_JSON"'"));print((d.get("activeWindowTitle") or "")[:80])')
TAGS=$(python3 -c 'import json;d=json.load(open("'"$SCREEN_JSON"'"));print(",".join(d.get("semanticTags") or []))')
say "captured: window='${WINDOW}' tags=[${TAGS}] ocrChars=${OCR_CHARS} shot=${SHOT_PNG}"
event screen ok "{\"ocrChars\":${OCR_CHARS:-0},\"window\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$WINDOW")}"

# --- Stage 2: reason with the local LLM --------------------------------------
stage "2/7 reason with local LLM (Qwen via llama.cpp)"
LLM_HEALTH=$(curl -s -o /dev/null -m 4 -w "%{http_code}" "${JARVIS_LLM_ENDPOINT}/health" 2>/dev/null)
DECISION_JSON="${JARVIS_LOOP_DIR}/${RUN_ID}.decision.json"
if [[ "$LLM_HEALTH" != "200" ]]; then
  say "$(c '1;33' "LLM NOT_CONFIGURED/UNAVAILABLE (health=${LLM_HEALTH:-000} at ${JARVIS_LLM_ENDPOINT})")"
  say "  next: start the daemon ->"
  say "    ${PROJECT_DIR}/scripts/jarvis-llm-daemon.sh start   (or: systemctl --user start jarvis-llm@18080)"
  printf '{"availability":"UNAVAILABLE","summary":null,"next_action":{"type":"none","target":"","reason":"local LLM not reachable","dangerous":false}}' > "$DECISION_JSON"
  event reason unavailable "{\"health\":\"${LLM_HEALTH:-000}\"}"
else
  PROMPT_FILE="${JARVIS_LOOP_DIR}/${RUN_ID}.prompt.json"
  python3 - "$SCREEN_JSON" "$COMMAND_TEXT" "$JARVIS_LLM_MODEL" > "$PROMPT_FILE" <<'PY'
import json,sys,os
scr=json.load(open(sys.argv[1])); cmd=sys.argv[2]; model=sys.argv[3]
an=scr.get("analysis") or {}
ocr=(an.get("ocrText") or "")[:3500]
persona=""
pf=os.environ.get("JARVIS_PERSONA_FILE","")
if pf and os.path.exists(pf):
    try: persona=open(pf,encoding="utf-8").read().strip()+"\n\n"
    except Exception: persona=""
sysmsg=(persona+
        "TASK: Using ONLY the screen context provided, answer the principal. "
        "Respond with STRICT JSON and nothing else: {\"summary\": <1-2 sentences, in "
        "J.A.R.V.I.S.'s voice, on what the user is working on or your reply>, "
        "\"next_action\": {\"type\": \"open_app|open_url|none\", \"target\": <app "
        "name like terminal/editor/browser/files OR a https URL>, \"reason\": "
        "<short>, \"dangerous\": <true|false>}}. If the user asks you to open or "
        "launch a tool, you MUST set type=open_app or open_url with a concrete target "
        "(one of: terminal, editor, browser, files, or a https URL) and never type=none "
        "in that case. Keep summary concise and in character. Do not emit thinking, "
        "tags, or prose outside the JSON. Never invent screen content you cannot see. /no_think")
import re as _re
if _re.search(r"[Ѐ-ӿ]", cmd):
    sysmsg+=" IMPORTANT: the principal wrote in Russian — the \"summary\" value MUST be written in Russian."
usr=(f"User command: {cmd}\n\nActive window: {scr.get('activeWindowTitle','')}\n"
     f"Process: {scr.get('activeProcessName','')}\nTags: {scr.get('semanticTags',[])}\n"
     f"OCR text from the screen:\n{ocr}")
print(json.dumps({"model":model,"temperature":0.4,"max_tokens":512,
                  "messages":[{"role":"system","content":sysmsg},
                              {"role":"user","content":usr}]}))
PY
  RAW="${JARVIS_LOOP_DIR}/${RUN_ID}.llm-raw.json"
  curl -s -m 120 "${JARVIS_LLM_ENDPOINT}/v1/chat/completions" \
       -H 'Content-Type: application/json' --data @"$PROMPT_FILE" > "$RAW" 2>/dev/null
  python3 - "$RAW" "$DECISION_JSON" <<'PY'
import json,sys,re
try:
    o=json.load(open(sys.argv[1]))
    content=o["choices"][0]["message"]["content"].strip()
except Exception as e:
    content=""
m=re.search(r"\{.*\}", content, re.S)
out={"availability":"READY","summary":content or None,
     "next_action":{"type":"none","target":"","reason":"unparsed","dangerous":False}}
if m:
    try:
        parsed=json.loads(m.group(0))
        out["summary"]=parsed.get("summary") or content
        na=parsed.get("next_action") or {}
        out["next_action"]={"type":na.get("type","none"),"target":na.get("target",""),
                            "reason":na.get("reason",""),"dangerous":bool(na.get("dangerous",False))}
    except Exception: pass
json.dump(out, open(sys.argv[2],"w"))
PY
  SUMMARY=$(python3 -c 'import json;print((json.load(open("'"$DECISION_JSON"'")).get("summary") or "")[:500])')
  say "$(c '1;32' 'LLM READY')"
  say "summary: ${SUMMARY}"
  event reason ok '{"availability":"READY"}'
fi

ACT_TYPE=$(python3 -c 'import json;print((json.load(open("'"$DECISION_JSON"'")).get("next_action") or {}).get("type","none"))')
ACT_TARGET=$(python3 -c 'import json;print((json.load(open("'"$DECISION_JSON"'")).get("next_action") or {}).get("target",""))')
ACT_DANGER=$(python3 -c 'import json;print(str((json.load(open("'"$DECISION_JSON"'")).get("next_action") or {}).get("dangerous",False)).lower())')
SUMMARY=$(python3 -c 'import json;print(json.load(open("'"$DECISION_JSON"'")).get("summary") or "I could not produce a summary because the local model is unavailable.")')

# --- Stage 3: memory write + read-back ---------------------------------------
stage "3/7 memory write + read-back"
python3 - "$MEMORY_STORE" "$SCREEN_JSON" "$DECISION_JSON" "$RUN_ID" "$JARVIS_USER" <<'PY'
import json,sys,time
store,scr_p,dec_p,run,user=sys.argv[1:6]
scr=json.load(open(scr_p)); dec=json.load(open(dec_p)); an=scr.get("analysis") or {}
rec={"ts":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"run":run,"userId":user,
     "window":scr.get("activeWindowTitle"),"process":scr.get("activeProcessName"),
     "tags":scr.get("semanticTags"),"ocrChars":len(an.get("ocrText") or ""),
     "screenshotPath":scr.get("screenshotPath"),
     "summary":dec.get("summary"),"decidedAction":dec.get("next_action")}
open(store,"a").write(json.dumps(rec)+"\n")
print("WROTE memory record to", store)
PY
say "read-back (last memory record):"
tail -1 "$MEMORY_STORE" | python3 -c 'import json,sys;r=json.loads(sys.stdin.read());print("   ts=%s window=%s summary=%s"%(r["ts"],(r.get("window") or "")[:40],(r.get("summary") or "")[:80]))'
MEM_COUNT=$(wc -l < "$MEMORY_STORE")
event memory ok "{\"store\":\"$MEMORY_STORE\",\"records\":${MEM_COUNT}}"
# Optional: also push this observation to the cluster memory-service (pgvector)
# so the assistant remembers what you were working on. No-op without a token.
if [[ -n "$SERVICE_JWT_SECRET" ]]; then
  "${PROJECT_DIR}/scripts/jarvis-memory-sync.sh" "screen-context" \
     "[screen] window='${WINDOW}' tags=[${TAGS}] — ${SUMMARY:-}" 2>&1 | sed 's/^/   /' || true
fi
# Obsidian daily journal: Jarvis quietly keeps a diary of what you were doing.
if [[ "${JARVIS_OBSIDIAN_ENABLED:-true}" != "false" && -f "${PROJECT_DIR}/scripts/jarvis-obsidian.py" ]]; then
  python3 "${PROJECT_DIR}/scripts/jarvis-obsidian.py" daily \
     --text "[${WINDOW}] ${SUMMARY:-}" >/dev/null 2>&1 \
     && say "journaled to Obsidian (01_Daily)" || true
fi

# --- Stage 4: decide + confirm ------------------------------------------------
stage "4/7 decide next action"
say "decided action: type=${ACT_TYPE} target='${ACT_TARGET}' dangerous=${ACT_DANGER}"

# Map a model target to an allow-listed local launcher. Unknown -> blocked.
resolve_launch() {
  local t; t="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$t" in
    *http://*|*https://*) echo "url:$1" ;;
    *terminal*|*shell*|*konsole*|*bash*) command -v gnome-terminal >/dev/null && echo "cmd:gnome-terminal" || echo "cmd:xterm" ;;
    *code*|*vscode*|*editor*) command -v code >/dev/null && echo "cmd:code" || { command -v gedit >/dev/null && echo "cmd:gedit" || echo "cmd:xdg-open:/home"; } ;;
    *files*|*nautilus*|*explorer*) command -v nautilus >/dev/null && echo "cmd:nautilus" || echo "none" ;;
    *browser*|*firefox*|*chrome*|*web*) echo "url:https://duckduckgo.com" ;;
    *) echo "none" ;;
  esac
}

# --- Stage 5: act (safe, with confirmation) ----------------------------------
stage "5/7 execute action"
ACTION_DONE="none"; ACTION_DETAIL=""
if ! $DO_ACT; then
  say "skipping action (--no-act)"
elif [[ "$ACT_TYPE" == "none" || -z "$ACT_TARGET" ]]; then
  say "no actionable next step decided"
elif [[ "$ACT_DANGER" == "true" ]]; then
  say "$(c '1;31' 'action flagged DANGEROUS — refusing without explicit confirmation')"
  event act blocked '{"reason":"dangerous"}'
else
  PLAN="$(resolve_launch "$ACT_TARGET")"
  if [[ "$PLAN" == "none" ]]; then
    say "target '${ACT_TARGET}' is not on the safe launcher allow-list — not executing"
    event act blocked '{"reason":"not_allowlisted","target":"'"$ACT_TARGET"'"}'
  else
    say "plan: $PLAN"
    PROCEED=$AUTO_YES
    if $DRY_RUN; then
      say "$(c '1;33' '[dry-run] would execute the plan above, not executing')"
      ACTION_DONE="dry-run"; ACTION_DETAIL="$PLAN"
    else
      if ! $PROCEED; then
        printf '%s' "$(c '1;33' 'Execute this action? [y/N] ')"; read -r ans </dev/tty 2>/dev/null || ans="n"
        [[ "$ans" =~ ^[Yy]$ ]] && PROCEED=true
      fi
      if $PROCEED; then
        case "$PLAN" in
          url:*)  xdg-open "${PLAN#url:}" >/dev/null 2>&1 & ACTION_DONE="open_url"; ACTION_DETAIL="${PLAN#url:}" ;;
          cmd:xdg-open:*) xdg-open "${PLAN#cmd:xdg-open:}" >/dev/null 2>&1 & ACTION_DONE="open"; ACTION_DETAIL="${PLAN#cmd:xdg-open:}" ;;
          cmd:*)  setsid "${PLAN#cmd:}" >/dev/null 2>&1 & ACTION_DONE="open_app"; ACTION_DETAIL="${PLAN#cmd:}" ;;
        esac
        say "$(c '1;32' "executed: ${ACTION_DONE} ${ACTION_DETAIL}")"
      else
        say "user declined the action"; ACTION_DONE="declined"
      fi
    fi
  fi
fi
event act ok "{\"done\":\"${ACTION_DONE}\",\"detail\":\"${ACTION_DETAIL}\"}"

# --- Stage 6: speak + answer --------------------------------------------------
stage "6/7 respond (text + local TTS)"
ANSWER="$SUMMARY"
case "$ACTION_DONE" in
  open_app|open|open_url) ANSWER="$SUMMARY I have opened ${ACTION_DETAIL} for you." ;;
  dry-run) ANSWER="$SUMMARY I would open ${ACTION_DETAIL}." ;;
esac
printf '%s %s\n' "$(c '1;36' '[jarvis answer]')" "$ANSWER"
if $DO_TTS; then
  "$(dirname "${BASH_SOURCE[0]}")/jarvis-say.sh" "$ANSWER" && say "(spoken via jarvis-say: Piper/spd-say)" || say "(TTS attempt failed; text returned)"
fi
event respond ok '{"spoken":'"$DO_TTS"'}'

# --- Stage 7: evidence --------------------------------------------------------
stage "7/7 evidence"
say "events:     $EVENTS_LOG"
say "screen:     $SCREEN_JSON  (shot: $SHOT_PNG)"
say "decision:   $DECISION_JSON"
say "memory:     $MEMORY_STORE  (records: $MEM_COUNT)"
say "cv metrics: curl -s http://127.0.0.1:18094/actuator/prometheus | grep jarvis_cv   (when the service runs in web mode)"
say "$(c '1;32' 'Jarvis loop complete.')"
event done ok "{\"action\":\"${ACTION_DONE}\"}"
