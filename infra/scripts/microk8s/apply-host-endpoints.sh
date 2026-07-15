#!/usr/bin/env bash
# =============================================================================
# Patch the host-model-daemon Endpoints object with the actual host IP (Phase 3)
# =============================================================================
# Phase 3 routes pod traffic to a llama.cpp process running on the host. The
# Service `host-model-daemon` has no selector; its Endpoints points to a
# specific host IP. This script detects that IP and updates the Endpoints
# object in-cluster.
#
# Auto-detection order:
#   1. $JARVIS_HOST_IP env var (explicit override)
#   2. The IPv4 address bound to the default route's interface
#   3. The first non-loopback address from `hostname -I`
#
# Usage:
#   ./infra/scripts/microk8s/apply-host-endpoints.sh
#   ./infra/scripts/microk8s/apply-host-endpoints.sh --ip=192.168.1.42
#   JARVIS_HOST_IP=10.0.0.5 ./infra/scripts/microk8s/apply-host-endpoints.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
ENDPOINT_NAME="host-model-daemon"
HOST_IP="${JARVIS_HOST_IP:-}"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/apply-host-endpoints.sh [options]

Options:
  --ip=ADDR          Use this IPv4 instead of auto-detection
  --namespace=NAME   Default: jarvis-prod
  --help, -h         Show this help

The patched Endpoints object becomes:
  host-model-daemon.<namespace>.svc.cluster.local -> ADDR:{18080,18081,18082}
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --ip=*)        HOST_IP="${arg#*=}" ;;
    --namespace=*) NAMESPACE="${arg#*=}" ;;
    --help|-h)     usage; exit 0 ;;
    *) echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
  esac
done

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "❌ missing: $1" >&2; exit 1; }; }
require_cmd kubectl

detect_host_ip() {
  local ip=""
  ip="$(ip route get 1.1.1.1 2>/dev/null | awk '/src/ {for (i=1;i<=NF;i++) if ($i=="src") print $(i+1)}' | head -n1)"
  if [[ -n "${ip}" ]]; then printf '%s' "${ip}"; return 0; fi
  ip="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -v '^127\.' | grep -E '^[0-9.]+$' | head -n1)"
  if [[ -n "${ip}" ]]; then printf '%s' "${ip}"; return 0; fi
  return 1
}

valid_ipv4() {
  [[ "$1" =~ ^([0-9]+\.){3}[0-9]+$ ]] || return 1
  IFS='.' read -r a b c d <<<"$1"
  for o in "${a}" "${b}" "${c}" "${d}"; do
    (( o >= 0 && o <= 255 )) || return 1
  done
  return 0
}

if [[ -z "${HOST_IP}" ]]; then
  HOST_IP="$(detect_host_ip || true)"
fi
if [[ -z "${HOST_IP}" ]]; then
  echo "❌ could not detect host IP. Pass --ip=ADDR or set JARVIS_HOST_IP." >&2
  exit 1
fi
if ! valid_ipv4 "${HOST_IP}"; then
  echo "❌ '${HOST_IP}' is not a valid IPv4." >&2
  exit 1
fi
if [[ "${HOST_IP}" == "0.0.0.0" || "${HOST_IP}" == "127.0.0.1" ]]; then
  echo "❌ refusing to patch with placeholder/loopback IP '${HOST_IP}'." >&2
  echo "   Use a real cluster-routable IP (your host's LAN IP)." >&2
  exit 1
fi

if ! kubectl -n "${NAMESPACE}" get endpoints "${ENDPOINT_NAME}" >/dev/null 2>&1; then
  echo "❌ Endpoints '${ENDPOINT_NAME}' not found in namespace '${NAMESPACE}'." >&2
  echo "   Apply the base overlay first (./scripts/product/jarvis-deploy-microk8s-prod.sh)." >&2
  exit 1
fi

echo "patching ${NAMESPACE}/${ENDPOINT_NAME} -> host IP ${HOST_IP}"

patch="$(cat <<EOF
{
  "subsets": [
    {
      "addresses": [{"ip": "${HOST_IP}"}],
      "ports": [
        {"name": "main",   "port": 18080, "protocol": "TCP"},
        {"name": "coding", "port": 18081, "protocol": "TCP"},
        {"name": "router", "port": 18082, "protocol": "TCP"}
      ]
    }
  ]
}
EOF
)"

kubectl -n "${NAMESPACE}" patch endpoints "${ENDPOINT_NAME}" \
  --type merge -p "${patch}"

echo ""
kubectl -n "${NAMESPACE}" get endpoints "${ENDPOINT_NAME}" -o wide
echo ""
echo "✅ host-model-daemon Endpoints now resolves to ${HOST_IP}:{18080,18081,18082}"
echo "   Pods inside ${NAMESPACE} can reach the host via:"
echo "     curl http://${ENDPOINT_NAME}.${NAMESPACE}.svc.cluster.local:18080/health"

# --------------------------------------------------------------------------
# host-tts-daemon (Piper neural TTS, :18090) mirrors the host-model-daemon
# pattern but was historically NOT patched here — the selectorless Endpoints and
# its scoped egress NetworkPolicy kept the stale hardcoded default IP, so
# voice-gateway could not reach Piper and the desktop showed "TTS Degraded".
# Point BOTH the Endpoints and the voice-gateway-egress-tts NetworkPolicy at the
# current host IP. Best-effort: skipped cleanly if the TTS resources are absent.
# --------------------------------------------------------------------------
TTS_ENDPOINT="host-tts-daemon"
TTS_NETPOL="voice-gateway-egress-tts"
if kubectl -n "${NAMESPACE}" get endpoints "${TTS_ENDPOINT}" >/dev/null 2>&1; then
  echo ""
  echo "patching ${NAMESPACE}/${TTS_ENDPOINT} -> host IP ${HOST_IP}"
  kubectl -n "${NAMESPACE}" patch endpoints "${TTS_ENDPOINT}" --type merge -p "$(cat <<EOF
{
  "subsets": [
    {
      "addresses": [{"ip": "${HOST_IP}"}],
      "ports": [{"name": "tts", "port": 18090, "protocol": "TCP"}]
    }
  ]
}
EOF
)"
  if kubectl -n "${NAMESPACE}" get networkpolicy "${TTS_NETPOL}" >/dev/null 2>&1; then
    kubectl -n "${NAMESPACE}" patch networkpolicy "${TTS_NETPOL}" --type json \
      -p "[{\"op\":\"replace\",\"path\":\"/spec/egress/0/to/0/ipBlock/cidr\",\"value\":\"${HOST_IP}/32\"}]"
    echo "✅ ${TTS_NETPOL} egress now allows ${HOST_IP}/32:18090"
  fi
  echo "✅ host-tts-daemon Endpoints now resolves to ${HOST_IP}:18090"
  echo "   Restart voice-gateway so it re-probes Piper (or wait for the in-place re-probe):"
  echo "     kubectl -n ${NAMESPACE} rollout restart deployment/voice-gateway"
else
  echo "ℹ️  host-tts-daemon Endpoints not present; skipping TTS daemon wiring."
fi
