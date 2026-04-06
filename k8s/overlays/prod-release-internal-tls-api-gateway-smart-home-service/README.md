# Internal TLS Slice 10: `api-gateway -> smart-home-service`

This committed overlay layers on top of
`k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker` and migrates
exactly one additional internal HTTPS hop:

- `smart-home-service` serves HTTPS on `8086`
- `api-gateway` calls `smart-home-service` over
  `https://smart-home-service.jarvis.svc.cluster.local:8086`

Prepare and validate the slice with:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-smart-home-service.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-smart-home-service.sh
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-smart-home-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh
```
