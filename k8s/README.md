# Jarvis 2.0 — Kubernetes Deployment

## Structure

```
k8s/
├── base/                       # Base resources (namespace + core services)
└── overlays/
    ├── prod/                   # Production overlay (PVC-backed storage, no hostPath)
    └── dev-hostpath/           # Dev-only overlay that adds hostPath-backed PVs
```

## Apply (via launcher)

```bash
./jarvis-launch.sh
```

Production uses PVCs for model volumes (`llm-models-pvc`, `vosk-models-pvc`).
In development, use `k8s/overlays/dev-hostpath` to bind those PVCs to hostPath PVs.

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

1. Build and push core images to registry, obtain immutable digests (`image@sha256:...`).
2. Run GitHub workflow `.github/workflows/prod-image-sign.yml`:
   - default mode: `keyless` (OIDC, recommended)
   - fallback mode: `key-pair` (`COSIGN_PRIVATE_KEY` + `COSIGN_PASSWORD` secrets)
3. Provide digest refs via workflow input `core_image_digests` or secret `COSIGN_CORE_IMAGES`.
4. Promote prod manifests to digest-pinned images for core workloads.

Registry requirements:

- registry must store OCI signatures/attestations (e.g. GHCR)
- prod rollout should consume immutable digests, not mutable tags

Roadmap (verifyImages `Audit` -> `Enforce`):

1. finish digest pinning for all core deployments
2. keep CI signing stable for release tags
3. switch `kyverno-prod-verify-images` to `Enforce`
4. set `K8S_PREFLIGHT_CORE_DIGEST_POLICY_MODE=enforce`

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
Dev hostPath overlay mounts models from `/tmp/jarvis-models`.

## Notes

- Namespace: `jarvis`
- Ingress: `k8s/base/ingress.yaml` (api.jarvis.local, voice.jarvis.local)
- Secrets are local and applied with `scripts/product/jarvis-secrets-apply.sh`
- `jarvis-secrets` must include `MQTT_USERNAME` and `MQTT_PASSWORD` for Mosquitto auth
