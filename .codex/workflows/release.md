# Release Workflow

Jarvis project convention.

## Checklist

- [ ] verify version bump or release identifier
- [ ] verify changelog or release notes
- [ ] run required tests
- [ ] verify docs honesty
- [ ] verify build artifacts
- [ ] verify runtime or rollout evidence
- [ ] write final release report

## Key Commands

- `mvn test`
- `./scripts/product/jarvis-build-release.sh`
- `./scripts/product/jarvis-promote-images.sh`
- `./scripts/product/jarvis-rollout-validate.sh`
- `./scripts/verify-prod.sh`

## Output

- artifact summary
- test evidence
- runtime evidence
- release verdict
