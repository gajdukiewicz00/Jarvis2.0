# Jarvis 2.0 — Backend Deployment

This file documents the production-like backend rollout path for the in-scope service layer.

TLS boundary for this deployment path:

- external ingress access is HTTPS/WSS
- internal cluster traffic is still HTTP by default
- twenty-one dedicated internal-TLS hops exist:
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
- `k8s/overlays/prod-release-internal-tls-verified` is the promoted deploy target
  for that exact verified twenty-one-hop state
- remaining HTTP in that promoted state is an explicit exception set:
  category A planner placeholder contracts, category B `user-profile`
  listener/probes on `8089`, category C optional `memory-service ->
  embedding-service`, and category D excluded LLM paths
- full internal TLS and mTLS are not part of the current verified deployment claim

Excluded from this runbook:

- real LLM/model rollout
- mobile
- desktop/launcher UI

`./jarvis-launch.sh` remains the local single-node bundle. It is not the digest-pinned release path for cluster deployment.

## Prerequisites

- `kubectl` with access to the target cluster
- a reachable namespace `jarvis`
- Docker plus registry credentials for the target image registry
- Java 21 and Maven 3.8+ if you need to rebuild backend images locally
- `jarvis-secrets` and `jarvis-tls` present in the target namespace

If you use the repo-standard kubeconfig location, the deploy scripts auto-detect `~/.jarvis/kubeconfig`.

See `docs/HTTPS_STANDARD.md` for the exact edge-TLS vs internal-TLS distinction.

## 1. Prepare Secrets And TLS

Secrets:

```bash
cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
chmod 600 ~/.jarvis/secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh
```

TLS secret:

```bash
./scripts/product/jarvis-generate-certs.sh
kubectl -n jarvis create secret tls jarvis-tls \
  --cert="$HOME/.jarvis/tls/jarvis.crt" \
  --key="$HOME/.jarvis/tls/jarvis.key" \
  --dry-run=client -o yaml | kubectl apply -f -
```

## 2. Build Backend Images

This phase intentionally excludes real LLM rollout. Build only the backend slice:

```bash
ENABLE_LLM=false ENABLE_MEMORY=true IMAGE_TAG=release-2026-03-21 \
  ./scripts/build-images.sh --no-import
```

## 3. Promote Images And Generate A Release Overlay

Promote the in-scope backend images to a registry and generate the digest-pinned overlay at `k8s/overlays/prod-release/kustomization.yaml`:

```bash
IMAGE_REGISTRY=ghcr.io IMAGE_REPO=<org>/jarvis IMAGE_TAG=release-2026-03-21 \
  ./scripts/product/jarvis-promote-images.sh --push
```

For a single-host k3s lab where Docker and the cluster share the same machine, a local OCI registry on `localhost:5000` is a valid promotion target:

```bash
docker run -d --restart unless-stopped -p 5000:5000 --name jarvis-registry registry:2
IMAGE_REGISTRY=localhost:5000 IMAGE_REPO=jarvis IMAGE_TAG=release-2026-03-21 \
  ./scripts/product/jarvis-promote-images.sh --push
```

If digests were produced elsewhere, use a refs file instead:

```bash
./scripts/product/jarvis-promote-images.sh \
  --refs-file=/path/to/backend-image-refs.env
```

Expected refs file format:

```text
api-gateway=ghcr.io/<org>/jarvis/api-gateway@sha256:<64-hex>
security-service=ghcr.io/<org>/jarvis/security-service@sha256:<64-hex>
...
```

Artifact policy:

- `k8s/overlays/prod-release/kustomization.yaml` is generated and must not be committed.
- The repo keeps `k8s/overlays/prod-release/README.md` and `.gitignore` committed so the policy is explicit.
- Regenerate the release overlay for each promotion instead of editing it by hand.

## 4. Preflight And Apply

Run the strongest preflight gate first. This uses server-side dry-run, checks required secrets, and enforces digest pinning for the core workloads:

```bash
./scripts/product/jarvis-deploy-prod.sh --preflight-only
```

If that passes, apply and validate the rollout:

```bash
./scripts/product/jarvis-deploy-prod.sh
```

This runs:

1. `scripts/ci/k8s-preflight.sh` in `server` mode with digest enforcement
2. `kubectl apply -k k8s/overlays/prod-release`
3. `scripts/product/jarvis-rollout-validate.sh --overlay=k8s/overlays/prod-release`

## 5. Optional Internal TLS Slice 1: `api-gateway -> nlp-service`

The default release path above keeps internal cluster traffic on HTTP.

To deploy the first verified internal HTTPS slice instead, use the committed
overlay `k8s/overlays/prod-release-internal-tls-api-gateway-nlp`.

This migrates exactly one in-cluster hop:

- `api-gateway` as HTTPS client
- `nlp-service` as HTTPS-only server

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-nlp.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-nlp.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-nlp.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh
```

The smoke proves:

- `nlp-service` responds on HTTPS
- `nlp-service` no longer accepts plain HTTP on that migrated internal port
- `api-gateway` can still proxy `/api/v1/nlp/analyze` successfully

## 6. Optional Internal TLS Slice 2: `planner-service -> api-gateway`

The next verified slice layers on top of the first one with overlay
`k8s/overlays/prod-release-internal-tls-planner-api-gateway`.

This migrates exactly one additional in-cluster hop:

- `planner-service` as HTTPS client to `api-gateway`
- `api-gateway` as dual-listener internal server:
  HTTP on `8080` remains for non-migrated callers
  HTTPS on `8443` is enabled for the migrated slice

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-planner-api-gateway.sh
./scripts/product/jarvis-apply-internal-tls-planner-api-gateway.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-planner-api-gateway.sh
./scripts/product/jarvis-smoke-internal-tls-planner-api-gateway.sh
```

The smoke proves:

- `api-gateway` still responds on internal HTTP `8080`
- `api-gateway` also responds on internal HTTPS `8443`
- plain HTTP is rejected on `8443`
- `planner-service` routes the migrated call to
  `https://api-gateway.jarvis.svc.cluster.local:8443`

## 7. Optional Internal TLS Slice 3: `orchestrator -> api-gateway`

The next verified slice layers on top of the planner slice with overlay
`k8s/overlays/prod-release-internal-tls-orchestrator-api-gateway`.

This migrates exactly one additional in-cluster hop:

- `orchestrator` as HTTPS client to `api-gateway`
- `api-gateway` reuses the existing internal HTTPS listener on `8443`

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-orchestrator-api-gateway.sh
./scripts/product/jarvis-apply-internal-tls-orchestrator-api-gateway.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-orchestrator-api-gateway.sh
./scripts/product/jarvis-smoke-internal-tls-orchestrator-api-gateway.sh
```

The smoke proves:

- `api-gateway` still responds on internal HTTPS `8443`
- plain HTTP is rejected on `8443`
- `orchestrator` routes the migrated call to
  `https://api-gateway.jarvis.svc.cluster.local:8443`

## 8. Optional Internal TLS Slice 4: `voice-gateway -> api-gateway`

The next verified slice layers on top of the orchestrator slice with overlay
`k8s/overlays/prod-release-internal-tls-voice-gateway-api-gateway`.

This migrates exactly one additional in-cluster hop:

- `voice-gateway` as HTTPS client to `api-gateway`
- `api-gateway` reuses the existing internal HTTPS listener on `8443`

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-voice-gateway-api-gateway.sh
./scripts/product/jarvis-apply-internal-tls-voice-gateway-api-gateway.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-voice-gateway-api-gateway.sh
./scripts/product/jarvis-smoke-internal-tls-voice-gateway-api-gateway.sh
```

The smoke proves:

- `api-gateway` still responds on internal HTTPS `8443`
- plain HTTP is rejected on `8443`
- `voice-gateway` routes the migrated call to
  `https://api-gateway.jarvis.svc.cluster.local:8443`

## 9. Optional Internal TLS Slice 5: `ingress -> api-gateway`

The next verified slice layers on top of the voice-gateway slice with overlay
`k8s/overlays/prod-release-internal-tls-ingress-api-gateway`.

This migrates exactly one additional internal hop:

- `ingress-nginx` as HTTPS client to `api-gateway`
- `api-gateway` reuses the existing internal HTTPS listener on `8443`

No new certificate material is introduced in this slice. It reuses the existing
`api-gateway` internal HTTPS server certificate already mounted by the earlier
`planner-service -> api-gateway` slice chain.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-ingress-api-gateway.sh
./scripts/product/jarvis-smoke-internal-tls-ingress-api-gateway.sh
```

The verification proves:

- the live ingress object uses `nginx.ingress.kubernetes.io/backend-protocol: HTTPS`
- all `jarvis-ingress` backends for `api-gateway` point to port `8443`
- edge HTTPS still reaches `api-gateway` through ingress
- edge WSS still upgrades successfully through ingress

## 10. Optional Internal TLS Slice 6: `api-gateway -> security-service`

The next verified slice layers on top of the ingress slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-security-service`.

This migrates exactly one additional internal hop:

- `security-service` as HTTPS server on `8088`
- `api-gateway` as HTTPS client to `security-service`

`api-gateway` listener behavior does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the
`security-service` server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-security-service.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-security-service.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-security-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh
```

The smoke proves:

- `security-service` responds on HTTPS `8088`
- `security-service` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the auth proxy call to
  `https://security-service.jarvis.svc.cluster.local:8088`
- auth flows through `api-gateway` still succeed after the hop migration

## 11. Optional Internal TLS Slice 7: `api-gateway -> analytics-service`

The next verified slice layers on top of the security-service slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-analytics-service`.

This migrates exactly one additional internal hop:

- `analytics-service` as HTTPS server on `8087`
- `api-gateway` as HTTPS client to `analytics-service`

`api-gateway` listener behavior still does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the
`analytics-service` server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-analytics-service.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-analytics-service.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-analytics-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh
```

The smoke proves:

- `analytics-service` responds on HTTPS `8087`
- `analytics-service` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the analytics overview call to
  `https://analytics-service.jarvis.svc.cluster.local:8087`
- analytics flows through `api-gateway` still succeed after the hop migration

## 12. Optional Internal TLS Slice 8: `api-gateway -> pc-control`

The next verified slice layers on top of the analytics-service slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-pc-control`.

This migrates exactly one additional internal hop:

- `pc-control` as HTTPS server on `8084`
- `api-gateway` as HTTPS client to `pc-control`

`api-gateway` listener behavior still does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the `pc-control`
server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-pc-control.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-pc-control.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-pc-control.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-pc-control.sh
```

The smoke proves:

- `pc-control` responds on HTTPS `8084`
- `pc-control` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the pc-control action call to
  `https://pc-control.jarvis.svc.cluster.local:8084`
- pc-control flows through `api-gateway` still succeed after the hop migration

## 13. Optional Internal TLS Slice 9: `api-gateway -> life-tracker`

The next verified slice layers on top of the pc-control slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker`.

This migrates exactly one additional internal hop:

- `life-tracker` as HTTPS server on `8085`
- `api-gateway` as HTTPS client to `life-tracker`

`api-gateway` listener behavior still does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the `life-tracker`
server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-life-tracker.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-life-tracker.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-life-tracker.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-life-tracker.sh
```

The smoke proves:

- `life-tracker` responds on HTTPS `8085`
- `life-tracker` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the life-tracker expenses call to
  `https://life-tracker.jarvis.svc.cluster.local:8085`
- life-tracker flows through `api-gateway` still succeed after the hop migration

## 14. Optional Internal TLS Slice 10: `api-gateway -> smart-home-service`

The next verified slice layers on top of the life-tracker slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-smart-home-service`.

This migrates exactly one additional internal hop:

- `smart-home-service` as HTTPS server on `8086`
- `api-gateway` as HTTPS client to `smart-home-service`

`api-gateway` listener behavior still does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the
`smart-home-service` server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-smart-home-service.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-smart-home-service.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-smart-home-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh
```

The smoke proves:

- `smart-home-service` responds on HTTPS `8086`
- `smart-home-service` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the smart-home device-list call to
  `https://smart-home-service.jarvis.svc.cluster.local:8086`
- smart-home flows through `api-gateway` still succeed after the hop migration

## 15. Optional Internal TLS Slice 11: `api-gateway -> orchestrator`

The next verified slice layers on top of the smart-home slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-orchestrator`.

This migrates exactly one additional internal hop:

- `orchestrator` as HTTPS server on `8083`
- `api-gateway` as HTTPS client to `orchestrator`

`api-gateway` listener behavior still does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the `orchestrator`
server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-orchestrator.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-orchestrator.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-orchestrator.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh
```

The smoke proves:

- `orchestrator` responds on HTTPS `8083`
- `orchestrator` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the orchestrator execute call to
  `https://orchestrator.jarvis.svc.cluster.local:8083`
- orchestrator flows through `api-gateway` still succeed after the hop migration

## 16. Optional Internal TLS Slice 12: `api-gateway -> voice-gateway`

The next verified slice layers on top of the orchestrator slice with overlay
`k8s/overlays/prod-release-internal-tls-api-gateway-voice-gateway`.

This migrates exactly one additional internal hop:

- `voice-gateway` as HTTPS server on `8081`
- `api-gateway` as HTTPS client to `voice-gateway`

`api-gateway` listener behavior still does not change in this slice. It keeps the
existing internal HTTP `8080` and internal HTTPS `8443` listeners from the
earlier slices.

The `api-gateway` side reuses the Jarvis CA trust already mounted for the first
`api-gateway -> nlp-service` slice. This slice adds only the `voice-gateway`
server certificate and keystore material.

Everything else in-cluster still remains HTTP, and this is not mTLS.

Prepare the slice secret:

```bash
./scripts/product/jarvis-generate-internal-tls-api-gateway-voice-gateway.sh
./scripts/product/jarvis-apply-internal-tls-api-gateway-voice-gateway.sh
```

Deploy and validate the slice:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-api-gateway-voice-gateway.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh
```

The smoke proves:

- `voice-gateway` responds on HTTPS `8081`
- `voice-gateway` rejects plain HTTP on that migrated internal port
- `api-gateway` routes the voice command call to
  `https://voice-gateway.jarvis.svc.cluster.local:8081`
- voice command flows through `api-gateway` still succeed after the hop migration

## 17. Promoted Internal TLS Release Path

The repo now keeps one first-class overlay for the already verified internal-TLS
state:

- `k8s/overlays/prod-release-internal-tls-verified`

It does not add any new hop. It simply carries forward the existing twenty-one
verified internal HTTPS hops on top of the generated digest-pinned
`k8s/overlays/prod-release` artifact.

That promoted path now includes the later verified narrow slices too:

- `voice-gateway -> orchestrator`
- `analytics-service -> life-tracker`
- `orchestrator -> nlp-service`
- `planner-service -> analytics-service`
- `api-gateway -> planner-service`
- `planner-service -> voice-gateway`
- `voice-gateway -> smart-home-service`
- `orchestrator -> smart-home-service`
- `orchestrator -> pc-control`

Use it when you want one stable deployment target instead of naming the last
slice overlay directly:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-verified.sh
./scripts/product/jarvis-smoke-prod-internal-tls-verified.sh
```

The validation entrypoint reuses the twenty-one existing hop smokes serially after
rollout validation. That keeps the proven behavior intact and avoids inventing a
new acceptance surface.

This promoted path is still not full internal TLS and still not mTLS.

What still remains HTTP after the promoted path:

- category A, real next closures after contract work:
  `planner-service -> user-profile` and `planner-service -> life-tracker`,
  which are still placeholder rather than production-grade planner contracts
- category B, closeable only after service rollout redesign:
  `user-profile` listener and probes on `8089`
- category C, optional/model-adjacent:
  `memory-service -> embedding-service` while those workloads stay scaled to zero
- category D, explicitly excluded from the current migration scope:
  `llm-service -> llm-server`, `llm-service -> memory-service`,
  `llm-service -> user-profile`

## 18. Verify External HTTPS And WSS

After rollout:

```bash
curl -I http://api.jarvis.local/actuator/health
curl --cacert "$HOME/.jarvis/tls/jarvis-ca.crt" https://api.jarvis.local/actuator/health
```

Expected:

- HTTP returns `301` or `308`
- HTTPS returns `200`

`voice.jarvis.local` is the external WSS hostname. The verified websocket path is:

- `wss://voice.jarvis.local/ws/voice`

This proves edge TLS. It does not prove internal cluster TLS.

## 19. Rollout Validation Only

To re-check a deployment without applying:

```bash
./scripts/product/jarvis-rollout-validate.sh \
  --overlay=./k8s/overlays/prod-release
```

The validator checks:

- required backend deployments and `postgres`
- optional `memory-service`, `embedding-service`, and `postgres-pgvector` when replicas are non-zero
- image drift between the live namespace and the rendered release overlay

## 20. Troubleshooting

```bash
kubectl get pods -n jarvis
kubectl get deploy,statefulset -n jarvis
kubectl describe deployment api-gateway -n jarvis
kubectl describe deployment nlp-service -n jarvis
kubectl describe deployment security-service -n jarvis
kubectl describe deployment analytics-service -n jarvis
kubectl describe deployment pc-control -n jarvis
kubectl describe deployment life-tracker -n jarvis
kubectl describe deployment smart-home-service -n jarvis
kubectl describe deployment orchestrator -n jarvis
kubectl describe deployment voice-gateway -n jarvis
kubectl logs deployment/api-gateway -n jarvis --tail=100
kubectl logs deployment/nlp-service -n jarvis --tail=100
kubectl logs deployment/security-service -n jarvis --tail=100
kubectl logs deployment/analytics-service -n jarvis --tail=100
kubectl logs deployment/pc-control -n jarvis --tail=100
kubectl logs deployment/life-tracker -n jarvis --tail=100
kubectl logs deployment/smart-home-service -n jarvis --tail=100
kubectl logs deployment/orchestrator -n jarvis --tail=100
kubectl logs deployment/voice-gateway -n jarvis --tail=100
./scripts/product/jarvis-rollout-validate.sh --overlay=./k8s/overlays/prod-release
./scripts/product/jarvis-smoke-internal-tls-api-gateway-nlp.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-security-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-analytics-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-pc-control.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-life-tracker.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-smart-home-service.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-orchestrator.sh
./scripts/product/jarvis-smoke-internal-tls-api-gateway-voice-gateway.sh
./scripts/product/jarvis-smoke-prod-internal-tls-verified.sh
```

## 21. Local Single-Node Path

For local k3s bring-up and desktop-oriented flows, use:

```bash
./jarvis-launch.sh
```

That path is separate from the digest-pinned cluster deployment flow above.

## 22. Acceptance Note

`scripts/product/jarvis-run-acceptance.sh` uses `kubectl port-forward` to the
internal `api-gateway` service and therefore speaks plain HTTP over that
operator tunnel. That script is still useful, but it is not proof of the edge
HTTPS/WSS path.

## 23. CI Coverage

Backend readiness automation lives in `.github/workflows/backend-readiness.yml`.

- `release-wiring` validates the promote/signing script chain without requiring a live registry or cluster
- `runtime-core-smoke` runs the hardened backend runtime smoke with `JARVIS_RUNTIME_SMOKE_SKIP_LLM=true`
- `runtime-data-smoke` runs the memory and analytics smoke path against a local runtime with `ENABLE_MEMORY=true`

The core and data smokes stay in separate jobs on purpose because `runtime-smoke.sh` exercises the non-memory backend slice, while the data stack requires different runtime flags and startup shape.
