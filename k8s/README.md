# Kubernetes Layout

This file stays under `k8s/` because it explains how to work in this subtree.

## Main Paths

- `base/` - core services, ingress, postgres, mosquitto, observability
- `overlays/prod/` - hardened prod-style overlay plus optional AI workloads
- `overlays/prod-release/` - generated digest-pinned release artifact
- `overlays/prod-release-internal-tls-*/` - directory-local internal-TLS slice overlays
- `overlays/dev-hostpath/` - dev-only hostPath helper overlay

## Canonical Entry Points

- local repo launcher for Kubernetes runtime: `./jarvis-launch.sh`
- product deploy scripts: `./scripts/product/jarvis-*.sh`

## Notes

- each `prod-release-internal-tls-*` directory keeps its own README because rollout commands and trust assumptions are specific to that overlay
- `overlays/prod-release/kustomization.yaml` is generated and should not be hand-edited
- optional AI workloads are defined in `overlays/prod`, but default to `replicas: 0`
