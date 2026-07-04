# Kubernetes Layout

> **STATUS — 2026-05-19: this tree is LEGACY and no longer the production source.**
> The canonical Kubernetes tree is [`infra/k8s/`](../infra/k8s/). `jarvis-launch.sh`
> resolves `K8S_DIR` to `infra/k8s/` by default; production scripts and the
> observability stack are applied from `infra/k8s/base/observability/`.
>
> This `k8s/` tree is retained only for the digest-pinned release artifact under
> `overlays/prod-release/` and for the historical internal-TLS slice overlays.
>
> **For new K8s work**, edit manifests under `infra/k8s/` and use
> `./scripts/product/jarvis-deploy-microk8s-prod.sh` + `./scripts/verify-prod.sh`.
> Do NOT apply `k8s/base/observability/` directly — it is no longer wired into
> the launcher and applying it could re-introduce the `namespace: jarvis` bug
> that broke Prometheus scrape on 2026-05-19.

This file stays under `k8s/` because it explains how to work in this subtree (legacy).

## Main Paths

- `base/` - core services, ingress, postgres, mosquitto, observability
- `overlays/prod/` - hardened prod-style overlay plus optional AI workloads, targeting production namespace `jarvis-prod`
- `overlays/prod-release/` - generated digest-pinned release artifact
- `overlays/prod-release-internal-tls-*/` - directory-local internal-TLS slice overlays
- `overlays/dev-hostpath/` - dev-only hostPath helper overlay
- `overlays/staging/` - non-production staging overlay

## Canonical Entry Points

- local mutable launcher path: `./jarvis-launch.sh`
- digest-pinned release deploy path: `./scripts/product/jarvis-deploy-prod.sh --overlay=./k8s/overlays/prod-release`
- the committed `overlays/prod-release` artifact excludes the optional LLM ingress stack unless you regenerate it with `./scripts/product/jarvis-promote-images.sh --include-llm`
- product deploy scripts: `./scripts/product/jarvis-*.sh`

Production namespace rule:

- `jarvis-prod` is the only production namespace.
- `dev-hostpath` and `staging` are non-production overlays even when they still carry older namespace assumptions for test or development purposes.

## Notes

- each `prod-release-internal-tls-*` directory keeps its own README because rollout commands and trust assumptions are specific to that overlay
- `overlays/prod-release/kustomization.yaml` is generated and should not be hand-edited
- optional AI workloads are defined in `overlays/prod`, but default to `replicas: 0`
- `jarvis-launch.sh --release-overlay` is the safe launcher path when you want the runtime to match a generated digest-pinned release overlay
- Deprecated runtime path. Kept temporarily for compatibility and migration evidence. Production runtime target is native host + MicroK8s under `jarvis-prod`.
