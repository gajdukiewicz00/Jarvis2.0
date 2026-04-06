# Internal TLS Slice: `ingress -> api-gateway`

This overlay layers on top of the committed
`prod-release-internal-tls-voice-gateway-api-gateway` path.

It keeps all previously verified internal HTTPS hops intact and adds one new
internal HTTPS hop:

- keep `api-gateway -> nlp-service`
- keep `planner-service -> api-gateway`
- keep `orchestrator -> api-gateway`
- keep `voice-gateway -> api-gateway`
- migrate `ingress-nginx -> api-gateway`

Behavior in this overlay:

- `api-gateway` reuses the existing internal HTTPS listener on `8443`
- `jarvis-ingress` switches all `api-gateway` backends from `8080` to `8443`
- nginx ingress uses `nginx.ingress.kubernetes.io/backend-protocol: HTTPS`
- `api-gateway` ingress allowlist is narrowed so ingress-nginx reaches `8443`
- edge HTTPS and WSS must still work after the change

This is not full internal TLS and not mTLS.

Prepare prerequisites already required by prior slices:

1. `./scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh`
2. `./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh`
3. `./scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh`
4. `./scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh`
5. `./scripts/product/jarvis-generate-internal-tls-orchestrator-api-gateway.sh`
6. `./scripts/product/jarvis-apply-internal-tls-orchestrator-api-gateway.sh`
7. `./scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh`
8. `./scripts/product/jarvis-apply-internal-tls-voice-gateway-api-gateway.sh`

Deploy and validate:

1. `./scripts/product/jarvis-deploy-prod-internal-tls-ingress-api-gateway.sh`
2. `./scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh`
3. `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh`
4. `./scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh`
5. `./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh`
6. `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`

The dedicated smoke proves:

- the live ingress object uses `backend-protocol: HTTPS`
- every `jarvis-ingress` backend for `api-gateway` points to port `8443`
- edge HTTPS still works through ingress
- edge WSS still upgrades successfully through ingress
