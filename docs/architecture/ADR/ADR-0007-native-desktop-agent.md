# ADR-0007: Native Desktop Agent stabilization

## Status

Accepted (Phase 6).

## Context

Phase 4 introduced the RabbitMQ command bus and Phase 5 added a
confirmation lane on top of it. Both phases ran the desktop side as a
naked sidecar started via `mvn exec:java`, with no concept of identity,
no health surface to the backend, no way to halt mic/cam/automation
without killing the whole process, and no record of what the agent had
been doing.

SPEC-1 § "Native Desktop Agent" requires:

* a stable agent identity that survives restarts;
* heartbeats so the backend can mark agents OFFLINE;
* a live feed of what the agent is doing/thinking;
* PC control behind an explicit permission model;
* an emergency kill switch that disables mic/cam/automation;
* startup-from-icon so the operator never types a command line.

This phase delivers those building blocks **without** introducing Spring
Boot into the JavaFX module (which would balloon the dependency
footprint and risk regressing the existing 31 desktop tests).

## Decision

### Identity & capabilities

* `AgentIdentity` (POJO in `command-schema`) — `agentId`, `hostId`,
  `hostname`, `os`, `osVersion`, `agentVersion`, `registeredAt`.
  Persisted atomically to `~/.jarvis/agent/identity.json`; a fresh
  `agentId` is minted only on first run.
* `CapabilityProbe` — best-effort detection of:
  voice capture / TTS playback / wake word / screen capture / webcam /
  keyboard / mouse / file system / browser CLI / IDE CLI / pc-power.
  Each probe is wrapped so missing dependencies degrade the capability
  set instead of failing the agent boot.

### Heartbeat & registry

* `HeartbeatPublisher` — plain OkHttp + single-thread scheduler, posts
  `AgentHeartbeat` to `POST /api/v1/agent/heartbeat` every 15s. Status
  override allows marking BOOTING during initialization.
* `AgentRegistry` (api-gateway) — in-memory `ConcurrentHashMap` keyed by
  `agentId`. A scheduled sweeper flips agents whose `lastSeen` exceeds
  `staleThresholdSeconds` to `OFFLINE`. Phase 8 will project this to
  Postgres + Kafka so the registry survives api-gateway restarts.

### Kill switch

* `KillSwitchManager` (Kotlin) — atomic `KillSwitchState` mirrored to
  `~/.jarvis/agent/killswitch.json`; engaged state SURVIVES restart so
  an operator who hit it during an incident does not have the agent
  silently reanimate.
* Single chokepoint `PermissionGate.ensureClear()` throws
  `KillSwitchEngagedException`; consumers convert that to a structured
  REJECTED `CommandResult` (orchestrator sees a normal failure rather
  than a timeout).
* All transitions emit `AgentEvent.KILL_SWITCH_ENGAGED` /
  `KILL_SWITCH_DISENGAGED` on the live feed.
* The backend exposes
  `POST /api/v1/agent/{agentId}/kill-switch` so a remote operator can
  engage / disengage from outside the host.

### Live feed

* `AgentLiveFeed` — bounded `ConcurrentLinkedDeque` (default 500 entries)
  + `CopyOnWriteArrayList` of subscribers. Same source feeds the desktop
  panel (Phase 11) and the Kafka audit projector (Phase 8).
* 11 event types match SPEC-1's required surface
  (`VOICE_SESSION_STARTED`, `INTENT_CLASSIFIED`, `COMMAND_QUEUED`,
  `CONFIRMATION_REQUESTED`, `COMMAND_EXECUTED`, `MEMORY_WRITTEN`,
  `CV_EVENT_RECEIVED`, `ERROR`, `DEGRADED_STATE`, `KILL_SWITCH_*`).

### Permission gate

* `PermissionAwareExecutor` and `KillSwitchAwareConfirmationStrategy`
  wrap the existing executor / strategy from Phase 4-5; no new bus
  contract was needed.
* Capability check (`PermissionGate.require(AgentCapability)`) is
  enforced at consumer entry, so a host without a webcam cannot
  accidentally execute a CV command — the broker NACKs to DLQ instead.

### Startup from icon

* `infra/scripts/agent/jarvis-agent.desktop` template + `install-desktop-icon.sh`
  installs into `~/.local/share/applications/` (menu entry) and
  `~/.config/autostart/` (start-on-login).
* Settings file pattern: `~/.jarvis/agent/agent.env` is sourced before
  the launcher runs, so the operator can change broker/backend/strategy
  without editing the .desktop entry.

## Consequences

* `AgentMain.kt` becomes the canonical bootstrap: identity → capabilities
  → kill switch + feed → backend register → status aggregator → heartbeat
  → command/confirmation consumers (now wrapped by gate) → shutdown hook.
* The Phase 4 `LoggingCommandExecutor` and Phase 5 `*Strategy`
  implementations are untouched; the gate wrappers compose around them.
* The api-gateway gets two new files (`AgentRegistry`,
  `AgentControlController`) and a `command-schema` dependency. Topology
  and ingress are unchanged.
* Memory footprint is small: identity / killswitch JSON files plus a
  ring buffer (~500 KB worst case) plus the heartbeat scheduler thread.
* Phase 8 has a clean integration point: subscribe to `AgentLiveFeed` →
  publish to `jarvis.desktop.activity.events`, project to Postgres.

## Alternatives considered

* **Embed Spring Boot in desktop-javafx for `@Scheduled` and
  `@RestController`.** Rejected — pulls Tomcat / actuator / data-jpa
  transitively; bloat in a JavaFX app is severe. Hand-rolled scheduler
  + OkHttp is ~80 LOC and avoids the regression risk.
* **Single-process JavaFX shell + agent in Phase 6.** Deferred to a
  Pass 2. The bus contract works regardless of whether the agent runs
  in the JavaFX process or as a sidecar; merging them adds JavaFX
  initialization order concerns that need real GUI testing.
* **WebSocket heartbeat instead of REST.** Rejected for Pass 1 —
  the heartbeat is fire-and-forget; a connection lifecycle adds
  failure modes for no real benefit. WebSocket fits better when the
  desktop panel needs push updates (Phase 11).

## References

* SPEC-1 § "Native Desktop Agent"
* SPEC-1 § Phase 6 task list
* `apps/desktop-javafx/src/main/kotlin/org/jarvis/agent/`
* `apps/api-gateway/src/main/java/org/jarvis/apigateway/agent/`
* [phase-6-acceptance-evidence.md](../phase-6-acceptance-evidence.md)
