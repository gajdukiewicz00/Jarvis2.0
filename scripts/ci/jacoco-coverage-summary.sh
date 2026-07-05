#!/usr/bin/env bash
# =============================================================================
# JaCoCo coverage summary aggregator.
# =============================================================================
# Aggregates every reactor module's `target/site/jacoco/jacoco.csv` (written
# by the jacoco-maven-plugin `report` goal, which the root pom.xml binds to
# the `test` phase for all modules) into one Markdown table:
#
#   | Module | Line coverage | Instruction coverage |
#
# Intended use in CI:
#   mvn -fae test ...
#   ./scripts/ci/jacoco-coverage-summary.sh >> "$GITHUB_STEP_SUMMARY"
#
# This script is a *reporting* aid, not a coverage gate: it always exits 0
# (even when zero jacoco.csv files are found, e.g. because every module
# failed before the test phase completed) so it never masks a real test
# failure raised by the `mvn test` step that ran before it.
#
# Usage: jacoco-coverage-summary.sh [root-dir]
#   root-dir  Directory to search for **/target/site/jacoco/jacoco.csv,
#             defaults to the repo root (two levels up from this script).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${1:-$(cd "${SCRIPT_DIR}/../.." && pwd)}"

if [[ ! -d "${ROOT_DIR}" ]]; then
  echo "jacoco-coverage-summary: root dir not found: ${ROOT_DIR}" >&2
  exit 1
fi

cd "${ROOT_DIR}"

mapfile -t CSV_FILES < <(find . -type f -path "*/target/site/jacoco/jacoco.csv" | sort)

if [[ "${#CSV_FILES[@]}" -eq 0 ]]; then
  echo "### JaCoCo coverage summary"
  echo ""
  echo "_No \`jacoco.csv\` reports were found under any module's \`target/site/jacoco/\`._"
  echo "_(tests may have failed before the report phase, or none have run yet)_"
  exit 0
fi

echo "### JaCoCo coverage summary"
echo ""
echo "| Module | Line coverage | Instruction coverage |"
echo "|---|---|---|"

# jacoco.csv columns:
# GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,
# BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,
# COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
awk -F',' '
  FNR == 1 {
    module = FILENAME
    sub(/^\.\//, "", module)
    sub(/\/target\/site\/jacoco\/jacoco\.csv$/, "", module)
    if (!(module in seen)) {
      seen[module] = 1
      order[++n] = module
    }
    next
  }
  {
    instr_missed[module]  += $4
    instr_covered[module] += $5
    line_missed[module]   += $8
    line_covered[module]  += $9

    total_instr_missed  += $4
    total_instr_covered += $5
    total_line_missed   += $8
    total_line_covered  += $9
  }
  END {
    # simple insertion sort over the module names for stable, readable output
    for (i = 2; i <= n; i++) {
      key = order[i]
      j = i - 1
      while (j >= 1 && order[j] > key) {
        order[j + 1] = order[j]
        j--
      }
      order[j + 1] = key
    }

    for (i = 1; i <= n; i++) {
      m = order[i]
      lt = line_missed[m] + line_covered[m]
      it = instr_missed[m] + instr_covered[m]
      lpct = (lt > 0) ? (line_covered[m] / lt * 100) : 0
      ipct = (it > 0) ? (instr_covered[m] / it * 100) : 0
      printf "| %s | %.1f%% (%d/%d) | %.1f%% (%d/%d) |\n", m, lpct, line_covered[m], lt, ipct, instr_covered[m], it
    }

    total_line = total_line_missed + total_line_covered
    total_instr = total_instr_missed + total_instr_covered
    total_lpct = (total_line > 0) ? (total_line_covered / total_line * 100) : 0
    total_ipct = (total_instr > 0) ? (total_instr_covered / total_instr * 100) : 0
    printf "| **TOTAL** | **%.1f%% (%d/%d)** | **%.1f%% (%d/%d)** |\n", total_lpct, total_line_covered, total_line, total_ipct, total_instr_covered, total_instr
  }
' "${CSV_FILES[@]}"
