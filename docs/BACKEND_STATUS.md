# Backend Readiness Status

Last verified: **2026-03-27**

This file is no longer the canonical source of truth for the Jarvis core backend.

Use these files for core backend truth:

- `CORE_BACKEND_REALITY.md`
- `CORE_BACKEND_ARCHITECTURE.md`
- `CORE_BACKEND_OPERATIONS.md`
- `CORE_BACKEND_GAPS.md`

This file now tracks the broader backend release/deployment picture around that core.

## Current State

What was verified in this pass:

- full Maven test pass across the declared core backend services
- local core runtime smoke without optional LLM/memory
- analytics smoke on top of a real core runtime
- core-scoped CI workflow wiring in `.github/workflows/backend-readiness.yml`
- core-only default image promotion in `scripts/product/jarvis-promote-images.sh`

## What This File Covers

- broader deployment and release posture
- Kubernetes release overlays and rollout tooling
- internal TLS release track
- items that are wider than the core backend contract itself

## Current Source Of Truth Boundary

- `k8s/base` matches the core backend service set
- `.github/workflows/backend-readiness.yml` is the core backend CI gate
- `k8s/overlays/prod` is broader than core scope and still contains optional workloads scaled to zero
- `k8s/overlays/prod-release*` remain deployment artifacts or broader release targets, not the definition of core backend scope

## CI And Smoke Policy

Core backend readiness CI now contains three first-class checks:

- `release-wiring`
- `runtime-core-smoke`
- `runtime-analytics-smoke`

Optional LLM/memory verification remains intentionally outside the core backend gate.

## Release Overlay Policy

`scripts/product/jarvis-promote-images.sh` now promotes core backend images by default.

Use optional flags only when you explicitly want the broader stacks in the release overlay:

- `--include-data`
- `--include-llm`

## Remaining Broader Release Gap

The main broader release/deployment gap is unchanged:

- there is still no dedicated production overlay that contains only the core backend and nothing else

That is a real release-clarity problem, but it is now documented as a broader deployment issue instead of being conflated with the core backend runtime itself.
