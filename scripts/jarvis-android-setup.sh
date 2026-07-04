#!/usr/bin/env bash
# Android ↔ Jarvis local-first sync: verify exposure + print the exact manual
# steps that the agent is NOT allowed to run (NodePort patch on a prod Service is
# blocked by the auto-mode guard). READ-ONLY: this script never patches anything.
set -uo pipefail
NS="${JARVIS_NAMESPACE:-jarvis-prod}"
NODE_IP="${JARVIS_HOST_IP:-10.113.0.176}"
NODEPORT="${JARVIS_SYNC_NODEPORT:-30095}"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
KC=(kubectl); command -v kubectl >/dev/null 2>&1 || KC=(k3s kubectl)
"${KC[@]}" -n "$NS" get svc sync-service >/dev/null 2>&1 || KC=(sudo -n env "KUBECONFIG=$KUBECONFIG" "${KC[@]}")

cyan(){ printf '\033[0;36m%s\033[0m\n' "$1"; }
ok(){ printf '\033[0;32m[ OK ]\033[0m %s\n' "$1"; }
warn(){ printf '\033[1;33m[WARN]\033[0m %s\n' "$1"; }

cyan "== Jarvis Android sync setup =="

# --dry-run: server-side smoke that does NOT require a phone or NodePort. Proves the
# sync-service pairing pipeline is alive in-cluster.
if [[ "${1:-}" == "--dry-run" ]]; then
  cyan "-- dry-run (server-side, no phone) --"
  H="$("${KC[@]}" -n "$NS" exec deploy/sync-service -- sh -c \
    'curl -s -o /dev/null -m6 -w "%{http_code}" http://localhost:8095/actuator/health' 2>/dev/null)"
  [[ "$H" == "200" ]] && ok "sync-service /actuator/health = 200 (in-cluster)" \
    || warn "sync-service health=$H (pod not ready?)"
  P="$("${KC[@]}" -n "$NS" exec deploy/sync-service -- sh -c \
    'curl -s -o /dev/null -m6 -w "%{http_code}" -X POST http://localhost:8095/api/v1/sync/pairing/init -d ""' 2>/dev/null)"
  # 200 = nonce returned; 401/403 = endpoint alive but needs the device service token
  # (still proves reachability). Anything else (000/404/5xx) is a real problem.
  if [[ "$P" == "200" ]]; then ok "pairing/init = 200 (returns a nonce server-side)"
  elif [[ "$P" == "401" || "$P" == "403" ]]; then ok "pairing/init reachable (HTTP $P — auth required, expected)"
  else warn "pairing/init=$P (unexpected)"; fi
  echo "dry-run proves the server is ready; phone round-trip still needs §9 NodePort + pairing."
  { [[ "$H" == "200" && ( "$P" == "200" || "$P" == "401" || "$P" == "403" ) ]]; }; exit $?
fi

SVC_TYPE="$("${KC[@]}" -n "$NS" get svc sync-service -o jsonpath='{.spec.type}' 2>/dev/null)"
CUR_NP="$("${KC[@]}" -n "$NS" get svc sync-service -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)"
echo "sync-service type=${SVC_TYPE:-?} nodePort=${CUR_NP:-none}"

if [[ "$SVC_TYPE" == "NodePort" && -n "$CUR_NP" ]]; then
  ok "sync-service exposed on NodePort ${CUR_NP}"
  BASE="http://${NODE_IP}:${CUR_NP}"
  CODE="$(curl -s -o /dev/null -m6 -w '%{http_code}' -X POST "${BASE}/api/v1/sync/pairing/init" -d '' 2>/dev/null)"
  if [[ "$CODE" == "200" ]]; then
    ok "pairing/init reachable at ${BASE}  (HTTP 200, returns nonce)"
  else
    warn "pairing/init -> HTTP ${CODE:-000} (NetworkPolicy may block external ingress to sync-service:8095)"
  fi
  echo
  cyan "Phone pairing (app 'Server' tab):"
  echo "  1. Connect the phone to the SAME LAN as this machine."
  echo "  2. Open the Jarvis app -> 'Server' tab."
  echo "  3. Enter:  ${BASE}    (use this machine's LAN IP if ${NODE_IP} is not on the phone's network)"
  echo "  4. Tap 'Спарить' -> expect 'Спарено ✓'."
  echo "  5. 'Health' tab -> grant Health Connect (sleep, steps). Sync runs every ~15 min."
  exit 0
fi

warn "sync-service is ${SVC_TYPE:-ClusterIP} — NOT reachable from the phone."
echo
cyan "MANUAL STEP (run yourself — the agent's guard blocks prod NodePort/NetworkPolicy patches):"
cat <<EOF
  sudo k3s kubectl -n ${NS} patch svc sync-service -p \\
    '{"spec":{"type":"NodePort","ports":[{"name":"http","port":8095,"targetPort":8095,"nodePort":${NODEPORT},"protocol":"TCP"}]}}'

  sudo k3s kubectl apply -f - <<'YAML'
  apiVersion: networking.k8s.io/v1
  kind: NetworkPolicy
  metadata: {name: sync-service-ingress-lan, namespace: ${NS}}
  spec:
    podSelector: {matchLabels: {app: sync-service}}
    policyTypes: [Ingress]
    ingress:
    - ports: [{protocol: TCP, port: 8095}]
  YAML

  # verify (expect JSON nonce):
  curl -s -X POST http://${NODE_IP}:${NODEPORT}/api/v1/sync/pairing/init -d '' ; echo
EOF
echo
echo "Then re-run: ./scripts/jarvis-android-setup.sh   (it will print pairing steps)."
exit 1
