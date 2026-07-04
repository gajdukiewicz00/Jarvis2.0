#!/usr/bin/env bash
# =============================================================================
# Patch the vision-security-service Endpoints object with the host IP.
# =============================================================================
# vision-security-service requires camera + screen access on the user's
# workstation, so the K8s manifest is a selectorless Service whose Endpoints
# object points back at the local Java process on host port 8094. This
# patcher is the runtime counterpart of
# infra/scripts/microk8s/apply-host-endpoints.sh — same pattern as the
# host-model-daemon bridge.
#
# Auto-detection order:
#   1. $JARVIS_HOST_IP env var (explicit override)
#   2. --ip=ADDR CLI flag
#   3. IPv4 address bound to the default route interface
#   4. First non-loopback address from `hostname -I`
#
# Usage:
#   ./infra/scripts/microk8s/apply-vision-security-host-endpoints.sh
#   ./infra/scripts/microk8s/apply-vision-security-host-endpoints.sh --ip=192.168.1.42
#   JARVIS_HOST_IP=10.0.0.5 ./infra/scripts/microk8s/apply-vision-security-host-endpoints.sh
# =============================================================================
set -euo pipefail

NAMESPACE="${JARVIS_NAMESPACE:-jarvis-prod}"
ENDPOINT_NAME="vision-security-service"
HOST_IP="${JARVIS_HOST_IP:-}"
HOST_PORT="${JARVIS_VISION_SECURITY_PORT:-8094}"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/apply-vision-security-host-endpoints.sh [options]

Options:
  --ip=ADDR          Use this IPv4 instead of auto-detection
  --port=PORT        Override host port (default: 8094, env: JARVIS_VISION_SECURITY_PORT)
  --namespace=NAME   Default: jarvis-prod
  --help, -h         Show this help

The patched Endpoints object becomes:
  vision-security-service.<namespace>.svc.cluster.local -> ADDR:PORT
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --ip=*)        HOST_IP="${arg#*=}" ;;
    --port=*)      HOST_PORT="${arg#*=}" ;;
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
  echo "   Pods cannot reach 127.0.0.1 on the host; use the host's LAN IP." >&2
  exit 1
fi
if [[ ! "${HOST_PORT}" =~ ^[0-9]+$ ]] || (( HOST_PORT < 1 || HOST_PORT > 65535 )); then
  echo "❌ '${HOST_PORT}' is not a valid TCP port." >&2
  exit 1
fi

if ! kubectl -n "${NAMESPACE}" get endpoints "${ENDPOINT_NAME}" >/dev/null 2>&1; then
  echo "❌ Endpoints '${ENDPOINT_NAME}' not found in namespace '${NAMESPACE}'." >&2
  echo "   Apply the base/overlay first so the selectorless Service exists." >&2
  exit 1
fi

echo "patching ${NAMESPACE}/${ENDPOINT_NAME} -> host ${HOST_IP}:${HOST_PORT}"

patch="$(cat <<EOF
{
  "subsets": [
    {
      "addresses": [{"ip": "${HOST_IP}"}],
      "ports": [
        {"name": "http", "port": ${HOST_PORT}, "protocol": "TCP"}
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
echo "✅ vision-security-service Endpoints now resolves to ${HOST_IP}:${HOST_PORT}"
echo "   api-gateway pods inside ${NAMESPACE} can reach the host via:"
echo "     curl http://${ENDPOINT_NAME}.${NAMESPACE}.svc.cluster.local:${HOST_PORT}/actuator/health/readiness"
