# HTTPS / TLS Standard

This file is kept as a short security/runtime policy note. It is not the project overview.

## Terms

- `edge TLS`: TLS terminates at the public ingress or public local gateway entrypoint
- `internal TLS`: service-to-service traffic also uses HTTPS
- `mTLS`: mutual certificate auth on internal connections

## Current Repo Truth

- edge TLS is implemented for the Kubernetes ingress path
- local public gateway HTTPS/WSS can be enabled with `JARVIS_USE_TLS=true`
- internal TLS exists only as a partial, overlay-driven migration path
- full internal TLS is not the default claim of `k8s/overlays/prod`
- mTLS is not implemented as the default runtime model in this repo

## Canonical Artifacts

- `k8s/ingress` resources and `jarvis-tls`
- `k8s/overlays/prod-release-internal-tls-*/`
- `k8s/overlays/prod-release-internal-tls-verified/`
- `scripts/product/jarvis-generate-certs.sh`
- `scripts/product/jarvis-install-tls.sh`

Use the overlay-local READMEs as the source of truth for the exact internal-TLS slice you are deploying.
