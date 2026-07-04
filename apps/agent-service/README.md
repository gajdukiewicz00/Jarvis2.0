# Agent Service (EPIC 12 — Role-Based Agent Swarm / "House Party Protocol")

Runs multiple bounded agents with explicit roles, per-task permissions, sandbox
isolation, a full lifecycle (queue / pause / resume / cancel / retry), and honest
combined reports. Increment D (D0–D9).

Port `8090` · package `org.jarvis.swarm` · Java 21 / Spring Boot 3.3 · **no database**
(in-memory tasks) · feature flag `swarm.enabled`.

## Safety posture (non-negotiable)

Every permissioned action passes through `AgentActionGuard` in this order:

1. **panic** — refused if the shared `SystemPanicState` is engaged;
2. **role ∩ user** — the permission must be in the task's granted set
   (`granted = role-ceiling ∩ user-requested`);
3. **system backstop** — dangerous permissions (`RUN_SHELL`, `WRITE_FILES`,
   `NETWORK_ACCESS`) additionally require the shared `ToolPermissionPolicy` grant, so an
   over-broad request can never exceed the platform policy.

Every decision is audited (`AgentAudit`, structured, no secrets). dryRun does not execute
side effects but the permission check still runs (so a dry-run honestly reflects whether
the action *would* be allowed). Reuses jarvis-common `ToolPermission` / `ToolPermissionPolicy`
/ `SystemPanicState` — the SAME gates as the gateway/orchestrator/voice paths.

- No autonomous destructive behavior; no shell/file/network without the gates above.
- Sandbox writes are confined to a per-task directory (`SandboxManager`, traversal-proof,
  explicit safe cleanup). Writing to the real repo would need `WRITE_FILES` (never done).
- TESTER runs only an allowlisted binary, in the sandbox, with a timeout; output is
  secret-redacted (`OutputSanitizer`).
- FINANCE/MEDIA require their access grant and never perform state-changing actions.

## Roles (permission ceilings — distinct per role)

| Role | Allowed permissions (ceiling) | Sandbox | Notes |
|---|---|---|---|
| CODER | READ_FILES, WRITE_FILES | yes | plan + sandbox stubs; no shell |
| TESTER | READ_FILES, RUN_SHELL | yes | propose; run only if RUN_SHELL granted |
| RESEARCH | READ_FILES, NETWORK_ACCESS | yes | offline unless NETWORK_ACCESS granted |
| DOCS | READ_FILES, WRITE_FILES | yes | sandbox docs; no shell |
| SECURITY | READ_FILES | yes | checklist + risky-pattern scan, redacted |
| MEDIA | READ_FILES, MEDIA_ACCESS | no | prepares media job spec if MEDIA_ACCESS |
| FINANCE | READ_FILES, FINANCE_ACCESS | no | read-only analysis; never creates txns |

FINANCE/MEDIA get no RUN_SHELL/WRITE_FILES/NETWORK_ACCESS; CODER gets no RUN_SHELL.

## Task lifecycle

`CREATED → QUEUED → RUNNING → COMPLETED` with `PAUSED` (pause/resume), `CANCELLED`
(cooperative token + interrupt), and `FAILED` (retry → QUEUED within the role budget).
Illegal transitions are rejected (409). Tasks run on a bounded worker pool; HTTP threads
never block.

## API (all require auth except `/actuator/health`; disabled → 503 when `swarm.enabled=false`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/agents/roles` | role catalog |
| POST | `/api/v1/agents/tasks` | create a task (role, goal, permissions[], dryRun) → 202 |
| GET | `/api/v1/agents/tasks` | list caller's tasks |
| GET | `/api/v1/agents/tasks/{id}` | task status + result |
| POST | `/api/v1/agents/tasks/{id}/cancel\|pause\|resume` | lifecycle control |
| POST | `/api/v1/agents/swarm` | run a goal across roles (`awaitCompletion` → combined report) |
| GET | `/api/v1/agents/swarm/{id}` | combined report (honest partial failure) |

## Observability

Structured audit lines (`AGENT_AUDIT …`) with task id + correlation id; Micrometer
counters `swarm.tasks{state=...}` and timer `swarm.task.duration` at `/actuator/prometheus`.

## Build & deploy

```bash
mvn -pl apps/agent-service -DskipTests -Dspotless.check.skip=true clean install \
  jib:build -Djib.to.image=localhost:5000/jarvis/agent-service:<tag>
# new service: create (not apply-the-overlay)
sudo k3s kubectl create -f k8s/base/agent-service/deployment.yaml
# updates: sudo k3s kubectl -n jarvis-prod set image deploy/agent-service agent-service=localhost:5000/jarvis/agent-service:<tag>
```

## Known limitations / intentionally not implemented

- Tasks are in-memory (reset on restart) — no DB by design (isolation, no NetworkPolicy).
- No global panic *endpoint* here — panic is enforced by reading the shared
  `SystemPanicState`; engaging it is the gateway's `/api/v1/agent/panic` (api-gateway).
- TESTER's run allowlist is intentionally tiny/safe (echo/true/false/ls/cat/printf/pwd);
  real build tools (mvn/gradle) are proposed, not executed, in the locked container.
- MEDIA prepares a media-service job spec but does not dispatch it (cross-service call
  needs a gateway route / NetworkPolicy allowlist — out of scope).
- RESEARCH does not fetch the internet even with NETWORK_ACCESS (live fetching unwired).
- LLM-backed planning for CODER/RESEARCH is a flagged follow-up; current workflows are
  deterministic but real (plans, sandbox files, scans, guarded command execution).
- Desktop JavaFX agent (`org.jarvis.agent.*`) is left untouched (GUI-bound).
