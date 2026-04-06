# Internal TLS Slice 12: `api-gateway -> voice-gateway`

This committed overlay layers on top of
`k8s/overlays/prod-release-internal-tls-api-gateway-orchestrator` and migrates
exactly one additional internal HTTPS hop:

- `voice-gateway` serves HTTPS on `8081`
- `api-gateway` calls `voice-gateway` over
  `https://voice-gateway.jarvis.svc.cluster.local:8081`

Prepare and validate the slice with:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-voice-gateway.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-voice-gateway.sh
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-voice-gateway.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh
```
