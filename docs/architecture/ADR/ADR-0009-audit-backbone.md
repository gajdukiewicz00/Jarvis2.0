# ADR-0009: Kafka audit and event backbone

## Status

Accepted (Phase 8).

## Context

Phases 4–7 produced rich operational state — every command, confirmation
decision, kill-switch toggle, and voice session had a structured log line.
Logs are great for incident debugging but useless for the diploma's
"queryable audit history" requirement and the desktop panel's "show
recent activity" surface.

SPEC-1 § "Messaging Split" mandates seven Kafka topics and a projector
that converts the audit topic into queryable Postgres rows.

## Decision

We add the event backbone in three layers:

```
producers (orchestrator / api-gateway / voice-gateway / desktop agent)
   │
   │ JarvisEvent (jarvis.audit.events)
   ▼
Kafka cluster (jarvis-prod, KRaft single-node)
   │
   ▼
memory-service AuditEventConsumer ──▶ audit_events table (Postgres)
   │
   ▼
GET /api/v1/audit/events  ←─── desktop panel (Phase 11)
```

Concrete pieces:

* New module `libs/event-schema` with `JarvisEvent`, `EventCategory`
  (7 categories, 1:1 to topics), `EventTopics` constants,
  `AuditEventType` (20 enum values), `EventSeverity`. Pure POJOs, no
  Spring dependency.
* `KafkaTopologyConfig` in `jarvis-common` declares all seven topics
  via Spring Kafka admin (3 partitions, 30-day retention, single
  replica for the MicroK8s baseline). Conditional on
  `spring.kafka.bootstrap-servers`.
* `AuditPublisher` in `jarvis-common` is a small wrapper around
  `KafkaTemplate` that fills required fields, picks the correct topic
  from `EventCategory`, and chooses a partition key (`commandId` →
  `agentId` → `eventId`). Failures are logged at WARN and never thrown
  — audit must never block the action it records.
* `memory-service` hosts the projector for Pass 1: Flyway V4 migration
  creates `audit_events` (jsonb payload, indexed by occurred_at /
  event_type / agent_id / user_id / command_id / severity); a
  `@KafkaListener` consumes `jarvis.audit.events` and writes one row;
  a `GET /api/v1/audit/events` endpoint exposes recent activity to the
  desktop. SPEC-1 calls out audit as a candidate for an own service —
  that extraction is straightforward later because the projector is a
  self-contained package (`org.jarvis.memory.audit`).
* Desktop agent (no Kafka client) posts events to a new
  `POST /api/v1/audit/ingest` on api-gateway, which re-publishes via
  the same `AuditPublisher`. `AuditForwarder.kt` subscribes to the
  Phase 6 `AgentLiveFeed` and forwards each entry asynchronously on
  a daemon thread.

## Consequences

* Every privileged action emits an audit event:
  - orchestrator `CommandPublisher.publish` → `COMMAND_QUEUED`
  - orchestrator `CommandResultListener` → `COMMAND_EXECUTED` /
    `COMMAND_FAILED` / `COMMAND_EXPIRED`
  - orchestrator `ConfirmationCoordinator` → all five
    `CONFIRMATION_*` outcomes
  - api-gateway `AgentRegistry.register` → `AGENT_REGISTERED`
  - api-gateway `AgentRegistry.engage/disengageKillSwitch` →
    `KILL_SWITCH_ENGAGED` / `KILL_SWITCH_DISENGAGED`
  - desktop agent live-feed entries (`VOICE_SESSION_STARTED`,
    `INTENT_CLASSIFIED`, `COMMAND_EXECUTED`, etc.) forwarded via REST.
* Existing `AUDIT` log lines are kept — when Kafka is unavailable, ops
  can still reconstruct history.
* `AuditPublisher` and `KafkaTopologyConfig` are auto-configurations
  guarded by `@ConditionalOnClass(KafkaTemplate.class)` and
  `@ConditionalOnProperty(spring.kafka.bootstrap-servers)`, so test
  contexts without Kafka don't break and new services need only a
  property line + `spring-kafka` dependency to opt in.
* Producers receive `AuditPublisher` via `ObjectProvider<AuditPublisher>`
  so unit tests construct the producer with `null` provider without
  any Spring context.
* The audit projector is in `memory-service` for Pass 1. SPEC-1
  explicitly allows audit-service to "start as a shared module before
  becoming a separate service"; the projector lives in its own package
  so extraction to a new module is mechanical.

## Alternatives considered

* **Postgres-only audit (no Kafka).** Rejected — services would have to
  share a database connection or call a write API on every action.
  Kafka decouples producers from the audit store and lets the same
  topic feed analytics (Phase 12), the desktop live feed (Phase 11),
  and external SIEM tools.
* **Avro / Protobuf schema.** Rejected for Pass 1. JSON is enough until
  the schema stabilises; Phase 12 can introduce Schema Registry once
  the audit consumers multiply.
* **Each producer writes directly to Postgres.** Rejected — concurrent
  writes from many services contend for connections and there is no
  natural backpressure path.

## References

* SPEC-1 § "Messaging Split"
* SPEC-1 § Phase 8 task list
* `libs/event-schema/`
* `apps/jarvis-common/.../eventbus/`
* `apps/memory-service/.../audit/`
* `apps/api-gateway/.../audit/AuditIngestController.java`
* `apps/desktop-javafx/.../agent/audit/AuditForwarder.kt`
* [phase-8-acceptance-evidence.md](../phase-8-acceptance-evidence.md)
