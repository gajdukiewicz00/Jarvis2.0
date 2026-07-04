# Internal TLS Slice 11: `api-gateway -> orchestrator`

This committed overlay layers on top of
`k8s/overlays/prod-release-internal-tls-api-gateway-smart-home-service` and
migrates exactly one additional internal HTTPS hop:

- `orchestrator` serves HTTPS on `8083`
- `api-gateway` calls `orchestrator` over
  `https://orchestrator.jarvis-prod.svc.cluster.local:8083`

Prepare and validate the slice with:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-orchestrator.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-orchestrator.sh
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-orchestrator.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh
```
