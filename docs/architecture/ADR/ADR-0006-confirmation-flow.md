# ADR-0006: Confirmation flow for dangerous commands

## Status

Accepted (Phase 5).

## Context

Phase 4 introduced a guaranteed-delivery command bus (RabbitMQ) with the
[envelope schema](ADR-0005-command-pipeline-rabbitmq.md). All commands
flowed straight to the agent execute queue regardless of risk.

SPEC-1 mandates that **dangerous commands must require explicit owner
confirmation**:

> Dangerous commands include: deleting files, sending messages,
> downloading files, running shell commands, spending money, opening
> doors, shutting down/rebooting/sleeping the PC, changing security
> settings, modifying persistent memory in bulk.

> If the speaker is not recognized as the owner, Jarvis must enter demo
> mode. In demo mode, Jarvis can answer general questions and show safe
> capabilities, but must not execute privileged local actions or expose
> private data.

Phase 5's job is to plumb that gate into the existing pipeline without
reinventing the bus.

## Decision

We split command dispatch into two paths inside the orchestrator:

```
                     ┌── SAFE / LOW ──────────────► execute queue (Phase 4)
CommandPublisher ────┤
.dispatch()          └── MEDIUM+ ─► ConfirmationCoordinator
                                       │
                       ┌──────────────┴───────────┐
                       ▼                           ▼
              demo mode? ─yes─► REJECTED      publish ConfirmationRequest
                                              ▼
                                              wait on confirmation.result
                                              ▼
                                ┌─APPROVED─► execute queue (Phase 4)
                                ├─DENIED────► REJECTED
                                ├─TIMEOUT───► REJECTED
                                └─non-owner─► REJECTED
```

Concrete pieces:

* `IntentRiskCatalog` — central authority for `intent → RiskClassification`
  (`RiskLevel` + optional `DangerousAction`). Unknown intents default to
  MEDIUM (i.e. require confirmation) — fail-safe by design.
* `RiskClassification.requiresConfirmation()` is `riskLevel >= MEDIUM`.
* `ConfirmationCoordinator` owns the staged pending future, demo-mode
  short-circuit, owner check, and the APPROVED → execute fan-out.
* `PendingConfirmationRegistry` mirrors the Phase 4
  `PendingCommandRegistry` shape: ConcurrentHashMap, scheduled sweeper,
  promotes timeouts to `REJECTED` so callers don't block forever.
* `ConfirmationResultListener` consumes
  `jarvis.commands.confirmation.result` and delegates to the coordinator.
* `DemoModeProperties` (`jarvis.demo-mode.enabled`) — when true, the
  coordinator does not even publish a confirmation request; it auto-rejects
  with `BLOCKED_DEMO_MODE` and writes an audit line.
* Owner check: `ConfirmationResult.decidedBy` MUST equal
  `CommandEnvelope.userId`. Mismatch → `BLOCKED_NON_OWNER`. Phase 6 will
  populate `decidedBy` from owner-voice recognition; for now the JWT
  subject of the confirmation channel is used.

The agent side ships three pluggable
`ConfirmationStrategy` implementations:

* `AutoApproveStrategy` (default in dev/smoke)
* `AutoDenyStrategy` (paranoid baseline / tests)
* `CliPromptStrategy` (interactive Y/N on stdin)

Phase 6 will replace these with a real JavaFX modal + voice prompt without
changing the bus or the coordinator.

## Consequences

* `CommandPublisher.dispatch(...)` callers no longer need to specify a
  risk level — the catalog is authoritative. The legacy 4-arg overload
  with `RiskLevel` is retained but **cannot down-grade** the catalog's
  classification (max of hint and catalog wins).
* Every dangerous decision now produces a structured audit log line with
  `cmd / user / intent / risk / decision / decidedBy / channel / reason`.
  Phase 8 will project these into Postgres + Kafka audit events.
* The two confirmation queues declared in Phase 4 (`...request`,
  `...result`) are now active and fully wired.
* `voice-gateway` exposes
  `POST /api/v1/voice/confirmations` for voice-channel decisions. The
  endpoint forwards to `confirmation.result`; full STT/TTS round-trip is
  Phase 7.
* `apps/desktop-javafx` agent sidecar (`AgentMain.kt`) now starts both
  `CommandConsumer` (execute queue) and `ConfirmationConsumer` (request
  queue) using the env-selected strategy.
* Risk coverage in this catalog is the foundation for Phase 7's voice
  loop: every intent the orchestrator wants to execute MUST be added to
  the catalog explicitly; otherwise it falls into the default MEDIUM
  bucket and asks for confirmation.

## Alternatives considered

* **Risk in the consumer instead of the orchestrator.** Rejected.
  Centralising risk in the orchestrator keeps a single source of truth
  and lets us reuse the same gate for all channels (voice, desktop UI,
  mobile, scheduled).
* **Synchronous JWT-only owner check, no confirmation queue.** Rejected.
  Owner-as-JWT is necessary but not sufficient: SPEC-1 wants a human
  decision moment for destructive actions, even when the JWT is valid.
* **Per-intent confirmation policy hardcoded in the executor.** Rejected.
  Couples policy to implementation; can't easily relax per-deployment.

## References

* SPEC-1 § "Command Safety Model"
* SPEC-1 § Phase 5
* `apps/orchestrator/.../command/risk/IntentRiskCatalog.java`
* `apps/orchestrator/.../command/confirmation/ConfirmationCoordinator.java`
* [ADR-0005](ADR-0005-command-pipeline-rabbitmq.md)
* [phase-5-acceptance-evidence.md](../phase-5-acceptance-evidence.md)
