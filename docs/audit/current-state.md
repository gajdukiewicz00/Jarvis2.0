# Jarvis 2.0 — Current State (verified)

Audit date: **2026-05-26**
Verifier: Claude Opus 4.7 (1M) session, repo at `/home/kwaqa/Jarvis/Jarvis2.0`, branch `main`.
Method: live cluster inspection (read-only) + reactor build + static kustomize render + unit test.

> This document is the dated, **evidence-checked** snapshot that the Stage-1 audit
> brief asked for. It does not replace the deeper prior analysis in
> [`JARVIS_AUDIT_REPORT.md`](JARVIS_AUDIT_REPORT.md) (2026-05-09) — it updates the
> *runtime* picture with what is actually deployed today and records the
> deliverables added in this session.

## 0. Headline findings

1. **The runtime is NOT down — it is fully deployed and healthy on k3s.** A
   complete Jarvis stack has been running for **27 days** in the k3s namespace
   `jarvis-prod`. Every workload is Ready (22/22 Deployments `1/1`, 4/4
   StatefulSets ready). This is the real, canonical deployment.
2. **k3s — not MicroK8s — is the live cluster on this host.** `scripts/lib/k8s-common.sh`
   already supports k3s as a first-class fallback (`/etc/rancher/k3s/k3s.yaml`).
   A MicroK8s install was attempted this session but **cannot run** because k3s
   already binds the kubelet ports (`10250`/`10248`); MicroK8s was stopped to
   avoid the conflict. See [Risk R-1](#3-architectural-risks).
3. **The working tree builds.** `mvn -T1C -DskipTests clean compile` over the full
   reactor exits `0` despite the large in-flight change set.
4. **The canonical K8s tree renders.** `kubectl kustomize infra/k8s/overlays/prod`
   produces **137 objects** (22 Deployments, 4 StatefulSets, 36 NetworkPolicies,
   1 Ingress on `api/voice/grafana.jarvis.local`) — and that topology matches the
   live `jarvis-prod` namespace.
5. **Reproducibility caveat still stands:** `main` carries ~460 uncommitted files
   (operator-confirmed "treat as truth, do not commit", see JARVIS_AUDIT_REPORT
   §0). A fresh `origin/main` clone does not match the running system.

## 1. Service status table

Legend: **OK** verified healthy live · **PARTIAL** works with caveats / feature-flagged ·
**STUB** present but not real impl · **MISSING** named but absent · status is
*live-cluster* unless noted.

| Subsystem | Module(s) | Live (k3s jarvis-prod) | Notes / evidence |
| --- | --- | --- | --- |
| API gateway | `api-gateway` | **OK** | pod Ready 6d19h; ingress `api.jarvis.local`; `/api/v1/status/report` added this session (not yet deployed) |
| Security / auth | `security-service` | **OK** | pod Ready; dual-plane JWT (fail-closed default verified in code) |
| Orchestrator + safety gate | `orchestrator`, `libs/command-schema` | **OK** | pod Ready; `RiskClassification`/`IntentRiskCatalog`, `RiskLevel{SAFE,LOW,MEDIUM,HIGH,…}`, confirmation flow over RabbitMQ |
| NLP | `nlp-service` | **OK** | pod Ready; rule-based |
| LLM adapter | `llm-service` | **OK** | pod Ready; gated by `JARVIS_LLM_ENABLED`; facade over host/cluster daemon |
| LLM inference | `llm-server` (in-cluster), host `llama.cpp` daemon | **OK** | `llm-server` pod Ready; canonical Phase-3 path is host-model-daemon |
| Embeddings | `embedding-service` (py) | **OK** | pod Ready; consumed by memory-service |
| Memory | `memory-service` + `postgres-pgvector` | **OK** | pods Ready; pgvector StatefulSet up; Obsidian note + audit + screen-context controllers present |
| Voice | `voice-gateway` | **PARTIAL** | pod Ready but 15 restarts/4d — investigate stability; vosk STT + espeak-ng TTS; `ru-RU` default |
| Vision | `vision-security-service` | **PARTIAL** | **host-local**, not in `jarvis-prod` (no pod); gated by `VISION_SECURITY_ENABLED`+local-bridge |
| Desktop control | `pc-control` + `desktop-javafx` | **PARTIAL** | `pc-control` pod Ready (stub-mode in cluster); real control is workstation-local |
| Planner | `planner-service` | **PARTIAL** | pod Ready; LLM-enhancement endpoints throw "not implemented" (see COMPONENT_STATUS) |
| Life / analytics / smart-home / user-profile | resp. modules | **OK** | all pods Ready |
| Sync / cloud-relay | `sync-service`, `cloud-relay` | **OK / off-prem** | sync pod Ready; cloud-relay is off-prem (`k8s/cloud`) |
| Infra: Postgres / pgvector / Kafka / RabbitMQ | StatefulSets | **OK** | all `1/1`, 16–27d uptime |
| Observability | Prometheus, Grafana, Loki, Tempo, Alloy | **OK** | all pods Ready |
| `/status/report` aggregator | `api-gateway` (new) | **PARTIAL** | code + unit test added & passing this session; **not yet built into the deployed image** |
| Android app | `apps/android-app` | **PARTIAL** | Gradle module, experimental scaffold; not in Maven reactor |
| `llm-server-py` | `apps/llm-server-py` | **STUB/deprecated** | superseded by host model daemon |

No subsystem is currently **BROKEN** on the live cluster. No named subsystem is
**MISSING**.

## 2. Blockers

| ID | Blocker | Impact | Status |
| --- | --- | --- | --- |
| B-1 | ~460 uncommitted files on `main` | Fresh clone ≠ running system; CI/reviewers cannot reproduce | Open (operator chose do-not-commit; see `JARVIS_COMMIT_PLAN.md`) |
| B-2 | MicroK8s cannot coexist with the live k3s | The "install MicroK8s and smoke" path is blocked on this host | Mitigated: MicroK8s stopped; **use k3s** (already supported) |
| B-3 | New `/status/report` + smoke/e2e not in deployed image | Live cluster (6d-old images) lacks the new endpoint | Open: requires an image rebuild + rollout (production change → needs authorization) |

## 3. Architectural risks

- **R-1 (host has two k8s distributions).** k3s (live) + a freshly-installed,
  stopped MicroK8s. Decide one. Recommendation: **standardize on k3s** here (it is
  what is deployed and what `k8s-common.sh` already prefers via kubeconfig) and
  uninstall MicroK8s, *or* deliberately migrate. Do not leave both.
- **R-2 (voice-gateway restarts).** 15 restarts in 4 days suggests a recurring
  failure (likely TTS/STT model or mic device). Triage before relying on the
  voice demo.
- **R-3 (two K8s source trees).** `k8s/` (legacy, banner added) vs `infra/k8s/`
  (canonical). Already documented; finish retiring `k8s/overlays/prod-release-*`.
- **R-4 (smoke vs reality).** `scripts/k8s-smoke.sh` expects MicroK8s/standard
  kubectl; on this host it must be pointed at the k3s kubeconfig. The new
  `scripts/jarvis-smoke.sh` works against k3s when `kubectl` resolves to it.

## 4. Quick fixes (low risk)

- Point smoke scripts at k3s: `export KUBECONFIG=/etc/rancher/k3s/k3s.yaml` (or run
  via `sudo k3s kubectl`).
- Decide MicroK8s: `sudo snap remove microk8s` if standardizing on k3s.
- Rebuild + roll `api-gateway` to ship `/status/report` (needs operator approval).

## 5. Recommended execution order

1. **Decide the k8s story** (R-1): keep k3s, remove MicroK8s. (operator decision)
2. **Triage voice-gateway restarts** (R-2).
3. **Ship `/status/report`**: rebuild `api-gateway` image, roll the Deployment, then
   `scripts/jarvis-smoke.sh` against k3s validates it end to end.
4. **Run the e2e scenarios** (`scripts/e2e-*.sh`) against an authorized gateway with
   a token to convert the PARTIAL rows above into verified-live.
5. **Resolve B-1** via `JARVIS_COMMIT_PLAN.md` so the repo is reproducible.

## 6. This session's additions (working tree)

- `apps/api-gateway/.../status/` — `StatusReportService` + `HttpHealthProbe` +
  `StatusReportController` (`GET /api/v1/status/report`) + `StatusReportServiceTest`
  (5 tests, passing). Registered in the capability descriptor.
- `scripts/jarvis-smoke.sh` — single umbrella smoke (k8s + local modes, graceful SKIP).
- `scripts/e2e-{memory,vision,desktop-dry-run,dangerous-command,status-report}.sh`
  + `scripts/lib/e2e-common.sh`.
- Docs: this file, `docs/testing/smoke.md`, `docs/testing/e2e.md`, `docs/roadmap.md`.
