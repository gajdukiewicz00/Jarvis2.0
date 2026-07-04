# ADR-0005: Command pipeline on RabbitMQ

## Status

Accepted (Phase 4).

## Context

Phase 0 inventory found that the orchestrator routed every action through a
single 79-branch `switch` statement (`apps/orchestrator/.../IntentRouter`)
that called other services synchronously over REST. This conflicts with
SPEC-1 § "Messaging Split" and Phase 4, which require:

* a guaranteed-delivery command bus,
* dead-letter handling for failed/expired commands,
* idempotency by `command_id`,
* per-message TTL enforcement,
* a clean `orchestrator -> agent -> result -> orchestrator` round trip,
* a separate confirmation lane for dangerous commands (Phase 5).

A REST-only model can't deliver these properties without re-implementing
broker semantics in application code.

## Decision

We adopt **RabbitMQ as the command bus** for orchestrator → desktop-agent
traffic and the supporting confirmation/task queues. Kafka stays the
event/audit/analytics backbone (Phase 8) — it does **not** carry commands.

The shared schema lives in a new Maven module `libs/command-schema`:

* `CommandEnvelope` — 12 required fields (`commandId`, `correlationId`,
  `userId`, `source`, `intent`, `riskLevel`, `requiresConfirmation`,
  `createdAt`, `expiresAt`, `status`, `auditEventId`, `payload`).
* `CommandResult` — `commandId`, `correlationId`, `status`, `completedAt`,
  `durationMillis`, `output`, `errorReason`, `auditEventId`.
* `RiskLevel` — `SAFE`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
* `CommandStatus` — full lifecycle including `AWAITING_CONFIRMATION`.
* `CommandTopology` — queue/exchange name constants, used by every service.
* `CommandFactory` — builder enforcing required fields and confirmation
  defaulting (`riskLevel >= MEDIUM ⇒ requiresConfirmation`).

Topology is declared in `apps/jarvis-common`'s
`RabbitTopologyConfig`, conditional on `spring-boot-starter-amqp` being on
the classpath and `spring.rabbitmq.host` being set:

| Queue | Direction | DLX |
| --- | --- | --- |
| `jarvis.commands.agent.execute` | orchestrator → desktop-agent | `jarvis.dlx.commands` |
| `jarvis.commands.agent.result` | desktop-agent → orchestrator | `jarvis.dlx.commands` |
| `jarvis.commands.confirmation.request` | orchestrator → confirmation UI | `jarvis.dlx.commands` |
| `jarvis.commands.confirmation.result` | confirmation UI → orchestrator | `jarvis.dlx.commands` |
| `jarvis.tasks.background` | async tasks | `jarvis.dlx.tasks` |
| `jarvis.tasks.llm` | LLM tasks | `jarvis.dlx.tasks` |

Two DLX exchanges (`jarvis.dlx.commands`, `jarvis.dlx.tasks`) each fan
into a single capture queue (`jarvis.dlq.commands`, `jarvis.dlq.tasks`).

## Consequences

* The orchestrator gets a `CommandPublisher` + `CommandResultListener` +
  `PendingCommandRegistry` (in-memory, ConcurrentHashMap, scheduled
  sweeper). Per-message TTL is set as the AMQP `expiration` property; both
  the broker (queue TTL) and the consumer (`CommandEnvelope.isExpired`)
  enforce it.
* The desktop-agent consumes the execute queue with the native
  `com.rabbitmq:amqp-client` library (no Spring Boot in
  `apps/desktop-javafx`). Idempotency is a Caffeine cache keyed by
  `commandId` (1h TTL).
* The result lane is a regular queue, not a reply-queue per request,
  because we want the result to survive an orchestrator restart and be
  reconciled later (Phase 8 audit projector consumes the same queue).
* Failed commands NACK with `requeue=false` and route to
  `jarvis.dlx.commands`. Expired commands publish an `EXPIRED`
  `CommandResult` and ack so the orchestrator's pending registry is
  drained promptly.
* Confirmation queues exist now but are wired in Phase 5; Phase 4 only
  declares them so the topology is stable across releases.
* Switch-79 in the orchestrator is **not** removed in this phase — it is
  migrated incrementally in Phase 5/6/7 as each intent learns its risk
  level and the desktop-agent grows real executors.

## Alternatives considered

* **Kafka instead of RabbitMQ** — rejected. Kafka excels at event
  retention and analytics (Phase 8) but lacks per-message acks, native
  TTL/DLQ on the broker, and queue-style competing consumers without
  consumer-group hacks.
* **Synchronous REST with retries** — rejected. The whole point is
  guaranteed delivery + offline desktop-agent + DLQ visibility, none of
  which a REST client gives natively.
* **Spring Cloud Stream** — overkill. We don't need the abstraction layer;
  the schema + topology are stable.

## References

* SPEC-1 § "Messaging Split"
* `docs/architecture/phase-4-acceptance-evidence.md`
* `libs/command-schema/`
* `apps/jarvis-common/.../messaging/RabbitTopologyConfig.java`
