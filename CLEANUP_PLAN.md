# Cleanup Plan

## Phase 1: Naming and Service Roles
- Rename/clarify `apps/orchestrator` vs `apps/llm-service` responsibilities.
- Option A: rename current `orchestrator` → `command-orchestrator`.
- Option B: move NLP routing into a dedicated `command-routing` module.

## Phase 2: Remove Build Artifacts
- Delete `target/` and `apps/*/target` from repo.
- Add to `.gitignore` to prevent reintroduction.

## Phase 3: Remove Stubs and Dead Code
- Remove or implement `LlmEnhancementService` in planner-service.
- Remove unused `SystemPromptConfig` or wire it into prompt builder.

## Phase 4: Consolidate Finance Analytics
- Move `analytics-service` expense summaries into `finance-service`.
- Keep analytics-service for cross-domain dashboards only.

## Phase 5: Deprecate Legacy Endpoints
- Deprecate `/api/v1/life/finance/expense(s)`.
- Update clients to `/transaction(s)` and remove aliases.
