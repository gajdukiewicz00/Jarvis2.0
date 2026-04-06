# Promoted Internal TLS Release Path

This committed overlay is the promoted deploy target for the already verified
internal-TLS state.

It does not introduce any new hop migration. It simply carries forward the
existing twenty-one verified internal HTTPS hops by layering on top of:

- `k8s/overlays/prod-release-internal-tls-orchestrator-pc-control`

Use it when you want one first-class deployment target for the proven
internal-TLS posture instead of applying the individual slice overlays by name.

Validate it with:

```bash
./scripts/product/jarvis-deploy-prod-internal-tls-verified.sh
./scripts/product/jarvis-smoke-prod-internal-tls-verified.sh
```
