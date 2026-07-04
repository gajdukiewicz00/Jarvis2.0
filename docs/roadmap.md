# Jarvis 2.0 — Roadmap

Status as of **2026-05-26**. Honest done/partial/next. Cross-refs:
[`docs/audit/current-state.md`](audit/current-state.md) (verified live snapshot),
[`docs/COMPONENT_STATUS.md`](COMPONENT_STATUS.md),
[`docs/audit/JARVIS_AUDIT_REPORT.md`](audit/JARVIS_AUDIT_REPORT.md).

## ✅ Done (verified)

- **Local-first microservice stack deployed and healthy** on k3s (`jarvis-prod`,
  27 days uptime, all pods Ready). No cloud LLM calls (`LocalOnlyEnforcer`).
- **Canonical deployment path**: `infra/k8s/overlays/prod` renders to 137 objects
  and matches the live namespace; `k8s/` carries a LEGACY banner.
- **Auth**: dual-plane JWT, fail-closed default, distinct service-secret
  enforcement (verified in code).
- **Datastores**: Postgres + pgvector + Kafka + RabbitMQ StatefulSets up.
- **Observability**: Prometheus, Grafana, Loki, Tempo, Alloy deployed.
- **Safety gate**: deterministic `RiskClassification`/`IntentRiskCatalog` +
  confirmation flow over RabbitMQ; `RiskLevel{SAFE..HIGH}`.
- **Build**: full Maven reactor compiles on the working tree.
- **New this session**: `/api/v1/status/report` aggregator (+ passing unit test),
  `scripts/jarvis-smoke.sh`, five `scripts/e2e-*.sh` scenarios, testing docs.
- **Movie-J.A.R.V.I.S. upgrade (2026-06, see [`MOVIE_JARVIS.md`](MOVIE_JARVIS.md))**: brain
  upgraded to **Qwen3-14B on GPU** (host + whole cluster via `host-model-daemon`); **Piper
  neural voice** on host and in `voice-gateway` (host-tts-daemon); single movie persona
  (host + cluster, language-adaptive); **cluster proactively speaks** (life-tracker
  `ProactiveWarningScheduler` → `/internal/voice/notify`); orchestrator + planner LLM enabled
  (planner endpoints now delegate, no more `NOT_IMPLEMENTED`); life-tracker activity now
  **persisted in Postgres** (Flyway V8); screen-context → cluster memory (`jarvis-memory-sync.sh`);
  `/status/report` shipped; `JwtServiceTest` added. Working tree not committed (operator rule).

## 🟡 Partial (works with caveats)

- **Voice loop** — deployed, but `voice-gateway` shows 15 restarts/4d; triage
  STT/TTS/mic stability. Default `ru-RU`, configurable. Push-to-talk fallback.
- **Vision** — real code (capture, OCR, screen-context, CV analysis) but
  **workstation-local**; not in `jarvis-prod`, gated by `VISION_SECURITY_ENABLED`
  + local-bridge. Automated OCR-of-known-text verification still manual.
- **Desktop control** — `pc-control` runs in stub mode in-cluster; real control is
  the host JavaFX/agent path.
- **`/status/report`** — code + test in working tree; **not yet in the deployed
  image** (needs `api-gateway` rebuild + rollout).
- **Planner LLM enhancement** — endpoints throw "not implemented".
- **Reproducibility** — ~460 uncommitted files on `main` (operator: do-not-commit);
  see `JARVIS_COMMIT_PLAN.md`.

## 🔜 Next

1. **Pick one k8s distribution on this host** — standardize on k3s (live) and
   remove the conflicting MicroK8s, or deliberately migrate. (blocker R-1)
2. **Triage voice-gateway restarts.** (R-2)
3. **Ship `/status/report`**: rebuild + roll `api-gateway`; then `jarvis-smoke.sh`
   and `e2e-status-report.sh` verify it live.
4. **Run the e2e suite** against an authorized gateway with a token to promote the
   PARTIAL rows in `current-state.md` to verified-live.
5. **Finish retiring** `k8s/overlays/prod-release-internal-tls-*` slice studies.
6. **Implement planner LLM-enhancement** or downgrade its advertised capability.
7. **Resolve the uncommitted-tree** per `JARVIS_COMMIT_PLAN.md` for reproducibility.

## Out of scope (by design, single-user local v1.0)

Multi-user / OIDC, cloud inference, off-prem data — explicitly not pursued; see
ADRs under `docs/architecture/ADR/`.
