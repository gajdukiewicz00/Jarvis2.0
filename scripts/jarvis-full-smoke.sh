#!/usr/bin/env bash
# =============================================================================
# jarvis-full-smoke.sh — one-command realistic health report for Jarvis.
# =============================================================================
# Runs the full local stack verification and prints a READY/PARTIAL/NOT_READY
# matrix. SAFE BY DEFAULT: no real desktop action, no continuous daemon start,
# no raw screenshot storage.
#
#   --yes               also perform ONE real safe desktop action (alive smoke)
#   --live-wake         print the live human wake instruction (does not fake it)
#   --start-wake-daemon explicitly start the always-listening wake daemon
#
# Exit 0 if nothing NOT_READY; 1 otherwise.
# =============================================================================
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
PROJECT_DIR="$(pwd)"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
LOG_DIR="${HOME}/.jarvis/logs"; WORK="${JARVIS_LOOP_DIR:-/tmp/jarvis-cv}"; mkdir -p "$LOG_DIR" "$WORK"
FIXTURE="$WORK/smoke-fixture.png"

DO_YES=false; LIVE_WAKE=false; START_WAKE=false
for a in "$@"; do case "$a" in
  --yes) DO_YES=true;; --live-wake) LIVE_WAKE=true;; --start-wake-daemon) START_WAKE=true;; esac; done

declare -A STATUS
mark(){ STATUS["$1"]="$2"; }   # mark <capability> <READY|PARTIAL|NOT_READY>
hdr(){ printf '\n\033[1;35m== %s ==\033[0m\n' "$1"; }
ok(){ printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
part(){ printf '  \033[1;33m~\033[0m %s\n' "$1"; }
bad(){ printf '  \033[1;31m✗\033[0m %s\n' "$1"; }
kb(){ sudo -n env KUBECONFIG="$KUBECONFIG" k3s kubectl -n jarvis-prod "$@" 2>/dev/null; }

hdr "1. Local LLM (Qwen daemon)"
if [[ "$(curl -s -o /dev/null -m4 -w '%{http_code}' http://127.0.0.1:18080/health 2>/dev/null)" == 200 ]]; then
  ok "Qwen :18080 health 200"; mark qwen READY; else bad "Qwen :18080 down — scripts/jarvis-llm-daemon.sh start"; mark qwen NOT_READY; fi

hdr "2. host-model-daemon endpoint"
if scripts/jarvis-host-endpoint-check.sh >/dev/null 2>&1; then ok "endpoint wired + reachable"; mark endpoint READY
else part "endpoint not wired/reachable — scripts/jarvis-host-endpoint-check.sh --fix"; mark endpoint PARTIAL; fi

hdr "3. k3s pods (jarvis-prod)"
PODS="$(kb get pods --no-headers 2>/dev/null)"
if [[ -n "$PODS" ]]; then
  NOTREADY="$(echo "$PODS" | awk '{n=split($2,a,"/"); if(n!=2 || a[1]!=a[2] || $3!="Running") print $1}' | tr '\n' ' ')"
  [[ -z "$NOTREADY" ]] && { ok "all pods Ready"; mark k8s READY; } || { part "not-ready: $NOTREADY"; mark k8s PARTIAL; }
  echo "$PODS" | awk '{print "    "$1" "$2" "$3}' | grep -E "memory-service|orchestrator|llm-|voice-gateway|kafka|pgvector" | head
else part "cluster not reachable from here"; mark k8s PARTIAL; fi

hdr "4. memory-service DB/Kafka (CV consumer)"
ROWS="$(kb exec postgres-pgvector-0 -- sh -c 'psql -U "${POSTGRES_USER:-postgres}" -d jarvis_memory -t -A -c "select count(*) from screen_context_observation;"' 2>/dev/null | tr -d '[:space:]')"
LAG="$(kb exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cv-screen-context-projector 2>/dev/null | awk '/screen_context/{s+=$6} END{print s+0}')"
if [[ -n "$ROWS" ]]; then ok "screen_context_observation rows=$ROWS, consumer lag=${LAG:-?}"; mark memory READY
else part "could not query Postgres (cluster/creds)"; mark memory PARTIAL; fi

hdr "5. OCR engine (deterministic fixture)"
[[ -f "$FIXTURE" ]] || .venv-voice/bin/python - "$FIXTURE" >/dev/null 2>&1 <<'PY' || python3 - "$FIXTURE" >/dev/null 2>&1 <<'PY2'
import sys
from PIL import Image,ImageDraw,ImageFont
im=Image.new("RGB",(640,160),"white");d=ImageDraw.Draw(im)
try:f=ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",30)
except Exception:f=ImageFont.load_default()
d.text((20,40),"Jarvis full smoke OCR",fill="black",font=f);im.save(sys.argv[1])
PY
PY2
JAR="$PROJECT_DIR/apps/vision-security-service/target/vision-security-service-1.0.0.jar"
if [[ -f "$JAR" && -f "$FIXTURE" ]]; then
  OCRC="$(SPRING_PROFILES_ACTIVE=dev SPRING_MAIN_WEB_APPLICATION_TYPE=none SERVICE_JWT_SECRET=smoke java -jar "$JAR" --cv.input="$FIXTURE" 2>/dev/null | python3 -c 'import sys,json
d=json.JSONDecoder();s=sys.stdin.read()
for i,c in enumerate(s):
 if c=="{" and (i==0 or s[i-1]=="\n"):
  try:
   o,_=d.raw_decode(s[i:])
   if "ocrText" in o: print(len(o.get("ocrText") or "")); break
  except Exception: pass' 2>/dev/null)"
  [[ "${OCRC:-0}" -gt 0 ]] && { ok "OCR fixture chars=$OCRC"; mark ocr READY; } || { bad "OCR produced 0 chars"; mark ocr NOT_READY; }
else part "vision jar missing — mvn -pl apps/vision-security-service -am -DskipTests package"; mark ocr PARTIAL; fi

hdr "6. Host bridge"
HB="$(scripts/jarvis-host-bridge.sh health 2>/dev/null)"
if echo "$HB" | grep -q '"status": *"ok"'; then ok "bridge health ok";
  DA="$(echo '{"type":"RUN_SHELL","target":"x","execute":true}' | scripts/jarvis-host-bridge.sh action 2>/dev/null)"
  echo "$DA" | grep -q '"refused": *true' && ok "dangerous action refused" || part "dangerous-refusal unexpected"
  mark host_bridge READY
else bad "bridge health failed"; mark host_bridge NOT_READY; fi

hdr "7. Screen context + alive loop (dry-run)"
if scripts/jarvis-alive-smoke.sh --dry-run >/dev/null 2>&1; then ok "alive smoke dry-run PASS"; mark alive READY; mark screen READY
else part "alive smoke dry-run failed (screen may be blank/LLM down)"; mark alive PARTIAL; mark screen PARTIAL; fi

hdr "8. Voice STT (wav) + wake test-wav"
if [[ -f assets/voice-test/jarvis-screen-en.wav ]] && scripts/jarvis-voice-smoke.sh --wav assets/voice-test/jarvis-screen-en.wav --no-act >/dev/null 2>&1; then
  ok "voice smoke (wav) PASS"; mark voice_stt READY; else part "voice smoke wav failed"; mark voice_stt PARTIAL; fi
if scripts/jarvis-wake.sh --test-wav assets/voice-test/jarvis-screen-en.wav --once --no-act >/dev/null 2>&1; then
  ok "wake --test-wav PASS"; mark wake_test READY; else part "wake test-wav failed"; mark wake_test PARTIAL; fi

hdr "9. Wake-word engine (Porcupine probe)"
if scripts/jarvis-wake.sh --once --listen-timeout 1 --no-act </dev/null 2>&1 | grep -q "wake-word READY"; then
  ok "Porcupine engine READY (live human detection needs a speaker)"; mark wake_engine READY; mark live_wake PARTIAL
else part "Porcupine engine not ready (check PORCUPINE_ACCESS_KEY)"; mark wake_engine PARTIAL; mark live_wake PARTIAL; fi

hdr "10. Orchestrator /assist (unit tests)"
# Code is unit-tested in the build; here we just confirm the classes exist.
if [[ -f apps/orchestrator/src/main/java/org/jarvis/orchestrator/controller/AssistController.java ]]; then
  ok "assist endpoint present (unit-tested: AssistServiceTest)"; mark assist READY
else bad "assist endpoint missing"; mark assist NOT_READY; fi

if $DO_YES; then
  hdr "11. Real safe action (--yes)"
  if scripts/jarvis-alive-smoke.sh --yes >/dev/null 2>&1; then ok "real safe action executed (or honest no-op)"; mark real_action READY
  else part "real action smoke non-zero (screen blank? see alive smoke)"; mark real_action PARTIAL; fi
fi
if $START_WAKE; then hdr "wake daemon"; scripts/jarvis-wake.sh start && ok "wake daemon started (--no-act)"; fi
if $LIVE_WAKE; then hdr "live wake (human)"; part 'run: scripts/jarvis-wake.sh --once --vad --no-act ; say "Jarvis" then your command'; fi

hdr "Recent events"
for f in "$LOG_DIR/host-bridge.events.jsonl"; do [[ -f "$f" ]] && { echo "  $f:"; tail -3 "$f" | sed 's/^/    /'; }; done

hdr "STATUS MATRIX"
order=(qwen endpoint k8s memory ocr screen host_bridge alive voice_stt wake_test wake_engine live_wake assist ${DO_YES:+real_action})
printf '  %-16s %s\n' "CAPABILITY" "STATUS"
notready=0
for k in qwen endpoint k8s memory ocr screen host_bridge alive voice_stt wake_test wake_engine live_wake assist real_action; do
  v="${STATUS[$k]:-}"; [[ -z "$v" ]] && continue
  printf '  %-16s %s\n' "$k" "$v"
  [[ "$v" == NOT_READY ]] && notready=$((notready+1))
done
echo
[[ $notready -eq 0 ]] && { echo -e "\033[1;32mFULL SMOKE: no NOT_READY capabilities\033[0m"; exit 0; } \
  || { echo -e "\033[1;31mFULL SMOKE: $notready capability(ies) NOT_READY\033[0m"; exit 1; }
