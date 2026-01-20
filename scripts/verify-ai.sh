#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

fail() {
  echo "[verify-ai] ERROR: $1" >&2
  exit 1
}

check_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "Missing required file: $path"
}

check_file "$ROOT_DIR/architecture.md"
check_file "$ROOT_DIR/llm-orchestrator.md"
check_file "$ROOT_DIR/todo-tools.json"
check_file "$ROOT_DIR/calendar-tools.json"
check_file "$ROOT_DIR/finance-tools.json"
check_file "$ROOT_DIR/memory-tools.json"
check_file "$ROOT_DIR/apps/llm-service/src/main/resources/prompts/llm-orchestrator-system.txt"
check_file "$ROOT_DIR/apps/llm-service/src/main/resources/tools/registry.json"

if ! rg -q "\{\{TOOLS_JSON\}\}" "$ROOT_DIR/apps/llm-service/src/main/resources/prompts/llm-orchestrator-system.txt"; then
  fail "System prompt missing {{TOOLS_JSON}} placeholder"
fi

for tool in create_todo update_todo list_todos complete_todo create_event move_event list_events find_free_slot list_transactions summarize_month analyze_spending budget_status search_memory; do
  if ! rg -q "\"name\"\s*:\s*\"${tool}\"" "$ROOT_DIR/apps/llm-service/src/main/resources/tools/registry.json"; then
    fail "Tool registry missing tool: $tool"
  fi
done

check_file "$ROOT_DIR/apps/planner-service/src/main/java/org/jarvis/planner/controller/ToolTodoController.java"
check_file "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolCalendarController.java"
check_file "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolFinanceController.java"
check_file "$ROOT_DIR/apps/memory-service/src/main/java/org/jarvis/memory/controller/ToolMemoryController.java"

check_file "$ROOT_DIR/apps/planner-service/src/main/java/org/jarvis/planner/tooling/ToolUserIdFilter.java"
check_file "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/tooling/ToolUserIdFilter.java"
check_file "$ROOT_DIR/apps/memory-service/src/main/java/org/jarvis/memory/tooling/ToolUserIdFilter.java"

if ! rg -q "X-Idempotency-Key" "$ROOT_DIR/apps/planner-service/src/main/java/org/jarvis/planner/controller/ToolTodoController.java"; then
  fail "ToolTodoController must require X-Idempotency-Key for mutations"
fi

if ! rg -q "X-Idempotency-Key" "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolCalendarController.java"; then
  fail "ToolCalendarController must require X-Idempotency-Key for mutations"
fi

for controller in \
  "$ROOT_DIR/apps/planner-service/src/main/java/org/jarvis/planner/controller/ToolTodoController.java" \
  "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolCalendarController.java" \
  "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolFinanceController.java" \
  "$ROOT_DIR/apps/memory-service/src/main/java/org/jarvis/memory/controller/ToolMemoryController.java"; do
  if ! rg -q "@RequestAttribute\\(\"toolUserId\"\\)" "$controller"; then
    fail "Tool controller missing toolUserId attribute: $controller"
  fi
done

if rg -q "(JpaRepository|JdbcTemplate|RestTemplate|WebClient)" "$ROOT_DIR/apps/llm-service/src/main/java/org/jarvis/llm/orchestrator"; then
  fail "LLM orchestrator should not use DB/HTTP clients directly"
fi

if rg -q "MemoryClient" "$ROOT_DIR/apps/llm-service/src/main/java/org/jarvis/llm/orchestrator"; then
  fail "LLM orchestrator must not call MemoryClient directly"
fi

check_file "$ROOT_DIR/apps/planner-service/src/main/java/org/jarvis/planner/tooling/ToolRequestCleanup.java"
check_file "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/tooling/ToolRequestCleanup.java"

if ! rg -q "@EnableScheduling" "$ROOT_DIR/apps/planner-service/src/main/java/org/jarvis/planner/PlannerServiceApplication.java"; then
  fail "PlannerServiceApplication must enable scheduling for tooling cleanup"
fi

if ! rg -q "@EnableScheduling" "$ROOT_DIR/apps/life-tracker/src/main/java/org/jarvis/lifetracker/LifeTrackerApplication.java"; then
  fail "LifeTrackerApplication must enable scheduling for tooling cleanup"
fi

if git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  tracked_files=()
  while IFS= read -r -d '' path; do
    [[ "$path" == docs/legacy/* ]] && continue
    [[ "$path" == scripts/legacy/* ]] && continue
    [[ "$path" == scripts/verify-ai.sh ]] && continue
    [[ -f "$ROOT_DIR/$path" ]] || continue
    if [[ "$path" == .env* ]]; then
      fail "Tracked .env files are not allowed: $path"
    fi
    tracked_files+=("$ROOT_DIR/$path")
  done < <(git -C "$ROOT_DIR" ls-files -z)

  if ((${#tracked_files[@]} > 0)); then
    bash_matches=$(rg -n "/usr/bin/bash" "${tracked_files[@]}" || true)
    if [[ -n "$bash_matches" ]]; then
      echo "$bash_matches" >&2
      fail "Hardcoded /usr/bin/bash found in tracked files"
    fi

    secret_matches=$(rg -n "BEGIN (RSA|EC|PRIVATE) KEY|PRIVATE KEY|password=|token=|Authorization: Bearer|aws_access_key|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}" "${tracked_files[@]}" || true)
    if [[ -n "$secret_matches" ]]; then
      echo "$secret_matches" >&2
      fail "Potential secrets detected in tracked files"
    fi
  fi
fi

echo "[verify-ai] OK"
