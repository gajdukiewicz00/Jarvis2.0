# Internal TLS Slice: `voice-gateway -> api-gateway`

This overlay layers on top of the already-verified
`prod-release-internal-tls-orchestrator-api-gateway` path.

Purpose:

- keep the existing `api-gateway -> nlp-service` HTTPS slice intact
- keep the existing `planner-service -> api-gateway` HTTPS slice intact
- keep the existing `orchestrator -> api-gateway` HTTPS slice intact
- add one new internal HTTPS hop: `voice-gateway -> api-gateway`
- reuse the existing `api-gateway` internal HTTPS listener on `8443`

What changes:

- `voice-gateway` calls `https://api-gateway.jarvis.svc.cluster.local:8443`
- `voice-gateway` mounts a dedicated truststore secret for the gateway CA
- network policy is patched narrowly so `voice-gateway` uses `api-gateway:8443`

What does not change:

- the default edge-TLS deployment path
- the existing `api-gateway` internal listener shape from prior slices
- non-migrated callers of `api-gateway`
- mTLS

Prerequisites:

1. Generate and apply the prior slice secrets:
   `./scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh`
   `./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh`
   `./scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh`
   `./scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh`
   `./scripts/product/jarvis-generate-internal-tls-orchestrator-api-gateway.sh`
   `./scripts/product/jarvis-apply-internal-tls-orchestrator-api-gateway.sh`
2. Generate and apply this slice secret:
   `./scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh`
   `./scripts/product/jarvis-apply-internal-tls-voice-gateway-api-gateway.sh`
3. Generate the digest-pinned base release overlay:
   `./scripts/product/jarvis-promote-images.sh --push`
4. Deploy this slice:
   `./scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-api-gateway.sh`

Validate with:

- `./scripts/product/jarvis-rollout-validate.sh --overlay=./k8s/overlays/prod-release-internal-tls-voice-gateway-api-gateway`
- `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh`
- `./scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh`
- `./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh`
- `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`

This overlay is still partial internal TLS only. It is not full internal TLS and not mTLS.
