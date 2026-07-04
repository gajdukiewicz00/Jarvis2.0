# ADR-0004: Production Namespace Is jarvis-prod

- Status: Accepted
- Date: `2026-04-28`

## Decision

`jarvis-prod` is the only production namespace.

## Context

The repo contains production overlays, launcher paths, release scripts, smoke scripts, and operational docs that historically defaulted to `jarvis`.

That creates ambiguity between:

- production
- development
- staging
- legacy namespaces

Phase 0 needs a single production namespace so that production docs, scripts, and release overlays stop drifting.

## Decision Details

- production manifests, production overlays, production rollout scripts, and production documentation must converge on `jarvis-prod`
- non-production namespaces such as staging, dev, or legacy namespaces may remain where they are intentionally part of test or development workflows
- Phase 0 does not blindly rewrite every test namespace
- production-facing defaults must stop presenting `jarvis` as the primary production namespace

## Consequences

- production instructions become less ambiguous
- generated release overlays, rollout validation, and operational scripts can share one explicit production target
- old `jarvis` references in production paths become migration debt to remove over time

## Non-Decision

This ADR does not claim that every non-production namespace must be renamed in Phase 0.

It only states that production convergence must land on `jarvis-prod`.
