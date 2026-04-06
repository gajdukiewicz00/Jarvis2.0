# Jarvis 2.0 — Kubernetes Deployment

## Structure

```
k8s/
├── base/                       # Base resources (namespace + core services)
└── overlays/
    ├── prod/                   # Production overlay (PVC-backed storage, no hostPath)
    ├── prod-release/           # Generated digest-pinned release artifact
    ├── prod-release-internal-tls-api-gateway-nlp/
    │                           # Committed patch overlay for the first internal TLS slice
    ├── prod-release-internal-tls-planner-api-gateway/
    │                           # Committed patch overlay for the second internal TLS slice
    ├── prod-release-internal-tls-orchestrator-api-gateway/
    │                           # Committed patch overlay for the third internal TLS slice
    ├── prod-release-internal-tls-voice-gateway-api-gateway/
    │                           # Committed patch overlay for the fourth internal TLS slice
    ├── prod-release-internal-tls-ingress-api-gateway/
    │                           # Committed patch overlay for the fifth internal TLS slice
    ├── prod-release-internal-tls-api-gateway-security-service/
    │                           # Committed patch overlay for the sixth internal TLS slice
    ├── prod-release-internal-tls-api-gateway-analytics-service/
    │                           # Committed patch overlay for the seventh internal TLS slice
    ├── prod-release-internal-tls-voice-gateway-orchestrator/
    ├── prod-release-internal-tls-analytics-service-life-tracker/
    ├── prod-release-internal-tls-orchestrator-nlp-service/
    ├── prod-release-internal-tls-planner-service-analytics-service/
    ├── prod-release-internal-tls-api-gateway-planner-service/
    ├── prod-release-internal-tls-planner-service-voice-gateway/
    ├── prod-release-internal-tls-voice-gateway-smart-home-service/
    ├── prod-release-internal-tls-orchestrator-smart-home-service/
    ├── prod-release-internal-tls-orchestrator-pc-control/
    ├── prod-release-internal-tls-verified/
    │                           # Promoted deploy target for the already verified 21-hop TLS state
    └── dev-hostpath/           # Dev-only overlay that adds hostPath-backed PVs
```

## Apply

Local single-node bundle:

```bash
./jarvis-launch.sh
```

Production-like backend rollout:

```bash
./scripts/product/jarvis-promote-images.sh --push
./scripts/product/jarvis-deploy-prod.sh
```

Production uses PVCs for model volumes (`llm-models-pvc`, `vosk-models-pvc`).
In development, use `k8s/overlays/dev-hostpath` to bind those PVCs to hostPath PVs.

## TLS Model

Current verified Kubernetes TLS posture:

- edge TLS: `yes`
- internal TLS: `partial`
- full mTLS: `no`

External traffic enters through ingress on:

- `https://api.jarvis.local`
- `wss://voice.jarvis.local`

Ingress terminates TLS with secret `jarvis-tls` and enforces HTTP to HTTPS redirect.
Behind ingress, the default verified path is still plain HTTP between services.

The verified internal HTTPS overlays today are:

- `ingress -> api-gateway`
- `api-gateway -> nlp-service`
- `planner-service -> api-gateway`
- `orchestrator -> api-gateway`
- `voice-gateway -> api-gateway`
- `api-gateway -> security-service`
- `api-gateway -> analytics-service`
- `api-gateway -> pc-control`
- `api-gateway -> life-tracker`
- `api-gateway -> smart-home-service`
- `api-gateway -> orchestrator`
- `api-gateway -> voice-gateway`
- `voice-gateway -> orchestrator`
- `analytics-service -> life-tracker`
- `orchestrator -> nlp-service`
- `planner-service -> analytics-service`
- `api-gateway -> planner-service`
- `planner-service -> voice-gateway`
- `voice-gateway -> smart-home-service`
- `orchestrator -> smart-home-service`
- `orchestrator -> pc-control`

That means:

- edge HTTPS/WSS is part of the deployment claim
- twenty-one narrow internal HTTPS hops are part of the verified claim
- all remaining internal HTTPS is not part of the current deployment claim
- mTLS is not part of the current deployment claim

`api-gateway` is configured to honor forwarded headers so HTTPS-originated
requests keep the correct scheme semantics behind ingress.

## Release Overlay

`k8s/overlays/prod` is the portable prod base overlay.

`k8s/overlays/prod-release` is the generated deployment artifact for real backend rollouts. `scripts/product/jarvis-promote-images.sh` writes `kustomization.yaml` there with immutable `image@sha256` refs for the promoted backend services.

`k8s/overlays/prod-release-internal-tls-api-gateway-nlp` is a committed patch overlay that layers on top of the generated `prod-release` artifact. It is the first verified internal HTTPS migration slice, not the default cluster path.

`k8s/overlays/prod-release-internal-tls-planner-api-gateway` layers on top of that first slice and keeps `api-gateway` on internal HTTP `8080` while also enabling internal HTTPS `8443` for the migrated `planner-service` caller.

`k8s/overlays/prod-release-internal-tls-orchestrator-api-gateway` layers on top of the planner slice and reuses that same `api-gateway:8443` listener for the migrated `orchestrator` caller.

`k8s/overlays/prod-release-internal-tls-voice-gateway-api-gateway` layers on top of the orchestrator slice and reuses that same `api-gateway:8443` listener for the migrated `voice-gateway` caller.

`k8s/overlays/prod-release-internal-tls-ingress-api-gateway` layers on top of the voice-gateway slice and switches `jarvis-ingress` from `api-gateway:8080` over HTTP to `api-gateway:8443` over HTTPS.

`k8s/overlays/prod-release-internal-tls-api-gateway-security-service` layers on top of the ingress slice and migrates `api-gateway -> security-service` from `http://security-service:8088` to `https://security-service.jarvis.svc.cluster.local:8088`, with HTTPS probes on `security-service`.

`k8s/overlays/prod-release-internal-tls-api-gateway-analytics-service` layers on top of the security-service slice and migrates `api-gateway -> analytics-service` from `http://analytics-service:8087` to `https://analytics-service.jarvis.svc.cluster.local:8087`, with HTTPS probes on `analytics-service`.

`k8s/overlays/prod-release-internal-tls-api-gateway-pc-control` layers on top of the analytics slice and migrates `api-gateway -> pc-control` from `http://pc-control:8084` to `https://pc-control.jarvis.svc.cluster.local:8084`, with HTTPS probes on `pc-control`.

`k8s/overlays/prod-release-internal-tls-api-gateway-life-tracker` layers on top of the pc-control slice and migrates `api-gateway -> life-tracker` from `http://life-tracker:8085` to `https://life-tracker.jarvis.svc.cluster.local:8085`, with HTTPS probes on `life-tracker`.

`k8s/overlays/prod-release-internal-tls-api-gateway-smart-home-service` layers on top of the life-tracker slice and migrates `api-gateway -> smart-home-service` from `http://smart-home-service:8086` to `https://smart-home-service.jarvis.svc.cluster.local:8086`, with HTTPS probes on `smart-home-service`.

`k8s/overlays/prod-release-internal-tls-api-gateway-orchestrator` layers on top of the smart-home slice and migrates `api-gateway -> orchestrator` from `http://orchestrator:8083` to `https://orchestrator.jarvis.svc.cluster.local:8083`, with HTTPS probes on `orchestrator`.

`k8s/overlays/prod-release-internal-tls-api-gateway-voice-gateway` layers on top of the orchestrator slice and migrates `api-gateway -> voice-gateway` from `http://voice-gateway:8081` to `https://voice-gateway.jarvis.svc.cluster.local:8081`, with HTTPS probes on `voice-gateway`.

`k8s/overlays/prod-release-internal-tls-voice-gateway-orchestrator` layers on top of the voice-gateway slice and migrates `voice-gateway -> orchestrator` from `http://orchestrator:8083` to `https://orchestrator.jarvis.svc.cluster.local:8083`.

`k8s/overlays/prod-release-internal-tls-analytics-service-life-tracker` layers on top of the previous slice and migrates `analytics-service -> life-tracker` from `http://life-tracker:8085` to `https://life-tracker.jarvis.svc.cluster.local:8085`.

`k8s/overlays/prod-release-internal-tls-orchestrator-nlp-service` layers on top of that analytics slice and migrates `orchestrator -> nlp-service` from `http://nlp-service:8082` to `https://nlp-service.jarvis.svc.cluster.local:8082`.

`k8s/overlays/prod-release-internal-tls-planner-service-analytics-service` layers on top of the orchestrator slice and migrates `planner-service -> analytics-service` from `http://analytics-service:8087` to `https://analytics-service.jarvis.svc.cluster.local:8087`.

`k8s/overlays/prod-release-internal-tls-api-gateway-planner-service` layers on top of the planner analytics slice and migrates `api-gateway -> planner-service` from `http://planner-service:8092` to `https://planner-service.jarvis.svc.cluster.local:8092`.

`k8s/overlays/prod-release-internal-tls-planner-service-voice-gateway` layers on top of the gateway planner slice and migrates `planner-service -> voice-gateway` from `http://voice-gateway:8081` to `https://voice-gateway.jarvis.svc.cluster.local:8081`.

`k8s/overlays/prod-release-internal-tls-voice-gateway-smart-home-service` layers on top of the planner voice slice and migrates `voice-gateway -> smart-home-service` from `http://smart-home-service:8086` to `https://smart-home-service.jarvis.svc.cluster.local:8086`.

`k8s/overlays/prod-release-internal-tls-orchestrator-smart-home-service` layers on top of the voice smart-home slice and migrates `orchestrator -> smart-home-service` from `http://smart-home-service:8086` to `https://smart-home-service.jarvis.svc.cluster.local:8086`.

`k8s/overlays/prod-release-internal-tls-orchestrator-pc-control` layers on top of the orchestrator smart-home slice and migrates `orchestrator -> pc-control` from `http://pc-control:8084` to `https://pc-control.jarvis.svc.cluster.local:8084`.

`k8s/overlays/prod-release-internal-tls-verified` is the promoted release-grade
overlay for the already verified internal-TLS posture. It does not add any new
hop; it simply carries forward the twenty-one committed slice migrations above as
one stable deploy target.

Artifact policy:

- `kustomization.yaml` is generated and gitignored
- `README.md` and `.gitignore` remain committed
- every promotion regenerates the overlay from real digests; it is not edited or committed by hand

Use the generated release overlay for:

- server-side preflight with digest enforcement
- `kubectl apply -k`
- rollout/image drift validation

## Optional LLM + Memory

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

### Optional Workloads (Default OFF in prod overlay)

- `llm-service`
- `memory-service`
- `embedding-service`
- `llm-server`

Enable manually (if needed):

```bash
kubectl -n jarvis scale deploy/embedding-service --replicas=1
kubectl -n jarvis scale deploy/memory-service --replicas=1
kubectl -n jarvis scale deploy/llm-server --replicas=1
kubectl -n jarvis scale deploy/llm-service --replicas=1
```

### Core Workloads (Default HA policy)

- `api-gateway`
- `orchestrator`
- `security-service`
- `voice-gateway`
- `planner-service`
- `life-tracker`

Policy:

- `replicas >= 2` for core workloads
- `PodDisruptionBudget` with `minAvailable: 1` for each core workload
- `topologySpreadConstraints` by `kubernetes.io/hostname` (`whenUnsatisfiable: ScheduleAnyway`) for each core workload
- `priorityClassName: jarvis-core-high` for core workloads
- explicit `resources.requests/limits` (cpu+memory) for each core workload
- explicit `terminationGracePeriodSeconds` + `preStop` hook for controlled shutdown during rolling updates
- stateful single-replica infra (`postgres`, `mosquitto`) is intentionally not treated as HA by PDB alone

Scheduling behavior:

- multi-node cluster: scheduler prefers placing core replicas on different nodes
- single-node cluster/dev: pods still schedule (no hard block) because `ScheduleAnyway`
- under node pressure: core pods with `jarvis-core-high` are preferred over optional workloads
- during updates/drain: graceful termination windows reduce abrupt connection drops

## Network Security Model (prod)

- `default-deny` NetworkPolicy blocks all ingress/egress by default in namespace `jarvis`
- explicit allowlist policies open only required service-to-service flows
- dedicated DNS egress allow keeps service discovery working (`port 53` TCP/UDP)
- DB/MQTT access is restricted to known clients only (`postgres`, `mosquitto`)

## ServiceAccount & RBAC Policy (prod)

- every core deployment uses a dedicated ServiceAccount:
  - `jarvis-api-gateway-sa`
  - `jarvis-orchestrator-sa`
  - `jarvis-security-service-sa`
  - `jarvis-voice-gateway-sa`
  - `jarvis-planner-service-sa`
  - `jarvis-life-tracker-sa`
- default ServiceAccount usage is forbidden for core workloads
- pod-level `automountServiceAccountToken: false` is enforced by default for core workloads
- any Kubernetes API access must be explicit and minimal:
  1. create a dedicated `Role` (least privileges only)
  2. bind it only to the target ServiceAccount via `RoleBinding`
  3. opt-in token mounting for that workload only
  4. if needed, add the workload to `K8S_PREFLIGHT_CORE_SA_TOKEN_EXCEPTIONS`
- `cluster-admin` bindings and wildcard `verbs/resources` are blocked by preflight

## Kyverno Admission Baseline (prod)

Enabled Kyverno `Policy` resources enforce:

- `runAsNonRoot=true`
- `allowPrivilegeEscalation=false`
- `readOnlyRootFilesystem=true`
- `seccompProfile.type=RuntimeDefault`
- forbid `privileged`, `hostNetwork`, `hostPID`, `hostIPC`
- forbid `hostPath` volumes
- for core workloads: forbid `capabilities.add`, require `capabilities.drop` includes `ALL`
- image tag policy: `:latest` is flagged in `Audit` mode (digests and explicit versions are allowed)
- verifyImages policy: signature verification for core images (`kyverno-prod-verify-images`) in `Audit` mode
- core resources policy (`cpu/memory` requests+limits) in `Audit` mode

Enforcement levels:

- `Enforce`: runtime/container/host isolation + core capabilities policy
- `Audit`: image `:latest` policy + core resources policy + verifyImages policy

### Signing process (cosign)

1. Build and push promoted backend images to registry, obtain immutable digests (`image@sha256:...`).
2. Run GitHub workflow `.github/workflows/prod-image-sign.yml`:
   - default mode: `keyless` (OIDC, recommended)
   - fallback mode: `key-pair` (`COSIGN_PRIVATE_KEY` + `COSIGN_PASSWORD` secrets)
3. Provide digest refs via workflow input `core_image_digests` or secret `COSIGN_CORE_IMAGES`.
4. The workflow calls `scripts/ci/cosign-sign-core-images.sh`, which signs the six core backend images consumed by the Kyverno `verifyImages` policy.
5. Generate `k8s/overlays/prod-release/kustomization.yaml` from those digests.
6. Deploy the generated release overlay instead of the mutable base overlay.

Registry requirements:

- registry must store OCI signatures/attestations (e.g. GHCR)
- prod rollout should consume immutable digests, not mutable tags

Roadmap (verifyImages `Audit` -> `Enforce`):

1. keep digest pinning in place for all core deployments
2. produce real cosign signatures for the promoted core digests in the target registry
3. confirm server-side preflight and rollout succeed against those signed digests
4. switch `kyverno-prod-verify-images` to `Enforce`
5. keep `K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE=enforce`

Do not move `verifyImages` to `Enforce` before step 2 is true for the registry and cluster you actually deploy to.

### Exception model (explicit allowlist)

- default: no exceptions
- temporary exception label (workload-level):
  - `security.jarvis.io/kyverno-exempt: "true"`
- required annotation for audit trail:
  - `security.jarvis.io/kyverno-exempt-reason: "<ticket-or-incident-id>"`
- exception is temporary only; remove label+annotation immediately after remediation
- exception without reason/ticket is not allowed for digest bypass in preflight

### Runbook: admission blocked deployment

1. Read deny reason from `kubectl apply` output (`SECURITY_HARD_FAIL...` message).
2. Inspect Kyverno policy details:
   - `kubectl -n jarvis get policy`
   - `kubectl -n jarvis describe policy <policy-name>`
3. Verify workload manifest against required fields (`securityContext`, `resources`, volumes).
4. If emergency unblock is required, add the temporary exception label + reason annotation, deploy, then schedule immediate rollback of exception.

### Netpol Debug Checklist (503/timeout)

- inspect effective policies:
  `kubectl -n jarvis get netpol`
  `kubectl -n jarvis describe netpol <name>`
- verify source/target labels:
  `kubectl -n jarvis get pods --show-labels`
- test connectivity from source pod:
  `kubectl -n jarvis exec -it <src-pod> -- sh`
  then `nc -vz <service> <port>` (or `wget/curl` for HTTP)
- for local troubleshooting only, apply a temporary allow-all policy in dev overlay and remove it after diagnosis

## Models

Prod models are mounted from PVCs provisioned by the cluster.
The dev `hostPath` overlay must be edited to point at this repo's absolute `models/` directory before use.

## Notes

- Namespace: `jarvis`
- Ingress: `k8s/base/ingress.yaml` (api.jarvis.local, voice.jarvis.local)
- Secrets are local and applied with `scripts/product/jarvis-secrets-apply.sh`
- `jarvis-secrets` must include `MQTT_USERNAME` and `MQTT_PASSWORD` for Mosquitto auth
- `jarvis-tls` is required for the ingress TLS path and is checked by server-side preflight
- internal service URLs and probes are still HTTP today, except the dedicated
  `api-gateway -> nlp-service`, `planner-service -> api-gateway`, and
  `orchestrator -> api-gateway`, `voice-gateway -> api-gateway`, and
  `api-gateway -> security-service`, `api-gateway -> analytics-service`, and
  `api-gateway -> pc-control`, `api-gateway -> life-tracker`, plus
  `api-gateway -> smart-home-service`, `api-gateway -> orchestrator`, and
  `api-gateway -> voice-gateway` internal-TLS slices
- ingress-to-backend traffic is still HTTP in the default prod overlay and HTTPS only in the dedicated
  `ingress -> api-gateway` internal-TLS slice
