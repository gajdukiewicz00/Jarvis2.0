# Summary

Status: ACCEPTED

## Implemented
- Launcher UX: Fix TLS / Reset Jarvis / Disk Cleanup / Enable GPU / Run Acceptance buttons with pkexec-driven scripts and UI status refresh.
- Health checks now load the local Java truststore when present to avoid PKIX errors after TLS fix.
- Backend start is single-flight (ProcessRunner start guard + lock handling).
- UI config persists LLM/Memory/GPU toggles without wrapper overrides; default ingress URL uses last-run summary or HTTPS.
- Disk cleanup logs to `~/.jarvis/logs/disk-cleanup.log` and honors pkexec user home.
- Docs updated for UI-first path and scrubbed of secret-like defaults in JWT/Secrets policy.
- LLM Orchestrator endpoint with strict JSON-only tool planning.
- Tool registry + domain tool schemas (`todo-tools.json`, `calendar-tools.json`, `finance-tools.json`, `memory-tools.json`).
- Todo service extended (tags, source, audit metadata, dueDate mapping).
- Calendar service extended (recurrence, conflicts, free-slot search).
- Finance service extended (transactions, budgets, goals, recurring + analytics endpoints).
- Tool API identity enforcement via `X-User-Id` filters and strict DTO validation (unknown fields rejected); docs scrubbed of auth header examples.
- Tool API endpoints with idempotency for mutations + scheduled TTL cleanup.
- Time tracking now persists active state and enforces one active record per user.
- User ID hardening migrations (backfill + NOT NULL + indexes).
- Automation engine docs + sample rules.
- AI verification gate (`scripts/verify-ai.sh`) and acceptance run (`scripts/acceptance-ai.sh`).
- Tool idempotency storage now updates existing entries on retries to avoid unique key collisions.
- API Gateway permits `/api/v1/tools/**` without JWT only for internal (no `X-Forwarded-For`) calls; external requests require auth.
- Planner tool endpoints are now explicitly permitted by service security for internal tool execution.
- Acceptance now enforces internal-only base URLs and can optionally verify ingress blocks tool routes.
- Added `docs/STATUS.md` and `docs/ops/flyway-repair-lifetracker-job.yaml` to document ACCEPTED status and Flyway repair.
- Life-tracker tests updated for required `userId` + `occurredAt`.
- Orchestrator now has production security config (restores security filter chain).
- User-profile migration adds `user_habits.updated_at` to align schema with entity mapping.
- TLS install script respects `sudo` user home for CA trust.
- Added Docker root reset helper for overlay2 storage errors and updated build scripts to surface the fix.
- DoD updated to include backend+DB readiness and UI smoke check.
- Launcher now auto-bootstraps (deps, secrets, TLS, /etc/hosts) and auto-starts backend + desktop UI.
- Release/install now include core launch scripts and desktop client jar for one-click startup.
- Launcher wrapper now auto-creates a desktop entry on first run to enable one-click launch without console steps.
- Launcher bootstrap now ensures Docker is usable (service + group) and stages a k3s kubeconfig for non-root kubectl.

## Ready to Use
- `/api/v1/llm/orchestrate` for planning tool calls.
- `/api/v1/tools/todo/*`, `/api/v1/tools/calendar/*`, `/api/v1/tools/finance/*`, `/api/v1/tools/memory/*` for deterministic execution (requires `X-User-Id`).
- Finance analytics endpoints for monthly summary and budget status.

## Remaining
- Wire a deterministic Tool Executor (caller that executes tool calls end-to-end).
- Consolidate orchestrator naming (command vs LLM orchestrator).
- Add integration tests for tool flows and idempotency.

## AI-Native Status
**Partially AI-native.**
- YES: LLM Orchestrator + Tool API + deterministic domain services are in place.
- NOT YET: End-to-end tool execution orchestration and UI/UX confirmation loop are not fully wired.
