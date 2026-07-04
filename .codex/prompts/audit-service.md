# Audit Service Prompt

Jarvis project convention.

## Goal

Audit one Jarvis microservice for correctness, integration quality, security posture, operational readiness, and documentation honesty.

## Context Files To Read First

- `AGENTS.md`
- `README.md`
- `.codex/README.md`
- `docs/services/<service>.md`
- `apps/<service>/pom.xml`
- `application*.yml` for the service
- controllers, services, clients, migrations, and tests for the service

## Constraints

- default to analysis first
- verify claims from code and runtime wiring
- measure docs against implementation
- give a readiness percentage with reasons

## Allowed Changes

- none by default
- if asked, follow up with a focused remediation patch

## Forbidden Changes

- broad refactors during audit
- changing contracts without explicit approval
- assuming optional AI paths are core unless runtime wiring proves it

## Audit Scope

- API surface and controller routes
- service configuration and defaults
- DB ownership, Flyway migrations, and schema risk
- auth and security boundaries
- health and readiness behavior
- gateway and orchestrator integration
- external dependencies and client contracts
- tests and missing coverage
- operator docs versus reality

## Verification Commands

```bash
mvn -pl apps/<service> -am test
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh
```

Add targeted commands when relevant:

```bash
./scripts/analytics-smoke.sh
./scripts/voice-local-smoke.sh
./scripts/verify-prod.sh
```

## Final Report Format

### Service Overview

- responsibility and main entry points

### Findings

- severity-ordered issues

### Integration Notes

- gateway, orchestrator, database, and external dependencies

### Docs Drift

- what the docs say versus what the code does

### Readiness Score

- percentage
- blockers
- next actions
