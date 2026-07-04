# Jarvis local production foundation

`infra/k8s/` is the canonical local production Kubernetes foundation for Jarvis.

Structure:

- `base/` contains reusable manifests for the shared `jarvis-prod` runtime.
- `overlays/prod/` turns that base into the mandatory local production profile.

Conventions:

- Namespace: `jarvis-prod`
- External HTTPS ingress: `api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local`
- Edge TLS secret: `jarvis-tls`
- App/runtime secrets: `jarvis-secrets`

Compatibility:

- The older `k8s/` tree is frozen and quarantined: `k8s/base/**` accepts no
  further edits, enforced by
  [`scripts/guards/reject-legacy-k8s-edits.sh`](../../scripts/guards/reject-legacy-k8s-edits.sh).
  It remains on disk only as historical reference; it is not a second live
  source of truth.
- `k8s/overlays/prod-release/**` is the sole exception — it stays writable
  because `scripts/product/jarvis-promote-images.sh` can still target it.
- All local production work targets `infra/k8s/overlays/prod`.

Suggested flow:

1. `./scripts/product/jarvis-generate-certs.sh`
2. `sudo ./scripts/product/jarvis-setup-hosts.sh`
3. `./scripts/product/jarvis-deploy-microk8s-prod.sh`
4. `./scripts/k8s-smoke.sh`

## Phase 2 image build (Dockerless)

OCI images are built without the Docker daemon:

```bash
# Build all 13 Java services (Jib) + 2 Python workers (podman) and push to
# the MicroK8s registry at localhost:5000:
./infra/scripts/microk8s/build-images.sh

# Variants:
./infra/scripts/microk8s/build-images.sh --mode=tar           # tarballs only
./infra/scripts/microk8s/build-images.sh --mode=ctr-import    # microk8s ctr image import
./infra/scripts/microk8s/build-images.sh --service=api-gateway
./infra/scripts/microk8s/build-images.sh --no-python
```

Java path: `mvn -pl apps/<svc> -am package jib:build` — no Dockerfile, no daemon.
Python path: `podman build -f apps/<svc>-py/Containerfile` — daemonless OCI.

The Phase 2 acceptance gate is:

```bash
./infra/scripts/microk8s/verify-no-docker-runtime.sh --strict
```

It enforces that no legacy image-build files, compose manifests, `docker/`
directory, `.dockerignore`, or active Docker CLI calls live in active paths.

## Phase 1 acceptance gate

Steps 1–4 above can be run in one shot through the Phase 1 wrapper, which also
adds a Postgres / Kafka / RabbitMQ restart-resilience test and captures all
output to a single evidence directory:

```bash
./infra/scripts/microk8s/verify-phase1.sh
```

The wrapper produces `${EVIDENCE_DIR:-/tmp/jarvis-phase1}/summary.txt` plus
per-step logs. Re-run with `--skip-deploy` to validate without re-applying.

Result template:
[`docs/architecture/phase-1-acceptance-evidence.md`](../../docs/architecture/phase-1-acceptance-evidence.md).

Sub-scripts:

- `infra/scripts/microk8s/persistence-test.sh` — write durable data into
  Postgres / Kafka / RabbitMQ, rollout-restart each StatefulSet, verify the
  data survived.
- `scripts/k8s-smoke.sh` — namespace ownership, brokers, ingress, HTTPS
  readiness.
