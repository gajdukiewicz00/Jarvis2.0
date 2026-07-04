# Jarvis 2.0 — Capabilities Matrix

Audit date: 2026-05-09
Source: [docs/COMPONENT_STATUS.md](COMPONENT_STATUS.md), [docs/audit/JARVIS_AUDIT_REPORT.md](audit/JARVIS_AUDIT_REPORT.md), code in `apps/`.

This matrix tells a recruiter, professor, or potential contributor at a glance what works today and what does not. It is updated at every release per [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) § 6.

> **2026-07-04 honesty note — "code-DONE" vs "verified-live":** the ✅/🟡 marks below
> predate the 2026-06 movie-upgrade sessions logged in [docs/COMPONENT_STATUS.md](COMPONENT_STATUS.md)
> and were last exercised while the cluster was up. The cluster (`jarvis-prod`) is
> currently **DOWN** after a 2026-07-04 host reboot — recover via
> `scripts/product/jarvis-recover-after-reboot.sh`. A ✅ here means **the implementation
> exists, compiles, and was exercised at least once** — it does **not** mean it has been
> re-run against the live stack since this reboot. Per
> [`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md) §(d),
> roughly **15-20%** of the wider 708-story backlog is headlessly re-verifiable
> (API-level, scriptable) right now; the rest of the DONE/PARTIAL figure is "verified by
> reading the code and its tests" — materially weaker than "watched it work live."
> Voice E2E, desktop GUI visuals, real (non-stub) PC actions, and Android pairing are
> the clearest **code-DONE-but-not-verified-live** examples (see that audit's §(d) for
> specifics on each).

## Status Legend

- ✅ **working** — implemented, tested in the canonical demo path, evidence linked.
- 🟡 **partial** — works for the happy path but has known caveats; OK for demo with disclaimer.
- 🟠 **experimental** — committed code; not in canonical demo; subject to change without notice.
- ⏳ **planned** — not implemented; in scope for a future release.
- ❌ **not in scope** — explicitly out of scope for v1.0; documented under [JARVIS_TARGET_STATE.md](architecture/JARVIS_TARGET_STATE.md) § 17.

## Capability Table

| # | Capability | Status | Where it lives | Evidence |
| --- | --- | --- | --- | --- |
| 1 | User registration / login (BCrypt + signed JWT) | ✅ | `security-service` | [security-service.md](services/security-service.md) |
| 2 | JWT refresh-token rotation with reuse detection | ✅ | `security-service` | [AUTH_MODEL.md](security/AUTH_MODEL.md) |
| 3 | Service-to-service auth via `X-Service-Token` | ✅ | `jarvis-common` `ServiceJwtProvider` | [AUTH_MODEL.md](security/AUTH_MODEL.md) |
| 4 | API gateway (REST + WS, JWT validation, request logging) | ✅ | `api-gateway` | [api-gateway.md](services/api-gateway.md) |
| 5 | Russian voice loop (vosk STT + espeak-ng TTS, WS) | ✅ | `voice-gateway` | [voice-gateway.md](services/voice-gateway.md), [DEMO.md](DEMO.md) Step 5 |
| 6 | English voice loop (vosk en-US) | 🟡 | `voice-gateway` (model present, default is ru-RU) | configurable via `JARVIS_VOSK_MODEL_PATH_EN` |
| 7 | Wake-word ("привет, Джарвис") via Porcupine | 🟡 | `voice-gateway` (requires `PORCUPINE_ACCESS_KEY`) | optional; button-to-talk works without it |
| 8 | NLP intent extraction (rule-based) | ✅ | `nlp-service` | [nlp-service.md](services/nlp-service.md) |
| 9 | NLP intent extraction (LLM-assisted) | 🟠 | `nlp-service` (calls `llm-service` if `ENABLE_LLM=true`) | experimental; rule-based stays primary |
| 10 | Orchestrator (intent → action router) | ✅ | `orchestrator` | bounded router, not general workflow engine |
| 11 | PC control (real, on workstation) | ✅ | `pc-control` (local mode) | LinuxSystemControlService |
| 12 | PC control (in K8s) | 🟡 | `pc-control` (`PC_CONTROL_STUB_MODE=true` in cluster) | stub by design |
| 13 | Smart-home device catalog (static, in-memory) | ✅ | `smart-home-service` | [smart-home-service.md](services/smart-home-service.md) |
| 14 | Smart-home real device wiring (Zigbee2MQTT, MQTT bridges) | ⏳ | future v1.1+ | mosquitto broker present in K8s |
| 15 | Life-tracker: finance | ✅ | `life-tracker` | V1+ migrations |
| 16 | Life-tracker: calendar | ✅ | `life-tracker` | V4 migration |
| 17 | Life-tracker: time tracking | ✅ | `life-tracker` | V5 migration |
| 18 | Analytics summaries over life-tracker | ✅ | `analytics-service` | [analytics-service.md](services/analytics-service.md) |
| 19 | Planner: tasks, reminders, recommendations | ✅ | `planner-service` | V1+ migrations |
| 20 | Planner: LLM-enhanced suggestions | ❌ (501) | `planner-service` `LlmEnhancementService` returns 501 | by design until LLM-tooling is mature |
| 21 | User profile / context | ✅ | `user-profile` | V1+ migrations |
| 22 | LLM facade (authenticated AI gateway) | ✅ | `llm-service` (gated by `ENABLE_LLM=true`) | [llm-service.md](services/llm-service.md) |
| 23 | LLM inference via `host-model-daemon` (llama.cpp on host) | ✅ | infra/scripts/model-runtime/, `k8s/base/host-model-daemon/` | Phase 3 ADR |
| 24 | LLM inference via `apps/llm-server-py` (Python wrapper) | 🟠 deprecated | retained for legacy local AI scripts | Phase-7 to retire |
| 25 | Semantic memory (pgvector + ANN) | ✅ | `memory-service` (`ENABLE_MEMORY=true`) | V2 pgvector migration |
| 26 | Embedding worker (multilingual-e5-small, 384 dims) | ✅ | `apps/embedding-service-py` (FastAPI) | port 15001 |
| 27 | Vision security: owner verification (host camera + screen capture) | 🟡 local only | `vision-security-service` | port 8094, local only |
| 28 | E2E sync inbox for paired devices | 🟠 | `apps/sync-service` + `apps/cloud-relay` | ADR-0013 |
| 29 | Android mobile client | 🟠 (Phase 12 scaffold) | `apps/android-app` (Gradle, not in Maven reactor) | ADR-0013 |
| 30 | JavaFX desktop shell + launcher | ✅ | `apps/desktop-javafx` | [desktop-javafx.md](services/desktop-javafx.md) |
| 31 | Desktop endpoint auto-detection | ✅ | `LocalRuntimeEndpointDetector` | works with `~/.jarvis/run/last-run.json` |
| 32 | Desktop manual endpoint override with validate-on-save | ⏳ v1.0 fix | `SettingsView.kt` | audit P1-011 |
| 33 | Desktop voice tab with separate STT / TTS pills | ⏳ v1.0 fix | `VoiceView` adapter | audit P1-013 |
| 34 | Desktop token storage (encrypted at rest) | ⏳ v1.1 | `TokenManager.kt` (currently plaintext Java Preferences) | audit P1-008 |
| 35 | Observability stack (Prometheus + Loki + Tempo + Grafana + Alloy) | ✅ K8s only | `infra/k8s/base/observability/`, `k8s/base/observability/` | [observability-stack.md](services/observability-stack.md) |
| 36 | Pre-built Grafana dashboards | 🟡 | `config/grafana-dashboards/`, provisioned via script | [observability-stack.md](services/observability-stack.md) |
| 37 | Trace ID propagation (Sleuth → Tempo via OTLP) | ✅ | every Spring service has tracing config | OTLP at 4317/4318 |
| 38 | Local Postgres (managed `pgvector/pgvector:pg16` container) | ✅ | scripts/runtime/common.sh | container `jarvis-local-postgres` |
| 39 | K8s deployment (`infra/k8s/`, **canonical from 2026-05-09**) | ✅ | `infra/k8s/overlays/prod` | `./scripts/product/jarvis-deploy-microk8s-prod.sh` |
| 40 | K8s deployment (`k8s/`, **legacy**) | 🟡 working but legacy | `k8s/overlays/prod`, `prod-release` | `jarvis-launch.sh`; see [k8s/README.md](../k8s/README.md) deprecation banner |
| 41 | K8s digest-pinned release overlay | ✅ | `k8s/overlays/prod-release/kustomization.yaml` | generated by `jarvis-promote-images.sh` |
| 42 | K8s pod security restricted profile (Kyverno enforce) | ✅ | `k8s/overlays/prod/kyverno/` | 7 policies |
| 43 | K8s NetworkPolicies | ✅ | `k8s/base/observability/networkpolicy.yaml`, `k8s/overlays/prod/networkpolicy-*.yaml` | layered |
| 44 | TLS at ingress (`api/voice/grafana.jarvis.local`) | ✅ | `k8s/base/ingress.yaml` | `force-ssl-redirect` |
| 45 | Internal mTLS between services | 🟠 experimental | 20 `k8s/overlays/prod-release-internal-tls-*/` slice studies | not in canonical release |
| 46 | Image signing (cosign) | ⏳ v1.1 | tracked in SECURITY_HARDENING_PLAN.md | not yet in CI |
| 47 | SBOM generation (cdxgen) | ⏳ v1.1 | tracked in SECURITY_HARDENING_PLAN.md | not yet in CI |
| 48 | SAST (CodeQL or similar) | ⏳ v1.1 | tracked in SECURITY_HARDENING_PLAN.md | not yet in CI |
| 49 | Dependency CVE scan in CI (trivy / dependabot) | 🟡 | dependabot present in repo | trivy missing |
| 50 | Per-jti access-token revocation cache | ⏳ v1.1 | audit P1-006 | refresh-token revocation works today |
| 51 | Multi-key JWT rotation with `kid` claim | ⏳ v1.1 | audit; today single-key only | hardening backlog |
| 52 | OIDC / multi-tenant auth | ❌ not in scope | future | v1.0 is single-user |
| 53 | Public-internet hardening profile | ❌ not in scope | future | v1.0 is LAN/host-only |

## Counts (v1.0 status)

| Status | Count | % |
| --- | --- | --- |
| ✅ working | 25 | 47% |
| 🟡 partial | 10 | 19% |
| 🟠 experimental | 6 | 11% |
| ⏳ planned | 9 | 17% |
| ❌ not in scope | 3 | 6% |

A v1.0 release is sane to ship when the ✅ + 🟡 set covers the canonical demo path (it does today), every 🟡 has an honest disclaimer in the README or per-service doc (audit gap-list), and every ⏳ in v1.1 has a tracked issue.

**Code-DONE vs verified-live (2026-07-04):** treat every ✅/🟡 row above as "implementation
exists and was exercised at least once," not "verified against the currently-running
stack" — the cluster is down as of 2026-07-04. See
[`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md)
for the honest current split between headlessly re-verifiable capabilities (~15-20%) and
code-verified-only ones.

## How to update this matrix

1. After any feature work that promotes a row from 🟡 → ✅ or ⏳ → 🟠/🟡, update this file.
2. The row's "Where it lives" column must point at a file or package; the "Evidence" column must point at a doc, test, or smoke-script that the reader can run.
3. The release checklist § 6 verifies this file is current within 2 weeks of release.
