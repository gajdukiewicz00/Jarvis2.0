#!/usr/bin/env bash
# =============================================================================
# jarvis-recover-after-reboot.sh — recover jarvis-prod pods stuck in stale
# states (ContainerStatusUnknown/Error/Init/Unknown/CrashLoopBackOff/Evicted)
# after a host reboot.
#
# THE PROBLEM: after the host reboots, k3s itself comes back fine, but the
# kubelet lost track of the containers that were running before the reboot.
# It does NOT auto-recreate them — the pod objects just sit in a stale state
# forever unless something removes them.
#
# WHY THIS SCRIPT NEVER USES `apply`/`set image`/`set env`/`rollout restart`/
# `./jarvis up`:
#   The pod SPECS already sitting in etcd (from the Deployment/StatefulSet
#   controllers) are correct and movie-tagged. The only thing wrong is the
#   kubelet's stale bookkeeping of the actual containers. Re-applying manifests
#   (`kubectl apply -k`, equivalently `./jarvis up`) would re-render kustomize
#   and, until the REPRO-1 fix lands, reset every image tag back to `:local`,
#   wiping the movie-tagged feature images (voice intent auth, confirmation
#   fix, bank parser, semantic memory, etc.). `set image`/`set env` mutate the
#   spec too, and `rollout restart` is unnecessary churn.
#
#   The safe, surgical fix is instead: delete ONLY the stale pod objects.
#   Their existing Deployment/StatefulSet controllers notice the missing pod
#   and recreate it from the SAME unchanged spec — no manifests touched, no
#   tags stomped. Every cluster-mutating line in this script is therefore a
#   targeted `kubectl delete pod <name>`; everything else is read-only
#   (`get`, `wait`).
#
# ORDER OF OPERATIONS: datastores/brokers (postgres, postgres-pgvector, kafka,
# rabbitmq, mosquitto, embedding-service, llm-server/llm-service) are recreated
# FIRST and given a chance to become Ready before the remaining app-service
# pods are deleted, so app services don't immediately crashloop waiting on a
# DB/broker/model backend that isn't up yet. Priority-matching is done by pod
# name prefix, so this is harmless (a no-op) on clusters where some of those
# services don't exist (e.g. this stack currently has no kafka/rabbitmq pods).
#
# Idempotent / safe to re-run: pods that are already healthy are left alone;
# if there's nothing stale, the script reports that and exits 0.
#
# Usage:
#   scripts/product/jarvis-recover-after-reboot.sh              # recover
#   scripts/product/jarvis-recover-after-reboot.sh --dry-run    # print only, no deletes
#   scripts/product/jarvis-recover-after-reboot.sh --help
#
# Env overrides:
#   JARVIS_NAMESPACE              namespace (default: jarvis-prod)
#   JARVIS_NODE_WAIT_TIMEOUT      node Ready wait, kubectl duration (default: 180s)
#   JARVIS_PRIORITY_WAIT_TIMEOUT  seconds to wait for datastores/brokers to
#                                 become Ready before touching app pods (default: 90)
#   JARVIS_POD_POLL_TIMEOUT       seconds for the final Running/Ready poll (default: 240)
#
# Exit codes: 0 recovered (or nothing to do) · 1 cannot reach cluster / k3s down
#             · 2 finished but some pods are still not Running/Ready
# =============================================================================
set -uo pipefail

NS="${JARVIS_NAMESPACE:-jarvis-prod}"
NODE_WAIT_TIMEOUT="${JARVIS_NODE_WAIT_TIMEOUT:-180s}"
PRIORITY_WAIT_TIMEOUT="${JARVIS_PRIORITY_WAIT_TIMEOUT:-90}"
POD_POLL_TIMEOUT="${JARVIS_POD_POLL_TIMEOUT:-240}"
POLL_INTERVAL=5

DRY_RUN=false
case "${1:-}" in
  --dry-run) DRY_RUN=true ;;
  -h|--help)
    sed -n '2,55p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

log()  { echo -e "\033[0;36m[INFO]\033[0m $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m $*"; }
err()  { echo -e "\033[0;31m[ERROR]\033[0m $*" >&2; }
ok()   { echo -e "\033[0;32m[ OK ]\033[0m $*"; }
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

echo "== Jarvis reboot auto-recovery (namespace=$NS, dry-run=$DRY_RUN) =="

# --- 1. resolve a kubectl invocation that can actually reach the cluster ----
# Bare `kubectl` normally fails here because the kubeconfig is root-owned;
# prefer `sudo k3s kubectl`, fall back to bare `kubectl` if that happens to work.
step "1. resolve kubectl"
KCTL=""
if sudo k3s kubectl -n "$NS" get ns >/dev/null 2>&1; then
  KCTL="sudo k3s kubectl"
elif kubectl -n "$NS" get ns >/dev/null 2>&1; then
  KCTL="kubectl"
fi
if [[ -z "$KCTL" ]]; then
  err "cannot reach the cluster via 'sudo k3s kubectl' or 'kubectl -n $NS get ns'."
  echo "  Checks to run by hand:"
  echo "    systemctl is-active k3s"
  echo "    sudo systemctl restart k3s   # if inactive"
  echo "    sudo k3s kubectl -n $NS get ns"
  exit 1
fi
k() { $KCTL -n "$NS" "$@"; }
ok "kubectl invocation: $KCTL"

# --- 2. verify the k3s systemd service is active ----------------------------
step "2. k3s systemd service"
if command -v systemctl >/dev/null 2>&1; then
  if ! systemctl is-active --quiet k3s 2>/dev/null; then
    err "k3s systemd service is not active."
    echo "  Run this yourself (privileged; this script will not run it for you), then re-run this script:"
    echo "    sudo systemctl restart k3s"
    exit 1
  fi
  ok "k3s systemd service is active"
else
  warn "systemctl not found — skipping the k3s service-state check (kubectl already answered above, so continuing)"
fi

# --- 3. wait for the node to be Ready ----------------------------------------
step "3. wait for node Ready"
log "kubectl wait node --all --for=condition=Ready --timeout=$NODE_WAIT_TIMEOUT"
if k wait node --all --for=condition=Ready --timeout="$NODE_WAIT_TIMEOUT" >/dev/null 2>&1; then
  ok "node(s) Ready"
else
  # `kubectl wait` can report an error on some k3s/kubectl version combos even
  # when the node is already Ready; re-check directly before failing.
  NOT_READY="$(k get nodes --no-headers 2>/dev/null | awk '$2 !~ /(^|,)Ready(,|$)/ {print $1}')"
  if [[ -n "$NOT_READY" ]]; then
    err "node(s) not Ready after $NODE_WAIT_TIMEOUT: $NOT_READY"
    echo "  check: $KCTL get nodes -o wide"
    exit 1
  fi
  ok "node(s) already Ready"
fi

# --- 4. enumerate pods and classify stale ones -------------------------------
step "4. enumerate pods in $NS"

# Datastores/brokers/model-backends recreated BEFORE the rest of the app pods.
# Matched by pod-name prefix; harmless no-op for services this cluster doesn't run.
PRIORITY_PREFIXES=(postgres postgres-pgvector kafka rabbitmq mosquitto embedding-service llm-server llm-service)

is_priority() {
  local name="$1" p
  for p in "${PRIORITY_PREFIXES[@]}"; do
    [[ "$name" == "$p"* ]] && return 0
  done
  return 1
}

# STATUS values that are always stale, regardless of the READY column.
is_stale_status() {
  case "$1" in
    ContainerStatusUnknown|Error|Unknown|Init:ContainerStatusUnknown|CrashLoopBackOff|Evicted) return 0 ;;
    *) return 1 ;;
  esac
}

# DELETION classifier — deliberately conservative: a pod whose STATUS is
# literally "Running" is NEVER deleted here, even if its READY column hasn't
# caught up yet (e.g. still warming up right after being recreated) — that's
# not the reboot-stale symptom and force-deleting it would just churn a pod
# that's fine. Everything else (the explicit broken-status list, or any
# STATUS that isn't cleanly Running/Completed — Pending, PodInitializing,
# Init:N/M, ImagePullBackOff, stuck Terminating, ...) is treated as stale and
# gets recreated, matching the reported symptom shapes (ContainerStatusUnknown
# /Error/Init/Unknown) after a reboot.
needs_delete() {
  local status="$1"
  is_stale_status "$status" && return 0
  [[ "$status" == "Running" || "$status" == "Completed" ]] && return 1
  return 0
}

# RECOVERY-COMPLETE classifier — used only to decide whether to keep polling
# (step 5b / step 6). Stricter than needs_delete: a pod only counts as healthy
# once it is Completed, or Running with every container Ready (READY "N/N",
# N>0). This is what actually confirms the recreated pod came all the way up.
is_healthy() {
  local status="$1" ready="$2" numer denom
  [[ "$status" == "Completed" ]] && return 0
  [[ "$status" != "Running" ]] && return 1
  numer="${ready%%/*}"; denom="${ready##*/}"
  [[ -n "$denom" && "$denom" != "0" && "$numer" == "$denom" ]] && return 0
  return 1
}

STALE_PRIORITY_NAMES=(); STALE_PRIORITY_INFO=()
STALE_APP_NAMES=(); STALE_APP_INFO=()
TOTAL_PODS=0

while read -r pname pready pstatus prest page; do
  [[ -z "$pname" ]] && continue
  TOTAL_PODS=$((TOTAL_PODS + 1))
  if needs_delete "$pstatus"; then
    if is_priority "$pname"; then
      STALE_PRIORITY_NAMES+=("$pname"); STALE_PRIORITY_INFO+=("$pname  ready=$pready status=$pstatus")
    else
      STALE_APP_NAMES+=("$pname"); STALE_APP_INFO+=("$pname  ready=$pready status=$pstatus")
    fi
  fi
done < <(k get pods --no-headers 2>/dev/null)

log "total pods: $TOTAL_PODS  |  stale datastores/brokers: ${#STALE_PRIORITY_NAMES[@]}  |  stale app pods: ${#STALE_APP_NAMES[@]}"
for i in "${STALE_PRIORITY_INFO[@]:-}"; do [[ -n "$i" ]] && echo "  [priority] $i"; done
for i in "${STALE_APP_INFO[@]:-}"; do [[ -n "$i" ]] && echo "  [app]      $i"; done

if [[ ${#STALE_PRIORITY_NAMES[@]} -eq 0 && ${#STALE_APP_NAMES[@]} -eq 0 ]]; then
  ok "no stale pods found — nothing to recover"
  exit 0
fi

if $DRY_RUN; then
  step "DRY RUN — no pods will be deleted"
  for n in "${STALE_PRIORITY_NAMES[@]:-}"; do [[ -n "$n" ]] && echo "  would delete (priority): pod/$n"; done
  for n in "${STALE_APP_NAMES[@]:-}"; do [[ -n "$n" ]] && echo "  would delete (app):      pod/$n"; done
  echo
  echo "Re-run without --dry-run to actually delete these pods so their"
  echo "Deployment/StatefulSet controllers recreate them from the existing spec."
  exit 0
fi

# --- 5a. delete stale datastore/broker pods FIRST ----------------------------
step "5a. recreate datastores/brokers"
if [[ ${#STALE_PRIORITY_NAMES[@]} -eq 0 ]]; then
  log "no stale datastore/broker pods"
else
  for n in "${STALE_PRIORITY_NAMES[@]}"; do
    # --force --grace-period=0: these pods are stuck in a state the kubelet
    # already lost track of after the reboot; a graceful delete would hang
    # waiting for an ack that a real, live container will never send.
    if k delete pod "$n" --force --grace-period=0 >/dev/null 2>&1; then
      ok "deleted pod/$n"
    else
      warn "delete pod/$n reported an error (continuing)"
    fi
  done
fi

# --- 5b. wait for datastores/brokers to come back Ready ----------------------
step "5b. wait for datastores/brokers (timeout=${PRIORITY_WAIT_TIMEOUT}s)"
if [[ ${#STALE_PRIORITY_NAMES[@]} -eq 0 ]]; then
  log "skipped (nothing was deleted in this group)"
else
  waited=0
  while :; do
    still_bad=0
    while read -r pname pready pstatus prest page; do
      [[ -z "$pname" ]] && continue
      is_priority "$pname" || continue
      is_healthy "$pstatus" "$pready" || still_bad=$((still_bad + 1))
    done < <(k get pods --no-headers 2>/dev/null)
    [[ "$still_bad" -eq 0 ]] && { ok "datastores/brokers Ready"; break; }
    if [[ "$waited" -ge "$PRIORITY_WAIT_TIMEOUT" ]]; then
      warn "$still_bad datastore/broker pod(s) still not Ready after ${PRIORITY_WAIT_TIMEOUT}s — proceeding anyway"
      break
    fi
    sleep "$POLL_INTERVAL"; waited=$((waited + POLL_INTERVAL))
  done
fi

# --- 5c. delete remaining stale app-service pods -----------------------------
step "5c. recreate app-service pods"
if [[ ${#STALE_APP_NAMES[@]} -eq 0 ]]; then
  log "no stale app-service pods"
else
  for n in "${STALE_APP_NAMES[@]}"; do
    if k delete pod "$n" --force --grace-period=0 >/dev/null 2>&1; then
      ok "deleted pod/$n"
    else
      warn "delete pod/$n reported an error (continuing)"
    fi
  done
fi

# --- 6. final poll until everything is Running/Ready -------------------------
step "6. final poll (timeout=${POD_POLL_TIMEOUT}s)"
waited=0
NOT_READY_LIST=""
while :; do
  NOT_READY_LIST=""
  not_ready=0
  total=0
  while read -r pname pready pstatus prest page; do
    [[ -z "$pname" ]] && continue
    total=$((total + 1))
    if ! is_healthy "$pstatus" "$pready"; then
      not_ready=$((not_ready + 1))
      NOT_READY_LIST+="  $pname  ready=$pready status=$pstatus"$'\n'
    fi
  done < <(k get pods --no-headers 2>/dev/null)
  if [[ "$not_ready" -eq 0 ]]; then
    ok "all $total pods Running/Ready or Completed"
    break
  fi
  if [[ "$waited" -ge "$POD_POLL_TIMEOUT" ]]; then
    warn "$not_ready/$total pod(s) still not Running/Ready after ${POD_POLL_TIMEOUT}s"
    break
  fi
  sleep "$POLL_INTERVAL"; waited=$((waited + POLL_INTERVAL))
done

# --- summary ------------------------------------------------------------------
printf '\n\033[1m== recovery summary ==\033[0m\n'
echo "  namespace:            $NS"
echo "  datastores recreated: ${#STALE_PRIORITY_NAMES[@]}"
echo "  app pods recreated:   ${#STALE_APP_NAMES[@]}"
if [[ -n "$NOT_READY_LIST" ]]; then
  echo "  still not Ready:"
  printf '%s' "$NOT_READY_LIST"
  echo "Run this script again, or investigate with: $KCTL -n $NS get pods"
  exit 2
fi
echo "  status:               all pods Running/Ready"
exit 0
