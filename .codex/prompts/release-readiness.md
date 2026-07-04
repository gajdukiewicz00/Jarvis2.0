# Release Readiness Prompt

Jarvis project convention.

## Goal

Decide whether the repository or change set is honestly ready for release, with a final GO or NO-GO verdict backed by build, docs, runtime, and security evidence.

## Context Files To Read First

- `AGENTS.md`
- `README.md`
- `.codex/README.md`
- `k8s/README.md`
- `docs/security/SECRETS_POLICY.md`
- relevant service docs
- product scripts under `scripts/product/`

## Constraints

- no optimism without evidence
- prefer NO-GO over weak assumptions
- verify release scripts and artifacts from repo content
- distinguish local launcher behavior from reproducible release overlays

## Allowed Changes

- release documentation fixes
- targeted packaging or script fixes
- versioning or metadata fixes

## Forbidden Changes

- hidden scope expansion
- declaring release readiness without smoke evidence
- claiming mutable overlay input is a release artifact

## Readiness Checklist

- versioning and release metadata
- reproducible deployment path
- module and integration tests
- docs honesty
- runtime smoke
- security and secret handling review
- artifact and image workflow sanity
- final rollback confidence

## Verification Commands

```bash
mvn test
./scripts/runtime-smoke.sh
./scripts/verify-prod.sh
./scripts/product/jarvis-build-release.sh
./scripts/product/jarvis-rollout-validate.sh
```

Add image-promotion checks when relevant:

```bash
./scripts/product/jarvis-promote-images.sh
```

## Final Report Format

### Verdict

- GO or NO-GO

### Evidence

- commands run and outcomes

### Release Risks

- blocking risks
- non-blocking follow-ups

### Docs And Artifact Honesty

- what matches reality
- what still needs correction
