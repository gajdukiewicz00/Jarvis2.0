#!/usr/bin/env bash

# Shared Kubernetes CLI bootstrap for local Jarvis scripts.
# Prefers MicroK8s when it is installed, then falls back to kubectl+kubeconfig.

if [[ -n "${JARVIS_K8S_COMMON_SH_LOADED:-}" ]]; then
  return 0
fi
JARVIS_K8S_COMMON_SH_LOADED=1

jarvis_detect_kubeconfig() {
  if [[ -n "${KUBECONFIG:-}" ]]; then
    return 0
  fi

  if [[ -r "${HOME}/.jarvis/kubeconfig" ]]; then
    export KUBECONFIG="${HOME}/.jarvis/kubeconfig"
    return 0
  fi

  if [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
  fi
}

jarvis_init_kubectl() {
  if [[ -n "${JARVIS_KUBECTL_INITIALIZED:-}" ]]; then
    return 0
  fi

  jarvis_detect_kubeconfig

  if type -P microk8s >/dev/null 2>&1; then
    JARVIS_KUBECTL_CMD=("$(type -P microk8s)" kubectl)
  elif type -P kubectl >/dev/null 2>&1; then
    JARVIS_KUBECTL_CMD=("$(type -P kubectl)")
  else
    return 1
  fi

  JARVIS_KUBECTL_INITIALIZED=1
}

jarvis_require_kubectl() {
  if jarvis_init_kubectl; then
    return 0
  fi

  echo "❌ Neither 'microk8s' nor 'kubectl' is available in PATH." >&2
  return 1
}

jarvis_kubectl_description() {
  jarvis_require_kubectl >/dev/null 2>&1 || return 1
  printf '%s' "${JARVIS_KUBECTL_CMD[*]}"
}

jarvis_cluster_reachable() {
  jarvis_require_kubectl >/dev/null 2>&1 || return 1
  "${JARVIS_KUBECTL_CMD[@]}" get --raw=/readyz >/dev/null 2>&1
}

kubectl() {
  jarvis_require_kubectl || return 1
  "${JARVIS_KUBECTL_CMD[@]}" "$@"
}

kctl() {
  kubectl "$@"
}
