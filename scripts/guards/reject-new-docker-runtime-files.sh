#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

MODE="all"
if [[ $# -gt 0 ]]; then
    case "$1" in
        --staged-only)
            MODE="staged"
            ;;
        --working-tree-only)
            MODE="working-tree"
            ;;
        --all)
            MODE="all"
            ;;
        --help|-h)
            cat <<'EOF'
Usage: ./scripts/guards/reject-new-docker-runtime-files.sh [--all|--staged-only|--working-tree-only]

Fails when newly added Docker runtime files are detected during Phase 0.
Existing tracked Docker assets are allowed for compatibility and migration evidence.
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
    echo "❌ git is required for reject-new-docker-runtime-files.sh" >&2
    exit 1
}

HAS_HEAD="false"
if git rev-parse --verify HEAD >/dev/null 2>&1; then
    HAS_HEAD="true"
fi

matches_blocked_runtime_path() {
    local path="$1"

    if [[ "${path}" =~ (^|/)(Dockerfile(\..*)?|docker-compose\.ya?ml|compose\.ya?ml)$ ]]; then
        return 0
    fi

    if [[ "${path}" =~ ^scripts(/.*)?/docker[^/]*\.sh$ ]]; then
        return 0
    fi

    if [[ "${path}" =~ ^scripts(/.*)?/compose[^/]*\.sh$ ]]; then
        return 0
    fi

    return 1
}

path_exists_in_head() {
    local path="$1"

    if [[ "${HAS_HEAD}" != "true" ]]; then
        return 1
    fi

    git cat-file -e "HEAD:${path}" 2>/dev/null
}

collect_candidates() {
    local source_mode="$1"

    case "${source_mode}" in
        staged)
            git diff --cached --name-only --diff-filter=A -z
            ;;
        working-tree)
            git ls-files --others --exclude-standard -z
            ;;
        *)
            return 1
            ;;
    esac
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

        matches_blocked_runtime_path "${path}" || continue
        path_exists_in_head "${path}" && continue

        blocked_paths+=("${path}")
    done < <(collect_candidates "${source_mode}")
}

case "${MODE}" in
    all)
        collect_and_check staged
        collect_and_check working-tree
        ;;
    staged)
        collect_and_check staged
        ;;
    working-tree)
        collect_and_check working-tree
        ;;
esac

if ((${#blocked_paths[@]} > 0)); then
    printf '❌ New Docker runtime files are blocked in Phase 0:\n' >&2
    printf '   Deprecated runtime path. Kept temporarily for compatibility and migration evidence.\n' >&2
    printf '   Production runtime target is native host + MicroK8s under jarvis-prod.\n' >&2
    printf '   Existing tracked Docker assets are allowed for now, but new ones are rejected.\n' >&2
    printf '\n' >&2
    printf 'Blocked files:\n' >&2
    printf '  - %s\n' "${blocked_paths[@]}" >&2
    exit 1
fi

echo "✅ reject-new-docker-runtime-files: OK"
