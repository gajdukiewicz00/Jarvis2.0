# Jarvis 2.0 — Release Checklist

The release manager runs this checklist when promoting a build to v1.0 or any subsequent minor.

For target-state criteria see [docs/architecture/JARVIS_TARGET_STATE.md](architecture/JARVIS_TARGET_STATE.md) § 16.
For the audit findings driving the gate criteria see [docs/audit/JARVIS_AUDIT_REPORT.md](audit/JARVIS_AUDIT_REPORT.md).

---

## 0. Pre-release sanity (≤ 5 minutes)

- [ ] On the target branch (`main` for v1.0), `git status --short` returns ≤ 5 lines.
- [ ] No uncommitted secrets: `git diff main..HEAD -- '*.env*' '*secret*' '*.key' '*.pem'` returns nothing.
- [ ] Last commit author is intentional, not a bot/agent without authorization.

## 1. Build (≤ 15 minutes)

- [ ] `mvn -DskipTests package` succeeds from a fresh clone in `< 10` min.
- [ ] `mvn test` succeeds in `< 15` min.
- [ ] `mvn verify -Pintegration` succeeds with podman + pgvector container reachable. Document failures with the exact stack trace and either fix or mark accepted.
- [ ] No new `target/` size explosions: `du -sh apps/*/target | sort -h` — flag any module > 200 MB.
- [ ] `./scripts/ci/check-backend-release-wiring.sh` passes (the same check CI runs).

## 2. Local runtime smoke (≤ 10 minutes)

- [ ] `./scripts/runtime-up.sh` brings every core service to UP within 180 s.
- [ ] `./scripts/runtime-status.sh` shows every line UP.
- [ ] `./scripts/runtime-smoke.sh` exits 0 (auth register, voice/pc WS roundtrip).
- [ ] `./scripts/product/jarvis-desktop-launch.sh` opens the JavaFX shell without exception. Login works against the local runtime.
- [ ] `./scripts/runtime-down.sh` terminates cleanly. State under `~/.jarvis/run/local-runtime/` is consistent (no orphan PID files).

## 3. AI smoke (≤ 10 minutes)

- [ ] `ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh` brings up the AI services.
- [ ] `./scripts/ai-local-smoke.sh` exits 0.
- [ ] LLM call via `curl http://localhost:8091/api/v1/llm/health` returns model loaded.
- [ ] Memory health: `curl http://localhost:8093/actuator/health` returns UP.
- [ ] Embedding service: `curl http://localhost:15001/health` returns model_loaded=true.
- [ ] Voice end-to-end via `./scripts/voice-local-smoke.sh` returns OK.

## 4. K8s smoke (≤ 30 minutes; required only for K8s release)

- [ ] `./scripts/product/jarvis-deploy-microk8s-prod.sh` succeeds (canonical `infra/k8s/overlays/prod`).
- [ ] `./scripts/verify-prod.sh` succeeds.
- [ ] `kubectl get pods -n jarvis-prod` shows all Running, no CrashLoopBackOff.
- [ ] `kubectl get ingress -n jarvis-prod` shows the three hostnames (`api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local`).
- [ ] `./scripts/verify-observability.sh` passes (datasources reachable, dashboards present, log+trace ingestion).

## 5. Security checks (≤ 15 minutes)

- [ ] `JWT_SECRET` and `SERVICE_JWT_SECRET` are independently random in `~/.jarvis/secrets/secrets.env`. No `JWT_SECRET=secret` placeholder in production secrets.
- [ ] `JARVIS_ALLOW_DISTINCT_LOCAL_SERVICE_JWT_SECRET=true` in any production environment env-file (so `SERVICE_JWT_ALLOW_SHARED_SECRET=false` is exported and the new guard catches a misconfigured fallback).
- [ ] `gitleaks detect` returns no findings.
- [ ] `trivy fs --severity HIGH,CRITICAL .` returns no unaccepted findings.
- [ ] `kubectl kustomize infra/k8s/overlays/prod | trivy config -` returns no unaccepted findings.
- [ ] CORS allowed-headers in `apps/api-gateway/src/main/resources/application.yaml` does **not** contain `"*"` (P1-010 closed).
- [ ] No new secret in `git log -- 'secrets/**'` since last release.

## 6. Documentation freshness (≤ 10 minutes)

- [ ] [README.md](../README.md) Quickstart section runs successfully on a clean machine.
- [ ] [docs/DEMO.md](DEMO.md) demo path runs successfully on a clean machine. **[1]**
- [ ] [docs/CAPABILITIES.md](CAPABILITIES.md) status matrix matches the current code (no module says "active" while its tests fail).
- [ ] [docs/COMPONENT_STATUS.md](COMPONENT_STATUS.md) audit date is within 2 weeks of release.
- [ ] [docs/security/SECURITY.md](security/SECURITY.md) lists the current open hardening items and whether each is mitigated, accepted, or still open.
- [ ] [SECURITY.md](../SECURITY.md) at root links to a working email and the canonical security docs.
- [ ] No README link returns 404: `find . -name '*.md' -not -path './target/*' -not -path './.git/*' -exec grep -l '\](.*\.md)' {} \; | xargs -I{} sh -c 'echo === {} ===; grep -oE "\]\([^)]+\.md(#[^)]+)?\)" {}'` (manual link check).

**[1]** `docs/DEMO.md` is now **SUPERSEDED** for the current k3s + Piper +
Qwen3-14B stack (see the banner at the top of that file); the canonical demo
path is [docs/HUMAN_LAYER_DEMO_RUNBOOK.md](HUMAN_LAYER_DEMO_RUNBOOK.md).
Treat this checklist item as **N/A** until `docs/DEMO.md` is updated or
retired — run the human-layer runbook's demo path instead and note that
substitution in the release notes.

## 7. Tagging and announcement

- [ ] Bump version in root `pom.xml`.
- [ ] `git tag -a v1.0.0 -m "Jarvis 2.0 release v1.0.0 (date: YYYY-MM-DD)"`.
- [ ] `git push origin v1.0.0`.
- [ ] Draft GitHub release notes from `git log v0.x..v1.0.0 --oneline` plus the audit report's Resolution column.
- [ ] If any P0/P1 audit finding remained open, document it explicitly under "Known limitations" in the release notes. **Do not omit.**

## 8. Post-release verification (≤ 1 hour)

- [ ] On a separate machine: clone the tag, run [docs/DEMO.md](DEMO.md) end-to-end. Note any surprise.
- [ ] Confirm the GitHub Security Advisory page shows the linked `SECURITY.md`.
- [ ] Update [docs/audit/JARVIS_AUDIT_REPORT.md](audit/JARVIS_AUDIT_REPORT.md) with the audit-date for the next iteration.

## 9. Rollback plan (have ready before you tag)

- [ ] Identify the last-known-good tag (e.g. `v0.9.0`) and document the rollback command: `kubectl apply -k infra/k8s/overlays/prod-vX.Y.Z` or the equivalent.
- [ ] Confirm the `jarvis-secrets` Secret is not changed in the new release; if it is, document the rotation step explicitly.
- [ ] Confirm Postgres migrations are forward-only (no `V*__delete_*.sql` requiring manual revert) — the codebase intentionally avoids destructive migrations.

## 10. What we do NOT promise in v1.0

These are explicit and recorded so reviewers don't expect them:

- No multi-user / multi-tenant.
- No public-internet exposure.
- No mobile app.
- No commercial-grade SLA.
- No SaaS hosting.

Anyone surprised by these absences should be redirected to [docs/architecture/JARVIS_TARGET_STATE.md](architecture/JARVIS_TARGET_STATE.md) § 17 (post-v1.0 scope).
