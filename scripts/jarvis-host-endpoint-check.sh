#!/usr/bin/env bash
# =============================================================================
# jarvis-host-endpoint-check.sh — verify (and optionally fix) the
# host-model-daemon Endpoints wiring so in-cluster llm-service can reach the
# host llama.cpp daemon.
# =============================================================================
# The host-model-daemon Service ships with a PLACEHOLDER Endpoints IP
# (192.0.2.1, RFC-5737). It must be patched to the real node/host IP. That
# patch lives in etcd and survives normal k3s restarts, but is reset if someone
# re-applies k8s/base, and the node IP can change across machines/networks.
#
# Exit codes: 0 OK · 2 still placeholder/empty · 3 cannot reach cluster · 4 fix failed
#
# Usage:
#   scripts/jarvis-host-endpoint-check.sh            # check only
#   scripts/jarvis-host-endpoint-check.sh --fix      # re-apply with detected node IP
#   JARVIS_HOST_IP=10.113.0.176 scripts/jarvis-host-endpoint-check.sh --fix
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${JARVIS_NAMESPACE:-jarvis-prod}"
PLACEHOLDER="192.0.2.1"
FIX=false
[[ "${1:-}" == "--fix" ]] && FIX=true

export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
# Pick a kubectl invocation that can actually read the (root-owned) kubeconfig.
KCBIN=kubectl
command -v kubectl >/dev/null 2>&1 || KCBIN="k3s kubectl"
KC=($KCBIN)
if ! "${KC[@]}" -n "$NS" get endpoints host-model-daemon >/dev/null 2>&1; then
  KC=(sudo -n env "KUBECONFIG=$KUBECONFIG" $KCBIN)
  "${KC[@]}" -n "$NS" get endpoints host-model-daemon >/dev/null 2>&1 || KC=(sudo -n env "KUBECONFIG=$KUBECONFIG" k3s kubectl)
fi

current_ip() {
  "${KC[@]}" -n "$NS" get endpoints host-model-daemon \
    -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null
}

detect_node_ip() {
  "${KC[@]}" get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null
}

IP="$(current_ip)"
if [[ -z "$IP" ]]; then
  if ! "${KC[@]}" -n "$NS" get endpoints host-model-daemon >/dev/null 2>&1; then
    echo "❌ cannot reach cluster / namespace $NS"; exit 3
  fi
  echo "❌ host-model-daemon Endpoints is EMPTY"; IP=""
fi
echo "host-model-daemon Endpoints IP: ${IP:-<empty>}"

if [[ "$IP" == "$PLACEHOLDER" || -z "$IP" ]]; then
  if $FIX; then
    NODE_IP="${JARVIS_HOST_IP:-$(detect_node_ip)}"
    [[ -n "$NODE_IP" ]] || { echo "❌ could not detect node IP; pass JARVIS_HOST_IP"; exit 4; }
    echo "→ re-applying via apply-host-endpoints.sh --ip=$NODE_IP"
    sudo -n env KUBECONFIG="$KUBECONFIG" PATH="$PATH" \
      bash "${PROJECT_DIR}/infra/scripts/microk8s/apply-host-endpoints.sh" --ip="$NODE_IP" \
      || { echo "❌ patch failed"; exit 4; }
    IP="$(current_ip)"
    echo "now: $IP"
  else
    echo "❌ STILL PLACEHOLDER — in-cluster LLM is not wired to the host daemon."
    echo "   fix: scripts/jarvis-host-endpoint-check.sh --fix"
    exit 2
  fi
fi

# Other half of the recurring regression: ensure llm-service points at the host daemon
# (it sometimes reverts to in-cluster llm-server:5000 which 400s). Only acts on --fix,
# only patches when wrong (idempotent), and never enables HOST_DAEMON_ENABLED.
if $FIX; then
  WANT_URL="http://host-model-daemon.${NS}.svc.cluster.local:18080"
  ENVDUMP="$("${KC[@]}" -n "$NS" get deploy llm-service -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' 2>/dev/null)"
  CUR_URL="$(printf '%s\n' "$ENVDUMP" | sed -n 's/^LLM_SERVER_URL=//p')"
  CUR_HD="$(printf '%s\n' "$ENVDUMP" | sed -n 's/^JARVIS_HOST_DAEMON_ENABLED=//p')"
  if [[ "$CUR_URL" != "$WANT_URL" || "$CUR_HD" != "false" ]]; then
    echo "→ correcting llm-service env (was url='$CUR_URL' host_daemon='$CUR_HD')"
    if "${KC[@]}" -n "$NS" set env deploy/llm-service "LLM_SERVER_URL=$WANT_URL" JARVIS_HOST_DAEMON_ENABLED=false >/dev/null 2>&1; then
      "${KC[@]}" -n "$NS" rollout status deploy/llm-service --timeout=120s >/dev/null 2>&1 \
        && echo "   llm-service env corrected + rolled out (HOST_DAEMON_ENABLED stays false; 18081/18082 untouched)"
    else
      echo "   ⚠ could not patch llm-service env — set manually"
    fi
  else
    echo "llm-service env already correct (-> host-model-daemon:18080, HOST_DAEMON_ENABLED=false)"
  fi
fi

# Proof 1 (host-side): the daemon answers on that IP:18080
CODE="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://${IP}:18080/health" 2>/dev/null)"
echo "host    http://${IP}:18080/health -> HTTP ${CODE:-000}"

# Proof 2 (EndpointSlice): kube-proxy in k8s 1.33 routes via the slice, not the legacy Endpoints
SLICE_IP="$("${KC[@]}" -n "$NS" get endpointslice -l kubernetes.io/service-name=host-model-daemon \
  -o jsonpath='{.items[0].endpoints[0].addresses[0]}' 2>/dev/null)"
echo "slice   EndpointSlice addr -> ${SLICE_IP:-<none>}"

# Proof 3 (cluster-side, authoritative): a pod can reach the Service (Endpoints + slice + NetworkPolicy)
POD_CODE="$("${KC[@]}" -n "$NS" exec deploy/llm-service -- sh -c \
  "curl -s -o /dev/null -m6 -w '%{http_code}' http://host-model-daemon.${NS}.svc.cluster.local:18080/health" 2>/dev/null)"
echo "cluster llm-service pod -> host-model-daemon:18080/health -> HTTP ${POD_CODE:-000}"

if [[ "$CODE" == "200" && "$POD_CODE" == "200" ]]; then
  echo "✅ host-model-daemon endpoint OK — daemon up AND reachable from the cluster"
  exit 0
fi
echo "⚠ daemon up=$CODE, cluster-reach=$POD_CODE, slice=$SLICE_IP"
echo "   if cluster-reach!=200: check EndpointSlice addr == node IP, and NetworkPolicy"
echo "   'llm-service-egress-host-model-daemon' allowing egress to <node>/32:18080."
echo "   NOTE: keep llm-service HOST_DAEMON_ENABLED=false — true requires the coding/router"
echo "   daemons on :18081/:18082 (not running) and will fail readiness."
exit 2
