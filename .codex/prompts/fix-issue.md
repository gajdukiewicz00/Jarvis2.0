# Fix Issue Prompt

Jarvis project convention.

## Goal

Fix one concrete issue with the smallest honest patch that reproduces the problem first, finds the root cause, and proves the fix with evidence.

## Context Files To Read First

- `AGENTS.md`
- `README.md`
- `.codex/README.md`
- relevant service doc under `docs/services/`
- relevant code, config, scripts, and tests

## Constraints

- reproduce before patching when feasible
- isolate the root cause before editing
- keep the patch minimal
- update tests or add focused coverage when practical
- keep docs aligned if behavior changes

## Allowed Changes

- targeted source fix
- focused test updates
- focused config or script updates
- matching docs updates

## Forbidden Changes

- unrelated refactors
- speculative changes without reproduction or evidence
- hidden behavior changes outside the issue scope
- adding secrets or private paths

## Workflow

1. define the exact failing symptom
2. reproduce it with a command, test, or log trace
3. locate root cause in code or config
4. patch only the needed files
5. run the smallest verification that proves the fix
6. update docs if user-facing or operator-facing behavior changed

## Verification Commands

Choose the narrowest matching set:

```bash
mvn -pl apps/<module> -am test
./scripts/runtime-smoke.sh
./scripts/voice-local-smoke.sh
./scripts/llm-smoke.sh
./scripts/memory-smoke.sh
```

## Final Report Format

### Issue

- original symptom

### Reproduction

- exact command or evidence

### Root Cause

- where the bug lived and why it happened

### Fix

- minimal change summary

### Verification

- commands run
- before/after evidence

### Residual Risk

- anything not covered by tests or runtime checks
