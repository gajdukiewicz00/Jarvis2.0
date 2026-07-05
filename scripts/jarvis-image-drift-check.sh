#!/usr/bin/env bash
# =============================================================================
# jarvis-image-drift-check.sh — compare LIVE deployment image tags in the
# jarvis-prod namespace against the pinned tags in the canonical overlay
# (infra/k8s/overlays/prod/kustomization.yaml — see docs/DEPLOYMENT_CANONICAL.md).
#
# WHY THIS EXISTS: a live Deployment's image can drift from what the
# committed overlay says it should be — e.g. someone ran `kubectl set image`
# by hand, or `jarvis-recover-after-reboot.sh`-style pod recreation papered
# over a spec that never actually got re-applied, or a promotion script wrote
# k8s/overlays/prod-release/** but the operator meant to update infra/k8s/.
# This script is the fast, read-only way to notice that before it causes a
# confusing "why is this pod running the wrong code" investigation.
#
# READ-ONLY: only `kubectl get` calls. Never applies, patches, or deletes
# anything. Safe to run against a live cluster at any time, including from a
# cron / CI job.
#
# Usage:
#   scripts/jarvis-image-drift-check.sh                # table + exit code
#   scripts/jarvis-image-drift-check.sh --namespace=ns  # override namespace
#   scripts/jarvis-image-drift-check.sh --quiet         # suppress the table, print only drifted rows
#
# Exit codes: 0 no drift · 1 drift found · 2 usage error · 3 cannot reach cluster
# =============================================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${JARVIS_NAMESPACE:-jarvis-prod}"
KUSTOMIZATION="${JARVIS_KUSTOMIZATION:-${ROOT_DIR}/infra/k8s/overlays/prod/kustomization.yaml}"
QUIET=false

usage() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") [--namespace=NAME] [--quiet] [--help]

Compares live 'kubectl get deploy' image tags in namespace NAME (default:
jarvis-prod) against the pinned images: block of
infra/k8s/overlays/prod/kustomization.yaml (the canonical deploy tree; see
docs/DEPLOYMENT_CANONICAL.md). Exits non-zero on drift.

Options:
  --namespace=NAME   Kubernetes namespace to inspect (default: jarvis-prod)
  --quiet            Only print drifted/missing rows, not the full table
  --help, -h         Show this help
EOF
}

for arg in "$@"; do
  case "${arg}" in
    --namespace=*) NS="${arg#*=}" ;;
    --quiet) QUIET=true ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: ${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -f "${KUSTOMIZATION}" ]] || { echo "❌ kustomization not found: ${KUSTOMIZATION}" >&2; exit 2; }
command -v awk >/dev/null 2>&1 || { echo "❌ awk is required" >&2; exit 2; }

# ---- resolve a kubectl invocation that can reach the cluster ---------------
# Mirrors ./jarvis resolve_kubectl: prefer plain kubectl, else sudo k3s kubectl.
KCTL=""
if kubectl version >/dev/null 2>&1 && kubectl -n "${NS}" get ns >/dev/null 2>&1; then
  KCTL="kubectl"
elif sudo -n k3s kubectl version >/dev/null 2>&1; then
  KCTL="sudo k3s kubectl"
elif command -v k3s >/dev/null 2>&1; then
  KCTL="sudo k3s kubectl"
else
  KCTL="kubectl"
fi
k() { ${KCTL} -n "${NS}" "$@"; }

if ! k get ns >/dev/null 2>&1; then
  echo "❌ cannot reach cluster/namespace '${NS}' (kubectl invocation: ${KCTL})" >&2
  exit 3
fi

# ---- parse the `images:` block of the kustomization ------------------------
# Expected shape (kustomize images transformer):
#   images:
#     - name: jarvis/api-gateway
#       newName: localhost:5000/jarvis/api-gateway
#       newTag: movie7
#     - name: jarvis/...
#       ...
# We deliberately hand-roll this instead of shelling out to `kubectl
# kustomize` / `yq` — neither is guaranteed present, and this file's shape is
# stable/simple enough that a small state machine is more portable than
# adding a new dependency.
declare -a IMG_NAMES=() IMG_NEWNAMES=() IMG_NEWTAGS=()
cur_name="" cur_newname="" cur_newtag=""
in_images=false

flush_entry() {
  if [[ -n "${cur_name}" ]]; then
    IMG_NAMES+=("${cur_name}")
    IMG_NEWNAMES+=("${cur_newname}")
    IMG_NEWTAGS+=("${cur_newtag}")
  fi
  cur_name=""; cur_newname=""; cur_newtag=""
}

while IFS= read -r line; do
  if [[ "${line}" =~ ^images:[[:space:]]*$ ]]; then
    in_images=true
    continue
  fi
  if ${in_images}; then
    # An unindented, non-comment line starts a new top-level kustomization key
    # and ends the images: block.
    if [[ "${line}" =~ ^[A-Za-z] ]]; then
      flush_entry
      in_images=false
      continue
    fi
    if [[ "${line}" =~ ^[[:space:]]*-[[:space:]]*name:[[:space:]]*(.+)[[:space:]]*$ ]]; then
      flush_entry
      cur_name="${BASH_REMATCH[1]}"
    elif [[ "${line}" =~ ^[[:space:]]*newName:[[:space:]]*(.+)[[:space:]]*$ ]]; then
      cur_newname="${BASH_REMATCH[1]}"
    elif [[ "${line}" =~ ^[[:space:]]*newTag:[[:space:]]*(.+)[[:space:]]*$ ]]; then
      cur_newtag="${BASH_REMATCH[1]}"
    fi
  fi
done < "${KUSTOMIZATION}"
${in_images} && flush_entry

if [[ "${#IMG_NAMES[@]}" -eq 0 ]]; then
  echo "❌ no images: entries parsed from ${KUSTOMIZATION} — is the file format unchanged?" >&2
  exit 2
fi

# ---- compare each pinned image against the live Deployment -----------------
# Deployment name convention in this repo == the image repo suffix after
# 'jarvis/' (verified against infra/k8s/base/*/deployment.yaml — e.g.
# image 'jarvis/api-gateway' <-> Deployment 'api-gateway').
TOTAL=0
DRIFT=0
declare -a ROWS=()

for i in "${!IMG_NAMES[@]}"; do
  name="${IMG_NAMES[$i]}"
  newname="${IMG_NEWNAMES[$i]}"
  newtag="${IMG_NEWTAGS[$i]}"
  [[ -n "${name}" ]] || continue
  dep="${name#jarvis/}"
  expected="${newname}:${newtag}"
  TOTAL=$((TOTAL + 1))

  live="$(k get deploy "${dep}" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
  if [[ -z "${live}" ]]; then
    status="MISSING"
    DRIFT=$((DRIFT + 1))
  elif [[ "${live}" == "${expected}" ]]; then
    status="OK"
  else
    status="DRIFT"
    DRIFT=$((DRIFT + 1))
  fi
  ROWS+=("${dep}|${expected}|${live:-<no deployment/no image>}|${status}")
done

print_table() {
  printf '%-22s  %-46s  %-46s  %s\n' "DEPLOYMENT" "EXPECTED (kustomization.yaml)" "LIVE (cluster)" "STATUS"
  printf '%-22s  %-46s  %-46s  %s\n' "----------" "-----------------------------" "--------------" "------"
  local row dep expected live status
  for row in "${ROWS[@]}"; do
    IFS='|' read -r dep expected live status <<<"${row}"
    printf '%-22s  %-46s  %-46s  %s\n' "${dep}" "${expected}" "${live}" "${status}"
  done
}

echo "== Jarvis image drift check (namespace=${NS}) =="
echo "canonical source: ${KUSTOMIZATION#${ROOT_DIR}/}"
echo ""

if ${QUIET}; then
  local_any=false
  for row in "${ROWS[@]}"; do
    [[ "${row}" == *"|DRIFT" || "${row}" == *"|MISSING" ]] && { echo "${row}" | tr '|' '\t'; local_any=true; }
  done
  ${local_any} || echo "(no drift)"
else
  print_table
fi

echo ""
if [[ "${DRIFT}" -gt 0 ]]; then
  echo "❌ image drift: ${DRIFT}/${TOTAL} deployment(s) differ from ${KUSTOMIZATION}"
  echo "   fix: re-apply the canonical overlay for the affected service(s), or"
  echo "   update kustomization.yaml if the live tag is the one that should win."
  exit 1
fi
echo "✅ no image drift: ${TOTAL}/${TOTAL} deployment(s) match the pinned overlay tags"
exit 0
