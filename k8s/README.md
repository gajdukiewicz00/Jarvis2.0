# Jarvis 2.0 — Kubernetes Deployment (prod-only)

## Structure

```
k8s/
├── base/                       # Base resources (namespace + core services)
└── overlays/
    └── prod/                   # Production overlay (k3s + ingress-nginx)
```

## Apply (via launcher)

```bash
./jarvis-launch.sh
```

Note: `k8s/overlays/prod` contains `__JARVIS_MODELS_PATH__` placeholders that are\n+replaced by `jarvis-launch.sh`. Applying with plain `kubectl apply -k` will not\n+expand them.

## Optional LLM + Memory

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

## Models

Models are mounted from `~/.jarvis/models` on the host.

## Notes

- Namespace: `jarvis`
- Ingress: `k8s/base/ingress.yaml` (api.jarvis.local, voice.jarvis.local)
- Secrets are local and applied with `scripts/product/jarvis-secrets-apply.sh`
