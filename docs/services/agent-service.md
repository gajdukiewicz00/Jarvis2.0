# agent-service

## 1. Name

`agent-service`

## 2. Type

Backend role-based dev-agent swarm service ("House Party Protocol", EPIC 12).

## 3. Purpose

Runs multiple bounded, role-scoped agents against a goal: each agent runs with an
explicit permission ceiling, a sandboxed working directory, a full task lifecycle
(queue/pause/resume/cancel/retry), and produces an honest (including partial-failure)
report. It reuses the shared `jarvis-common` safety primitives (`ToolPermission`,
`ToolPermissionPolicy`, `SystemPanicState`) — the same gates enforced by the
gateway/orchestrator/voice paths — so a swarm task can never exceed the platform's
system-wide permission policy regardless of what a task requests.

## 4. Current Reality

The service is implemented and safety-gated, not a toy stub: every permissioned action
passes through `AgentActionGuard` (panic check → role ∩ user permission intersection →
system backstop for dangerous permissions), every decision is audited, and CODER/DOCS
sandbox writes are traversal-proof. The TESTER role can genuinely execute an
allowlisted test command (not just simulate one) when `RUN_SHELL` is granted — see
§8. LLM-backed planning for CODER/RESEARCH is not wired up yet; current role
workflows are deterministic (real sandbox files, real guarded process execution, real
diff/scan output) rather than LLM-generated. RESEARCH does not fetch the network even
with `NETWORK_ACCESS` granted (live fetching is unwired). MEDIA prepares a
media-service job spec but does not dispatch it to `media-service` (no gateway route /
NetworkPolicy allowlist wired between the two services yet).

## 5. Entry Points

- Spring Boot app: `org.jarvis.swarm.AgentServiceApplication`
- REST base path: `/api/v1/agents`

## 6. Configuration

Main configuration source:

- `apps/agent-service/src/main/resources/application.yml`

Important settings include:

- server port `8090` (`JARVIS_AGENT_PORT`)
- `swarm.enabled` (default `true`, env `SWARM_ENABLED`) — feature flag; when `false`
  all agent/swarm endpoints return 503
- `swarm.workspace.dir` (default `/tmp/jarvis-agents`, env `SWARM_SANDBOX_DIR`) — root
  for per-task sandboxes; the pod runs `readOnlyRootFilesystem=true` so only `/tmp`
  (an `emptyDir`) is writable
- `swarm.queue.capacity` / `swarm.queue.worker-pool-size` — bounded task queue and
  worker pool (HTTP threads never block on task execution)
- `swarm.task.default-timeout-seconds` / `swarm.task.default-max-retries`
- `swarm.swarm-run.wait-timeout-seconds` / `swarm.swarm-run.max-roles` — how long a
  swarm run waits for all child tasks before reporting a partial result, and the max
  roles per swarm run
- `jarvis.tools.granted-permissions` (env `JARVIS_TOOLS_GRANTED_PERMISSIONS`) — the
  system-wide permission backstop; the k8s deployment sets it to
  `PLANNER_ACCESS,CALENDAR_ACCESS,MEMORY_ACCESS,SMART_HOME_ACCESS,PC_CONTROL,NOTIFICATION_ACCESS,READ_FILES,NETWORK_ACCESS`
  — notably **excluding** `RUN_SHELL`, `WRITE_FILES`, and dangerous permissions, so
  even a task that requests and is role-granted one of those is still refused by the
  system backstop unless this list is deliberately widened
- `jarvis.agent.task-store` (`memory` default / `file` / `postgres`) — see §10

## 7. API / WebSocket Surface

All endpoints require authentication except `/actuator/health`; when
`swarm.enabled=false` they return 503.

- `GET /api/v1/agents/roles` — role catalog (permission ceilings + defaults)
- `POST /api/v1/agents/tasks` — create a task (`role`, `goal`, `permissions[]`,
  `dryRun`) → 202 Accepted
- `GET /api/v1/agents/tasks` — list the caller's tasks
- `GET /api/v1/agents/tasks/{id}` — task status + result
- `POST /api/v1/agents/tasks/{id}/cancel` — cancel (cooperative token + interrupt)
- `POST /api/v1/agents/tasks/{id}/pause` — pause
- `POST /api/v1/agents/tasks/{id}/resume` — resume
- `POST /api/v1/agents/swarm` — run a goal across multiple roles
  (`awaitCompletion=true` blocks and returns the combined report)
- `GET /api/v1/agents/swarm/{id}` — combined report for a swarm run (honest partial
  failure — a report can show some roles succeeded and others failed)

No WebSocket endpoint.

Task lifecycle: `CREATED → QUEUED → RUNNING → COMPLETED`, with `PAUSED`
(pause/resume), `CANCELLED` (cooperative cancellation), and `FAILED` (retry → QUEUED
within the role's retry budget). Illegal state transitions are rejected with 409.

## 8. Main Internal Components

- `RoleCatalogController`, `AgentTaskController`, `SwarmController`
- `AgentTaskService`, `SwarmCoordinator` — task queueing and multi-role swarm runs
- `RoleExecutorRegistry` + one `RoleExecutor` per role:
  - `CoderAgentExecutor` — plan + sandbox file stubs, unified-diff report; no shell
  - `TesterAgentExecutor` — proposes a test command; **actually runs** it (in the
    sandbox, 30s timeout, output secret-redacted via `OutputSanitizer`) only when
    `RUN_SHELL` is requested AND effectively granted AND the command passes
    `TestCommandAllowlist`. That allowlist permits a fixed set of side-effect-free
    binaries with any arguments (`echo`, `true`, `false`, `ls`, `cat`, `printf`,
    `pwd`), plus argument-validated real test-runner invocations: `mvn`/`mvnw` (only
    `[-q] [-pl <safe-relative-path>] test`), `gradle`/`gradlew test`,
    `npm test` / `npm run test`, and `pytest` (no args, or one safe relative path).
    Anything else is refused, never merely warned about. `TestOutputSummarizer`
    extracts a pass/fail line from Surefire/Gradle/Jest/pytest-style output.
  - `ResearchAgentExecutor` — offline unless `NETWORK_ACCESS` is granted; does not
    actually fetch the network yet even when granted
  - `DocsAgentExecutor` — writes docs into the sandbox; no shell
  - `SecurityAgentExecutor` — checklist + risky-pattern scan, output redacted
  - `MediaAgentExecutor` — prepares a media-service job spec (does not dispatch it)
    when `MEDIA_ACCESS` is granted
  - `FinanceAgentExecutor` — read-only analysis when `FINANCE_ACCESS` is granted;
    never creates transactions
- `AgentActionGuard` — the safety gate every permissioned action passes through
  (panic → role ∩ user permission → system backstop)
- `SandboxManager` — per-task sandbox directory creation and path-traversal guard
- `AgentAudit` (structured audit log, `AGENT_AUDIT …` lines, no secrets),
  `SwarmMetrics` (Micrometer: `swarm.tasks{state=...}` counter,
  `swarm.task.duration` timer, exposed at `/actuator/prometheus`)

## 9. Dependencies On Other Services

- `jarvis-common` — `ToolPermission`/`ToolPermissionPolicy`/`SystemPanicState` (shared
  safety gates) and the shared service-JWT/gateway-delegation security filter chain
- No mandatory downstream Jarvis service dependency for core behavior. MEDIA prepares
  a job spec for [`media-service`](media-service.md) but does not call it — cross-service
  dispatch needs a gateway route / NetworkPolicy allowlist that does not exist yet.
- No global panic *endpoint* here — panic is enforced by reading the shared
  `SystemPanicState`; engaging it is the gateway's `/api/v1/agent/panic`
  (see [api-gateway.md](api-gateway.md)).

## 10. Data / Storage

`jarvis.agent.task-store` selects the `AgentTaskStore` implementation:

| Value | Survives pod restart? | Notes |
|---|---|---|
| `memory` (default) | no | in-memory tasks; the default runtime baseline — no database provisioned unless explicitly configured |
| `file` | yes (same pod/volume) | JSON-per-task |
| `postgres` | yes | JPA + Flyway (`db/migration/V1__create_agent_task.sql`); requires `SWARM_DB_URL`/`SWARM_DB_USERNAME`/`SWARM_DB_PASSWORD` |

Postgres/JPA/Flyway auto-configuration is excluded by default and only re-imported
when `jarvis.agent.task-store=postgres` is set — the in-memory default never touches a
datasource.

Sandbox file artifacts live under `swarm.workspace.dir` (`/tmp/jarvis-agents` by
default, an `emptyDir` — not persisted across pod restarts).

## 11. Security Model

- `/actuator/health|info|prometheus` is public; every agent/swarm endpoint requires
  authentication (shared `BaseSecurityConfig` service-JWT + gateway-delegation filter
  chain — internal service, reached through the gateway or another service's token)
- Every permissioned action is checked against: (1) the shared `SystemPanicState`
  (refused if panic engaged), (2) the task's role ∩ user-requested permission set,
  and (3) for dangerous permissions (`RUN_SHELL`, `WRITE_FILES`, `NETWORK_ACCESS`) the
  system-wide `ToolPermissionPolicy` backstop — an over-broad per-task request can
  never exceed platform policy
- `dryRun` still runs the permission check (so a dry-run honestly reflects whether the
  action *would* be allowed) but never executes side effects
- TESTER's real-execution surface is a strict allowlist (see §8), run in the sandbox
  with a hard timeout, output secret-redacted
- Pod runs `runAsNonRoot`, `readOnlyRootFilesystem=true`, all capabilities dropped

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/agent-service -am test
```

Build & deploy (from the module README):

```bash
mvn -pl apps/agent-service -DskipTests -Dspotless.check.skip=true clean install \
  jib:build -Djib.to.image=localhost:5000/jarvis/agent-service:<tag>
sudo k3s kubectl create -f k8s/base/agent-service/deployment.yaml   # first deploy only
sudo k3s kubectl -n jarvis-prod set image deploy/agent-service agent-service=localhost:5000/jarvis/agent-service:<tag>
```

## 13. Implementation Status

Implemented and deployed (`k8s/base/agent-service/deployment.yaml`, namespace
`jarvis-prod`, port 8090, replicas 1). Safety-gated by design; several role
capabilities are intentionally partial (see §4 and §14).

## 14. Known Gaps / Caveats

- Tasks are in-memory by default (reset on restart) — `file`/`postgres` stores are
  opt-in.
- TESTER's allowlisted binaries are deliberately narrow; anything outside
  `TestCommandAllowlist` is refused, not just warned about.
- MEDIA prepares a job spec for `media-service` but does not dispatch it — no
  cross-service wiring yet.
- RESEARCH does not fetch the network even with `NETWORK_ACCESS` granted.
- LLM-backed planning for CODER/RESEARCH is a flagged follow-up; current workflows
  are deterministic, not model-generated.
- The desktop JavaFX agent (`org.jarvis.agent.*` in `apps/desktop-javafx`) is a
  separate, GUI-bound component untouched by this service.
