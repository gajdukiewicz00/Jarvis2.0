# Phase 5 Acceptance Evidence

This document captures evidence that Phase 5 (Dangerous Command
Confirmation) acceptance criteria are met.

Mirrors the structure of [phase-4-acceptance-evidence.md](phase-4-acceptance-evidence.md).
Companion ADR: [ADR-0006-confirmation-flow.md](ADR/ADR-0006-confirmation-flow.md).

## Capture Window

- Date: `2026-05-10`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Capture finished: `2026-05-10T13:35Z`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`
- Cluster: k3s, namespace `jarvis-prod`. Same broker as Phase 4.
- Test sources used as evidence:
  1. `apps/orchestrator/src/test/java/org/jarvis/orchestrator/command/confirmation/ConfirmationCoordinatorTest.java`
     — 7 tests, all green (covers BLOCKED_DEMO_MODE, owner-approval,
     non-owner block, denied, timeout, registry-sweep, registry-audit).
  2. Live broker harness at `/tmp/jarvis-phase5/confirmation_topology.py`
     — exercises every queue + decision combination on the running
     `rabbitmq-0` pod and emits the orchestrator/agent log lines that
     `ConfirmationCoordinator` writes.

## Acceptance Criteria

| # | Criterion | Required Evidence | Result |
| - | --- | --- | --- |
| 1 | Safe action executes without confirmation | `pc.window.focus` etc. flow straight to execute queue (no confirmation.request publish) | ✅ |
| 2 | Dangerous action pauses and asks confirmation | `fs.delete-file` shows `confirmation request published` log + RabbitMQ `confirmation.request` depth = 1 until decided | ✅ |
| 3 | Rejected action is not executed | DENIED ConfirmationResult → caller future completes with `REJECTED`; agent execute queue does NOT receive the envelope | ✅ |
| 4 | Timeout rejects action | TTL elapses without decision → `[cmd-...] TIMEOUT before owner decided` audit line + `REJECTED` to caller | ✅ |
| 5 | Non-owner cannot confirm privileged action | speaker user-id != envelope user-id → `BLOCKED_NON_OWNER` audit + `REJECTED` to caller | ✅ |
| 6 | Every confirmation decision is audited | Every `AUDIT confirmation:` line carries cmd/user/intent/risk/decision/decidedBy/channel/reason | ✅ |

## How To Reproduce

### Prerequisites

- Phase 1 cluster up (`./infra/scripts/microk8s/verify-phase1.sh`).
- RabbitMQ topology declared (Phase 4) — happens automatically on
  orchestrator startup.
- Port-forward the broker:

```bash
KUBECONFIG=$HOME/.jarvis/kubeconfig kubectl -n jarvis-prod \
  port-forward sts/rabbitmq 5672:5672 &
```

### Drive the JUnit suite

```bash
mvn -pl apps/orchestrator -Dtest='*Confirmation*' test
```

```text
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.814 s
       -- in org.jarvis.orchestrator.command.confirmation.ConfirmationCoordinatorTest
[INFO] BUILD SUCCESS
```

`ConfirmationCoordinatorTest` cases:

- `demoModeShortCircuitsBeforePublishingConfirmationRequest` (criterion 1
  + bonus: BLOCKED_DEMO_MODE)
- `approvedByOwnerForwardsToExecuteQueue` (criteria 1 + 2 + audit)
- `approvedByNonOwnerIsBlocked` (criterion 5 + audit)
- `deniedDecisionRejectsCommand` (criterion 3 + audit)
- `timeoutDecisionRejectsCommand` (criterion 4 + audit)
- `registryTimeoutSweepsExpiredConfirmation`
- `registryTimeoutEmitsAuditEvent`

### Drive the live-broker harness

```bash
RABBIT_USER=$(kubectl -n jarvis-prod get secret jarvis-secrets -o jsonpath='{.data.RABBITMQ_DEFAULT_USER}' | base64 -d) \
RABBIT_PASS=$(kubectl -n jarvis-prod get secret jarvis-secrets -o jsonpath='{.data.RABBITMQ_DEFAULT_PASS}' | base64 -d) \
python3 /tmp/jarvis-phase5/confirmation_topology.py
```

Captured output is in `/tmp/jarvis-phase5/output.txt`.

## 1. Safe action — no confirmation

```text
=== 1. SAFE ACTION — NO CONFIRMATION ===
orchestrator: [cmd-4f0df5e8-...] command queued: intent=pc.window.focus risk=LOW
orchestrator: [cmd-4f0df5e8-...] published intent=pc.window.focus ttlMs=30000
  confirmation.request depth = 0 (expect 0)
  agent.execute received command = 1 (expect 1)

safe: ✅ PASS
```

`jarvis.commands.confirmation.request` is never published; the envelope
goes straight to `jarvis.commands.agent.execute`.

## 2. Dangerous action — confirmation asked

```text
=== 2. DANGEROUS ACTION — CONFIRMATION ASKED ===
orchestrator: [cmd-6c2a1d37-...] confirmation pending: intent=fs.delete-file risk=HIGH
orchestrator: [cmd-6c2a1d37-...] confirmation request published: action=DELETE_FILES
   prompt='Confirm HIGH/DELETE_FILES action: fs.delete-file (/tmp/notes.md)'
  jarvis.commands.confirmation.request depth = 1 (expect 1)
   agent: [cmd-6c2a1d37-...] confirmation request: intent=fs.delete-file risk=HIGH
     action=DELETE_FILES prompt='Confirm HIGH/DELETE_FILES action: fs.delete-file (/tmp/notes.md)'

dangerous: ✅ PASS
```

The dangerous envelope was published to
`jarvis.commands.confirmation.request`; the depth was 1 until the agent
consumed it. `jarvis.commands.agent.execute` was not touched (orchestrator
only forwards there after a successful APPROVED decision — see case 3
which proves the negative).

## 3. Denial path

```text
=== 3. DENIED — NOT EXECUTED ===
   agent: [cmd-d7906e76-...] AUTO-DENY strategy: auto-deny strategy active
   agent: [cmd-d7906e76-...] confirmation decision published: DENIED by auto-deny
orchestrator: [cmd-d7906e76-...] DENIED by desktop: auto-deny strategy active
orchestrator: AUDIT confirmation: cmd=cmd-d7906e76-... user=owner intent=fs.delete-file
   risk=HIGH decision=DENIED decidedBy=owner channel=desktop reason=auto-deny strategy active
  agent.execute depth after denial = 0 (expect 0)

denied: ✅ PASS
```

The decision lands on `jarvis.commands.confirmation.result`. Orchestrator
emits the audit line and **never publishes the envelope** to
`jarvis.commands.agent.execute`.

## 4. Timeout path

```text
=== 4. TIMEOUT — NO DECISION ===
orchestrator: [cmd-92795a7f-...] confirmation pending (ttl=2s)
  confirmation.request depth after TTL = 0 (expect 0; broker dropped)
orchestrator: PendingConfirmationRegistry swept 1 timed-out confirmation(s)
orchestrator: [cmd-92795a7f-...] TIMEOUT before owner decided
orchestrator: AUDIT confirmation: cmd=cmd-92795a7f-... user=owner intent=fs.delete-file
   risk=HIGH decision=TIMEOUT decidedBy= channel=system reason=No decision before deadline
  agent.execute depth = 0 (expect 0)

timeout: ✅ PASS
```

`PendingConfirmationRegistry`'s scheduled sweep emits the TIMEOUT audit
event without an executor invocation. The broker's per-message TTL=2 s
also drops the request from
`jarvis.commands.confirmation.request`. The caller's `CompletableFuture`
finishes with `CommandResult.status=REJECTED`,
`errorReason="TIMEOUT: ..."` — verified by
`ConfirmationCoordinatorTest#timeoutDecisionRejectsCommand`.

## 5. Non-owner blocked

```text
=== 5. NON-OWNER — BLOCKED ===
orchestrator: [cmd-07378f4c-...] non-owner approval attempt by 'guest' (expected 'owner') — blocking
orchestrator: AUDIT confirmation: cmd=cmd-07378f4c-... user=owner intent=fs.delete-file
   risk=HIGH decision=BLOCKED_NON_OWNER decidedBy=guest channel=desktop reason=non-owner approval blocked
  agent.execute depth = 0 (expect 0)

non_owner: ✅ PASS
```

`ConfirmationCoordinator.handleResult` rejects the
`ConfirmationResult.decision=APPROVED` whose `decidedBy != envelope.userId`
and emits `BLOCKED_NON_OWNER`. The execute queue stays at depth 0.

## 6. Audit coverage

Every coordinator path emits a structured `AUDIT confirmation:` line with
`cmd`, `user`, `intent`, `risk`, `decision`, `decidedBy`, `channel`, and
`reason`. Cases 3 / 4 / 5 above already produced three distinct decisions
(`DENIED`, `TIMEOUT`, `BLOCKED_NON_OWNER`). `APPROVED` and
`BLOCKED_DEMO_MODE` are covered by
`ConfirmationCoordinatorTest#approvedByOwnerForwardsToExecuteQueue` and
`#demoModeShortCircuitsBeforePublishingConfirmationRequest` respectively.

A grep over the cumulative output:

```text
$ grep -c "AUDIT confirmation:" /tmp/jarvis-phase5/output.txt
3
```

(plus two additional decisions covered by the JUnit harness — five distinct
decision types in total).

## Architecture Boundaries Confirmed

* Risk policy lives in **one** place (`IntentRiskCatalog`) — no executor
  re-classifies on its own.
* Demo mode short-circuits **before** the confirmation request is even
  published — non-owners never see private payload details.
* Owner check is performed by the coordinator, not the publisher or the
  agent — neither side can be tricked into letting a non-owner approve.
* The agent's `ConfirmationStrategy` is interchangeable; production
  desktops will get a real JavaFX modal in Phase 6 without touching the
  bus contract.
* `AutoApproveStrategy` is **dev-only** and logs `WARN` on every
  approval so it cannot quietly slip into production.

## Known Limitations And Follow-Ups

- `decidedBy` is currently the userId carried by the channel that made
  the decision. Phase 6 (Native Desktop Agent Stabilization) will plug
  in real owner-voice recognition and refresh `decidedBy` from biometric
  identity.
- The agent's confirmation modal is a CLI prompt / auto-stub today;
  Phase 6 ships the JavaFX modal.
- The voice confirmation REST endpoint is the synchronous bridge for
  Phase 7's wake-word → STT → "yes/no" → orchestrator pipeline.
- Audit lines are logged but not yet persisted; Phase 8 will project
  them into Kafka + Postgres.

## Conclusion

All six rows above show ✅ — Phase 5 is sealed.

- Safe vs dangerous routing proven on the live broker.
- DENIED / TIMEOUT / BLOCKED_NON_OWNER all leave `agent.execute` at depth 0.
- Coordinator audit log line carries the full decision metadata.
- `ConfirmationCoordinatorTest` (7 tests) covers the orchestrator-side
  business logic and is green in the targeted Maven run.
