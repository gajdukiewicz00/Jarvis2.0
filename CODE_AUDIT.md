# Code Audit (AI Integration)

## High Priority
- `apps/orchestrator` vs `apps/llm-service` overlap: two “orchestrator” roles exist; LLM Orchestrator is now in `apps/llm-service`, while `apps/orchestrator` still handles NLP routing. This naming collision risks confusion and duplicated routing logic.
- `apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/TimeRecordController.java` uses a process-local `activeRecord` (not multi-user, not durable). This is unsafe for prod concurrency.

## Medium Priority
- Build artifacts in repo (e.g., `target/`, `apps/*/target`) should be removed and added to `.gitignore` to avoid accidental commits.
- `apps/llm-service/src/main/java/org/jarvis/llm/config/SystemPromptConfig.java` is unused (prompt builder overrides it). This can mislead future changes.
- `apps/planner-service/src/main/java/org/jarvis/planner/service/LlmEnhancementService.java` is a stub and not wired into tool flows.
- `apps/analytics-service` aggregates expenses independently; `apps/life-tracker` now also provides finance analytics. Consider consolidating to avoid divergence.

## Low Priority
- Legacy aliases in finance (`/api/v1/life/finance/expense(s)`) should be deprecated and removed once clients migrate.
- Multiple planning utilities (`DailyPlanGenerator`, `WeeklyPlanGenerator`, `ScheduleOptimizer`) are not connected to Tool API; evaluate whether to keep or migrate.
