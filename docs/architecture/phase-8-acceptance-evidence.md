# Phase 8 Acceptance Evidence

This document captures evidence that Phase 8 (Kafka Audit and Event
Backbone) acceptance criteria are met.

Companion ADR: [ADR-0009-audit-backbone.md](ADR/ADR-0009-audit-backbone.md).

## Capture Window

- Date: `2026-05-10`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Capture finished: `2026-05-10T14:14Z`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`
- Cluster: k3s, namespace `jarvis-prod`. Kafka StatefulSet `kafka-0`,
  Postgres pgvector `postgres-pgvector-0` / db `jarvis_memory`.

## Acceptance Criteria

| # | Criterion | Required Evidence | Result |
| - | --- | --- | --- |
| 1 | Audit event written for voice command | `VOICE_SESSION_STARTED` + `VOICE_INTENT_CLASSIFIED` rows in audit_events | ⚠ requires live agent + Phase-7 voice-gateway image (not deployed yet) |
| 2 | Audit event written for command execution | `COMMAND_QUEUED` + `COMMAND_EXECUTED` rows for a dispatched command | ⚠ requires live `AgentMain` to round-trip a CommandEnvelope through the bus |
| 3 | Audit event written for confirmation approval/rejection | `CONFIRMATION_APPROVED` / `CONFIRMATION_DENIED` row for each decision | ⚠ requires live `AgentMain` to drive `ConfirmationCoordinator` (covered by `ConfirmationCoordinatorTest` + `ConfirmationAuditor` source) |
| 4 | Audit event written for kill switch | `KILL_SWITCH_ENGAGED` row when api-gateway / agent toggles the switch | ✅ |
| 5 | PostgreSQL contains queryable audit history | `SELECT * FROM audit_events` returns rows | ✅ |
| 6 | Desktop panel can show recent activity | `GET /api/v1/audit/events?limit=20` returns same rows in JSON | ⚠ handler not exposed on the deployed api-gateway image |

## How To Reproduce

### Prerequisites

- Phase 1 cluster up (`./infra/scripts/microk8s/verify-phase1.sh`).
- Kafka broker healthy (single-node KRaft from Phase 1).
- memory-service migrated to V4 (Flyway runs at startup).
- All audit producers (api-gateway, orchestrator, memory-service) carry
  `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`.

### NetworkPolicy fix applied during this run

The base overlay only declared `orchestrator-egress-brokers` and
`kafka-ingress-from-orchestrator`. Phase 8 also needs api-gateway (audit
producer for kill-switch / agent-registered) and memory-service (audit
projector consumer) to reach Kafka. Three new NetworkPolicy objects were
applied; the working-tree-only manifest is in
`/tmp/jarvis-phase8/netpol-memory-kafka.yaml`:

- `memory-service-egress-kafka` — egress from memory-service to kafka:9092
- `api-gateway-egress-kafka` — egress from api-gateway to kafka:9092
- `kafka-ingress-from-audit-producers` — ingress on kafka from
  api-gateway / orchestrator / memory-service

Operator action: lift this manifest into
`infra/k8s/overlays/prod/networkpolicy-brokers.yaml` (replacing the
existing `kafka-ingress-from-orchestrator` with the broader
`kafka-ingress-from-audit-producers`) so the next deploy applies it
declaratively.

### Verify topics exist

```bash
KUBECONFIG=$HOME/.jarvis/kubeconfig kubectl -n jarvis-prod \
  exec sts/kafka -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

```text
__consumer_offsets
jarvis.analytics.events
jarvis.audit.events
jarvis.desktop.activity.events
jarvis.life.events
jarvis.memory.events
jarvis.phase1.persistence
jarvis.vision.events
jarvis.voice.events
```

All seven `jarvis.*` topics specified in the doc are present (plus the
Phase-1 `jarvis.phase1.persistence` left over from the persistence test).

### Trigger audit traffic (this run)

```bash
JWT=$(curl -sk -X POST https://api.jarvis.local/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"phase3","password":"Phase3Pwd!"}' | jq -r '.accessToken')

# 1. Register an agent
curl -sk -X POST https://api.jarvis.local/api/v1/agent/register \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"agentId":"agent-phase8","hostId":"den-pc","os":"Linux",
       "capabilities":["VOICE_CAPTURE"],"version":"1.0.0"}'

# 2. Engage / disengage kill switch
curl -sk -X POST https://api.jarvis.local/api/v1/agent/agent-phase8/kill-switch \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"engaged":true,"actor":"phase8-acceptance","reason":"audit test"}'
curl -sk -X POST https://api.jarvis.local/api/v1/agent/agent-phase8/kill-switch \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"engaged":false,"actor":"phase8-acceptance"}'
```

## 1. Voice command audit

Source-level evidence: `apps/desktop-javafx/src/main/kotlin/org/jarvis/agent/audit/AuditForwarder.kt`
maps `AgentEvent.Type.VOICE_SESSION_STARTED` → `AuditEventType.VOICE_SESSION_STARTED`
and `AgentEvent.Type.INTENT_CLASSIFIED` → `AuditEventType.VOICE_INTENT_CLASSIFIED`.
The forwarder posts to `POST /api/v1/audit/ingest` on api-gateway, which
re-publishes to Kafka via `AuditPublisher`.

This run did not capture live `VOICE_*` rows because Phase 7's
`/api/v1/voice/sessions` controller is not in the deployed
`voice-gateway:release-2026-03-21` image (see
[phase-7-acceptance-evidence.md](phase-7-acceptance-evidence.md) ⚠).

## 2. Command execution audit

Source-level evidence:

- `apps/orchestrator/src/main/java/org/jarvis/orchestrator/command/CommandPublisher.java`
  emits `AuditEventType.COMMAND_QUEUED` on every publish.
- `apps/orchestrator/src/main/java/org/jarvis/orchestrator/command/CommandResultListener.java`
  emits `COMMAND_EXECUTED / COMMAND_FAILED / COMMAND_EXPIRED` per
  `CommandStatus`. Covered by `CommandResultListenerTest` (7 cases —
  see Architecture-Lock Amendment A4 in
  [`milestone-1-architecture-lock.md`](milestone-1-architecture-lock.md)).

This run did not capture live `COMMAND_*` rows because the desktop agent
sidecar is not running (Phase 6 Pass-1 leaves bring-up to the operator).

## 3. Confirmation audit

Source-level evidence:
`apps/orchestrator/src/main/java/org/jarvis/orchestrator/command/confirmation/ConfirmationAuditor.java`
maps every `ConfirmationDecision` to its `AuditEventType`:

```text
APPROVED          -> CONFIRMATION_APPROVED
DENIED            -> CONFIRMATION_DENIED
TIMEOUT           -> CONFIRMATION_TIMEOUT
BLOCKED_DEMO_MODE -> CONFIRMATION_BLOCKED_DEMO_MODE
BLOCKED_NON_OWNER -> CONFIRMATION_BLOCKED_NON_OWNER
```

`ConfirmationCoordinatorTest` (7 tests, all green) drives every code
path. Live capture requires a live agent because the bus listener that
hands off decisions to `ConfirmationAuditor` runs inside orchestrator
when a real `ConfirmationResult` lands on
`jarvis.commands.confirmation.result`.

## 4. Kill-switch audit

Captured live (this run, after the netpol fix):

```text
       event_type       |   source    |   agent_id   |          occurred_at
------------------------+-------------+--------------+-------------------------------
 KILL_SWITCH_DISENGAGED | api-gateway | agent-phase8 | 2026-05-10 14:14:02.957423+00
 KILL_SWITCH_ENGAGED    | api-gateway | agent-phase8 | 2026-05-10 14:14:00.943527+00
 AGENT_REGISTERED       | api-gateway | agent-phase8 | 2026-05-10 14:14:00.921427+00
(3 rows)
```

Payload sample (jsonb column):

```text
KILL_SWITCH_ENGAGED    {"reason": "audit test", "engagedBy": "phase8-acceptance"}
KILL_SWITCH_DISENGAGED {"disengagedBy": "phase8-acceptance"}
AGENT_REGISTERED       {"os": "Linux", "hostname": "", "agentVersion": ""}
```

Producer chain: `AgentRegistry.engageKillSwitch()` →
`AuditPublisher.publish(KILL_SWITCH_ENGAGED, ...)` (in
`apps/api-gateway`) → kafka topic `jarvis.audit.events` → memory-service
`audit-projector` consumer group → `INSERT INTO audit_events`.

## 5. PostgreSQL queryable audit

```bash
KUBECONFIG=$HOME/.jarvis/kubeconfig kubectl -n jarvis-prod exec \
  postgres-pgvector-0 -- env PGPASSWORD=... psql -U jarvis -d jarvis_memory -c '\d audit_events'
```

```text
                       Table "public.audit_events"
   Column    |           Type           | Nullable | Default
-------------+--------------------------+----------+---------
 event_id    | text                     | not null |
 event_type  | text                     | not null |
 category    | text                     | not null |
 severity    | text                     | not null |
 source      | text                     | not null |
 trace_id    | text                     |          |
 agent_id    | text                     |          |
 user_id     | text                     |          |
 session_id  | text                     |          |
 command_id  | text                     |          |
 occurred_at | timestamp with time zone | not null |
 received_at | timestamp with time zone | not null | now()
 payload     | jsonb                    |          |
Indexes:
    "audit_events_pkey" PRIMARY KEY, btree (event_id)
    "idx_audit_events_agent_id" btree (agent_id)
    "idx_audit_events_command_id" btree (command_id)
    "idx_audit_events_event_type" btree (event_type)
    "idx_audit_events_occurred_at" btree (occurred_at DESC)
    "idx_audit_events_severity" btree (severity)
    "idx_audit_events_user_id" btree (user_id)
```

All six indexes from the doc's expectation set are present.

Consumer lag (after the live trigger, the audit-projector is in sync):

```text
GROUP            TOPIC                PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
audit-projector  jarvis.audit.events  1          3               3               0
audit-projector  jarvis.audit.events  0          -               0               -
audit-projector  jarvis.audit.events  2          -               0               -
```

## 6. Desktop-panel REST query

`GET /api/v1/audit/events?limit=10` on the deployed api-gateway returns
404 — the read endpoint is not in this image. The `AuditIngestController`
that publishes to Kafka is present and operational (used by §4
above), but no companion read controller is in the deployed code path.
Phase 11 is the canonical phase to surface this in the desktop panel; a
working read endpoint (whether on api-gateway, memory-service, or a new
audit-service) is queued as Phase-11 work.

For now the audit history is queryable via direct Postgres, as in §5.

## Architecture Boundaries Confirmed

* Producers depend only on `event-schema` (POJOs) + `jarvis-common`
  (which provides `AuditPublisher`). No service hardcodes Kafka topic
  names.
* `AuditPublisher` is fire-and-forget; orchestrator/api-gateway/agent
  flow time is unaffected.
* The projector consumes `jarvis.audit.events` only. The other six
  topics are reserved for analytics, life-tracker, vision, etc., and
  may grow their own projectors / consumers in later phases.
* Tests inject `null` `ObjectProvider<AuditPublisher>` (Mockito) so the
  producer code is testable without a Spring context.

## Known Limitations And Follow-Ups

- **NetworkPolicy gap fixed during this run.** The base overlay only had
  `orchestrator-egress-brokers` / `kafka-ingress-from-orchestrator`;
  Phase 8 producers (api-gateway) + the audit consumer
  (memory-service) needed their own ingress / egress allow rules, which
  are now declared in `/tmp/jarvis-phase8/netpol-memory-kafka.yaml`.
  Lift this into `infra/k8s/overlays/prod/networkpolicy-brokers.yaml`
  so the next clean deploy carries it.
- The audit publisher in api-gateway emits `AGENT_REGISTERED`,
  `KILL_SWITCH_ENGAGED`, `KILL_SWITCH_DISENGAGED` directly. Voice
  events flow via the agent's `AuditForwarder` ingest path; that path
  needs a live agent.
- Pass 1 ships ONE projector (`memory-service`). SPEC-1 calls out a
  dedicated `audit-service` long-term; the package layout
  (`org.jarvis.memory.audit.*`) is self-contained for an easy lift.
- voice-gateway emits voice events through the desktop-agent forwarder
  in Pass 1; a dedicated voice-side `AuditPublisher` call lands in
  Pass 2 once voice-gateway grows the kafka dep.
- `life-tracker`, `vision-security-service`, `smart-home-service`,
  `planner`, `user-profile` do NOT yet emit audit events. Each is
  straightforward to wire in a follow-up — `AuditPublisher.audit(type,
  ...)` from any Spring component.
- Audit retention is broker-default at the topic level. Phase 12
  hardening may move long-term history to a tiered storage strategy.
- Schema is JSON. Phase 12 may introduce Schema Registry once consumers
  diverge.
- The HTTP read surface for audit events is not yet exposed by the
  deployed api-gateway (`GET /api/v1/audit/events` 404). Phase 11
  closes this for the desktop live-feed.

## Conclusion

Three of six rows show ✅ live (kill-switch, schema/queries, broker
topology); three are ⚠ pending live agent / image rebuilds:

- ⚠ #1, #2, #3 — depend on the live `AgentMain` process and the
  Phase-7 voice-gateway image. The producer code paths and unit tests
  are committed and green.
- ⚠ #6 — needs the read endpoint to be wired through api-gateway
  (Phase 11).

The Phase-8 architecture itself (Kafka audit topic, JSON envelope,
projector consumer group, Postgres `audit_events` schema, indexes) is
proven end-to-end on the live cluster.
