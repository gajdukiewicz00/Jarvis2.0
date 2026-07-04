# Internal TLS Slice: `api-gateway -> pc-control`

This overlay layers on top of the committed
`prod-release-internal-tls-api-gateway-analytics-service` path.

It keeps all previously verified internal HTTPS hops intact and adds one new
internal HTTPS hop:

- keep `ingress -> api-gateway`
- keep `api-gateway -> nlp-service`
- keep `planner-service -> api-gateway`
- keep `orchestrator -> api-gateway`
- keep `voice-gateway -> api-gateway`
- keep `api-gateway -> security-service`
- keep `api-gateway -> analytics-service`
- migrate `api-gateway -> pc-control`

Behavior in this overlay:

- `pc-control` serves HTTPS on its existing service port `8084`
- `api-gateway` calls `https://pc-control.jarvis-prod.svc.cluster.local:8084`
- `pc-control` liveness/readiness/startup probes switch to HTTPS
- `api-gateway` reuses its existing Jarvis CA trust and does not add a new
  internal listener or change any other downstream URL

This is not full internal TLS and not mTLS.

Prepare the dedicated server-certificate secret:

1. `./scripts/product/jarvis-generate-internal-tls-api-gateway-pc-control.sh`
2. `./scripts/product/jarvis-apply-internal-tls-api-gateway-pc-control.sh`

Deploy and validate:

1. `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-pc-control.sh`
2. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-pc-control.sh`
3. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh`
4. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh`
5. `./scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh`
6. `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh`
7. `./scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh`
8. `./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh`
9. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`

The dedicated smoke proves:

- `pc-control` readiness responds on HTTPS
- `pc-control` no longer accepts plain HTTP on `8084` in this slice
- `api-gateway` completes a real pc-control action request against
  `https://pc-control.jarvis-prod.svc.cluster.local:8084`
