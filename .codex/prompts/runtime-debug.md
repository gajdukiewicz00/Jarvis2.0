# Runtime Debug Prompt

Jarvis project convention.

## Goal

Debug a runtime failure with reproducible evidence from logs, scripts, ports, health checks, and environment-specific differences between local and Kubernetes execution.

## Context Files To Read First

- `AGENTS.md`
- `README.md`
- `.codex/README.md`
- relevant `docs/services/<service>.md`
- `scripts/runtime/common.sh`
- relevant runtime or product scripts
- relevant `application*.yml`
- relevant k8s manifests

## Constraints

- start from the failing symptom, not a guessed fix
- record exact commands and outputs
- compare local and Kubernetes behavior explicitly when the issue crosses both
- verify port, startup, and dependency assumptions from repo files

## Allowed Changes

- targeted code, config, or script fixes once root cause is clear
- logging improvements when they directly help diagnosis
- doc corrections when runbooks are wrong

## Forbidden Changes

- blind retries presented as fixes
- changing multiple runtime layers at once without isolating the problem
- secret changes or host-specific changes without explanation

## Debug Checklist

- collect logs
- identify the failing script or entry point
- confirm ports from `scripts/runtime/common.sh`
- probe health endpoints and gateway paths
- compare local-only services versus Kubernetes-deployed services
- identify environment-specific dependencies
- capture exact commands and timestamps

## Verification Commands

```bash
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh
./scripts/voice-local-smoke.sh
./scripts/verify-observability.sh
./scripts/verify-prod.sh
./jarvis-logs.sh
```

Health endpoint paths should be verified from each service config before treating them as standard.

## Final Report Format

### Symptom

- what failed and where

### Reproduction

- exact commands

### Evidence

- logs, ports, probes, and config references

### Root Cause

- verified cause, not guesswork

### Fix

- patch or operational correction

### Verification

- commands rerun and results
