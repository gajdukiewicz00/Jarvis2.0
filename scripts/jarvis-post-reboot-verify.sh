#!/usr/bin/env bash
# =============================================================================
# jarvis-post-reboot-verify.sh — READ-ONLY verification chain for "did the
# stack come back up correctly after the host rebooted?"
#
# This never mutates the cluster. If a step fails, the fix is one of the
# existing, purpose-built repair scripts (printed inline as a hint):
#   - k3s down                         -> sudo systemctl restart k3s
#   - stale pods after reboot          -> scripts/product/jarvis-recover-after-reboot.sh
#   - host-model-daemon endpoint stale -> scripts/jarvis-host-endpoint-check.sh --fix
#
# Chain (every step runs — this is a full diagnostic pass, not fail-fast):
#   1. k3s systemd service active
#   2. k3s API reachable
#   3. node InternalIP resolvable
#   4. host-model-daemon Endpoints IP == node InternalIP (no hardcoded IP —
#      the node IP is read live from `kubectl get nodes`, never assumed)
#   5. all jarvis-prod pods Running/Completed
#   6. api-gateway /actuator/health reports UP (via the current node IP)
#   7. brain reachable FROM INSIDE the cluster (llm-service pod curling
#      host-model-daemon.<ns>.svc.cluster.local:18080/health)
#
# Usage:
#   scripts/jarvis-post-reboot-verify.sh [--namespace=NAME] [--help]
#
# Exit codes: 0 all checks passed · 1 one or more checks failed
# =============================================================================
set -uo pipefail

NS="${JARVIS_NAMESPACE:-jarvis-prod}"

usage() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") [--namespace=NAME] [--help]

Read-only post-reboot verification chain: k3s up? node IP? host-model-daemon
endpoint == node IP? pods Ready? gateway UP? brain reachable via cluster?
Never patches or deletes anything — see docs/DEPLOYMENT_CANONICAL.md for the
repair scripts to run when a step fails.

Options:
  --namespace=NAME   Kubernetes namespace to inspect (default: jarvis-prod)
  --help, -h         Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*) NS="${arg#*=}" ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

pass=0
fail=0
ok()   { printf '  \033[0;32m[PASS]\033[0m %s\n' "$1"; pass=$((pass + 1)); }
no()   { printf '  \033[0;31m[FAIL]\033[0m %s\n' "$1"; fail=$((fail + 1)); }
hint() { printf '         \033[0;33m%s\033[0m\n' "$1"; }
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

echo "== Jarvis post-reboot verify (namespace=${NS}) =="

# --- 1. k3s systemd service ---------------------------------------------------
step "1. k3s systemd service"
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet k3s 2>/dev/null; then
  ok "k3s systemd service active"
else
  no "k3s systemd service NOT active"
  hint "fix: sudo systemctl restart k3s"
fi

# --- 2. k3s API reachable ------------------------------------------------------
step "2. k3s API"
KCTL=""
if kubectl version >/dev/null 2>&1 && kubectl -n "${NS}" get ns >/dev/null 2>&1; then
  KCTL="kubectl"
elif sudo -n k3s kubectl version >/dev/null 2>&1 && sudo -n k3s kubectl -n "${NS}" get ns >/dev/null 2>&1; then
  KCTL="sudo k3s kubectl"
fi
k() { ${KCTL} -n "${NS}" "$@"; }

if [[ -n "${KCTL}" ]]; then
  ok "k3s API reachable (kubectl invocation: ${KCTL})"
else
  no "k3s API NOT reachable"
  hint "fix: sudo systemctl restart k3s ; then sudo k3s kubectl -n ${NS} get ns"
  printf '\n\033[1m== post-reboot-verify result: %d passed, %d failed (cluster unreachable — remaining checks skipped) ==\033[0m\n' "${pass}" "$((fail + 5))"
  exit 1
fi

# --- 3. node InternalIP --------------------------------------------------------
step "3. node IP"
NODE_IP="$(${KCTL} get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null | awk '{print $1}')"
if [[ -n "${NODE_IP}" ]]; then
  ok "node InternalIP = ${NODE_IP}"
else
  no "node InternalIP not resolvable"
  hint "check: ${KCTL} get nodes -o wide"
fi

# --- 4. host-model-daemon endpoint == node IP ----------------------------------
step "4. host-model-daemon endpoint"
EP_IP="$(k get endpoints host-model-daemon -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)"
if [[ -n "${NODE_IP}" && "${EP_IP}" == "${NODE_IP}" ]]; then
  ok "host-model-daemon Endpoints IP (${EP_IP}) == node InternalIP"
else
  no "host-model-daemon Endpoints IP (${EP_IP:-<empty>}) != node InternalIP (${NODE_IP:-<unknown>})"
  hint "fix: ./scripts/jarvis-host-endpoint-check.sh --fix"
fi

# --- 5. pods Running/Completed --------------------------------------------------
step "5. pods Ready"
PODS_RAW="$(k get pods --no-headers 2>/dev/null || true)"
TOTAL_PODS="$(printf '%s\n' "${PODS_RAW}" | grep -c . || true)"
NOT_READY="$(printf '%s\n' "${PODS_RAW}" | grep -vE ' Running | Completed ' | grep -c . || true)"
if [[ "${TOTAL_PODS:-0}" -gt 0 && "${NOT_READY:-1}" -eq 0 ]]; then
  ok "all ${TOTAL_PODS} pods Running/Completed"
else
  no "${NOT_READY:-?}/${TOTAL_PODS:-?} pods not Running/Completed"
  printf '%s\n' "${PODS_RAW}" | grep -vE ' Running | Completed ' | sed 's/^/         /'
  hint "fix: scripts/product/jarvis-recover-after-reboot.sh"
fi

# --- 6. gateway health -----------------------------------------------------------
step "6. gateway /actuator/health"
if [[ -n "${NODE_IP}" ]]; then
  if curl -sk -m8 -H 'Host: api.jarvis.local' "https://${NODE_IP}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    ok "gateway health UP (https://${NODE_IP}/actuator/health, Host: api.jarvis.local)"
  else
    no "gateway health not UP (https://${NODE_IP}/actuator/health)"
    hint "check: /etc/hosts has 'api.jarvis.local', and: ${KCTL} -n ${NS} logs deploy/api-gateway --tail=50"
  fi
else
  no "gateway health — skipped, no node IP resolved"
fi

# --- 7. brain reachable from inside the cluster -----------------------------------
step "7. brain via cluster (llm-service -> host-model-daemon:18080)"
POD_CODE="$(k exec deploy/llm-service -- sh -c \
  "curl -s -o /dev/null -m6 -w '%{http_code}' http://host-model-daemon.${NS}.svc.cluster.local:18080/health" 2>/dev/null || true)"
if [[ "${POD_CODE}" == "200" ]]; then
  ok "brain reachable from cluster (HTTP ${POD_CODE})"
else
  no "brain NOT reachable from cluster (HTTP ${POD_CODE:-000})"
  hint "fix: ./scripts/jarvis-host-endpoint-check.sh --fix ; check NetworkPolicy egress to <node>/32:18080"
fi

# --- summary -----------------------------------------------------------------------
printf '\n\033[1m== post-reboot-verify result: %d passed, %d failed ==\033[0m\n' "${pass}" "${fail}"
[[ "${fail}" -eq 0 ]]
