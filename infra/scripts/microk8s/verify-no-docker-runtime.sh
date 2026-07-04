#!/usr/bin/env bash
# =============================================================================
# Phase 2 acceptance gate: repository has no active Docker runtime path.
# =============================================================================
# Fails (exit 1) if the working tree contains, in active paths:
#   - any file named `Dockerfile` (case-sensitive — `Containerfile` is OCI
#     standard and is allowed because it's daemonless via podman/buildah)
#   - any file named `docker-compose.yml`, `compose.yml`, `compose.yaml`
#   - any directory named `docker/` (replaced by `infra/k8s/` + `apps/*-py`)
#   - any path under `docker/nginx/` (replaced by Nginx Ingress in k8s)
#
# Allowed (not failed by this script):
#   - anything under `docs/archive/` (intentional archived migration notes)
#   - anything under `.git/`, `target/`, `node_modules/`, `.gradle/` (build/VCS)
#   - `Containerfile` (OCI standard, daemonless)
#
# Advisory (does not fail, only prints a notice):
#   - active shell scripts that still invoke the Docker CLI. Pass --strict to
#     treat them as active runtime paths.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/verify-no-docker-runtime.sh [options]

Options:
  --strict    Also fail on active shell scripts that invoke the Docker CLI.
  --help, -h  Show this help.
EOF
}

STRICT=false
for arg in "$@"; do
  case "${arg}" in
    --strict) STRICT=true ;;
    --help|-h) usage; exit 0 ;;
    *) echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
  esac
done

cd "${ROOT_DIR}"

violations=()

# Common find prune to skip irrelevant trees.
find_active() {
  find . \
    \( -path './.git'         -o \
       -path './target'       -o \
       -path './*/target'     -o \
       -path './**/target'    -o \
       -path './node_modules' -o \
       -path './**/node_modules' -o \
       -path './.gradle'      -o \
       -path './**/.gradle'   -o \
       -path './**/.idea'     -o \
       -path './**/.mvn'      -o \
       -path './docs/archive' -o \
       -path './logs'         -o \
       -path './**/.pytest_cache' -o \
       -path './**/__pycache__' \
    \) -prune -o "$@" -print
}

echo "── Phase 2 verify-no-docker-runtime ──"
echo "Repo root: ${ROOT_DIR}"
echo ""

# --------------------------------------------------------------------------
# 1. Dockerfile (case-sensitive)
# --------------------------------------------------------------------------
echo "[1/5] active 'Dockerfile' files..."
mapfile -t bad_dockerfiles < <(find_active -type f -name 'Dockerfile' 2>/dev/null)
if (( ${#bad_dockerfiles[@]} > 0 )); then
  for f in "${bad_dockerfiles[@]}"; do
    violations+=("Dockerfile: ${f}")
  done
  printf '  ❌ %d found\n' "${#bad_dockerfiles[@]}"
else
  echo "  ✅ none"
fi

# --------------------------------------------------------------------------
# 2. docker-compose / compose
# --------------------------------------------------------------------------
echo "[2/5] active docker-compose files..."
mapfile -t bad_compose < <(find_active -type f \
  \( -name 'docker-compose.yml' -o -name 'docker-compose.yaml' \
     -o -name 'compose.yml' -o -name 'compose.yaml' \) 2>/dev/null)
if (( ${#bad_compose[@]} > 0 )); then
  for f in "${bad_compose[@]}"; do
    violations+=("compose: ${f}")
  done
  printf '  ❌ %d found\n' "${#bad_compose[@]}"
else
  echo "  ✅ none"
fi

# --------------------------------------------------------------------------
# 3. docker/ directory (top-level or nested, but excluding docs/archive/)
# --------------------------------------------------------------------------
echo "[3/5] active docker/ directories..."
mapfile -t bad_dirs < <(find_active -type d -name 'docker' 2>/dev/null)
if (( ${#bad_dirs[@]} > 0 )); then
  for d in "${bad_dirs[@]}"; do
    violations+=("docker/ directory: ${d}")
  done
  printf '  ❌ %d found\n' "${#bad_dirs[@]}"
else
  echo "  ✅ none"
fi

# --------------------------------------------------------------------------
# 4. docker nginx runtime path
# --------------------------------------------------------------------------
echo "[4/5] docker/nginx/ paths..."
mapfile -t bad_nginx < <(find_active -path '*/docker/nginx*' 2>/dev/null)
if (( ${#bad_nginx[@]} > 0 )); then
  for f in "${bad_nginx[@]}"; do
    violations+=("docker nginx: ${f}")
  done
  printf '  ❌ %d found\n' "${#bad_nginx[@]}"
else
  echo "  ✅ none"
fi

# --------------------------------------------------------------------------
# 5. .dockerignore — leftover, no Docker means no use for it
# --------------------------------------------------------------------------
echo "[5/5] active .dockerignore files..."
mapfile -t bad_ignore < <(find_active -type f -name '.dockerignore' 2>/dev/null)
if (( ${#bad_ignore[@]} > 0 )); then
  for f in "${bad_ignore[@]}"; do
    violations+=(".dockerignore: ${f}")
  done
  printf '  ❌ %d found\n' "${#bad_ignore[@]}"
else
  echo "  ✅ none"
fi

# --------------------------------------------------------------------------
# Advisory: Docker CLI calls in active shell scripts (does not fail unless --strict)
# --------------------------------------------------------------------------
echo ""
echo "── advisory ──"

script_invokes_docker_cli() {
  local file="$1"
  awk '
    /^[[:space:]]*#/ { next }
    /(^|[^[:alnum:]_./-])docker([[:space:]]+[[:alnum:]_-]+|[[:space:]]*$)/ { found=1 }
    /(^|[^[:alnum:]_./-])docker-compose([[:space:]]|$)/ { found=1 }
    /(^|[[:space:]])command[[:space:]]+-v[[:space:]]+docker([[:space:]]|$)/ { found=1 }
    /(^|[[:space:]])systemctl[[:space:]]+(start|stop|restart|enable|disable|status)[[:space:]]+docker([[:space:]]|$)/ { found=1 }
    END { exit found ? 0 : 1 }
  ' "${file}"
}

mapfile -t docker_cli_callers < <(
  while IFS= read -r f; do
    [[ "${f}" == "./infra/scripts/microk8s/verify-no-docker-runtime.sh" ]] && continue
    if script_invokes_docker_cli "${f}"; then
      printf '%s\n' "${f#./}"
    fi
  done < <(find_active -type f -name '*.sh' 2>/dev/null) | sort -u
)
if (( ${#docker_cli_callers[@]} > 0 )); then
  echo "  ⚠ shell scripts that still invoke the Docker CLI:"
  for f in "${docker_cli_callers[@]}"; do
    echo "    - ${f}"
  done
  if [[ "${STRICT}" == "true" ]]; then
    for f in "${docker_cli_callers[@]}"; do
      violations+=("docker CLI in script (--strict): ${f}")
    done
  fi
else
  echo "  ✅ no scripts invoke docker CLI"
fi

# --------------------------------------------------------------------------
# verdict
# --------------------------------------------------------------------------
echo ""
if (( ${#violations[@]} > 0 )); then
  echo "❌ Phase 2 acceptance failed: ${#violations[@]} active Docker runtime artifact(s) detected"
  for v in "${violations[@]}"; do
    echo "  - ${v}"
  done
  exit 1
fi

echo "✅ Phase 2: repository has no active Docker runtime path"
