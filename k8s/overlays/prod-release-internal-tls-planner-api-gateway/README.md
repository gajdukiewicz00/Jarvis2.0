# Internal TLS Slice: `planner-service -> api-gateway`

This overlay layers on top of the already-verified
`prod-release-internal-tls-api-gateway-nlp` path.

Purpose:

- keep the existing `api-gateway -> nlp-service` HTTPS slice intact
- add one new internal HTTPS hop: `planner-service -> api-gateway`
- avoid changing the default `prod-release` path in place

What changes:

- `api-gateway` keeps HTTP on `8080` for ingress and non-migrated callers
- `api-gateway` adds a second internal HTTPS listener on `8443`
- `planner-service` calls `https://api-gateway.jarvis.svc.cluster.local:8443`
- `api-gateway` probes switch to the migrated HTTPS listener in this overlay
- network policy is patched narrowly for `planner-service -> api-gateway:8443`

What does not change:

- the default edge-TLS deployment path
- non-migrated callers of `api-gateway`
- any service other than `planner-service`
- mTLS

Prerequisites:

1. Generate and apply the first slice secret:
   `./scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh`
   `./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh`
2. Generate and apply this slice secret:
   `./scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh`
   `./scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh`
3. Generate the digest-pinned base release overlay:
   `./scripts/product/jarvis-promote-images.sh --push`
4. Deploy this slice:
   `./scripts/product/jarvis-deploy-prod-internal-tls-planner-api-gateway.sh`

Validate with:

- `./scripts/product/jarvis-rollout-validate.sh --overlay=./k8s/overlays/prod-release-internal-tls-planner-api-gateway`
- `./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh`
- `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`

This overlay is still partial internal TLS only. It is not full internal TLS and not mTLS.
