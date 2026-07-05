#!/usr/bin/env bash
# Unit test for scripts/ci/jacoco-coverage-summary.sh.
#
# Builds a synthetic reactor tree with a handful of jacoco.csv fixtures under
# a scratch directory, runs the aggregator against it, and asserts the
# rendered Markdown table contains the expected per-module and total
# coverage figures. Also asserts the "no reports found" path exits 0 with an
# explanatory note (it must never itself fail the build).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${SCRIPT_DIR}/jacoco-coverage-summary.sh"

TMP_ROOT="$(mktemp -d)"
cleanup() {
  rm -rf "${TMP_ROOT}"
}
trap cleanup EXIT

write_csv() {
  local path="$1"
  shift
  mkdir -p "$(dirname "${path}")"
  {
    echo "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
    printf '%s\n' "$@"
  } > "${path}"
}

echo "[jacoco-summary-test] positive: aggregates multiple modules correctly"

# apps/api-gateway: instruction 140/150 covered, line 70/75 covered
write_csv "${TMP_ROOT}/apps/api-gateway/target/site/jacoco/jacoco.csv" \
  "api-gateway,org.jarvis.gateway,FooController,10,90,2,8,5,45,1,9,1,9" \
  "api-gateway,org.jarvis.gateway,BarService,0,50,0,10,0,25,0,5,0,5"

# apps/zzz-service: 0% covered
write_csv "${TMP_ROOT}/apps/zzz-service/target/site/jacoco/jacoco.csv" \
  "zzz-service,org.jarvis.zzz,Thing,100,0,10,0,50,0,10,0,10,0"

# libs/command-schema: 100% covered
write_csv "${TMP_ROOT}/libs/command-schema/target/site/jacoco/jacoco.csv" \
  "command-schema,org.jarvis.cmd,Cmd,0,20,0,4,0,10,0,3,0,3"

OUT="$("${TARGET}" "${TMP_ROOT}")"

assert_contains() {
  local needle="$1"
  if ! grep -qF -- "${needle}" <<< "${OUT}"; then
    echo "[jacoco-summary-test] ERROR: expected output to contain: ${needle}" >&2
    echo "--- full output ---" >&2
    echo "${OUT}" >&2
    exit 1
  fi
}

assert_contains "### JaCoCo coverage summary"
assert_contains "| apps/api-gateway | 93.3% (70/75) | 93.3% (140/150) |"
assert_contains "| apps/zzz-service | 0.0% (0/50) | 0.0% (0/100) |"
assert_contains "| libs/command-schema | 100.0% (10/10) | 100.0% (20/20) |"
assert_contains "| **TOTAL** | **59.3% (80/135)** | **59.3% (160/270)** |"

echo "[jacoco-summary-test] negative: no jacoco.csv anywhere -> exits 0 with a note"
EMPTY_DIR="${TMP_ROOT}/empty-root"
mkdir -p "${EMPTY_DIR}"
# `set -e` means this line itself aborts the test (non-zero) if the target
# script ever regresses to a non-zero exit on the "nothing found" path.
NEGATIVE_OUT="$("${TARGET}" "${EMPTY_DIR}")"

if ! grep -qF "No \`jacoco.csv\` reports were found" <<< "${NEGATIVE_OUT}"; then
  echo "[jacoco-summary-test] ERROR: expected the no-reports note in output" >&2
  echo "${NEGATIVE_OUT}" >&2
  exit 1
fi

echo "[jacoco-summary-test] OK"
