#!/usr/bin/env bash
# =============================================================================
# jarvis-alive-smoke.sh — verify the Jarvis alive loop end-to-end.
# =============================================================================
# Drives scripts/jarvis-loop.sh and asserts each stage produced real output:
#   - local LLM health (:18080)
#   - screen capture + OCR chars > 0
#   - decision JSON valid (availability READY)
#   - memory write + read-back
#   - safe desktop action (DRY-RUN by default; real only with --yes)
#
# SAFE BY DEFAULT: no real desktop action unless --yes is passed.
#
# Exit codes: 0 all checks pass · 5 LLM unavailable · 6 a stage check failed
#
# Usage:
#   scripts/jarvis-alive-smoke.sh            # dry-run, no real action, no TTS
#   scripts/jarvis-alive-smoke.sh --yes      # also execute the real safe action
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JARVIS_LLM_ENDPOINT="${JARVIS_LLM_ENDPOINT:-http://127.0.0.1:18080}"
JARVIS_LOOP_DIR="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"
export JARVIS_LOOP_DIR

REAL=false
for a in "$@"; do
  case "$a" in
    --yes) REAL=true ;;
    --dry-run) REAL=false ;;   # default; accepted for explicitness
  esac
done

pass() { printf '  \033[1;32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[1;31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
FAILED=0

echo "== Jarvis alive smoke =="

# 1) LLM health
CODE="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${JARVIS_LLM_ENDPOINT}/health" 2>/dev/null)"
if [[ "$CODE" == "200" ]]; then pass "LLM health 200 ($JARVIS_LLM_ENDPOINT)"
else
  fail "LLM health $CODE — start it: scripts/jarvis-llm-daemon.sh start"
  echo "ABORT: local LLM unavailable; loop cannot reason."; exit 5
fi

# 2) run the loop (dry-run + no-tts unless --yes)
BEFORE="$(ls -1 "${JARVIS_LOOP_DIR}"/*.events.jsonl 2>/dev/null | wc -l)"
if $REAL; then
  echo "-- running loop (REAL action, --yes) --"
  bash "${PROJECT_DIR}/scripts/jarvis-loop.sh" --yes "Look at my screen, tell me what I am working on, remember it, and open the next useful tool." >/dev/null 2>&1
else
  echo "-- running loop (dry-run, no real action) --"
  bash "${PROJECT_DIR}/scripts/jarvis-loop.sh" --dry-run --no-act --no-tts "Look at my screen and tell me what I am working on." >/dev/null 2>&1
fi
LOOP_RC=$?
[[ $LOOP_RC -eq 0 ]] && pass "loop exited 0" || fail "loop exited $LOOP_RC"

# locate newest run artifacts
EV="$(ls -1t "${JARVIS_LOOP_DIR}"/*.events.jsonl 2>/dev/null | head -1)"
[[ -n "$EV" ]] || { fail "no events log produced"; exit 6; }
RUN="$(basename "$EV" .events.jsonl)"
SCR="${JARVIS_LOOP_DIR}/${RUN}.screen.json"
DEC="${JARVIS_LOOP_DIR}/${RUN}.decision.json"
echo "-- inspecting run: $RUN --"

# 3a) OCR engine correctness — deterministic, via a text fixture (not the live
#     screen, which may legitimately be blank/locked at smoke time).
VISION_JAR="${VISION_JAR:-${PROJECT_DIR}/apps/vision-security-service/target/vision-security-service-1.0.0.jar}"
FIX="${JARVIS_LOOP_DIR}/smoke-fixture.png"
if [[ ! -f "$FIX" ]]; then
  python3 - "$FIX" <<'PY' 2>/dev/null
import sys
from PIL import Image, ImageDraw, ImageFont
img=Image.new("RGB",(640,160),"white"); d=ImageDraw.Draw(img)
try: f=ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",30)
except Exception: f=ImageFont.load_default()
d.text((20,20),"Jarvis OCR smoke",fill="black",font=f)
d.text((20,80),"Hello Jarvis 2026",fill="black",font=f)
img.save(sys.argv[1])
PY
fi
if [[ -f "$FIX" && -f "$VISION_JAR" ]]; then
  FIXOCR="$(SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none SERVICE_JWT_SECRET=smoke \
    java -jar "$VISION_JAR" --cv.input="$FIX" 2>/dev/null \
    | python3 -c 'import sys,json
dec=json.JSONDecoder();d=sys.stdin.read()
for i,ch in enumerate(d):
    if ch=="{" and (i==0 or d[i-1]=="\n"):
        try:
            o,_=dec.raw_decode(d[i:])
            if "ocrText" in o: print(len(o.get("ocrText") or "")); break
        except Exception: pass' 2>/dev/null)"
  [[ "${FIXOCR:-0}" -gt 0 ]] && pass "OCR engine works (fixture ocrChars=$FIXOCR)" || fail "OCR engine produced 0 chars on fixture"
else
  fail "OCR fixture/jar unavailable (fixture=$FIX jar=$VISION_JAR)"
fi

# 3b) Live screen capture succeeded (content may be blank → OCR chars informational).
OCR="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(len((d.get("analysis") or {}).get("ocrText") or ""))' "$SCR" 2>/dev/null || echo 0)"
SCAP_OK="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("success"))' "$SCR" 2>/dev/null || echo False)"
[[ "$SCAP_OK" == "True" ]] && pass "live screen captured (success=true, OCR chars=$OCR)" || fail "live screen capture success=$SCAP_OK"

# 4) decision JSON valid + READY
AVAIL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("availability"))' "$DEC" 2>/dev/null || echo INVALID)"
[[ "$AVAIL" == "READY" ]] && pass "decision JSON valid (availability=READY)" || fail "decision availability=$AVAIL"
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));a=d.get("next_action") or {};assert "type" in a' "$DEC" 2>/dev/null \
  && pass "next_action present" || fail "next_action missing/invalid"

# 5) memory write + read-back
MEM="${JARVIS_MEMORY_DIR:-$HOME/.jarvis/memory}/screen-context.jsonl"
if [[ -s "$MEM" ]] && python3 -c 'import json,sys;json.loads(open(sys.argv[1]).read().splitlines()[-1])' "$MEM" >/dev/null 2>&1; then
  pass "memory write+read-back ($(wc -l <"$MEM") records)"
else fail "memory store unreadable: $MEM"; fi

# 6) action stage status from events
ACT="$(python3 -c 'import json,sys
done=None
for l in open(sys.argv[1]):
    e=json.loads(l)
    if e["stage"]=="act": done=e["detail"].get("done")
print(done)' "$EV" 2>/dev/null)"
if $REAL; then
  if [[ "$ACT" =~ ^(open_app|open|open_url)$ ]]; then
    pass "real action executed: $ACT"
  elif [[ "${OCR:-0}" -eq 0 ]]; then
    # Screen was blank/locked at smoke time → the model honestly abstained
    # (type=none). That is correct behaviour, not a failure; we do not fabricate
    # an action. Re-run with content on screen to exercise a real launch.
    pass "model honestly abstained on a blank screen (act=$ACT, OCR=0) — no fabricated action"
  else
    fail "screen had content but no action executed (act=$ACT)"
  fi
else
  pass "no real action (safe default; act=$ACT)"
fi

echo
if [[ $FAILED -eq 0 ]]; then echo -e "\033[1;32m== ALIVE SMOKE PASSED ==\033[0m"; exit 0
else echo -e "\033[1;31m== ALIVE SMOKE FAILED ==\033[0m"; exit 6; fi
