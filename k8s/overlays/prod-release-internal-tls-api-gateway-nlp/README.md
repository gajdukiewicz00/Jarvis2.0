# Internal TLS Slice: `api-gateway -> nlp-service`

This overlay is a committed migration path, not the default production path.

Purpose:

- keep the verified `prod-release` deployment path intact
- switch one internal service-to-service slice from HTTP to HTTPS
- prove truststore, keystore, and HTTPS probe wiring before wider rollout

What changes:

- `api-gateway` becomes an HTTPS client for `nlp-service`
- `nlp-service` becomes an HTTPS-only server on port `8082`
- `nlp-service` probes switch to HTTPS
- all other in-cluster traffic remains HTTP

What does not change:

- edge TLS / ingress behavior
- `api-gateway` as an internal server listener
- any other backend service-to-service path
- mTLS

Prerequisites:

1. Generate and apply the internal TLS secret:
   `./scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh`
   `./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh`
2. Generate the digest-pinned base release overlay:
   `./scripts/product/jarvis-promote-images.sh --push`
3. Deploy this slice:
   `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-nlp.sh`

Validate with:

- `./scripts/product/jarvis-rollout-validate.sh --overlay=./k8s/overlays/prod-release-internal-tls-api-gateway-nlp`
- `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`

This overlay is partial internal TLS only. It is not full internal TLS and not mTLS.
