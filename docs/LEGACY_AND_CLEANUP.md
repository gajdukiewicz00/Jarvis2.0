# Legacy and Cleanup Inventory

Audit date: 2026-05-08. Branch: `main`. Snapshot is descriptive, **not
prescriptive** — nothing is deleted by this audit. Any item marked
`remove after confirmation` requires explicit operator approval before any
file is deleted.

## Purpose

This document tracks deprecated, legacy-candidate, unused-candidate, and
runtime-drift items found in the Jarvis2.0 repository. It is the
companion to [COMPONENT_STATUS.md](COMPONENT_STATUS.md) and
[RUNTIME_MODES.md](RUNTIME_MODES.md).

If a component is referenced by **at least one** canonical script, `pom.xml`,
Spring config, k8s manifest, or production README, it stays in
`legacy candidate` or `needs review`, **not** in `confirmed deprecated`.

## Status Legend

- **active** — exercised by at least one canonical entry point.
- **deprecated** — superseded but kept by an explicit ADR or README warning.
- **legacy candidate** — looks stale; one or more canonical paths still touch
  it. Cleanup blocked on operator review.
- **unused candidate** — no live consumers found in this audit; cleanup is
  still gated on operator review because external systems may reference it.
- **drifted** — referenced but the peer file/manifest has diverged or vanished.
- **remove after confirmation** — only after the operator has signed off.

## Confirmed deprecated

Items that have an explicit deprecation marker in code or ADRs.

| Item | Path | Reason | Evidence |
| --- | --- | --- | --- |
| Python LLM wrapper | [`apps/llm-server-py/`](../apps/llm-server-py/) | Phase 3 host model daemon supersedes it; only legacy local-runtime scripts still drive it. | [`apps/llm-server-py/README.md`](../apps/llm-server-py/README.md) lines 1–14: "Phase 3 (host model daemon) supersedes this Python wrapper." |
| Service Dockerfiles | `apps/<service>/Dockerfile` | Replaced by Jib-built OCI images. | Working-tree `D` for all 13 service `Dockerfile`s in `git status`; `pom.xml` lines 192–234 wire `jib-maven-plugin`; [ADR-0003](architecture/ADR/ADR-0003-docker-runtime-deprecation-before-removal.md). |
| Top-level `docker/` directory | `docker/` (gone from disk) | Superseded by `apps/embedding-service-py/Containerfile` and `apps/llm-server-py/Containerfile`. | Working-tree `D` for `docker/embedding-service/**`, `docker/llm-server/**`, `docker/postgres/`, `docker/rabbitmq/`, `docker/nginx/`, `docker/postgres-init/`, `docker/mosquitto/`. |
| `.dockerignore` | `.dockerignore` | Daemonless build path replaces docker CLI. | Working-tree `D` in `git status`. |
| `mosquitto` (cluster) | [`k8s/base/mosquitto/`](../k8s/base/mosquitto/) | Documented as legacy retained for future smart-home integration. | [`docs/architecture/phase-1-acceptance-evidence.md`](architecture/phase-1-acceptance-evidence.md) line 150: "Mosquitto is deprecated and only kept for the future smart-home integration". |
| Runtime guidance to `jarvis-launch.sh` k3s path | [`jarvis-launch.sh`](../jarvis-launch.sh) | The launcher itself notes its own deprecated-runtime label inside `README.md`/`k8s/README.md`. | [`README.md`](../README.md) line 64; [`k8s/README.md`](../k8s/README.md) line 32. The path is still wired and used; it's deprecated *as a release vehicle*, not as a developer launcher. |

## Legacy candidates

Items that look stale but at least one live consumer references them.

| Item | Path | Why suspicious | Risk if removed | Recommended action | Confidence | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| `k8s/` tree | [`k8s/`](../k8s/) | `infra/k8s/README.md` declares `infra/k8s/` canonical; phase-1 evidence calls `k8s/` legacy. | High — `jarvis-launch.sh`, `make launch`, all `scripts/product/jarvis-deploy-prod*.sh`, and the digest-pinned `prod-release` overlay live here. | needs manual review; do not remove until launcher migrates | medium | [`docs/architecture/phase-1-acceptance-evidence.md`](architecture/phase-1-acceptance-evidence.md) line 164; [`jarvis-launch.sh`](../jarvis-launch.sh) line 13 |
| `infra/k8s/` tree | [`infra/k8s/`](../infra/k8s/) | Parallel to `k8s/`; the only canonical scripts referencing it are `jarvis-deploy-microk8s-prod.sh` and `verify-prod.sh`. | Medium — `verify-prod.sh` is in `Makefile` (`make verify-prod`); `jarvis-deploy-microk8s-prod.sh` is the documented MicroK8s path. | needs manual review; converge with `k8s/` | medium | `diff -rq k8s/base infra/k8s/base` shows kafka, rabbitmq, and several diverged manifests |
| Internal-TLS slice overlays | [`k8s/overlays/prod-release-internal-tls-*/`](../k8s/overlays/) (~25 directories) | Each pair has its own `apply`/`generate`/`smoke`/`deploy` script — large surface area. | Medium — each is a documented deployment slice with its own README. | mark legacy candidate; treat as one slice family in cleanup planning | low | `scripts/product/jarvis-apply-internal-tls-*.sh` (×20+) |
| Pre-Phase-0 archive | (operator-side archive `~/Jarvis/_analysis/old-archive/`) | Pre-Phase-0 codebase per [milestone-1-architecture-lock.md](architecture/milestone-1-architecture-lock.md) line 115. | Low — operator-side, not in the repo. | keep until thesis defense | high | milestone-1-architecture-lock.md |
| `docs/services/llm-server.md` | [`docs/services/llm-server.md`](services/llm-server.md) | Still references `docker/llm-server/...` paths that no longer exist. | Low — doc only. | update doc to point at `apps/llm-server-py/` and add a banner | high | `head -5` of the file references `docker/llm-server` |
| `docs/services/embedding-service.md` | [`docs/services/embedding-service.md`](services/embedding-service.md) | Still references `docker/embedding-service/...` paths that no longer exist. | Low — doc only. | update doc to point at `apps/embedding-service-py/` and add a banner | high | `head -5` of the file references `docker/embedding-service` |
| `docs/archive/docker-root-symlink-fix.md` | [`docs/archive/docker-root-symlink-fix.md`](archive/docker-root-symlink-fix.md) | Already self-described as archived. | Low | keep — already correctly archived | high | header line 1 |

## Unused candidates

Items with no canonical caller in this audit. They are still kept; cleanup
only after the operator confirms no external/CI/cron consumer relies on them.

| Item | Path | Why suspicious | Risk if removed | Recommended action | Confidence | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Older log exports | [`logs/exports/`](../logs/) (5 directories incl. `last-run-20260328-1608 (2)`) | Operator-side run captures with timestamped names. | Low | keep until operator confirms; not a build input | high | `find logs -maxdepth 4` |
| `logs/` at repo root | [`logs/`](../logs/) | Root-level runtime captures committed to the repo. | Low | needs manual review — confirm `.gitignore` policy | medium | working-tree shows `M .gitignore` and committed `logs/exports/` content |
| `scripts/logs/api-gateway-*.log` | [`scripts/logs/`](../scripts/logs/) | Old captured runtime logs; not consumed by any script. | Low | needs manual review | medium | `grep -r "scripts/logs" --include="*.sh"` returns no consumers |
| `models/llm/`, `models/stt/{vosk,whisper}/` | [`models/`](../models/) | Top-level model storage with a `README.md`; Mode-1 default is `~/.jarvis/models/`. | Medium — `jarvis-launch.sh` defaults `JARVIS_MODELS_DIR` to `${PROJECT_DIR}/models` if env not set. | keep — load-bearing default | high | [`jarvis-launch.sh`](../jarvis-launch.sh) line 17 |

## Runtime drift

Cases where docs / scripts / manifests disagree.

| Drift | Where | Reality | Fix path |
| --- | --- | --- | --- |
| Working-tree deletion of `k8s/overlays/prod/llm-server.yaml` while `jarvis-launch.sh` still scales `deployment/llm-server`. | [`jarvis-launch.sh`](../jarvis-launch.sh) lines 1265, 1275, 1314 vs `git status` working-tree `D k8s/overlays/prod/llm-server.yaml` (deleted on disk). | If a user runs `jarvis-launch.sh --enable-llm` from this working tree, the deployment scale call will fail because the manifest is no longer applied. | **RESOLVED (2026-07-04):** canonical tree is `infra/k8s/` (`jarvis-launch.sh` `K8S_DIR` line 16). The in-cluster llm-server is retained: `infra/k8s/overlays/prod/llm-server.yaml` IS present and wired (overlay `kustomization.yaml` resources, `networkpolicy-allowlist.yaml`, `patches/local-prod-replicas.yaml`), pinned at tag `movie9`. Because the launcher renders from `infra/k8s/`, `--enable-llm` scaling `deployment/llm-server` is backed by an applied manifest; the legacy `k8s/overlays/prod/llm-server.yaml` deletion no longer affects the launcher. |
| `docker/` references in service docs | [`docs/services/llm-server.md`](services/llm-server.md), [`docs/services/embedding-service.md`](services/embedding-service.md) point at `docker/embedding-service/app/main.py` etc. | The `docker/` directory has been removed. The Python workers live under `apps/embedding-service-py/` and `apps/llm-server-py/`. | Doc update — banner + path swap (handled by this audit). |
| README "no mobile client/module exists" vs catalog row "`apps/android-app` Phase 12 scaffold" | [`README.md`](../README.md) line 86 vs line 170 (in pre-audit copy). | The Android scaffold exists as a Gradle subproject excluded from the Maven reactor. | Doc update — single source of truth (handled by this audit). |
| README "optional Python AI workers under `docker/`" | [`README.md`](../README.md) line 13 (pre-audit). | Workers now live under `apps/{embedding-service-py,llm-server-py}` and the `docker/` directory is gone. | Doc update (handled by this audit). |
| `infra/k8s/README.md` calls itself canonical for production while `jarvis-launch.sh` and `prod-release/` live under `k8s/`. | [`infra/k8s/README.md`](../infra/k8s/README.md) lines 1–20 vs [`jarvis-launch.sh`](../jarvis-launch.sh) line 13. | Two trees both have live consumers. | needs manual review; one should be designated; update READMEs once the operator picks a direction. |
| `desktop-client` label still appears in some payloads | [`README.md`](../README.md) line 55; [`docs/architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md`](architecture/SPEC-1-Jarvis-Local-AI-Operating-System.md) line 67 | Label refers to today's `desktop-javafx`, not a separate module. | docs are already explicit; keep the disambiguation paragraph |
| `planner-service` LLM endpoints return `501` | [`apps/planner-service/src/main/java/org/jarvis/planner/service/LlmEnhancementService.java:33,43`](../apps/planner-service/src/main/java/org/jarvis/planner/service/LlmEnhancementService.java) | Endpoints throw `UnsupportedOperationException` at the service layer. | Already documented in [`docs/services/planner-service.md`](services/planner-service.md) line 144. |
| Runtime smart-home executor is logging-stub | [`docs/architecture/milestone-1-architecture-lock.md`](architecture/milestone-1-architecture-lock.md) line 293 | "logging-only stub (`executor: logging-stub`)" | Documented; not in scope for cleanup. |

## Do not delete without confirmation

These look old, but at least one canonical script touches them:

- [`k8s/`](../k8s/) — entire tree is the workspace launcher target.
- [`infra/k8s/`](../infra/k8s/) — `verify-prod.sh` and `jarvis-deploy-microk8s-prod.sh` target it.
- [`apps/llm-server-py/`](../apps/llm-server-py/) — local AI scripts still launch the Python wrapper.
- [`apps/embedding-service-py/`](../apps/embedding-service-py/) — `memory-service` requires it.
- [`docker/llm-server/`](../docker/llm-server/) (gone on disk, present in git index) — referenced by `apps/llm-server-py/README.md` and the README in `infra/k8s/`. Restoring vs. cleaning up requires operator decision.
- [`scripts/runtime/llm_server_stub.py`](../scripts/runtime/llm_server_stub.py) — used by phase-0 evidence scripts and runtime smoke.
- [`scripts/runtime/VoiceWsScenario.java`](../scripts/runtime/VoiceWsScenario.java), [`scripts/runtime/WsProbe.java`](../scripts/runtime/WsProbe.java) — diagnostics for voice WS.
- [`scripts/runtime/make_service_jwt.py`](../scripts/runtime/make_service_jwt.py) — used by service-to-service JWT helpers.
- All [`scripts/product/jarvis-*.sh`](../scripts/product/) — referenced by Make targets and the launcher.
- [`config/grafana-dashboards/`](../config/grafana-dashboards/) — provisioned at launch.
- [`assets/desktop/`, `assets/icons/`, `icons/`](../assets/) — packaged into the desktop app.

## Recommended cleanup plan

### P0 — Documentation and runtime truth (this audit)

- [x] Add a single source of truth for component status — `docs/COMPONENT_STATUS.md`
- [x] Document supported runtime modes — `docs/RUNTIME_MODES.md`
- [x] Document legacy and cleanup state — this file
- [x] Fix README mobile contradiction and `docker/` references
- [x] Add banners to `docs/services/llm-server.md` and `docs/services/embedding-service.md`
- [x] Operator decision (2026-07-04): **YES** — `jarvis-launch.sh --enable-llm`
      keeps scaling `deployment/llm-server`. The in-cluster llm-server is
      retained on the canonical `infra/k8s/` tree, where
      `infra/k8s/overlays/prod/llm-server.yaml` **is present and wired** (overlay
      `kustomization.yaml` resources, `networkpolicy-allowlist.yaml`, and
      `patches/local-prod-replicas.yaml`) and pinned at tag `movie9`. The legacy
      `k8s/overlays/prod/llm-server.yaml` working-tree deletion is moot on the
      canonical tree.
- [x] Operator decision (2026-07-04): **`infra/k8s/` is the go-forward
      production layout** — it is the tree `jarvis-launch.sh` renders via
      `K8S_DIR=${PROJECT_DIR}/infra/k8s` (line 16). Legacy `k8s/` is retained
      frozen for reference until a later removal pass; both READMEs should note
      `infra/k8s/` as canonical.

### P1 — Marking deprecated paths

- [ ] Once the operator confirms the docker → Containerfile migration is
      complete, lock the policy with `scripts/guards/reject-new-docker-runtime-files.sh`
      in CI (already exists) and remove the `docker/` references from any
      remaining ADR or evidence document.
- [ ] Tag a single ADR pinning `apps/desktop-javafx` as the only desktop
      module (already covered by [ADR-0002](architecture/ADR/ADR-0002-desktop-javafx-native-desktop-agent.md))
      and ensure `desktop-client` is removed from runtime payloads.

### P2 — Future removal candidates (after confirmation)

- [ ] `apps/llm-server-py/` after the local runtime is migrated to drive the
      host model daemon directly (see Phase 7 plan in
      `apps/llm-server-py/README.md`).
- [ ] One of `k8s/` or `infra/k8s/` after the migration decision is made.
- [ ] `mosquitto` once the smart-home Phase finalizes its broker choice.
- [ ] Repo-tracked `logs/`, `scripts/logs/` after operator confirms no consumer.

## Verification commands used

```bash
git status --short
find . -maxdepth 3 -type d -not -path '*/.*' | sort
find . -name pom.xml -not -path '*/target/*' | sort
diff -rq k8s/base infra/k8s/base
diff -rq k8s/overlays/prod infra/k8s/overlays/prod
grep -RnE 'deprecated|legacy|obsolete|TODO|FIXME|HACK|stub|placeholder|not implemented|Not Implemented' \
  docs k8s scripts infra/k8s --include="*.md"
grep -RnE 'llm-server' --include="*.sh" --include="*.yaml" --include="*.yml" --include="*.md"
grep -nE '501|Not Implemented|not implemented' apps/planner-service/src/main/java -r
ls apps/embedding-service-py apps/llm-server-py apps/android-app
```

Outcomes are summarized inline in each table above.
