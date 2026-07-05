# Deployment Canonical — the ONE path

Audit date: 2026-07-05. This page is the single, unambiguous answer to "how do
I deploy/verify/recover Jarvis 2.0" so operators stop re-deriving it from
`git log` and script archaeology. Cross-refs: [RUNTIME_MODES.md](RUNTIME_MODES.md)
(all seven runtime modes in detail), [LEGACY_AND_CLEANUP.md](LEGACY_AND_CLEANUP.md)
(why the legacy tree still exists on disk), [`infra/k8s/README.md`](../infra/k8s/README.md)
(the overlay's own README), [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) (the
release-gate checklist that assumes this page).

## TL;DR

- **The canonical Kubernetes source of truth is `infra/k8s/overlays/prod`.**
  Nothing else. Not `k8s/overlays/prod`, not `k8s/overlays/prod-release`, not
  a hand-patched live Deployment.
- **`k8s/` is frozen.** `k8s/base/**` accepts no further edits, enforced by
  [`scripts/guards/reject-legacy-k8s-edits.sh`](../scripts/guards/reject-legacy-k8s-edits.sh).
  It stays on disk only as historical reference for the digest-pinned
  `k8s/overlays/prod-release/**` release studies — that one subtree remains
  writable because [`scripts/product/jarvis-promote-images.sh`](../scripts/product/jarvis-promote-images.sh)
  still targets it.
- **The one-click operator path is [`scripts/jarvis-oneclick.sh`](../scripts/jarvis-oneclick.sh).**
  One click: start k3s if needed → repair the host-model-daemon endpoint →
  recover stale pods → wait for gateway health → launch the desktop shell. It
  never runs `kubectl apply`/`jarvis up` (see "Why recovery never re-applies"
  below) — it only patches Endpoints and deletes individually stale pods, so
  it cannot stomp the movie-tagged images pinned in `infra/k8s/overlays/prod`.

If you remember nothing else from this page: **read from `infra/k8s/overlays/prod`,
recover surgically, never blind-`apply` in a hurry.**

## Why two trees exist (and why that's fine)

`k8s/` was the original Kubernetes tree. `infra/k8s/` was introduced as the
MicroK8s-oriented "production foundation" tree and later became the
canonical one for k3s too — it adds Kafka/RabbitMQ StatefulSets that
`k8s/base/` never had, and as of 2026-07-04 it also carries the movie-tagged
image pins directly (closing the "re-apply silently reverts to generic tags"
risk described in [`docs/roadmap.md`](roadmap.md)).

`k8s/` is **not** deleted because `k8s/overlays/prod-release/**` (the
digest-pinned release-vehicle overlay) and ~25 `k8s/overlays/prod-release-internal-tls-*/`
slice studies still live under it and have their own apply/generate/smoke
scripts in [`scripts/product/`](../scripts/product/). Deleting the tree would
break those without warning. Instead it is quarantined:
`k8s/base/**` is read-only by convention, checked by
[`scripts/guards/reject-legacy-k8s-edits.sh`](../scripts/guards/reject-legacy-k8s-edits.sh)
(`--staged` for a pre-commit hook, `--check` for an ad-hoc working-tree scan,
`--all` for both — it is not currently wired into a git hook or CI job, so
run it by hand before touching anything under `k8s/`).

## The one-click path

```bash
./scripts/jarvis-oneclick.sh
```

Steps (see the script's own header comment for the full rationale):

1. Self-heal a stale k3s `node-ip` in `/etc/rancher/k3s/config.yaml` if DHCP
   changed the machine's IP since the config was written.
2. Start k3s if the systemd unit isn't active; restart it if the unit says
   "active" but the API refuses connections (the OOM-crash symptom).
3. Start the host brain (`jarvis-llm@18080.service`) and repoint the
   selectorless `host-model-daemon` Endpoints/EndpointSlice at the **current**
   node InternalIP (read live via `kubectl get nodes`, never hardcoded).
4. If `api-gateway` isn't Ready, run
   [`scripts/product/jarvis-recover-after-reboot.sh`](../scripts/product/jarvis-recover-after-reboot.sh)
   — a **targeted `kubectl delete pod`** recovery, never `apply`/`jarvis up`
   (see below for why that distinction matters).
5. Poll `https://<node-ip>/actuator/health` (Host: `api.jarvis.local`) until
   UP, then launch the desktop GUI.

### Why recovery never re-applies

The pod *specs* already sitting in etcd are correct and movie-tagged.
Re-applying manifests (`kubectl apply -k`, i.e. `./jarvis up` /
`jarvis-launch.sh`) re-renders kustomize and can reset image tags back to
whatever the overlay's on-disk `newTag:` says — which is exactly the failure
mode `jarvis-image-drift-check.sh` (below) exists to catch. The safe fix for
reboot-stale pods is a targeted `kubectl delete pod <name>`: the existing
Deployment/StatefulSet controller notices the missing pod and recreates it
from the **same unchanged spec**. Nothing here mutates a manifest.

## Day-2 operational scripts (this wave)

Three new read-only(-by-default) scripts round out the deploy/runtime
toolkit, alongside the existing
[`scripts/jarvis-host-endpoint-check.sh`](../scripts/jarvis-host-endpoint-check.sh)
and
[`scripts/product/jarvis-recover-after-reboot.sh`](../scripts/product/jarvis-recover-after-reboot.sh):

| Script | Answers | Mutates? |
| --- | --- | --- |
| [`scripts/jarvis-image-drift-check.sh`](../scripts/jarvis-image-drift-check.sh) | Do the LIVE Deployment image tags in `jarvis-prod` match the pins in `infra/k8s/overlays/prod/kustomization.yaml`? | No — `kubectl get` only |
| [`scripts/jarvis-post-reboot-verify.sh`](../scripts/jarvis-post-reboot-verify.sh) | Is the stack actually healthy after a reboot? (k3s up, node IP, endpoint==node IP, pods Ready, gateway UP, brain reachable via cluster) | No — `kubectl get`/`exec curl` only |
| [`scripts/jarvis-smoke-e2e.sh`](../scripts/jarvis-smoke-e2e.sh) | Is the stack actually *usable*? (login, LLM chat, memory, planner) | No cluster mutation; creates no persistent state beyond a login token |

All three are also wired into the root [`./jarvis`](../jarvis) CLI:

```bash
./jarvis drift-check      # scripts/jarvis-image-drift-check.sh
./jarvis reboot-verify     # scripts/jarvis-post-reboot-verify.sh
./jarvis smoke-e2e         # scripts/jarvis-smoke-e2e.sh
./jarvis doctor            # extended: k3s service + API, node Ready, all
                           # Deployments Ready, host-model-daemon endpoint ==
                           # node IP, gateway /actuator/health UP, brain :18080
./jarvis status            # extended: deployment readiness, node IP, and
                           # host-model-daemon endpoint-match at a glance
```

None of `doctor`/`status`/`drift-check`/`reboot-verify`/`smoke-e2e` hardcode a
cluster IP — they all resolve the current node InternalIP live via
`kubectl get nodes`, because the operator's machine IP has changed via DHCP
more than once (see the self-heal step in `jarvis-oneclick.sh` above).
`scripts/jarvis-smoke-e2e.sh` still falls back to the historical
`10.113.0.176` as an absolute last resort only if it cannot reach the cluster
to ask — this mirrors the existing fallback in
`scripts/jarvis-smoke-verify.sh` / `scripts/jarvis-final-check.sh` and exists
purely so the script fails with a clear message instead of an unbound
variable.

## Runbook: "the host just rebooted, what do I run"

1. `./jarvis reboot-verify` — read-only; tells you exactly which of the six
   checks (k3s service, API, node IP, endpoint match, pods Ready, gateway,
   brain) is broken.
2. If it reports stale pods: `scripts/product/jarvis-recover-after-reboot.sh`
   (add `--dry-run` first if you want to see what it *would* delete).
3. If it reports the host-model-daemon endpoint mismatch:
   `./scripts/jarvis-host-endpoint-check.sh --fix`.
4. Re-run `./jarvis reboot-verify` until all six checks pass.
5. `./jarvis smoke-e2e` to confirm the stack is usable end-to-end (login,
   LLM chat, memory, planner), not just "Ready".
6. `./jarvis drift-check` to confirm nothing silently drifted from the
   pinned overlay tags during recovery.

Or, for the fully automated version of steps 1-4 plus launching the desktop
shell: `./scripts/jarvis-oneclick.sh`.

## What NOT to do

- Do not `kubectl apply -k infra/k8s/overlays/prod` (or `./jarvis up` /
  `jarvis-launch.sh`) as a reboot-recovery reflex. It re-renders kustomize and
  risks reverting movie-tagged images to whatever `newTag:` says on disk —
  use `jarvis-recover-after-reboot.sh` instead (see above).
- Do not hand-edit anything under `k8s/base/**`. It is frozen; edit
  `infra/k8s/` instead.
- Do not assume the node IP is `10.113.0.176`. It changes with DHCP. Every
  script in this wave re-detects it live.
