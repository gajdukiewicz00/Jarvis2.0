# Runtime Modes

Audit date: 2026-05-08. This page enumerates the runtime modes the repository
*actually* supports today, with the canonical entry point and the components it
brings up. Cross-reference: [COMPONENT_STATUS.md](COMPONENT_STATUS.md),
[LEGACY_AND_CLEANUP.md](LEGACY_AND_CLEANUP.md).

## Mode 1 — Local native processes

Canonical entry point: [`scripts/runtime-up.sh`](../scripts/runtime-up.sh) and
its peers `runtime-down.sh`, `runtime-status.sh`, `runtime-smoke.sh`.

State, logs, and PIDs land under `~/.jarvis/run/local-runtime/` and
`~/.jarvis/logs/local-runtime/` ([`scripts/runtime/common.sh`](../scripts/runtime/common.sh)).

Core services started:

```
security-service, user-profile, nlp-service, orchestrator, voice-gateway,
pc-control, vision-security-service, smart-home-service, life-tracker,
analytics-service, api-gateway, planner-service
```

Optional services (see "Mode 4 — AI overlay"):

```
llm-server (deprecated wrapper), embedding-service, memory-service, llm-service
```

Default ports come from [`scripts/runtime/common.sh`](../scripts/runtime/common.sh)
and are listed in [README.md](../README.md) under "Local Runtime Defaults".

PostgreSQL: when no external DB env is provided, the local runtime auto-starts
a managed `pgvector/pgvector:pg16` container under `podman` (or the engine in
`JARVIS_LOCAL_CONTAINER_ENGINE`).

## Mode 2 — Kubernetes (k3s, mutable launcher)

Canonical entry point: [`./jarvis-launch.sh`](../jarvis-launch.sh) (or
`make launch`).

Brings up: k3s → ingress-nginx → TLS edge → namespace `jarvis-prod` →
observability stack → core deployments from `infra/k8s/overlays/prod`
(rendered with `localhost:5000/jarvis/*:local` images via `prepare_prod_overlay`).

Required deployments (all wait with timeout 180s):

```
postgres (StatefulSet), security-service, api-gateway, orchestrator,
life-tracker, analytics-service, planner-service, user-profile, nlp-service,
pc-control, smart-home-service
```

`voice-gateway` is optional unless `JARVIS_REQUIRE_VOICE_GATEWAY=true`.

Image tag rule: `IMAGE_TAG=local` by default. The launcher rewrites
`infra/k8s/overlays/prod/kustomization.yaml` images on a temp copy at deploy
time, so the on-disk overlay is kept stable. `JARVIS_K8S_DIR` can retarget the
launcher at an alternate tree for migration smoke tests only (see
[`infra/k8s/README.md`](../infra/k8s/README.md)); production tooling does not
honor it.

## Mode 3 — Kubernetes (digest-pinned release overlay)

Canonical entry point: [`./jarvis-launch.sh --release-overlay`](../jarvis-launch.sh)
or [`./scripts/product/jarvis-deploy-prod.sh`](../scripts/product/jarvis-deploy-prod.sh).

Source overlay: [`k8s/overlays/prod-release/kustomization.yaml`](../k8s/overlays/prod-release/kustomization.yaml),
*generated* by [`./scripts/product/jarvis-promote-images.sh`](../scripts/product/jarvis-promote-images.sh)
with immutable `sha256:` digests. The committed file is dated
`2026-03-23T13:15:53+01:00` and excludes the optional LLM stack via
`exclude-llm-stack.patch.yaml`.

Includes by default:

- core 12 services with digest-pinned images
- excludes `llm-server`, `llm-service`, `embedding-service`, `memory-service`
  unless `jarvis-promote-images.sh --include-llm` / `--include-data` was used
  when regenerating

Internal-TLS slice overlays under `k8s/overlays/prod-release-internal-tls-*/`
each carry their own README and target a specific service-pair edge (e.g.
`api-gateway-life-tracker`).

## Mode 4 — Kubernetes (MicroK8s production foundation, alternative tree)

Canonical entry point:
[`./scripts/product/jarvis-deploy-microk8s-prod.sh`](../scripts/product/jarvis-deploy-microk8s-prod.sh).

Default overlay: `infra/k8s/overlays/prod` (NOT `k8s/`). Per
[`infra/k8s/README.md`](../infra/k8s/README.md), `infra/k8s/` is the canonical
Kubernetes source of truth. `jarvis-launch.sh` (Mode 2) now targets
`infra/k8s/` as well by default; `k8s/base/**` is frozen and quarantined
(enforced by
[`scripts/guards/reject-legacy-k8s-edits.sh`](../scripts/guards/reject-legacy-k8s-edits.sh)),
so there is a single live tree, not two.

`infra/k8s/base/` adds Kafka and RabbitMQ StatefulSets that `k8s/base/` lacks.

## Mode 5 — AI overlay (optional, local or k8s)

Activated by environment flags or CLI flags:

- `ENABLE_LLM=true` (or `--enable-llm`)
- `ENABLE_MEMORY=true` (or `--enable-memory`)
- `ENABLE_GPU=true` is the default; `--disable-gpu` forces a CPU profile

Local path: [`scripts/setup-ai-local.sh`](../scripts/setup-ai-local.sh) +
[`scripts/ai-up.sh`](../scripts/ai-up.sh) /
[`scripts/ai-down.sh`](../scripts/ai-down.sh) /
[`scripts/ai-local-smoke.sh`](../scripts/ai-local-smoke.sh).

Kubernetes path: `jarvis-launch.sh --enable-llm --enable-memory` scales
deployments `llm-server`, `llm-service`, `embedding-service`, `memory-service`,
and StatefulSet `postgres-pgvector` from 0 → 1.

Production canonical inference path is the **native llama.cpp host daemon**
(Phase 3) reached through the selectorless Kubernetes Service
[`k8s/base/host-model-daemon/`](../k8s/base/host-model-daemon/), patched at
deploy time by [`infra/scripts/microk8s/apply-host-endpoints.sh`](../infra/scripts/microk8s/apply-host-endpoints.sh).
The Python wrapper [`apps/llm-server-py`](../apps/llm-server-py) is the
deprecated runtime path that the local scripts still drive.

## Mode 6 — Desktop launcher

Canonical entry point: `mvn -f apps/desktop-javafx/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run`
or [`scripts/product/jarvis-desktop-launch.sh`](../scripts/product/jarvis-desktop-launch.sh)
(install via [`scripts/product/jarvis-desktop-install.sh`](../scripts/product/jarvis-desktop-install.sh),
uninstall via `jarvis-desktop-uninstall.sh`).

`apps/desktop-javafx` is the **only** desktop module today (see
[ADR-0002](architecture/ADR/ADR-0002-desktop-javafx-native-desktop-agent.md)).
Some payloads still carry the historic `desktop-client` label; in current repo
reality that label points at the JavaFX shell.

## Mode 7 — Observability

Always brought up before workloads by `jarvis-launch.sh`
(`ensure_observability_stack`). Components: Prometheus, Loki, Tempo, Grafana,
Alloy ([`k8s/base/observability/`](../k8s/base/observability/)).

Verification: [`scripts/verify-observability.sh`](../scripts/verify-observability.sh)
or `make obs-verify`. Grafana admin sync is performed by
[`scripts/sync-grafana-admin.sh`](../scripts/sync-grafana-admin.sh).

Dashboards under [`config/grafana-dashboards/`](../config/grafana-dashboards/)
are provisioned by [`scripts/provision-grafana-dashboards.sh`](../scripts/provision-grafana-dashboards.sh).

## Local-only Components

These are *never* deployed by `k8s/base` or any cluster overlay and only run
in Mode 1:

- `vision-security-service` (workstation CV; not in `k8s/base/kustomization.yaml`)
- `pc-control` real desktop control (cluster runs the same image with
  `PC_CONTROL_STUB_MODE=true`; the `apps/pc-control` jar is the same artifact)

## Optional Components

- `voice-gateway` (degrade-to-WARN unless `JARVIS_REQUIRE_VOICE_GATEWAY=true`)
- `llm-service`, `memory-service`, `embedding-service`, `llm-server` (gated by
  `ENABLE_LLM` / `ENABLE_MEMORY`)
- `postgres-pgvector` (only when `ENABLE_MEMORY=true`)

## Unsupported / Drifted Combinations

- **`jarvis-launch.sh --enable-llm`** still references the
  `llm-server` Deployment. The manifest `k8s/overlays/prod/llm-server.yaml` is
  **deleted in the working tree** (see `git status` snapshot 2026-05-08), so
  `kubectl scale deployment llm-server` will fail until either the manifest is
  regenerated or the launcher is updated to target only `host-model-daemon`.
- ~~**`infra/k8s/` vs `k8s/`** drift between `jarvis-deploy-microk8s-prod.sh`
  and `jarvis-launch.sh`~~ — resolved. Both now default to `infra/k8s/` as the
  single canonical tree; `k8s/base/**` is frozen (see
  [`infra/k8s/README.md`](../infra/k8s/README.md)). `JARVIS_K8S_DIR` can still
  point `jarvis-launch.sh` at an alternate tree for migration smoke tests, but
  that override is not honored by production tooling.
- **Mobile (`apps/android-app`)** is a Gradle scaffold (Phase 12 Pass 1).
  No runtime mode produces an installed APK; build is operator-driven via
  `./gradlew assembleDebug` after running `gradle wrapper --gradle-version 8.5`.
