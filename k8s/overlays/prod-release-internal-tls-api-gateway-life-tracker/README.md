# Internal TLS Slice 9: `api-gateway -> life-tracker`

This committed overlay layers on top of
`k8s/overlays/prod-release-internal-tls-api-gateway-pc-control` and migrates
exactly one additional internal HTTPS hop:

- `life-tracker` serves HTTPS on `8085`
- `api-gateway` calls `life-tracker` over
  `https://life-tracker.jarvis.svc.cluster.local:8085`

Prepare and validate the slice with:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-life-tracker.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-life-tracker.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-life-tracker.sh
```
