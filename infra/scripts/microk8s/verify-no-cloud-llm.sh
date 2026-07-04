#!/usr/bin/env bash
# =============================================================================
# Phase 3 acceptance gate: no cloud LLM URLs and no automatic model downloads.
# =============================================================================
# Static check that complements the runtime LocalOnlyEnforcer in llm-service.
#
# Fails (exit 1) if active source / config files contain:
#   1. Cloud LLM provider hostnames (api.openai.com, api.anthropic.com, etc.)
#   2. Hugging Face Hub download patterns (hf_hub_download, snapshot_download,
#      AutoModel.from_pretrained without local_files_only, etc.)
#   3. Generic remote model downloads (wget/curl to *.huggingface.co/...)
#
# Skipped paths: .git, target/, node_modules/, .venv/, .gradle/, docs/, and
# this verify script itself.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${ROOT_DIR}"

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/microk8s/verify-no-cloud-llm.sh [options]

Options:
  --strict   Also fail on `from_pretrained(` calls without local_files_only=True
  --help, -h Show this help.

Phase 3 acceptance:
  - No cloud LLM provider URLs in active source/config (api.openai.com, etc.)
  - No automatic model downloads (hf_hub_download, snapshot_download, ...)
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

CLOUD_HOSTS=(
  api.openai.com
  api.anthropic.com
  generativelanguage.googleapis.com
  api.cohere.ai
  api.mistral.ai
  api.perplexity.ai
  api.deepseek.com
  api.together.xyz
  api.fireworks.ai
  api.replicate.com
  api.groq.com
  api.x.ai
)

DOWNLOAD_PATTERNS=(
  'hf_hub_download'
  'snapshot_download'
  'huggingface_hub\.'
  'from_pretrained\([^)]*[^)]*\)'   # rough; refined below
  'urllib\.request\.urlretrieve'
  'urllib\.urlretrieve'
)

# Common excludes via --include / --exclude flags.
GREP_OPTS=(
  -rln
  --binary-files=without-match
  --exclude-dir=.git
  --exclude-dir=target
  --exclude-dir=build
  --exclude-dir=node_modules
  --exclude-dir=.venv
  --exclude-dir=venv
  --exclude-dir=.gradle
  --exclude-dir=__pycache__
  --exclude-dir=.pytest_cache
  --exclude-dir=docs
  --exclude='verify-no-cloud-llm.sh'
)

violations=()

scan_cloud_hosts() {
  echo "[1/3] cloud LLM hostnames..."
  local pattern
  pattern="$(printf '%s|' "${CLOUD_HOSTS[@]}")"
  pattern="${pattern%|}"

  mapfile -t hits < <(grep -E "${GREP_OPTS[@]}" "${pattern}" . 2>/dev/null || true)
  if (( ${#hits[@]} > 0 )); then
    for f in "${hits[@]}"; do
      violations+=("cloud-host: ${f}")
    done
    printf '  ❌ %d file(s) reference cloud LLM hostnames\n' "${#hits[@]}"
  else
    echo "  ✅ none"
  fi
}

scan_download_patterns() {
  echo "[2/3] automatic model download patterns..."
  local hit_total=0
  for p in 'hf_hub_download' 'snapshot_download' 'huggingface_hub\.'; do
    mapfile -t hits < <(grep -E "${GREP_OPTS[@]}" "${p}" . 2>/dev/null || true)
    if (( ${#hits[@]} > 0 )); then
      for f in "${hits[@]}"; do
        violations+=("auto-download(${p}): ${f}")
        hit_total=$((hit_total + 1))
      done
    fi
  done
  if (( hit_total > 0 )); then
    printf '  ❌ %d match(es)\n' "${hit_total}"
  else
    echo "  ✅ none"
  fi
}

scan_remote_curl() {
  echo "[3/3] remote curl/wget to model hubs..."
  mapfile -t hits < <(grep -EI "${GREP_OPTS[@]}" \
    '(curl|wget)[^|;&]+(huggingface\.co|api\.openai\.com|api\.anthropic\.com)' \
    . 2>/dev/null || true)
  if (( ${#hits[@]} > 0 )); then
    for f in "${hits[@]}"; do
      violations+=("remote-fetch: ${f}")
    done
    printf '  ❌ %d file(s) curl/wget remote model resources\n' "${#hits[@]}"
  else
    echo "  ✅ none"
  fi
}

scan_strict_from_pretrained() {
  if [[ "${STRICT}" != "true" ]]; then
    return 0
  fi
  echo "[strict] from_pretrained without local_files_only..."
  # Only check Python files
  mapfile -t hits < <(grep -REn --include='*.py' \
    --exclude-dir=.git --exclude-dir=__pycache__ --exclude-dir=.venv --exclude-dir=venv \
    'from_pretrained\(' . 2>/dev/null \
    | grep -v 'local_files_only[[:space:]]*=[[:space:]]*True' || true)
  if (( ${#hits[@]} > 0 )); then
    for line in "${hits[@]}"; do
      violations+=("from_pretrained without local_files_only=True: ${line}")
    done
    printf '  ❌ %d match(es)\n' "${#hits[@]}"
  else
    echo "  ✅ none"
  fi
}

echo "── Phase 3 verify-no-cloud-llm ──"
echo ""
scan_cloud_hosts
scan_download_patterns
scan_remote_curl
scan_strict_from_pretrained

echo ""
if (( ${#violations[@]} > 0 )); then
  echo "❌ Phase 3 acceptance failed: ${#violations[@]} violation(s)"
  for v in "${violations[@]}"; do
    echo "  - ${v}"
  done
  exit 1
fi
echo "✅ Phase 3: no cloud LLM URLs, no automatic model downloads"
