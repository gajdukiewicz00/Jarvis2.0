# Review Prompt

Jarvis project convention.

## Goal

Perform a production-minded review of the requested change set or area with emphasis on correctness, architecture, security, tests, docs drift, and runtime risk.

## Context Files To Read First

- `AGENTS.md`
- `README.md`
- `.codex/README.md`
- relevant files under `docs/services/`
- relevant module `pom.xml`
- relevant `application*.yml`
- relevant scripts under `scripts/`

## Constraints

- review before proposing fixes
- prefer code review findings over summaries
- distinguish verified facts from inference
- treat code and runtime wiring as source of truth
- call out missing tests and documentation drift explicitly

## Allowed Changes

- none by default
- if the user explicitly asks for fixes after review, make only targeted patches

## Forbidden Changes

- broad refactors during review
- unrelated cleanup
- secret handling changes without clear justification
- claiming runtime safety without evidence

## Review Checklist

- correctness and edge cases
- architecture boundaries between gateway, orchestrator, services, desktop, and optional AI stack
- auth, secrets, unsafe command paths, and network exposure
- test coverage and broken assumptions
- docs honesty versus code
- operational or release risk

## Verification Commands

Use the smallest honest set that matches the reviewed area:

```bash
git status --short
mvn -pl apps/<module> -am test
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh
./scripts/verify-observability.sh
```

For Kubernetes or release-facing work, add:

```bash
./scripts/verify-prod.sh
./scripts/product/jarvis-rollout-validate.sh
```

## Final Report Format

### Findings

- severity-ordered issues with file references

### Open Questions

- assumptions that need confirmation

### Verification

- commands run
- what passed
- what was not run

### Risk Summary

- runtime risk
- release risk
- docs drift risk
