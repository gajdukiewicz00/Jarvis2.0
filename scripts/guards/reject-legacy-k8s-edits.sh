#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

MODE="staged"
if [[ $# -gt 0 ]]; then
    case "$1" in
        --check)
            MODE="working-tree"
            ;;
        --staged)
            MODE="staged"
            ;;
        --all)
            MODE="all"
            ;;
        --help|-h)
            cat <<'EOF'
Usage: ./scripts/guards/reject-legacy-k8s-edits.sh [--staged|--check|--all]

Fails when the frozen legacy k8s/base/** tree is modified. The canonical
Kubernetes source of truth is infra/k8s/ (see infra/k8s/README.md). k8s/base/**
is quarantined: no further edits are accepted there.

k8s/overlays/prod-release/** is allowlisted because it is still written by
scripts/product/jarvis-promote-images.sh in some release flows.

Modes:
  --staged   Inspect the staged diff (git diff --cached). Default; use this
             form from a pre-commit hook.
  --check    Inspect the full working tree (staged + unstaged + untracked)
             against HEAD. Use this for a manual, ad-hoc check.
  --all      Run both the staged and working-tree checks.
EOF
            exit 0
            ;;
        *)
            echo "❌ Unknown argument: $1" >&2
            exit 1
            ;;
    esac
fi

command -v git >/dev/null 2>&1 || {
    echo "❌ git is required for reject-legacy-k8s-edits.sh" >&2
    exit 1
}

HAS_HEAD="false"
if git rev-parse --verify HEAD >/dev/null 2>&1; then
    HAS_HEAD="true"
fi

is_allowlisted_path() {
    local path="$1"

    # infra/k8s/overlays/prod-release is a different tree entirely, but keep
    # this check explicit in case the blocked pattern below is ever widened.
    [[ "${path}" =~ ^k8s/overlays/prod-release(/.*)?$ ]]
}

matches_blocked_legacy_k8s_path() {
    local path="$1"

    is_allowlisted_path "${path}" && return 1

    [[ "${path}" =~ ^k8s/base(/.*)?$ ]]
}

collect_staged_candidates() {
    git diff --cached --name-only -z
}

collect_working_tree_candidates() {
    # Staged + unstaged modifications relative to HEAD.
    if [[ "${HAS_HEAD}" == "true" ]]; then
        git diff HEAD --name-only -z
    else
        git diff --cached --name-only -z
    fi
    # Untracked new files (e.g. a new file dropped straight into k8s/base/).
    git ls-files --others --exclude-standard -z
}

declare -A seen_paths=()
declare -a blocked_paths=()

collect_and_check() {
    local source_mode="$1"
    local path

    while IFS= read -r -d '' path; do
        [[ -n "${path}" ]] || continue

        if [[ -n "${seen_paths[${path}]+x}" ]]; then
            continue
        fi
        seen_paths["${path}"]=1

        matches_blocked_legacy_k8s_path "${path}" || continue

        blocked_paths+=("${path}")
    done < <(
        case "${source_mode}" in
            staged)
                collect_staged_candidates
                ;;
            working-tree)
                collect_working_tree_candidates
                ;;
        esac
    )
}

case "${MODE}" in
    staged)
        collect_and_check staged
        ;;
    working-tree)
        collect_and_check working-tree
        ;;
    all)
        collect_and_check staged
        collect_and_check working-tree
        ;;
esac

if ((${#blocked_paths[@]} > 0)); then
    printf '❌ Edits to the frozen legacy k8s/ tree are blocked:\n' >&2
    printf '   k8s/base/** is quarantined. Canonical source of truth is infra/k8s/.\n' >&2
    printf '   See infra/k8s/README.md. k8s/overlays/prod-release/** remains allowed\n' >&2
    printf '   (still written by scripts/product/jarvis-promote-images.sh).\n' >&2
    printf '\n' >&2
    printf 'Blocked files:\n' >&2
    printf '  - %s\n' "${blocked_paths[@]}" >&2
    exit 1
fi

echo "✅ reject-legacy-k8s-edits: OK"
