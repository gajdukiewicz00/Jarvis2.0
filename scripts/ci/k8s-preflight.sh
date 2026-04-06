#!/usr/bin/env bash
# Validate k8s manifests before deploy:
# 1) root-restricted render + server-side dry-run apply
# 2) fail if any image tag uses :latest
# 3) enforce MQTT auth hardening checks
#
# IMPORTANT: render uses root-restricted `kubectl kustomize` (no LoadRestrictionsNone)
# so CI/local preflight fails fast on out-of-tree references.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OVERLAY_PATH="${1:-${PROJECT_ROOT}/k8s/overlays/prod}"
# Default to client-side preflight so render checks are deterministic without cluster access.
# Use K8S_PREFLIGHT_MODE=server to enable secret checks + server-side dry-run apply.
PREFLIGHT_MODE="${K8S_PREFLIGHT_MODE:-client}"
TOOLCHAIN_IMAGE="${K8S_PREFLIGHT_TOOLCHAIN_IMAGE:-bitnami/kubectl:latest}"
K8S_NAMESPACE="${K8S_PREFLIGHT_NAMESPACE:-jarvis}"
REQUIRED_SECRETS_RAW="${K8S_PREFLIGHT_REQUIRED_SECRETS:-jarvis-secrets,jarvis-tls}"
PRIMARY_SECRET_NAME="${K8S_PREFLIGHT_PRIMARY_SECRET_NAME:-jarvis-secrets}"
REQUIRED_SECRET_KEYS_RAW="${K8S_PREFLIGHT_REQUIRED_SECRET_KEYS:-POSTGRES_USER,POSTGRES_PASSWORD,SPRING_DATASOURCE_USERNAME,SPRING_DATASOURCE_PASSWORD,JWT_SECRET,SERVICE_JWT_SECRET,SPRING_RABBITMQ_USERNAME,SPRING_RABBITMQ_PASSWORD,MQTT_USERNAME,MQTT_PASSWORD}"
RENDER_OUTPUT="${K8S_PREFLIGHT_RENDER_OUTPUT:-/tmp/prod-render.yaml}"
CORE_WORKLOADS_RAW="${K8S_PREFLIGHT_CORE_WORKLOADS:-api-gateway,orchestrator,security-service,voice-gateway,planner-service,life-tracker}"
OPTIONAL_WORKLOADS_RAW="${K8S_PREFLIGHT_OPTIONAL_WORKLOADS:-llm-service,memory-service,embedding-service,llm-server}"
CORE_SA_TOKEN_EXCEPTIONS_RAW="${K8S_PREFLIGHT_CORE_SA_TOKEN_EXCEPTIONS:-}"
KYVERNO_EXCEPTION_LABEL_KEY="${K8S_PREFLIGHT_KYVERNO_EXCEPTION_LABEL_KEY:-security.jarvis.io/kyverno-exempt}"
KYVERNO_EXCEPTION_LABEL_VALUE="${K8S_PREFLIGHT_KYVERNO_EXCEPTION_LABEL_VALUE:-true}"
CORE_DIGEST_POLICY_MODE="${K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE:-audit}"
IFS=',' read -r -a REQUIRED_SECRETS <<< "${REQUIRED_SECRETS_RAW}"
IFS=',' read -r -a REQUIRED_SECRET_KEYS <<< "${REQUIRED_SECRET_KEYS_RAW}"
IFS=',' read -r -a CORE_WORKLOADS <<< "${CORE_WORKLOADS_RAW}"
IFS=',' read -r -a OPTIONAL_WORKLOADS <<< "${OPTIONAL_WORKLOADS_RAW}"
IFS=',' read -r -a CORE_SA_TOKEN_EXCEPTIONS <<< "${CORE_SA_TOKEN_EXCEPTIONS_RAW}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

require_cmd grep
require_cmd awk

detect_kubeconfig_local() {
  if [[ -n "${KUBECONFIG:-}" ]]; then
    return 0
  fi
  if [[ -r "${HOME}/.jarvis/kubeconfig" ]]; then
    export KUBECONFIG="${HOME}/.jarvis/kubeconfig"
    echo "🔐 Using kubeconfig: ${KUBECONFIG}"
    return 0
  fi
  if [[ -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
    echo "🔐 Using kubeconfig: ${KUBECONFIG}"
  fi
}

ensure_server_cluster_access_local() {
  if [[ "${PREFLIGHT_MODE}" != "server" ]]; then
    return 0
  fi

  detect_kubeconfig_local

  local err_file
  err_file="$(mktemp)"
  if kubectl cluster-info >/dev/null 2>"${err_file}"; then
    rm -f "${err_file}"
    return 0
  fi

  echo "❌ Server preflight requires a reachable Kubernetes cluster and valid credentials"
  sed 's/^/   /' "${err_file}"
  rm -f "${err_file}"
  echo "   Set KUBECONFIG explicitly or use K8S_PREFLIGHT_MODE=client for render-only validation."
  exit 1
}

secret_exists_or_fail_local() {
  local secret_name="$1"
  local err_file
  err_file="$(mktemp)"

  if kubectl -n "${K8S_NAMESPACE}" get secret "${secret_name}" >/dev/null 2>"${err_file}"; then
    rm -f "${err_file}"
    return 0
  fi

  if grep -Eqi "not found" "${err_file}"; then
    rm -f "${err_file}"
    echo "❌ Required secret '${secret_name}' is missing in namespace '${K8S_NAMESPACE}'"
    case "${secret_name}" in
      jarvis-secrets)
        echo "   Apply secrets first: ./scripts/product/jarvis-secrets-apply.sh"
        ;;
      jarvis-tls)
        echo "   Create/apply the TLS secret before deploy: kubectl create secret tls jarvis-tls ..."
        ;;
    esac
    exit 1
  fi

  echo "❌ Unable to query secret '${secret_name}' in namespace '${K8S_NAMESPACE}'"
  sed 's/^/   /' "${err_file}"
  rm -f "${err_file}"
  echo "   Fix kubeconfig/cluster credentials before running server preflight."
  exit 1
}

secret_key_exists_or_fail_local() {
  local secret_name="$1"
  local secret_key="$2"
  local err_file
  local value

  err_file="$(mktemp)"
  value="$(kubectl -n "${K8S_NAMESPACE}" get secret "${secret_name}" -o "jsonpath={.data.${secret_key}}" 2>"${err_file}" || true)"

  if [[ -n "${value}" ]]; then
    rm -f "${err_file}"
    return 0
  fi

  if grep -Eqi "not found" "${err_file}"; then
    echo "❌ Required secret '${secret_name}' is missing in namespace '${K8S_NAMESPACE}'"
    case "${secret_name}" in
      jarvis-secrets)
        echo "   Apply secrets first: ./scripts/product/jarvis-secrets-apply.sh"
        ;;
      jarvis-tls)
        echo "   Create/apply the TLS secret before deploy: kubectl create secret tls jarvis-tls ..."
        ;;
    esac
  elif [[ -s "${err_file}" ]]; then
    echo "❌ Unable to query key '${secret_key}' from secret '${secret_name}'"
    sed 's/^/   /' "${err_file}"
    echo "   Fix kubeconfig/cluster credentials before running server preflight."
  else
    echo "❌ Required key '${secret_key}' is missing from secret '${secret_name}' in namespace '${K8S_NAMESPACE}'"
    echo "   Update your secrets env file and re-run: ./scripts/product/jarvis-secrets-apply.sh"
  fi

  rm -f "${err_file}"
  exit 1
}

is_core_sa_token_exception() {
  local workload="$1"
  local exception
  for exception in "${CORE_SA_TOKEN_EXCEPTIONS[@]}"; do
    exception="${exception//[[:space:]]/}"
    [[ -z "${exception}" ]] && continue
    if [[ "${workload}" == "${exception}" ]]; then
      return 0
    fi
  done
  return 1
}

echo "🔎 Checking for forbidden ':latest' image tags in k8s manifests..."
if grep -RIn --include="*.yaml" --include="*.yml" "image:.*:latest" "${PROJECT_ROOT}/k8s"; then
  echo "❌ Found ':latest' image tags in k8s manifests"
  exit 1
fi

if [[ -x "${PROJECT_ROOT}/scripts/ci/check-mqtt-hardening.sh" ]]; then
  "${PROJECT_ROOT}/scripts/ci/check-mqtt-hardening.sh"
fi

render_overlay_local() {
  echo "🧾 Rendering overlay via root-restricted kubectl kustomize..."
  kubectl kustomize "${OVERLAY_PATH}" | tee "${RENDER_OUTPUT}" >/dev/null
}

validate_render_output_local() {
  if [[ ! -s "${RENDER_OUTPUT}" ]]; then
    echo "❌ Render output is empty: ${RENDER_OUTPUT}"
    exit 1
  fi
  if ! grep -Eq "name:[[:space:]]*mosquitto" "${RENDER_OUTPUT}"; then
    echo "❌ Render output missing expected 'mosquitto' resource marker"
    exit 1
  fi
  if ! grep -Eq "readinessProbe|livenessProbe|startupProbe" "${RENDER_OUTPUT}"; then
    echo "❌ Render output missing expected probe markers (readiness/liveness/startup)"
    exit 1
  fi
  if ! grep -Eq "persistentVolumeClaim" "${RENDER_OUTPUT}"; then
    echo "❌ Render output missing expected PVC markers"
    exit 1
  fi
  validate_kyverno_baseline_local
  validate_core_kyverno_hardening_local
  validate_core_capabilities_and_images_local
  validate_core_image_digest_policy_local
  validate_networkpolicy_baseline_local
  validate_core_service_account_policy_local
  validate_rbac_safety_local
  validate_hardening_targets_local
  validate_core_pdb_coverage_local
  validate_core_replica_policy_local
  validate_core_scheduling_policy_local
  validate_core_priority_policy_local
  validate_core_resources_policy_local
  validate_core_termination_policy_local
  validate_optional_replica_policy_local
}

validate_kyverno_baseline_local() {
  local policy_name
  local kyverno_policy_names=(
    kyverno-prod-require-runtime-hardening
    kyverno-prod-require-container-hardening
    kyverno-prod-disallow-host-scope
    kyverno-prod-core-require-resources
    kyverno-prod-disallow-capabilities
    kyverno-prod-disallow-latest-tag
    kyverno-prod-verify-images
  )

  for policy_name in "${kyverno_policy_names[@]}"; do
    if ! awk -v policy_name="${policy_name}" '
      BEGIN { RS="---"; found=0 }
      $0 ~ /apiVersion:[[:space:]]*kyverno.io\/v1/ &&
      $0 ~ /kind:[[:space:]]*Policy/ &&
      $0 ~ ("name:[[:space:]]*" policy_name "([[:space:]]|$)") {
        found=1
      }
      END { exit !found }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Kyverno baseline check failed: missing policy '${policy_name}' in rendered prod overlay"
      exit 1
    fi
  done

  if grep -Eq "hostPath:[[:space:]]*" "${RENDER_OUTPUT}"; then
    echo "❌ Kyverno baseline check failed: rendered prod manifest contains forbidden hostPath volumes"
    exit 1
  fi

  if ! awk '
    BEGIN { RS="---"; found=0 }
    $0 ~ /apiVersion:[[:space:]]*kyverno.io\/v1/ &&
    $0 ~ /kind:[[:space:]]*Policy/ &&
    $0 ~ /name:[[:space:]]*kyverno-prod-verify-images/ &&
    $0 ~ /verifyImages:[[:space:]]*/ {
      found=1
    }
    END { exit !found }
  ' "${RENDER_OUTPUT}"; then
    echo "❌ Kyverno baseline check failed: kyverno-prod-verify-images policy missing verifyImages rules"
    exit 1
  fi
}

validate_core_kyverno_hardening_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" -v exception_key="${KYVERNO_EXCEPTION_LABEL_KEY}" -v exception_value="${KYVERNO_EXCEPTION_LABEL_VALUE}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        exempt=(index($0, exception_key ": " exception_value) > 0 || index($0, exception_key ": \"" exception_value "\"") > 0)
        has_non_root=($0 ~ /runAsNonRoot:[[:space:]]*true/)
        has_seccomp=($0 ~ /seccompProfile:[[:space:]]*/ && $0 ~ /type:[[:space:]]*RuntimeDefault/)
        has_no_priv=($0 ~ /allowPrivilegeEscalation:[[:space:]]*false/)
        has_ro_rootfs=($0 ~ /readOnlyRootFilesystem:[[:space:]]*true/)
        no_host_ns=($0 !~ /hostNetwork:[[:space:]]*true/ && $0 !~ /hostPID:[[:space:]]*true/ && $0 !~ /hostIPC:[[:space:]]*true/)
        no_hostpath=($0 !~ /hostPath:[[:space:]]*/)
        if (exempt || (has_non_root && has_seccomp && has_no_priv && has_ro_rootfs && no_host_ns && no_hostpath)) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Kyverno hardening check failed for core workload '${workload}': expected runAsNonRoot=true, seccomp RuntimeDefault, allowPrivilegeEscalation=false, readOnlyRootFilesystem=true, no host namespaces/hostPath, or explicit allowlist label ${KYVERNO_EXCEPTION_LABEL_KEY}=${KYVERNO_EXCEPTION_LABEL_VALUE}"
      exit 1
    fi
  done
}

validate_core_capabilities_and_images_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" -v exception_key="${KYVERNO_EXCEPTION_LABEL_KEY}" -v exception_value="${KYVERNO_EXCEPTION_LABEL_VALUE}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        exempt=(index($0, exception_key ": " exception_value) > 0 || index($0, exception_key ": \"" exception_value "\"") > 0)
        no_cap_add=($0 !~ /(^|[[:space:]])add:[[:space:]]*/)
        has_drop_all=($0 ~ /capabilities:[[:space:]]*/ && $0 ~ /drop:[[:space:]]*/ && $0 ~ /-[[:space:]]*ALL/)
        no_latest=($0 !~ /image:[[:space:]]*[^[:space:]]*:latest([[:space:]]|$|@)/)
        if (exempt || (no_cap_add && has_drop_all && no_latest)) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Core image/capabilities check failed for '${workload}': expected no capabilities.add, capabilities.drop includes ALL, no image with :latest, or explicit allowlist label ${KYVERNO_EXCEPTION_LABEL_KEY}=${KYVERNO_EXCEPTION_LABEL_VALUE}"
      exit 1
    fi
  done
}

validate_core_image_digest_policy_local() {
  local workload
  local missing=""

  if [[ "${CORE_DIGEST_POLICY_MODE}" != "audit" && "${CORE_DIGEST_POLICY_MODE}" != "enforce" ]]; then
    echo "❌ Invalid K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE='${CORE_DIGEST_POLICY_MODE}' (expected: audit|enforce)"
    exit 1
  fi

  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" -v exception_key="${KYVERNO_EXCEPTION_LABEL_KEY}" -v exception_value="${KYVERNO_EXCEPTION_LABEL_VALUE}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        exempt=(index($0, exception_key ": " exception_value) > 0 || index($0, exception_key ": \"" exception_value "\"") > 0)
        has_reason=(index($0, "security.jarvis.io/kyverno-exempt-reason:") > 0)
        image_count=0
        all_digest=1
        n=split($0, lines, "\n")
        for (i=1; i<=n; i++) {
          line=lines[i]
          if (line ~ /^[[:space:]]*image:[[:space:]]*/) {
            image_count++
            image=line
            sub(/^[[:space:]]*image:[[:space:]]*/, "", image)
            gsub(/["'\'']/, "", image)
            if (image !~ /@sha256:[0-9a-fA-F]{64}$/) {
              all_digest=0
            }
          }
        }
        if ((image_count > 0 && all_digest) || (exempt && has_reason)) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      missing="${missing}${workload} "
    fi
  done

  if [[ -n "${missing}" ]]; then
    if [[ "${CORE_DIGEST_POLICY_MODE}" == "enforce" ]]; then
      echo "❌ Core digest policy check failed (mode=enforce): missing digest pinning (image@sha256) or approved exception label+reason for: ${missing}"
      exit 1
    fi
    echo "⚠️  Core digest policy audit: workloads without digest pinning or approved exception (label+reason): ${missing}"
    echo "   To enforce this gate, set K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE=enforce."
  fi
}

validate_networkpolicy_baseline_local() {
  if ! awk '
    BEGIN { RS="---"; found=0 }
    $0 ~ /kind:[[:space:]]*NetworkPolicy/ &&
    $0 ~ /name:[[:space:]]*jarvis-default-deny/ &&
    $0 ~ /policyTypes:[[:space:]]*/ &&
    $0 ~ /-[[:space:]]*Ingress/ &&
    $0 ~ /-[[:space:]]*Egress/ {
      found=1
    }
    END { exit !found }
  ' "${RENDER_OUTPUT}"; then
    echo "❌ NetworkPolicy baseline check failed: missing default-deny ingress+egress policy"
    exit 1
  fi

  if ! awk '
    BEGIN { RS="---"; found=0 }
    $0 ~ /kind:[[:space:]]*NetworkPolicy/ &&
    $0 ~ /name:[[:space:]]*jarvis-allow-dns-egress/ &&
    $0 ~ /port:[[:space:]]*53/ {
      found=1
    }
    END { exit !found }
  ' "${RENDER_OUTPUT}"; then
    echo "❌ NetworkPolicy baseline check failed: missing DNS egress allow policy"
    exit 1
  fi

  if ! awk '
    BEGIN { RS="---"; found=0; has_core=0 }
    $0 ~ /kind:[[:space:]]*NetworkPolicy/ &&
    $0 ~ /name:[[:space:]]*api-gateway-egress-core/ &&
    $0 ~ /podSelector:[[:space:]]*/ &&
    $0 ~ /app:[[:space:]]*api-gateway/ {
      found=1
      if ($0 ~ /app:[[:space:]]*orchestrator/ &&
          $0 ~ /app:[[:space:]]*planner-service/ &&
          $0 ~ /app:[[:space:]]*life-tracker/ &&
          $0 ~ /app:[[:space:]]*security-service/ &&
          $0 ~ /port:[[:space:]]*8083/ &&
          $0 ~ /port:[[:space:]]*8092/ &&
          $0 ~ /port:[[:space:]]*8085/ &&
          $0 ~ /port:[[:space:]]*8088/) {
        has_core=1
      }
    }
    END { exit !(found && has_core) }
  ' "${RENDER_OUTPUT}"; then
    echo "❌ NetworkPolicy baseline check failed: api-gateway egress to core backends is incomplete"
    exit 1
  fi
}

validate_core_service_account_policy_local() {
  local workload
  local expected_sa
  local require_automount_false

  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    expected_sa="jarvis-${workload}-sa"
    require_automount_false=1
    if is_core_sa_token_exception "${workload}"; then
      require_automount_false=0
    fi

    if ! awk -v workload="${workload}" -v expected_sa="${expected_sa}" -v require_automount_false="${require_automount_false}" '
      BEGIN { RS="---"; found=0; sa_ok=0; not_default=1; automount_ok=(require_automount_false ? 0 : 1) }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        if ($0 ~ ("serviceAccountName:[[:space:]]*" expected_sa "([[:space:]]|$)")) {
          sa_ok=1
        }
        if ($0 ~ /serviceAccountName:[[:space:]]*default([[:space:]]|$)/) {
          not_default=0
        }
        if (require_automount_false && $0 ~ /automountServiceAccountToken:[[:space:]]*false/) {
          automount_ok=1
        }
      }
      END { exit !(found && sa_ok && not_default && automount_ok) }
    ' "${RENDER_OUTPUT}"; then
      if [[ "${require_automount_false}" -eq 1 ]]; then
        echo "❌ RBAC policy check failed for core workload '${workload}': expected serviceAccountName='${expected_sa}', non-default SA, and automountServiceAccountToken=false"
      else
        echo "❌ RBAC policy check failed for core workload '${workload}': expected serviceAccountName='${expected_sa}' and non-default SA"
      fi
      exit 1
    fi

    if ! awk -v expected_sa="${expected_sa}" '
      BEGIN { RS="---"; found=0 }
      $0 ~ /kind:[[:space:]]*ServiceAccount/ && $0 ~ ("name:[[:space:]]*" expected_sa "([[:space:]]|$)") {
        found=1
      }
      END { exit !found }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ RBAC policy check failed: missing ServiceAccount resource '${expected_sa}'"
      exit 1
    fi
  done
}

validate_rbac_safety_local() {
  if awk '
    BEGIN { RS="---"; bad=0 }
    $0 ~ /kind:[[:space:]]*(RoleBinding|ClusterRoleBinding)/ &&
    $0 ~ /roleRef:[[:space:]]*/ &&
    $0 ~ /name:[[:space:]]*cluster-admin([[:space:]]|$)/ {
      bad=1
    }
    END { exit bad ? 0 : 1 }
  ' "${RENDER_OUTPUT}"; then
    echo "❌ RBAC safety check failed: cluster-admin binding is not allowed in prod overlay"
    exit 1
  fi

  if awk '
    BEGIN { RS="---"; bad=0 }
    $0 ~ /kind:[[:space:]]*(Role|ClusterRole)/ {
      if ($0 ~ /verbs:[[:space:]]*\[[^]]*\*[^]]*\]/ || $0 ~ /resources:[[:space:]]*\[[^]]*\*[^]]*\]/) {
        bad=1
      }
      n=split($0, lines, "\n")
      in_verbs=0
      in_resources=0
      for (i=1; i<=n; i++) {
        line=lines[i]
        if (line ~ /^[[:space:]]*verbs:[[:space:]]*$/) {
          in_verbs=1
          in_resources=0
          continue
        }
        if (line ~ /^[[:space:]]*resources:[[:space:]]*$/) {
          in_resources=1
          in_verbs=0
          continue
        }
        if ((in_verbs || in_resources) && line ~ /^[[:space:]]*-[[:space:]]*["'\'']?\*["'\'']?[[:space:]]*$/) {
          bad=1
        }
        if (line ~ /^[[:space:]]*[A-Za-z0-9_.-]+:[[:space:]]*.*$/ &&
            line !~ /^[[:space:]]*-[[:space:]]*/ &&
            line !~ /^[[:space:]]*(verbs|resources):/) {
          in_verbs=0
          in_resources=0
        }
      }
    }
    END { exit bad ? 0 : 1 }
  ' "${RENDER_OUTPUT}"; then
    echo "❌ RBAC safety check failed: wildcard verbs/resources in Role/ClusterRole are not allowed"
    exit 1
  fi
}

validate_hardening_targets_local() {
  local workload
  for workload in llm-service memory-service embedding-service llm-server; do
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; hardened=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        if ($0 ~ /readOnlyRootFilesystem:[[:space:]]*true/ && $0 ~ /allowPrivilegeEscalation:[[:space:]]*false/) {
          hardened=1
        }
      }
      END { exit !(found && hardened) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Hardening check failed for deployment '${workload}': expected readOnlyRootFilesystem=true and allowPrivilegeEscalation=false"
      exit 1
    fi
  done
}

validate_core_pdb_coverage_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0 }
      $0 ~ /kind:[[:space:]]*PodDisruptionBudget/ && $0 ~ ("app:[[:space:]]*" workload "([[:space:]]|$)") { found=1 }
      END { exit !found }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ PDB coverage check failed: no PodDisruptionBudget selector for core workload '${workload}'"
      exit 1
    fi
  done
}

validate_core_replica_policy_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        replicas=1
        n=split($0, lines, "\n")
        for (i=1; i<=n; i++) {
          if (lines[i] ~ /^[[:space:]]*replicas:[[:space:]]*[0-9]+[[:space:]]*$/) {
            line=lines[i]
            sub(/^[[:space:]]*replicas:[[:space:]]*/, "", line)
            replicas=line+0
          }
        }
        if (replicas >= 2) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Replica policy check failed for core workload '${workload}': expected replicas >= 2"
      exit 1
    fi
  done
}

validate_core_scheduling_policy_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        if ($0 ~ /topologySpreadConstraints:[[:space:]]*/ || $0 ~ /podAntiAffinity:[[:space:]]*/) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Scheduling policy check failed for core workload '${workload}': expected topologySpreadConstraints or podAntiAffinity"
      exit 1
    fi
  done
}

validate_core_priority_policy_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        if ($0 ~ /priorityClassName:[[:space:]]*jarvis-core-high/) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Priority policy check failed for core workload '${workload}': expected priorityClassName=jarvis-core-high"
      exit 1
    fi
  done
}

validate_core_resources_policy_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        if ($0 ~ /resources:[[:space:]]*/ && $0 ~ /requests:[[:space:]]*/ && $0 ~ /limits:[[:space:]]*/ && $0 ~ /cpu:[[:space:]]*/ && $0 ~ /memory:[[:space:]]*/) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Resource policy check failed for core workload '${workload}': expected resources.requests/limits with cpu+memory"
      exit 1
    fi
  done
}

validate_core_termination_policy_local() {
  local workload
  for workload in "${CORE_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        if ($0 ~ /terminationGracePeriodSeconds:[[:space:]]*[0-9]+/) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Termination policy check failed for core workload '${workload}': expected terminationGracePeriodSeconds"
      exit 1
    fi
  done
}

validate_optional_replica_policy_local() {
  local workload
  for workload in "${OPTIONAL_WORKLOADS[@]}"; do
    workload="${workload//[[:space:]]/}"
    [[ -z "${workload}" ]] && continue
    if ! awk -v workload="${workload}" '
      BEGIN { RS="---"; found=0; ok=0 }
      $0 ~ /kind:[[:space:]]*Deployment/ && $0 ~ ("name:[[:space:]]*" workload "([[:space:]]|$)") {
        found=1
        replicas=1
        n=split($0, lines, "\n")
        for (i=1; i<=n; i++) {
          if (lines[i] ~ /^[[:space:]]*replicas:[[:space:]]*[0-9]+[[:space:]]*$/) {
            line=lines[i]
            sub(/^[[:space:]]*replicas:[[:space:]]*/, "", line)
            replicas=line+0
          }
        }
        if (replicas == 0) {
          ok=1
        }
      }
      END { exit !(found && ok) }
    ' "${RENDER_OUTPUT}"; then
      echo "❌ Replica policy check failed for optional workload '${workload}': expected replicas = 0"
      exit 1
    fi
  done
}

run_local_preflight() {
  local dry_run_flag="--dry-run=${PREFLIGHT_MODE}"
  ensure_server_cluster_access_local
  ensure_required_secrets_exist_local
  render_overlay_local
  validate_render_output_local
  if [[ "${PREFLIGHT_MODE}" == "client" ]]; then
    echo "🧪 Running k8s preflight (client mode): render-only checks passed"
    return
  fi
  echo "🧪 Running k8s preflight (${PREFLIGHT_MODE} dry-run)..."
  kubectl apply "${dry_run_flag}" -f "${RENDER_OUTPUT}"
}

ensure_required_secrets_exist_local() {
  if [[ "${#REQUIRED_SECRETS[@]}" -eq 0 ]]; then
    return
  fi
  if [[ "${PREFLIGHT_MODE}" != "server" ]]; then
    return
  fi
  echo "🔐 Verifying required secrets in namespace '${K8S_NAMESPACE}'..."
  for secret_name in "${REQUIRED_SECRETS[@]}"; do
    secret_name="${secret_name//[[:space:]]/}"
    if [[ -z "${secret_name}" ]]; then
      continue
    fi
    secret_exists_or_fail_local "${secret_name}"
  done

  if [[ -n "${PRIMARY_SECRET_NAME//[[:space:]]/}" && "${#REQUIRED_SECRET_KEYS[@]}" -gt 0 ]]; then
    echo "🔐 Verifying required keys in secret '${PRIMARY_SECRET_NAME}'..."
    for secret_key in "${REQUIRED_SECRET_KEYS[@]}"; do
      secret_key="${secret_key//[[:space:]]/}"
      if [[ -z "${secret_key}" ]]; then
        continue
      fi
      secret_key_exists_or_fail_local "${PRIMARY_SECRET_NAME}" "${secret_key}"
    done
  fi
}

container_kube_mount_args() {
  if [[ -r "${HOME}/.jarvis/kubeconfig" ]]; then
    printf '%s\n' "-v" "${HOME}/.jarvis:/root/.jarvis:ro" "-e" "KUBECONFIG=/root/.jarvis/kubeconfig"
  elif [[ -n "${KUBECONFIG:-}" && -r "${KUBECONFIG}" ]]; then
    local host_kubeconfig
    local container_kubeconfig
    host_kubeconfig="$(cd "$(dirname "${KUBECONFIG}")" && pwd)/$(basename "${KUBECONFIG}")"
    container_kubeconfig="/tmp/jarvis-preflight-kubeconfig"
    printf '%s\n' "-v" "${host_kubeconfig}:${container_kubeconfig}:ro" "-e" "KUBECONFIG=${container_kubeconfig}"
  fi
}

required_secret_keys_csv() {
  local result=""
  local secret_key
  for secret_key in "${REQUIRED_SECRET_KEYS[@]}"; do
    secret_key="${secret_key//[[:space:]]/}"
    [[ -z "${secret_key}" ]] && continue
    if [[ -n "${result}" ]]; then
      result+=","
    fi
    result+="${secret_key}"
  done
  printf '%s' "${result}"
}

run_container_preflight() {
  require_cmd docker
  local dry_run_flag="--dry-run=${PREFLIGHT_MODE}"
  local secrets_csv="${REQUIRED_SECRETS_RAW}"
  local secret_keys_csv
  local mount_args=()

  secret_keys_csv="$(required_secret_keys_csv)"
  while IFS= read -r line; do
    [[ -z "${line}" ]] && continue
    mount_args+=("${line}")
  done < <(container_kube_mount_args)

  echo "🧪 Running k8s preflight in container (${TOOLCHAIN_IMAGE})..."
  docker run --rm \
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}" \
    -v "${HOME}/.kube:/root/.kube:ro" \
    -v "/tmp:/tmp" \
    "${mount_args[@]}" \
    -w "${PROJECT_ROOT}" \
    -e "K8S_NAMESPACE=${K8S_NAMESPACE}" \
    -e "REQUIRED_SECRETS=${secrets_csv}" \
    -e "PRIMARY_SECRET_NAME=${PRIMARY_SECRET_NAME}" \
    -e "REQUIRED_SECRET_KEYS=${secret_keys_csv}" \
    -e "PREFLIGHT_MODE=${PREFLIGHT_MODE}" \
    -e "RENDER_OUTPUT=${RENDER_OUTPUT}" \
    "${TOOLCHAIN_IMAGE}" \
    sh -ec '
      secrets_csv="${REQUIRED_SECRETS}"
      required_secret_keys="${REQUIRED_SECRET_KEYS}"
      primary_secret_name="${PRIMARY_SECRET_NAME}"
      if [ "${PREFLIGHT_MODE}" = "server" ] && ! kubectl cluster-info >/dev/null 2>&1; then
        echo "❌ Server preflight requires a reachable Kubernetes cluster and valid credentials"
        echo "   Set KUBECONFIG explicitly or use K8S_PREFLIGHT_MODE=client for render-only validation."
        exit 1
      fi
      if [ "${PREFLIGHT_MODE}" = "server" ]; then
        old_ifs="${IFS}"
        IFS=","
        for secret_name in ${secrets_csv}; do
          if [ -n "${secret_name}" ] && ! kubectl -n "${K8S_NAMESPACE}" get secret "${secret_name}" >/dev/null 2>&1; then
            echo "❌ Required secret '\''${secret_name}'\'' is missing in namespace '\''${K8S_NAMESPACE}'\''"
            exit 1
          fi
        done
        if [ -n "${primary_secret_name}" ] && [ -n "${required_secret_keys}" ]; then
          for secret_key in ${required_secret_keys}; do
            if [ -n "${secret_key}" ] && [ -z "$(kubectl -n "${K8S_NAMESPACE}" get secret "${primary_secret_name}" -o "jsonpath={.data.${secret_key}}" 2>/dev/null)" ]; then
              echo "❌ Required key '\''${secret_key}'\'' is missing from secret '\''${primary_secret_name}'\'' in namespace '\''${K8S_NAMESPACE}'\''"
              exit 1
            fi
          done
        fi
        IFS="${old_ifs}"
      fi
      kubectl kustomize "'"${OVERLAY_PATH}"'" > "${RENDER_OUTPUT}"
      if [ "${PREFLIGHT_MODE}" = "client" ]; then
        echo "🧪 Running k8s preflight (client mode): render-only checks passed"
      else
        kubectl apply '"${dry_run_flag}"' -f "${RENDER_OUTPUT}"
      fi
    '
  validate_render_output_local
}

if command -v kubectl >/dev/null 2>&1; then
  run_local_preflight
else
  if [[ -n "${CI:-}" ]] || [[ "${K8S_PREFLIGHT_FORCE_CONTAINER:-false}" == "true" ]]; then
    run_container_preflight
  else
    echo "⚠️  Skipping k8s preflight locally: missing kubectl toolchain."
    echo "   Install kubectl or run with K8S_PREFLIGHT_FORCE_CONTAINER=true."
    exit 0
  fi
fi

echo "✅ k8s preflight passed"
