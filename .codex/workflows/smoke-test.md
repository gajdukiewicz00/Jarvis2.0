# Smoke Test Workflow

Jarvis project convention.

## Local Runtime Smoke

- [ ] run `./scripts/runtime-status.sh`
- [ ] run `./scripts/runtime-smoke.sh`
- [ ] collect failing service names, ports, and logs

## Kubernetes Smoke

- [ ] verify active overlay and target namespace
- [ ] run `./scripts/product/jarvis-rollout-validate.sh` when applicable
- [ ] run `./scripts/verify-prod.sh` when applicable

## Health And Readiness Checks

- [ ] verify health endpoints from service config before probing
- [ ] confirm readiness behavior for gateway-facing services
- [ ] collect evidence for any startup loops or partial readiness

## Gateway Checks

- [ ] verify `api-gateway` health
- [ ] verify critical routed paths
- [ ] note any service dependency failures

## Voice Checks

- [ ] run `./scripts/voice-local-smoke.sh` when voice paths are in scope
- [ ] verify WebSocket or voice pipeline assumptions from code and docs

## AI Optional Checks

- [ ] if AI stack is enabled, run `./scripts/ai-local-smoke.sh`
- [ ] add `./scripts/llm-smoke.sh` and `./scripts/memory-smoke.sh` as needed
- [ ] do not treat optional AI services as required unless the task scope does

## Logs To Collect

- [ ] failing service logs
- [ ] gateway logs for routed failures
- [ ] Kubernetes events and rollout output when applicable

## Output

- pass/fail summary
- commands run
- evidence paths
- blockers and next actions
