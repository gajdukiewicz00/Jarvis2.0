# Deploy Workflow

Jarvis project convention.

## Pre-Deploy Checks

- [ ] confirm target environment and namespace
- [ ] verify image/build provenance
- [ ] verify required secrets are managed outside git
- [ ] verify release overlay or deployment path is the intended one

## Build And Image Verification

- [ ] run relevant build and test steps
- [ ] verify image tags or digests match the intended release
- [ ] avoid mutable-image assumptions in final reports

## Kubernetes Apply Or Reconcile

- [ ] apply or launch using the approved deploy path
- [ ] capture command output
- [ ] record rollout targets

## Readiness Wait

- [ ] wait for pods, services, and ingress readiness
- [ ] inspect rollout failures before retrying

## Smoke

- [ ] run smoke checks after readiness
- [ ] capture endpoint and health evidence

## Rollback Notes

- [ ] identify rollback command or overlay
- [ ] record what would trigger rollback

## Output

- deploy command log
- readiness evidence
- smoke evidence
- rollback plan
