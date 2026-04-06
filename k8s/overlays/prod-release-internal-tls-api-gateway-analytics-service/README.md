# Internal TLS Slice: `api-gateway -> analytics-service`

This overlay layers on top of the committed
`prod-release-internal-tls-api-gateway-security-service` path.

It keeps all previously verified internal HTTPS hops intact and adds one new
internal HTTPS hop:

- keep `ingress -> api-gateway`
- keep `api-gateway -> nlp-service`
- keep `planner-service -> api-gateway`
- keep `orchestrator -> api-gateway`
- keep `voice-gateway -> api-gateway`
- keep `api-gateway -> security-service`
- migrate `api-gateway -> analytics-service`

Behavior in this overlay:

- `analytics-service` serves HTTPS on its existing service port `8087`
- `api-gateway` calls `https://analytics-service.jarvis.svc.cluster.local:8087`
- `analytics-service` liveness/readiness/startup probes switch to HTTPS
- `api-gateway` reuses its existing Jarvis CA trust and does not add a new
  internal listener or change any other downstream URL

This is not full internal TLS and not mTLS.

Prepare the dedicated server-certificate secret:

1. `./scripts/product/jarvis-generate-internal-tls-api-gateway-analytics-service.sh`
2. `./scripts/product/jarvis-apply-internal-tls-api-gateway-analytics-service.sh`

Deploy and validate:

1. `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-analytics-service.sh`
2. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh`
3. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh`
4. `./scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh`
5. `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh`
6. `./scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh`
7. `./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh`
8. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`

The dedicated smoke proves:

- `analytics-service` readiness responds on HTTPS
- `analytics-service` no longer accepts plain HTTP on `8087` in this slice
- `api-gateway` completes a real analytics overview request against
  `https://analytics-service.jarvis.svc.cluster.local:8087`
