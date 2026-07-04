# Jarvis 2.0 — State Durability & Accepted-Risk Register (2026-07-04)

This document catalogs every known **in-memory, non-persisted application state**
in the codebase: what it holds, what is lost when the owning pod/process restarts,
who notices, and what the future fix looks like. Grounded in a 2026-07-04 code
audit (read-only, no cluster access). All quotes below are verbatim from the
javadoc/comments in the named source files.

**Framing**: for the v1.0 cycle, all four items below are **accepted risk** — each
is a deliberate MVP scope cut (documented in the source itself), not an oversight.
This register exists so the trade-off stays visible and is revisited before v1.1,
rather than being silently forgotten. Item 1 (`SystemPanicState`) is the
highest-priority item because it is a **safety** control, not a convenience
feature — see its section below.

Related: [`docs/audit/2026-07-04-status-reconciliation.md`](../audit/2026-07-04-status-reconciliation.md)
section (e) covers a different kind of durability problem (k8s image-tag drift on
re-apply); this document covers in-process *data* loss on restart.

## Summary table

| # | Component | File | What's lost on restart | Priority |
|---|---|---|---|---|
| 1 | `SystemPanicState` | `apps/jarvis-common/.../safety/SystemPanicState.java` | Global panic/kill-switch engagement | **Highest — safety** |
| 2 | `EnergyStateService` | `apps/planner-service/.../service/EnergyStateService.java` | Per-user energy level | Low |
| 3 | `InMemoryAgentTaskStore` | `apps/agent-service/.../task/InMemoryAgentTaskStore.java` | All agent-swarm task records | Medium |
| 4 | `InMemoryMediaJobStore` | `apps/media-service/.../job/InMemoryMediaJobStore.java` | All media (dubbing) job records | Medium |

---

## 1. `SystemPanicState` — global panic / kill-switch (highest priority)

**File**: `apps/jarvis-common/src/main/java/org/jarvis/common/safety/SystemPanicState.java`

Verbatim javadoc:

> Shared global panic / kill-switch state (EPIC 3 — "stop all running
> workflows"). When engaged, every action path that consults it refuses to
> execute or publish — the gateway tool executor, the orchestrator command
> publisher, and the voice fast-path.
>
> In-memory per service; engage/clear are propagated across services by
> {@code PanicBroadcaster} over a RabbitMQ fanout so a single
> {@code POST /api/v1/agent/panic} halts the whole stack. A restarted service
> resets to not-engaged (documented limitation; durable backing is future
> work).

**Implementation**: an `AtomicReference<State>` initialized to
`(engaged=false, ...)` every time the JVM starts. Registered as a
`@ConditionalOnMissingBean` in `SafetyAutoConfiguration`, so **every service that
depends on `jarvis-common` gets its own independent in-memory instance**
(confirmed present and consulted separately in `api-gateway`
(`PanicGuardFilter`, `AgentExecutionService`, `AgentControlController`),
`orchestrator` (`CommandPublisher`, `InternalControlController`,
`VoiceLoopController`), and `agent-service` (`AgentActionGuard`)).

**What's lost**: the *engaged* flag itself. If an operator has engaged panic
mid-incident (e.g., a runaway agent task or a misbehaving voice command loop) and
any one of those services restarts — a crash, a rolling deploy, a node reboot — that
service comes back up with panic **not engaged**, even though the rest of the
stack (and the operator) still believe the system is halted.

**Verified propagation gap (found during this audit, beyond what the javadoc
states)**: the javadoc's own name for the propagation mechanism —
`PanicBroadcaster` over a RabbitMQ fanout — does not match what exists in code.
The actual propagator found is `apps/api-gateway/.../agent/PanicPropagator.java`,
whose own javadoc says: *"Best-effort propagation of the global panic kill-switch
to the orchestrator. The api-gateway has no message broker, so it pushes
engage/clear over HTTP to the orchestrator's SVC_INTERNAL control endpoint. Failure
to reach the orchestrator never blocks the panic action."* This is a single
best-effort **HTTP** call, api-gateway → orchestrator only — not a fanout, and not
"the whole stack." No controller or consumer was found in `agent-service` that
receives an external engage/clear call at all; `agent-service`'s
`AgentActionGuard` only ever reads its own local, never-externally-updated
`SystemPanicState` instance. So the actual blast radius of "one `POST
/api/v1/agent/panic` halts the whole stack" is narrower than documented, and is
itself worth a follow-up ticket independent of the restart problem below.

**User-visible impact**: after any restart of api-gateway, orchestrator, or
agent-service during an active panic, that service silently re-opens its action
paths — voice commands, orchestrator-published commands, or agent-swarm tasks can
execute again — with no error, no log distinguishing this from a normal boot, and
no re-sync against the other services' state. Since restarts are routine (rolling
deploys, crash-loop recovery, node reboots — this audit found the cluster itself
currently down after a host reboot), this is not a rare edge case.

**Recommended future fix** (effort estimate):
- **Short-term (S, ~1-2 days)**: on boot, each service queries a shared source
  (e.g., the orchestrator's `/internal/control/panic` state, or a Redis/Postgres
  flag) before serving any action-bearing request, instead of defaulting to
  not-engaged. Cheapest fix, closes the "restart during active panic" hole
  without a new datastore.
- **Long-term (M, ~1 sprint)**: back `SystemPanicState` with a durable store
  (a `panic_state` row in the shared Postgres, or a dedicated Redis key) written on
  every `engage()`/`clear()` and read on startup by all three consuming services;
  replace the ad hoc HTTP propagator with a real broadcast (RabbitMQ fanout, as the
  original javadoc already intended) so agent-service is actually reachable.

**Accepted for v1.0**: yes — single-operator, single-host deployment where the
operator who engaged panic is also the one restarting services, so the practical
exposure window is short. Flagged here so it is not lost track of before any
multi-user or higher-autonomy phase.

---

## 2. `EnergyStateService` — per-user energy level

**File**: `apps/planner-service/src/main/java/org/jarvis/planner/service/EnergyStateService.java`

Verbatim javadoc:

> B3 — per-user current energy state. In-memory and transient by design (no
> migration); defaults to {@link EnergyLevel#NORMAL}. A future iteration can
> persist this or derive it from sleep/steps; for now it is set explicitly
> (voice/API) and optionally seeded by callers.

**Implementation**: `ConcurrentHashMap<String, EnergyLevel>` keyed by `userId`,
`getOrDefault(userId, NORMAL)`.

**What's lost**: whatever energy level (e.g., LOW/NORMAL/HIGH) the user last set
via voice or API. Falls back to `NORMAL`.

**User-visible impact**: low. Planner recommendations that factor in energy level
silently reset to the neutral default after a `planner-service` restart; the user
would need to re-state their energy level. No data corruption, no safety
implication — just a one-time UX nudge to re-set a preference.

**Recommended future fix** (effort estimate): **S, ~half a day** — persist to the
existing per-user Postgres row (planner-service already has Flyway migrations for
other planner state) or derive it automatically from `life-tracker` sleep/steps
data, as the javadoc itself already suggests as the intended follow-up.

**Accepted for v1.0**: yes — explicitly designed this way per its own javadoc
("in-memory and transient by design"), not an oversight.

---

## 3. `InMemoryAgentTaskStore` — agent-swarm task records

**Interface**: `apps/agent-service/src/main/java/org/jarvis/swarm/task/AgentTaskStore.java`

> Persistence boundary for agent tasks (in-memory for the MVP; swappable later).

**Implementation**: `apps/agent-service/src/main/java/org/jarvis/swarm/task/InMemoryAgentTaskStore.java`

> Thread-safe in-memory task store. The MVP avoids a database so the service stays
> isolated (no migrations, no NetworkPolicy allowlist). Tasks are ephemeral and
> reset on restart — documented as a known limitation.

Backed by a `ConcurrentHashMap<String, AgentTask>`, exposing `save`, `findById`,
`findByUser`, `findBySwarm`.

**What's lost**: every `AgentTask` record — task id, status, role, correlation id,
swarm membership, audit trail linkage — for every task ever created against this
`agent-service` instance. A restart wipes the entire task history, including any
task that was mid-execution (its `ExecutionContext` checkpoint state lives in the
same process and is lost too).

**User-visible impact**: medium. `GET /api/v1/agent/tasks/{id}` (or the swarm
equivalents) return 404 for anything created before the restart; the API/UI cannot
distinguish "never existed" from "existed but service restarted." A task that was
`RUNNING` at restart time simply disappears rather than being marked `FAILED` or
resumed — no orphaned-task cleanup or resume-on-boot logic exists.

**Recommended future fix** (effort estimate): **M, ~3-5 days**. The interface is
explicitly designed to be swappable (`AgentTaskStore` is already an interface with
a single `InMemory*` implementation, `@Repository`-annotated) — implementing a
`JdbcAgentTaskStore`/`JpaAgentTaskStore` against Postgres is a drop-in replacement
requiring no changes to `AgentTaskService`, `AgentActionGuard`, or the web layer.
Needs: a migration for the `agent_tasks` table, a NetworkPolicy allowlist entry to
Postgres (currently intentionally isolated, per the comment above), and a decision
on what happens to tasks found `RUNNING` at boot (mark `FAILED`, or attempt safe
resume via the audit trail).

**Accepted for v1.0**: yes — explicitly an MVP scope cut ("MVP avoids a database so
the service stays isolated"), with the swap path already designed in.

---

## 4. `InMemoryMediaJobStore` — media (dubbing) job records

**Interface**: `apps/media-service/src/main/java/org/jarvis/media/job/MediaJobStore.java`

> Persistence boundary for media jobs (in-memory for the MVP; swappable later).

**Implementation**: `apps/media-service/src/main/java/org/jarvis/media/job/InMemoryMediaJobStore.java`

> Thread-safe in-memory job store. The MVP intentionally avoids a database so the
> service stays isolated (no cross-service network policy, no migrations). Jobs are
> ephemeral and reset on restart — documented as a known limitation.

Backed by a `ConcurrentHashMap<String, MediaJob>`, exposing `save`, `findById`,
`findByUser`.

**What's lost**: every `MediaJob` record (RU-dubbing pipeline jobs — audio
extraction, ASR, translation, TTS synthesis, mux) created against this
`media-service` instance, plus any `JobArtifact` bookkeeping tied to it. Given the
ASR/translation/TTS stages are currently 100% mock (see
`docs/audit/2026-07-04-status-reconciliation.md` section (c)), the practical blast
radius today is smaller than it will be once those stages are real — but the job
bookkeeping loss is identical either way.

**User-visible impact**: medium. A user polling `GET
/api/v1/media/jobs/{id}` for dubbing progress gets a 404 after a `media-service`
restart, indistinguishable from "job never existed." Any in-flight job (mid
ffmpeg/ffprobe processing) loses its tracked status even though the underlying
workspace files (`WorkspaceManager`) may still exist on disk — creating a
potential orphaned-file cleanup gap, not just a lost status row.

**Recommended future fix** (effort estimate): **M, ~3-5 days** — same shape as
item 3: implement a Postgres-backed `MediaJobStore`, drop-in via the existing
interface (`MediaJobService` already depends on the interface, not the
implementation), add a migration + NetworkPolicy allowlist entry, and add a boot-
time reconciliation pass that checks `WorkspaceManager` for orphaned job
directories with no matching store entry.

**Accepted for v1.0**: yes — explicitly an MVP scope cut, same rationale and same
swap-friendly design as item 3.

---

## Verification

The four javadoc blocks quoted above were copied verbatim from the source files on
2026-07-04; re-run to confirm no drift:

```bash
sed -n '9,19p'  apps/jarvis-common/src/main/java/org/jarvis/common/safety/SystemPanicState.java
sed -n '9,13p'  apps/planner-service/src/main/java/org/jarvis/planner/service/EnergyStateService.java
sed -n '6p'     apps/agent-service/src/main/java/org/jarvis/swarm/task/AgentTaskStore.java
sed -n '11,15p' apps/agent-service/src/main/java/org/jarvis/swarm/task/InMemoryAgentTaskStore.java
sed -n '6p'     apps/media-service/src/main/java/org/jarvis/media/job/MediaJobStore.java
sed -n '11,14p' apps/media-service/src/main/java/org/jarvis/media/job/InMemoryMediaJobStore.java
```
