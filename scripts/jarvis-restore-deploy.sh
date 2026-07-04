#!/usr/bin/env bash
# =============================================================================
# jarvis-restore-deploy.sh — restore the feature images + env after a cluster
# re-apply reverted everything to ":local".
#
# A `kubectl apply -f k8s/base` (or `jarvis up`) resets deployment image tags to
# the manifest default (:local) and some resources to base defaults — wiping the
# movie-tagged feature images (voice intent auth, confirmation fix, bank parser,
# semantic memory, etc.) and re-introducing the voice-gateway 1Gi OOM.
#
# This re-points each service at its last-known-good movie tag (set image PRESERVES
# env), bumps voice-gateway memory, re-enables the orchestrator LLM, and repairs the
# host-model-daemon endpoint. llm-service (the brain) is restored LAST with a health
# gate that auto-reverts to :local if chat breaks.
#
#   Run:  bash scripts/jarvis-restore-deploy.sh
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
K="sudo k3s kubectl -n jarvis-prod"
REG="localhost:5000/jarvis"

# service -> last-known-good movie tag (excludes llm-service; handled separately)
SVCS="voice-gateway:movie5 life-tracker:movie9 api-gateway:movie3 orchestrator:moviepc3 \
nlp-service:movie4 memory-service:movie16 smart-home-service:movie3 analytics-service:movie2 \
planner-service:movie2 security-service:movie1 sync-service:movie2"

echo "== Restoring feature images =="
for pair in $SVCS; do
  svc="${pair%%:*}"; tag="${pair##*:}"
  # only set if the tag exists in the registry
  if curl -s "http://localhost:5000/v2/jarvis/$svc/tags/list" 2>/dev/null | grep -q "\"$tag\""; then
    $K set image "deploy/$svc" "$svc=$REG/$svc:$tag" >/dev/null 2>&1 && echo "  $svc -> $tag" || echo "  $svc FAILED"
  else
    echo "  $svc: tag $tag missing in registry — skipped"
  fi
done

echo "== Stability + wiring env =="
$K set resources deploy/voice-gateway --limits=memory=2Gi --requests=memory=768Mi >/dev/null 2>&1 && echo "  voice-gateway mem 2Gi/768Mi"
$K set env deploy/orchestrator JARVIS_LLM_ENABLED=true >/dev/null 2>&1 && echo "  orchestrator JARVIS_LLM_ENABLED=true"

echo "== Repair brain endpoint + llm-service env (idempotent) =="
bash "$ROOT/scripts/jarvis-host-endpoint-check.sh" --fix 2>&1 | tail -2

echo "== Wait for rollouts =="
for pair in $SVCS; do svc="${pair%%:*}"; $K rollout status "deploy/$svc" --timeout=180s >/dev/null 2>&1 && echo "  $svc ok" || echo "  $svc rollout slow/failed"; done

echo "== Brain: restore llm-service:movie2 with health gate =="
GW="https://10.113.0.176"; H="Host: api.jarvis.local"
brain_model(){ local t; t="$(curl -sk -H "$H" $GW/api/v1/security/auth/login -H 'Content-Type: application/json' -d '{"username":"test1111","password":"test1111"}' | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)";
  curl -sk -m45 -H "$H" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -X POST $GW/api/v1/llm/chat -d '{"sessionId":"restore","messages":[{"role":"user","content":"ответь одним словом: жив /no_think"}]}' 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin).get("model",""))' 2>/dev/null; }
if curl -s http://localhost:5000/v2/jarvis/llm-service/tags/list 2>/dev/null | grep -q '"movie2"'; then
  $K set image deploy/llm-service llm-service=$REG/llm-service:movie2 >/dev/null 2>&1
  $K rollout status deploy/llm-service --timeout=180s >/dev/null 2>&1
  sleep 3; M="$(brain_model)"
  if [ -n "$M" ]; then echo "  llm-service:movie2 OK — brain model=$M"
  else
    echo "  ⚠️ brain did not answer on movie2 — reverting to :local"
    $K set image deploy/llm-service llm-service=$REG/llm-service:local >/dev/null 2>&1
    $K rollout status deploy/llm-service --timeout=180s >/dev/null 2>&1
    sleep 3; echo "  reverted; brain model=$(brain_model)"
  fi
fi

echo "== Verify =="
bash "$ROOT/scripts/jarvis-smoke-verify.sh" 2>&1 | sed -r 's/\x1B\[[0-9;]*[mK]//g' | grep -E 'result'
echo
echo "Done. For Android (optional, operator): re-open NodePort via fix-sync-auth path + patch svc sync-service to NodePort 30095."
echo "Run ./scripts/jarvis-final-check.sh and ./scripts/jarvis-demo-check.sh to confirm features."
