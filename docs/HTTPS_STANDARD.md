# HTTPS / TLS Standard

Last verified: **2026-03-23**

This document is the TLS source of truth for the in-scope backend.

It distinguishes three different models on purpose:

- `edge TLS`: TLS terminates at ingress or another public entrypoint
- `internal TLS`: service-to-service traffic inside the runtime/cluster also uses HTTPS
- `mTLS`: both sides of an internal connection authenticate each other with client/server certificates

Do not treat those as interchangeable.

## Current Model

### 1. Edge TLS

This is implemented and verified.

- External REST entrypoint: `https://api.jarvis.local`
- External voice websocket entrypoint: `wss://voice.jarvis.local/ws/voice`
- Kubernetes ingress terminates TLS with secret `jarvis-tls`
- HTTP to HTTPS redirect is enforced by ingress
- `api-gateway` now honors forwarded headers with `server.forward-headers-strategy=framework`

Verified in this environment:

- `http://api.jarvis.local/...` returns `308` to `https://api.jarvis.local/...`
- `https://api.jarvis.local/actuator/health` returns `200`
- authenticated `wss://voice.jarvis.local/ws/voice` handshake succeeds

### 2. Internal TLS

This is partially implemented and verified in twenty-one narrow internal HTTPS hops.

Implemented and verified now:

- local runtime can serve the public gateway over HTTPS/WSS with `JARVIS_USE_TLS=true`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-nlp`
  migrates `api-gateway -> nlp-service` from HTTP to HTTPS
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-planner-api-gateway`
  layers on top of the first slice and migrates `planner-service -> api-gateway`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-orchestrator-api-gateway`
  layers on top of the second slice and migrates `orchestrator -> api-gateway`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-voice-gateway-api-gateway`
  layers on top of the third slice and migrates `voice-gateway -> api-gateway`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-ingress-api-gateway`
  layers on top of the fourth slice and migrates `ingress -> api-gateway`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-security-service`
  layers on top of the ingress slice and migrates `api-gateway -> security-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-analytics-service`
  layers on top of the security-service slice and migrates `api-gateway -> analytics-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-pc-control`
  layers on top of the analytics slice and migrates `api-gateway -> pc-control`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker`
  layers on top of the pc-control slice and migrates `api-gateway -> life-tracker`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-smart-home-service`
  layers on top of the life-tracker slice and migrates `api-gateway -> smart-home-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-orchestrator`
  layers on top of the smart-home slice and migrates `api-gateway -> orchestrator`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-voice-gateway`
  layers on top of the orchestrator slice and migrates `api-gateway -> voice-gateway`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-voice-gateway-orchestrator`
  layers on top of the gateway/voice slice and migrates `voice-gateway -> orchestrator`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-analytics-service-life-tracker`
  layers on top of the voice/orchestrator slice and migrates `analytics-service -> life-tracker`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-orchestrator-nlp-service`
  layers on top of the analytics/life-tracker slice and migrates `orchestrator -> nlp-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-planner-service-analytics-service`
  layers on top of the orchestrator/nlp slice and migrates `planner-service -> analytics-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-api-gateway-planner-service`
  layers on top of the planner/analytics slice and migrates `api-gateway -> planner-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-planner-service-voice-gateway`
  layers on top of the gateway/planner slice and migrates `planner-service -> voice-gateway`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-voice-gateway-smart-home-service`
  layers on top of the planner/voice slice and migrates `voice-gateway -> smart-home-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-orchestrator-smart-home-service`
  layers on top of the voice/smart-home slice and migrates `orchestrator -> smart-home-service`
- the dedicated overlay `k8s/overlays/prod-release-internal-tls-orchestrator-pc-control`
  layers on top of the orchestrator/smart-home slice and migrates `orchestrator -> pc-control`
- the promoted overlay `k8s/overlays/prod-release-internal-tls-verified`
  carries forward that exact twenty-one-hop state as one first-class deploy target
- in that overlay:
  - `nlp-service` serves HTTPS only on port `8082`
  - `api-gateway` talks to `https://nlp-service.jarvis.svc.cluster.local:8082`
  - `nlp-service` liveness/readiness/startup probes use HTTPS
  - trust is provided by secret `jarvis-internal-tls-api-gateway-nlp`
- in the second overlay:
  - `api-gateway` keeps internal HTTP on `8080` for non-migrated callers
  - `api-gateway` also serves internal HTTPS on `8443`
  - `planner-service` talks to `https://api-gateway.jarvis.svc.cluster.local:8443`
  - `api-gateway` liveness/readiness/startup probes use HTTPS on `8443`
  - trust is provided by secret `jarvis-internal-tls-planner-api-gateway`
- in the third overlay:
  - `api-gateway` reuses the existing internal HTTPS listener on `8443`
  - `orchestrator` talks to `https://api-gateway.jarvis.svc.cluster.local:8443`
  - trust is provided by secret `jarvis-internal-tls-orchestrator-api-gateway`
- in the fourth overlay:
  - `api-gateway` still reuses the existing internal HTTPS listener on `8443`
  - `voice-gateway` talks to `https://api-gateway.jarvis.svc.cluster.local:8443`
  - trust is provided by secret `jarvis-internal-tls-voice-gateway-api-gateway`
- in the fifth overlay:
  - `jarvis-ingress` uses `nginx.ingress.kubernetes.io/backend-protocol: HTTPS`
  - every `jarvis-ingress` backend for `api-gateway` points to `8443`
  - `api-gateway` still reuses the existing internal HTTPS listener on `8443`
  - edge HTTPS and WSS continue to work through ingress after the migration
- in the sixth overlay:
  - `security-service` serves HTTPS only on port `8088`
  - `api-gateway` talks to `https://security-service.jarvis.svc.cluster.local:8088`
  - `security-service` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `security-service` is provided by secret
    `jarvis-internal-tls-api-gateway-security-service`
- in the seventh overlay:
  - `analytics-service` serves HTTPS only on port `8087`
  - `api-gateway` talks to `https://analytics-service.jarvis.svc.cluster.local:8087`
  - `analytics-service` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `analytics-service` is provided by secret
    `jarvis-internal-tls-api-gateway-analytics-service`
- in the eighth overlay:
  - `pc-control` serves HTTPS only on port `8084`
  - `api-gateway` talks to `https://pc-control.jarvis.svc.cluster.local:8084`
  - `pc-control` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `pc-control` is provided by secret
    `jarvis-internal-tls-api-gateway-pc-control`
- in the ninth overlay:
  - `life-tracker` serves HTTPS only on port `8085`
  - `api-gateway` talks to `https://life-tracker.jarvis.svc.cluster.local:8085`
  - `life-tracker` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `life-tracker` is provided by secret
    `jarvis-internal-tls-api-gateway-life-tracker`
- in the tenth overlay:
  - `smart-home-service` serves HTTPS only on port `8086`
  - `api-gateway` talks to `https://smart-home-service.jarvis.svc.cluster.local:8086`
  - `smart-home-service` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `smart-home-service` is provided by secret
    `jarvis-internal-tls-api-gateway-smart-home-service`
- in the eleventh overlay:
  - `orchestrator` serves HTTPS only on port `8083`
  - `api-gateway` talks to `https://orchestrator.jarvis.svc.cluster.local:8083`
  - `orchestrator` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `orchestrator` is provided by secret
    `jarvis-internal-tls-api-gateway-orchestrator`
- in the twelfth overlay:
  - `voice-gateway` serves HTTPS only on port `8081`
  - `api-gateway` talks to `https://voice-gateway.jarvis.svc.cluster.local:8081`
  - `voice-gateway` liveness/readiness probes use HTTPS
  - `api-gateway` reuses the existing Jarvis CA trust mounted for the first slice
  - trust and keystore material for `voice-gateway` is provided by secret
    `jarvis-internal-tls-api-gateway-voice-gateway`
- the verified commands for that slice are:
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-nlp.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh`
  - `./scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh`
  - `./scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-planner-api-gateway.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh`
  - `./scripts/product/jarvis-generate-internal-tls-orchestrator-api-gateway.sh`
  - `./scripts/product/jarvis-apply-internal-tls-orchestrator-api-gateway.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-api-gateway.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh`
  - `./scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh`
  - `./scripts/product/jarvis-apply-internal-tls-voice-gateway-api-gateway.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-api-gateway.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-ingress-api-gateway.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-security-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-security-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-security-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-analytics-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-analytics-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-analytics-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-pc-control.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-pc-control.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-pc-control.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-pc-control.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-life-tracker.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-life-tracker.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-life-tracker.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-voice-gateway.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-voice-gateway.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-voice-gateway.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh`
  - `./scripts/product/jarvis-generate-internal-tls-voice-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-apply-internal-tls-voice-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-orchestrator.sh`
  - `./scripts/product/jarvis-generate-internal-tls-analytics-service-life-tracker.sh`
  - `./scripts/product/jarvis-apply-internal-tls-analytics-service-life-tracker.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-analytics-service-life-tracker.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-analytics-service-life-tracker.sh`
  - `./scripts/product/jarvis-generate-internal-tls-orchestrator-nlp-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-orchestrator-nlp-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-nlp-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-orchestrator-nlp-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-planner-service-analytics-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-planner-service-analytics-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-planner-service-analytics-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-planner-service-analytics-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-api-gateway-planner-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-api-gateway-planner-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-planner-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-api-gateway-planner-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-planner-service-voice-gateway.sh`
  - `./scripts/product/jarvis-apply-internal-tls-planner-service-voice-gateway.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-planner-service-voice-gateway.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-planner-service-voice-gateway.sh`
  - `./scripts/product/jarvis-generate-internal-tls-voice-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-voice-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-voice-gateway-smart-home-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-orchestrator-smart-home-service.sh`
  - `./scripts/product/jarvis-apply-internal-tls-orchestrator-smart-home-service.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-smart-home-service.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-orchestrator-smart-home-service.sh`
  - `./scripts/product/jarvis-generate-internal-tls-orchestrator-pc-control.sh`
  - `./scripts/product/jarvis-apply-internal-tls-orchestrator-pc-control.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-pc-control.sh`
  - `./scripts/product/jarvis-smoke-internal-tls-orchestrator-pc-control.sh`
  - `./scripts/product/jarvis-deploy-prod-internal-tls-verified.sh`
  - `./scripts/product/jarvis-smoke-prod-internal-tls-verified.sh`

Prepared now:

- local certificate generation creates:
  - `~/.jarvis/tls/jarvis-ca.crt`
  - `~/.jarvis/tls/jarvis.crt`
  - `~/.jarvis/tls/jarvis.key`
  - `~/.jarvis/tls/jarvis-keystore.p12`
  - `~/.jarvis/tls/jarvis-cacerts.jks`
- the shared server certificate now includes:
  - edge DNS names
  - `localhost` / `127.0.0.1`
  - backend service DNS SANs for a future internal-TLS migration
- local runtime smoke follows `https://127.0.0.1:8080` and `wss://127.0.0.1:8080/...` when that mode is enabled

Remaining and intentionally not closed in this pass:

### Category A — Real Next Closures After Contract Work

- `planner-service -> user-profile`
  placeholder client with fake goal data; no real planner/user-profile contract
  was migrated yet
- `planner-service -> life-tracker`
  health-only placeholder client; no real planner/life-tracker DTO contract was
  migrated yet

### Category B — Closeable Only After Service Strategy / Rollout Redesign

- `user-profile` listener and probes on `8089`
  changing the service itself now would require either a dual-listener rollout
  or a coordinated cutover for remaining consumers, including the excluded
  `llm-service -> user-profile` path

### Category C — Optional / Model-Adjacent

- `memory-service -> embedding-service`
  optional model-adjacent path, still HTTP while those workloads stay scaled to
  zero in the promoted overlay

### Category D — Explicitly Excluded From The Current Verified Scope

- excluded stack:
  `llm-service -> llm-server`, `llm-service -> memory-service`,
  `llm-service -> user-profile`

### 3. Full mTLS

This is not implemented.

Missing for mTLS:

- per-service certificates or SPIFFE-like identities
- client certificate presentation on internal calls
- mutual certificate validation policy between services
- cert rotation/distribution machinery for workload identities

## Current Boundary

### Kubernetes / prod-like path

- Edge TLS: `yes`
- Internal TLS: `partial`
- mTLS: `no`

Default `prod-release` traffic model:

```text
External client
  -> HTTPS / WSS
Ingress
  -> HTTP
api-gateway
  -> HTTP / WS
backend services
```

Verified incremental internal-TLS slices:

```text
External client
  -> HTTPS / WSS
Ingress
  -> HTTPS
api-gateway
  -> HTTPS
nlp-service
```

```text
planner-service
  -> HTTPS
api-gateway
  -> HTTPS
nlp-service
```

```text
orchestrator
  -> HTTPS
api-gateway
```

```text
voice-gateway
  -> HTTPS
api-gateway
```

```text
ingress-nginx
  -> HTTPS
api-gateway
```

```text
api-gateway
  -> HTTPS
security-service
```

```text
api-gateway
  -> HTTPS
analytics-service
```

Everything outside those seven migrated hops still remains HTTP.

### Local runtime path

Two supported modes now exist:

- direct local HTTP mode:
  - `JARVIS_USE_TLS=false`
  - public gateway URL: `http://127.0.0.1:8080`
  - websocket URL: `ws://127.0.0.1:8080/ws/voice`
- local self-signed HTTPS mode:
  - `JARVIS_USE_TLS=true`
  - public gateway URL: `https://127.0.0.1:8080`
  - websocket URL: `wss://127.0.0.1:8080/ws/voice`
  - requires certificate material from `scripts/product/jarvis-generate-certs.sh`

This local TLS mode secures the public gateway hop only. The backend services behind
that gateway remain plain HTTP unless a separate internal TLS migration is completed.

## Certificate Material

Generate local CA, edge certs, local gateway keystore, and Java truststore:

```bash
./scripts/product/jarvis-generate-certs.sh
```

If your local certificate predates the expanded SAN set for future internal TLS
migration, the script warns and you can refresh it explicitly with:

```bash
./scripts/product/jarvis-generate-certs.sh --force
```

Optional system trust install:

```bash
sudo ./scripts/product/jarvis-install-tls.sh
```

Optional hosts setup for edge ingress names:

```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
```

Kubernetes ingress TLS secret:

```bash
kubectl -n jarvis create secret tls jarvis-tls \
  --cert="$HOME/.jarvis/tls/jarvis.crt" \
  --key="$HOME/.jarvis/tls/jarvis.key" \
  --dry-run=client -o yaml | kubectl apply -f -
```

## Verification

### Edge HTTPS / WSS

```bash
curl -I http://api.jarvis.local/actuator/health
curl --cacert "$HOME/.jarvis/tls/jarvis-ca.crt" https://api.jarvis.local/actuator/health
```

For WSS validation, use an authenticated websocket client or the verified local
runtime smoke path below.

Practical repo checks:

- ingress redirect and TLS secret: `k8s/base/ingress.yaml`
- deployment runbook: `DEPLOYMENT_INSTRUCTIONS.md`
- cluster deployment details: `k8s/README.md`

### Local gateway HTTPS / WSS

```bash
JARVIS_USE_TLS=true ENABLE_LLM=false ./scripts/runtime-up.sh
JARVIS_USE_TLS=true JARVIS_RUNTIME_SMOKE_SKIP_LLM=true JARVIS_SKIP_BUILD=true ./scripts/runtime-smoke.sh
```

## What Still Uses HTTP

These are still plain HTTP by design today:

### Category A — Real Next Closures After Contract Work

- `planner-service -> user-profile`
- `planner-service -> life-tracker`

### Category B — Closeable Only After Service Strategy / Rollout Redesign

- `user-profile` listener and probes on `8089`

### Category C — Optional / Model-Adjacent

- optional `memory-service -> embedding-service`

### Category D — Explicitly Excluded From The Current Verified Scope

- excluded `llm-service -> llm-server`, `llm-service -> memory-service`, and
  `llm-service -> user-profile`

Also outside the promoted runtime claim and still HTTP:

- local direct service ports such as `planner-service:8092`, `life-tracker:8085`, `analytics-service:8087`
- internal port-forward acceptance paths such as `scripts/product/jarvis-run-acceptance.sh`

That is an internal-TLS gap, not an edge-TLS gap.

## Safe Claims

Claims that are accurate now:

- external backend access is HTTPS/WSS-complete
- ingress redirect behavior is enforced
- websocket access works over WSS
- local runtime can expose the gateway over self-signed HTTPS/WSS
- twenty-one internal HTTPS hops are verified:
  `ingress -> api-gateway`, `api-gateway -> nlp-service`,
  `planner-service -> api-gateway`, `orchestrator -> api-gateway`,
  `voice-gateway -> api-gateway`, `api-gateway -> security-service`,
  `api-gateway -> analytics-service`, `api-gateway -> pc-control`,
  `api-gateway -> life-tracker`, `api-gateway -> smart-home-service`,
  `api-gateway -> orchestrator`, `api-gateway -> voice-gateway`,
  `voice-gateway -> orchestrator`, `analytics-service -> life-tracker`,
  `orchestrator -> nlp-service`, `planner-service -> analytics-service`,
  `api-gateway -> planner-service`, `planner-service -> voice-gateway`,
  `voice-gateway -> smart-home-service`, `orchestrator -> smart-home-service`,
  and `orchestrator -> pc-control`
- the promoted overlay `k8s/overlays/prod-release-internal-tls-verified`
  reuses those same twenty-one verified hops without adding a twenty-second hop

Claims that are not accurate now:

- “the full backend is on internal TLS”
- “the cluster uses end-to-end HTTPS”
- “the platform has mTLS between services”

## Migration Path To Internal TLS

Recommended order:

1. introduce a separate internal-TLS overlay instead of silently changing the current `prod` behavior
2. keep the twenty-one verified narrow slices on their dedicated overlays:
   `ingress -> api-gateway`, `api-gateway -> nlp-service`,
   `planner-service -> api-gateway`, `orchestrator -> api-gateway`,
   `voice-gateway -> api-gateway`, `api-gateway -> security-service`,
   `api-gateway -> analytics-service`, `api-gateway -> pc-control`,
   `api-gateway -> life-tracker`, `api-gateway -> smart-home-service`,
   `api-gateway -> orchestrator`, `api-gateway -> voice-gateway`,
   `voice-gateway -> orchestrator`, `analytics-service -> life-tracker`,
   `orchestrator -> nlp-service`, `planner-service -> analytics-service`,
   `api-gateway -> planner-service`, `planner-service -> voice-gateway`,
   `voice-gateway -> smart-home-service`, `orchestrator -> smart-home-service`,
   and `orchestrator -> pc-control`
3. use `k8s/overlays/prod-release-internal-tls-verified` as the promoted
   release-grade deploy target for that carried-forward twenty-one-hop state
4. leave the remaining HTTP only where it is currently explicit and justified:
   category A planner placeholder contracts, category B `user-profile`
   listener/probes, category C optional model-adjacent memory traffic, and
   category D excluded LLM stack
5. then repeat the secret/truststore/probe pattern service by service
6. move service URLs from `http://service:port` to `https://service.namespace.svc.cluster.local:port`
7. validate each rollout and only then consider per-service certs and mTLS

Do not call the system “mTLS-enabled” until step 6 is complete.
