# Jarvis 2.0 — Audit Report

Audit date: 2026-05-09
Auditor: autonomous senior engineer pass (Claude Opus 4.7, 1M context)
Scope: full repo at `/home/kwaqa/Jarvis/Jarvis2.0`, branch `main`
Method: repo-first, evidence-first. Every claim cites file path and line.

Cross-references:
- Existing per-component status: [docs/COMPONENT_STATUS.md](../COMPONENT_STATUS.md)
- Existing runtime modes: [docs/RUNTIME_MODES.md](../RUNTIME_MODES.md)
- Existing security audit: [docs/security/SECURITY_AUDIT.md](../security/SECURITY_AUDIT.md)
- Existing hardening plan: [docs/security/SECURITY_HARDENING_PLAN.md](../security/SECURITY_HARDENING_PLAN.md)
- Existing legacy/cleanup tracking: [docs/LEGACY_AND_CLEANUP.md](../LEGACY_AND_CLEANUP.md)

This audit does **not** duplicate those documents. It synthesizes them with fresh evidence and produces a single, prioritized punch list.

---

## 0. Operator Direction Captured

Confirmed with operator on 2026-05-09:

| Decision | Choice | Implication |
| --- | --- | --- |
| In-flight uncommitted changes (200+ files, 25+ new dirs) | Treat as current truth, do not commit | Audit/improvements operate on the working tree as source of truth. |
| K8s canonical tree | `infra/k8s/` is canonical; `k8s/` becomes legacy | Big surgery: rewire launcher entry points, retire 20+ `k8s/overlays/prod-release-internal-tls-*/` overlays, document migration. |
| v1.0 audience | University defense + portfolio | Optimize for clean local startup, working demo, honest README, recruiter-readable architecture, no critical security holes. K8s presence = bonus, not gate. |
| AI scope for v1.0 | Required (voice + LLM + memory must work end-to-end) | Verify host-model-daemon, llm-service health, memory + pgvector, embedding worker, voice STT/TTS — all must be reachable in a documented demo path. |

Documented assumptions where no answer was given:

- Voice priority: Russian first (matches `jarvis.vosk.default-language: ru-RU` at [VoiceWebSocketHandler.java](../../apps/voice-gateway/src/main/java/org/jarvis/voicegateway/websocket/VoiceWebSocketHandler.java)), English next.
- Single-user local mode is acceptable for v1.0 demo. Multi-user / OIDC remains future scope.
- Inference: `host-model-daemon` (native llama.cpp) is the canonical Phase-3 path; `apps/llm-server-py` stays only for the legacy local script flow until those scripts call the daemon directly.
- Vector memory: `pgvector` + `intfloat/multilingual-e5-small` (384 dims) per [V2__create_memory_chunk.sql](../../apps/memory-service/src/main/resources/db/migration/V2__create_memory_chunk.sql:10).
- Module deletions: none. Mark, gate, or document — do not remove.

---

## 1. Executive Summary

**Current readiness (honest):** ~70 % toward v1.0 demo target. The repo is **not** broken; it is **drifting** under heavy active development.

**What works (verified)**

- Builds: Maven 3.8.7 + JDK 21.0.10 build the shared libs cleanly. Reactor compiles.
- Runtime scripts: `runtime-up.sh`, `runtime-down.sh`, `runtime-status.sh`, `runtime-smoke.sh` exist and are wired through `scripts/runtime/common.sh`.
- Auth: dual-plane JWT (user + service), refresh token rotation + reuse-detection, BCrypt password hashing — all real code.
- Service-to-service: `ServiceJwtFilter` + `GatewayAuthFilter` properly reject forged `X-User-*` headers without a valid `X-Service-Token`.
- Local-only enforcement: `LocalOnlyEnforcer` blocks cloud LLM URLs in non-test profiles; `pc-control` real mode and `vision-security-service` are gated by `RuntimeMode.LOCAL` checks.
- Observability: Prometheus + Loki + Tempo + Grafana + Alloy manifests with NetworkPolicies and Grafana ConfigMap-provisioned datasources.
- Migrations: pgvector wired (V2 in memory-service); life-tracker has 7 migrations; security-service refresh-token table exists.
- Documentation: extensive — 13 ADRs, 12 phase-evidence docs, per-service docs, security docs, COMPONENT_STATUS, RUNTIME_MODES, LEGACY_AND_CLEANUP all current to 2026-05-08.

**What does not work (or only half-works)**

- **Two parallel K8s trees**, both live: `k8s/` (jarvis-launch.sh target) and `infra/k8s/` (jarvis-deploy-microk8s-prod.sh target). Drift documented in [LEGACY_AND_CLEANUP.md](../LEGACY_AND_CLEANUP.md). Operator just chose `infra/k8s/` as canonical, but launcher still targets `k8s/`. Single source of truth is missing.
- **Massive uncommitted state**: 200+ modified files, 25+ untracked directories. Fresh checkout from `origin/main` does **not** match documented behavior. Reproducibility is broken until this is committed.
- **Apps marked active but not in build/test path**: `apps/sync-service`, `apps/cloud-relay`, `apps/embedding-service-py`, `apps/llm-server-py`, `apps/android-app`, `apps/vision-security-service` are **untracked**. README claims they exist; they do — but git history doesn't show them yet.
- **JWT enabled defaults to FALSE in code** ([F-001](../security/SECURITY_HARDENING_PLAN.md)). Single misconfigured deployment silently bypasses auth.
- **`SERVICE_JWT_SECRET` falls back to `JWT_SECRET`** (F-002). One secret leak breaks both auth planes. Verified at [api-gateway application.yaml:160](../../apps/api-gateway/src/main/resources/application.yaml).
- **Access-token revocation is not implemented** (F-007). Disabled users keep valid access tokens until expiry.
- **Actuator endpoints public** (F-009): `/actuator/health` and `/actuator/prometheus` reachable without auth through ingress.
- **Desktop tokens stored plaintext** in Java Preferences (`~/.java/.userPrefs/org/jarvis/desktop/`).
- **20+ `k8s/overlays/prod-release-internal-tls-*/` overlays** crowd the tree; most are experimental slice studies.
- **`apps/llm-server-py`** is documented as deprecated but still actively driven by the local AI runtime scripts.

**Biggest risks for the chosen audience (university defense + portfolio):**

1. **Reviewer cannot reproduce a fresh-checkout demo** because main is dirty and the canonical K8s target is moving. Fix: commit + flip launcher to canonical tree.
2. **Auth fail-open default** would be fatal in any serious code review.
3. **Public actuator** is the easiest red-team finding any reviewer would flag.
4. **Demo script does not exist** — only smoke scripts. Need a single 5-minute "open this URL → click here → see this" path.

**What to fix first (P0, days 1–2):**

1. Commit the in-flight work in clean groups (operator chose "do not commit" — auditor instead writes a `docs/audit/JARVIS_COMMIT_PLAN.md` so operator can do it themselves quickly).
2. Make JWT fail-closed by default ([F-001](../security/SECURITY_HARDENING_PLAN.md)).
3. Force `SERVICE_JWT_SECRET` to be distinct in non-test profiles ([F-002](../security/SECURITY_HARDENING_PLAN.md)).
4. Move actuator scrape behind NetworkPolicy / internal-only path ([F-009](../security/SECURITY_HARDENING_PLAN.md)).
5. Mark k8s/ as legacy in README + docs; rewire `make launch` and `jarvis-launch.sh --release-overlay` to a clearly-labeled migration path while keeping the old path working with deprecation banner.

---

## 2. Findings — P0 (blockers for v1.0 demo)

Each finding has: ID, severity, category, evidence (file:line), why it matters, recommended fix, verification command.

### P0-001 — Fresh checkout does not match documented state

- **Severity:** P0
- **Category:** runtime, product honesty, reproducibility
- **Evidence:** `git status --short` returns 200+ modified files and 25+ untracked directories on `main`. Notably untracked: `apps/cloud-relay/`, `apps/sync-service/`, `apps/vision-security-service/`, `apps/embedding-service-py/`, `apps/llm-server-py/`, `apps/android-app/`, `infra/`, `libs/`, `models/`, `docs/security/SECURITY_*`, `docs/COMPONENT_STATUS.md`, `docs/RUNTIME_MODES.md`, `docs/LEGACY_AND_CLEANUP.md`, multiple `apps/*/src/main/java/**` packages.
- **Why it matters:** README claims these modules exist and are wired. They do exist locally — but a recruiter / professor / reviewer cloning `origin/main` will not see them. All evidence in this audit becomes uncheckable for them.
- **Recommended fix:** Operator chose "treat as truth, do not commit" — so this audit produces [`docs/audit/JARVIS_COMMIT_PLAN.md`](JARVIS_COMMIT_PLAN.md) with a suggested logical commit grouping the operator can run in five steps. Until those are pushed, README + docs continue to lie about reproducibility.
- **Verification:** `git status --short | wc -l` should approach 0 after operator follows the plan; `git fetch && git status` on a fresh clone should show "nothing to commit".

### P0-002 — JWT enforcement defaults to FALSE in code

- **Severity:** P0
- **Category:** security
- **Evidence:** `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java:48` defaults `enabled=false`; flipped to `true` only by `application.yaml:144`. Documented as F-001 in [SECURITY_AUDIT.md](../security/SECURITY_AUDIT.md).
- **Why it matters:** Any deployment that omits the production application.yaml profile (e.g. test container, misconfigured overlay, an operator's `--spring.config.location=…` typo) silently disables auth on the gateway. Fail-open is the wrong default for a security-critical filter.
- **Recommended fix:** Flip the code default to `true` and require an explicit `jarvis.jwt.enabled=false` opt-out for known-safe contexts. Add a startup log line that prints the resolved value at INFO so operators can always see it.
- **Verification:** Unit test that asserts `JwtAuthFilter` rejects a request with no Authorization header when no `jarvis.jwt.enabled` is set (i.e. default).

### P0-003 — `SERVICE_JWT_SECRET` falls back to `JWT_SECRET`

- **Severity:** P0
- **Category:** security
- **Evidence:** `apps/jarvis-common/src/main/java/org/jarvis/common/security/ServiceJwtProvider.java:40-48` chooses `serviceSecret` if non-blank else `jwtSecret`. Wired by `apps/api-gateway/src/main/resources/application.yaml:160`: `secret: ${SERVICE_JWT_SECRET:${JWT_SECRET}}`. Documented as F-002.
- **Why it matters:** A single compromise of `JWT_SECRET` collapses both user-auth and service-auth planes. The whole point of dual-plane auth is independent compromise domains.
- **Recommended fix:** In non-test profiles, fail startup with a clear error if `SERVICE_JWT_SECRET` is unset or equal to `JWT_SECRET`. Provide a `--jarvis.security.allow-shared-jwt-secret=true` opt-out for local dev only, gated by `spring.profiles.active=local`.
- **Verification:** Boot api-gateway with `JWT_SECRET=x SERVICE_JWT_SECRET=x SPRING_PROFILES_ACTIVE=prod` → expect explicit refusal-to-start with a one-line cause.

### P0-004 — Two parallel K8s trees still both live

- **Severity:** P0
- **Category:** runtime, Kubernetes, product honesty
- **Evidence:**
  - `k8s/base/` (15 services, no Kafka/RabbitMQ) used by `jarvis-launch.sh:1305+` and `make launch`.
  - `infra/k8s/base/` (18 services + `kafka/statefulset.yaml` + `rabbitmq/statefulset.yaml`) used by `scripts/product/jarvis-deploy-microk8s-prod.sh` and `scripts/verify-prod.sh`.
  - 28 overlays under `k8s/overlays/`, mostly the experimental `prod-release-internal-tls-*` slice studies. Operator chose `infra/k8s/` as canonical going forward.
- **Why it matters:** A reviewer / operator following one path gets a different topology than the other. The README says k8s/ is the launcher target; the operator now wants infra/k8s/. Until reconciled, every K8s claim is conditional on which tree you read.
- **Recommended fix:**
  1. Update README + ARCHITECTURE.md + RUNTIME_MODES.md to mark **`infra/k8s/` as canonical from 2026-05-09**.
  2. Add a `k8s/README.md` deprecation banner pointing to `infra/k8s/`.
  3. Add a `scripts/product/jarvis-deploy-prod.sh` shim or a launcher flag that targets `infra/k8s/overlays/prod` cleanly.
  4. Mark all 20 `k8s/overlays/prod-release-internal-tls-*` overlays as **experimental slice studies, not for production**, in their respective READMEs.
  5. Open a dedicated migration ticket in `docs/LEGACY_AND_CLEANUP.md` with a checklist for retiring `k8s/`.
- **Verification:** `grep -R "k8s/overlays/prod" jarvis-launch.sh scripts/product Makefile` should show every reference is either deprecated/legacy or behind a feature flag; `infra/k8s/overlays/prod` should be the default in all canonical entry points.

### P0-005 — Demo path is not documented as a single repeatable script

- **Severity:** P0
- **Category:** documentation, product honesty
- **Evidence:** README + RUNTIME_MODES.md describe modes 1–7 in detail, but no document says "to demo Jarvis end-to-end, run X, open Y, click Z." Existing `scripts/runtime-smoke.sh` is a CI smoke, not a demo. `scripts/voice-local-smoke.sh` is a stack test, not a UX flow.
- **Why it matters:** University defense or portfolio review needs a 5-minute path from clone to "I see Jarvis answer a voice command on my desk." Without it, every reviewer rebuilds it from scratch and gives up.
- **Recommended fix:** Create [`docs/DEMO.md`](../DEMO.md) with a single 30-line "fresh clone → working demo" path. Also create [`docs/RELEASE_CHECKLIST.md`](../RELEASE_CHECKLIST.md) for the release manager view.
- **Verification:** A second engineer follows DEMO.md on a clean machine and reaches a working voice answer in under 30 minutes (excluding model download).

---

## 3. Findings — P1 (important; fix before public release)

### P1-006 — Access-token revocation is not implemented

- **Severity:** P1
- **Category:** security
- **Evidence:** F-007 in SECURITY_AUDIT.md. `apps/security-service/src/main/java/org/jarvis/security/service/AuthService.java` revokes refresh tokens on user disable / password change but does not maintain an access-token deny-list. `JwtAuthFilter` only validates signature + expiry.
- **Why it matters:** A disabled user keeps full authority for up to 1 hour (default access token TTL). This is documented in the audit — but for a portfolio piece it's worth shortening the access-token TTL aggressively (e.g. 5 min) so that "no immediate revocation" becomes a much smaller window.
- **Recommended fix:** Either (a) shorten access TTL to 5 min and document the trade-off, or (b) add a per-jti revocation cache (Caffeine, in-process) populated by AuthService on disable/logout/password-change events.
- **Verification:** Unit test on AuthService asserting that `disableUser()` adds the active jti to the revocation cache; integration test on JwtAuthFilter asserting that a revoked-jti token is rejected with 401.

### P1-007 — `/actuator/health` and `/actuator/prometheus` are public via ingress

- **Severity:** P1
- **Category:** security, observability
- **Evidence:** F-009 in SECURITY_AUDIT.md. `JwtAuthFilter.java:51-62` lists those paths as public. `application.yaml:108-124` exposes them.
- **Why it matters:** Anyone with network reach can see liveness, readiness, build version, JVM metrics. For a public-network deployment this is data leakage and is the easiest finding any reviewer makes.
- **Recommended fix:** Put `/actuator/**` behind:
  - NetworkPolicy egress rule (Prometheus scrape only from `monitoring` namespace label).
  - In ingress, drop `/actuator` for external traffic; serve only the LAN-internal Service.
  - Behind the gateway, gate `/actuator/prometheus` on `hasAuthority('SVC_INTERNAL')`.
- **Verification:** `curl -k https://api.jarvis.local/actuator/health` from outside the cluster should return 404 or 401 once fixed.

### P1-008 — Desktop tokens stored plaintext in Java Preferences

- **Severity:** P1
- **Category:** security, desktop
- **Evidence:** `apps/desktop-javafx/src/main/kotlin/org/jarvis/desktop/auth/TokenManager.kt:1-169` writes tokens to `Preferences.userNodeForPackage(...)`. On Linux this lands at `~/.java/.userPrefs/...` in plain text.
- **Why it matters:** Backup leak / shared workstation / unencrypted disk = token leak.
- **Recommended fix:** Either AES-encrypt with a key derived from the OS keystore (Linux: `libsecret` via `secret-tool`; macOS: Keychain; Windows: Credential Manager) **or** at minimum encrypt-at-rest with a key file at `~/.jarvis/desktop/key` with `0600` perms. Document the trade-off honestly in [docs/security/SECURITY.md](../security/SECURITY.md).
- **Verification:** After login, `cat ~/.java/.userPrefs/org/jarvis/desktop/...` should not contain a base64 JWT.

### P1-009 — `apps/llm-server-py` deprecated but still in canonical local AI path

- **Severity:** P1
- **Category:** product honesty, LLM
- **Evidence:** `apps/llm-server-py/README.md:1` says "DEPRECATED — Phase 3". But `scripts/ai-up.sh` and `scripts/runtime/python_ai.sh` still start it. The deprecation message says "Phase 7 (voice loop) is expected to migrate those scripts to invoke the host model daemon directly."
- **Why it matters:** Product narrative diverges from runtime narrative. A reviewer reading the README sees host-model-daemon as the inference path and can't reconcile "why is there a Python wrapper running?"
- **Recommended fix:** Either (a) finish the Phase-7 migration so local AI scripts call the host-model-daemon over HTTP directly, then delete `apps/llm-server-py/` cleanly; or (b) downgrade the README claim from "deprecated" to "legacy local-runtime backend" and explain when each is used.
- **Verification:** `grep -R "llm-server-py" scripts/ docs/` should show consistent narrative.

### P1-010 — CORS allows `"*"` headers with credentials

- **Severity:** P1
- **Category:** security
- **Evidence:** F-005 in SECURITY_AUDIT.md. `application.yaml:165-179`: `allowed-headers: "*"` plus `allow-credentials: true`. Ingress is narrower (explicit `Authorization, Content-Type, X-Requested-With`).
- **Why it matters:** If anyone ever broadens `allowed-origins` (currently safe at two LAN hostnames), wildcard headers become an attack surface.
- **Recommended fix:** Switch `allowed-headers` to an explicit list matching the ingress: `Authorization, Content-Type, X-Requested-With`. Add a config-validation test that fails if `*` appears with credentials.
- **Verification:** Boot test that asserts no allowed-header is `*` when allow-credentials is true.

### P1-011 — Stale endpoint risk in desktop on manual override

- **Severity:** P1
- **Category:** desktop, runtime
- **Evidence:** `apps/desktop-javafx/src/main/kotlin/org/jarvis/desktop/api/ApiClient.kt:149-157, 222-230` does throw "Connection refused" on bad URL — that's good. But `SettingsView.kt:364-368` lets the user pin a manual override and shows a status pill (`SettingsView.kt:395-435`) — without testing the new URL before saving.
- **Why it matters:** If user pins an unreachable URL, login silently fails until they revisit Settings. Worse: if they pin an attacker-controlled URL, the desktop sends credentials there.
- **Recommended fix:** On Save in SettingsView, call `GET <url>/actuator/health` first. If non-2xx, show a confirm dialog "this endpoint is not reachable — save anyway?" and never auto-send credentials to a freshly-pinned untrusted URL.
- **Verification:** Manual test in SettingsView with an obviously bad URL (`http://0.0.0.0:1`); save should refuse or warn.

### P1-012 — `apps/sync-service`, `cloud-relay`, `vision-security-service` claimed but untracked in git

- **Severity:** P1
- **Category:** product honesty, build
- **Evidence:** Root `pom.xml:64-71` lists them as reactor modules. README claims they are "active". `git status` says `apps/sync-service/`, `apps/cloud-relay/`, `apps/vision-security-service/` are **untracked**.
- **Why it matters:** Reactor build will refuse to start on a fresh clone because referenced modules don't exist. README is a lie until committed.
- **Recommended fix:** Same as P0-001 — included in commit plan.
- **Verification:** `git ls-files apps/sync-service | head -1` should return at least one file.

### P1-013 — Voice TTS hard-coded `espeak-ng` with no documented graceful degradation in desktop

- **Severity:** P1
- **Category:** voice, desktop
- **Evidence:** `apps/voice-gateway/src/main/java/org/jarvis/voicegateway/health/VoiceReadinessService.java:193-209` self-tests TTS with synthesizing one phrase. If `espeak-ng` is missing, the readiness component shows DEGRADED but the desktop voice tab does not show a clear "TTS unavailable" badge — it shows the standard health pill only.
- **Why it matters:** Demo failure mode: voice button works, no audio comes back, user thinks the app is broken. They have no way to know `apt install espeak-ng` would fix it.
- **Recommended fix:** Surface STT and TTS readiness as separate status pills in the Voice tab. When TTS is DOWN, show the install hint inline (`sudo apt install espeak-ng`).
- **Verification:** Manual test on a system without `espeak-ng` — Voice tab should have a clearly-labelled red "TTS missing" indicator with an install hint.

### P1-014 — CI runs only smoke; no security tests, no K8s tests, no SBOM

- **Severity:** P1
- **Category:** testing, security, CI
- **Evidence:** `.github/workflows/backend-readiness.yml:1-133` runs release-wiring check + runtime-core-smoke + runtime-analytics-smoke. No SAST, no dependency scan, no secret scan, no kustomize validation, no image build.
- **Why it matters:** Hardening plan P2 items (SBOM, SAST, container scanning, image signing) require CI hooks. None exist.
- **Recommended fix:** Add a second workflow `security-and-build.yml` with: (a) `gitleaks` for secret scan; (b) `trivy fs` for dependency CVEs; (c) `kubectl kustomize k8s/base` + `kubectl kustomize infra/k8s/base` static validation; (d) optionally `cdxgen` for SBOM. Keep them as separate jobs so smoke stays fast.
- **Verification:** `gh workflow list` shows two workflows; new workflow runs to completion on a sample PR.

### P1-015 — `vision-security-service` Maven module exists but is not in `infra/k8s/base/kustomization.yaml`

- **Severity:** P1
- **Category:** product honesty, K8s
- **Evidence:** Root `pom.xml:62` includes `apps/vision-security-service`. `infra/k8s/base/kustomization.yaml` does **not** include `vision-security-service/` — by design (it's local-only). Same in `k8s/base/kustomization.yaml`. Documented in [docs/services/vision-security-service.md](../services/vision-security-service.md).
- **Why it matters:** This is correct behavior, but a reviewer who scans the modules list and looks for K8s manifests will be confused. Need a one-line note inline.
- **Recommended fix:** Add a comment in `infra/k8s/base/kustomization.yaml` and `k8s/base/kustomization.yaml` explicitly listing local-only services and why they're not deployed in cluster mode.
- **Verification:** Comment review.

---

## 4. Findings — P2 (quality / polish)

### P2-016 — README is dense; no "first 5 minutes" path

- **Severity:** P2
- **Category:** documentation
- **Evidence:** README is ~17 KB. The first 50 lines are project overview; the run instructions appear after the module catalog.
- **Recommended fix:** Add a `## Quickstart` block right after the title with literally 6 commands (`make build`, `make local-up`, open browser, etc.) and link out to deeper docs.

### P2-017 — `k8s/overlays/prod-release-internal-tls-*` overlay churn

- **Severity:** P2
- **Category:** Kubernetes, product honesty
- **Evidence:** 20 overlays under that pattern. Each pairs two services (e.g. api-gateway↔life-tracker) for internal TLS slice studies.
- **Recommended fix:** Move all 20 to `k8s/overlays/internal-tls-experiments/<pair>/` with one shared README explaining purpose. Reduces top-level overlay count from 28 to ~8.

### P2-018 — Many `phase-N-acceptance-evidence.md` documents — no consolidated demo doc

- **Severity:** P2
- **Category:** documentation
- **Evidence:** 12 phase documents under `docs/architecture/`. Useful audit trail, but no single "current capabilities" doc for a recruiter.
- **Recommended fix:** Add a one-page `docs/CAPABILITIES.md` matrix: capability → status (working / partial / experimental / planned) → evidence file. Acts as a recruiter-friendly index.

### P2-019 — No `SECURITY.md` at repo root

- **Severity:** P2
- **Category:** security, documentation
- **Evidence:** `docs/security/SECURITY.md` exists but root has no `SECURITY.md` (GitHub auto-detects this for security advisories).
- **Recommended fix:** Add a 30-line `SECURITY.md` at root that points to `docs/security/SECURITY.md` and gives a private disclosure email. GitHub then auto-displays it on the repo's Security tab.

### P2-020 — `models/` directory in repo root may contain large binaries

- **Severity:** P2
- **Category:** repo hygiene
- **Evidence:** `ls models/` exists; `.gitignore` line excludes `models/*`. But the directory itself is committed. Untracked contents are fine; need to confirm.
- **Recommended fix:** Verify nothing > 1 MB is staged: `git diff --cached --stat` + `git ls-files models/ | xargs -I{} stat -c "%s %n" {}`. Add a `models/README.md` explaining what does/does not belong there.

### P2-021 — Multiple agent-config dirs (`.agents/`, `.claude/`, `.codex/`, `.cursor/`, `.vscode/`)

- **Severity:** P2
- **Category:** repo hygiene
- **Evidence:** All present at repo root. `.gitignore` may or may not cover them.
- **Recommended fix:** Pick one canonical agent-config layout, gitignore the rest, and document in CONTRIBUTING.md.

### P2-022 — `apps/llm-server-py` README has "deprecated" but Containerfile and tests are current

- **Severity:** P2
- **Category:** product honesty
- **Evidence:** `apps/llm-server-py/Containerfile`, `requirements.txt`, `requirements-local.txt`, `app/main.py` are all live. README says deprecated but the runtime artifacts are not retired.
- **Recommended fix:** Either retire the directory or update the README to match its actual role.

---

## 5. Findings — P3 (nice-to-have)

- **P3-023** (docs): Translate the per-service docs to a common section structure (Status / Ports / Endpoints / Health / Config / Failure modes). Reduces reading load.
- **P3-024** (testing): Add a single `mvn -P portfolio-smoke` profile that runs the smallest realistic end-to-end (security-service + api-gateway + life-tracker + planner-service + analytics-service), so a reviewer can validate the core in 90 seconds.
- **P3-025** (observability): Pre-built Grafana dashboards for: gateway request volume, voice loop latency p50/p95, LLM call success rate, login attempts. Some exist under `config/grafana-dashboards/`; need an overview page.
- **P3-026** (desktop): Consolidate `desktop-app` and `desktop-client` historical labels — the README admits some payloads still carry the legacy label. Choose one.
- **P3-027** (docs): Add a `docs/diagrams/` page with the topology PNG referenced from README so the architecture diagram renders inline on GitHub instead of as ASCII art.

---

## 6. Category Roll-Up

| Category | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- |
| security | P0-002, P0-003 | P1-006, P1-007, P1-008, P1-010 | P2-019 | — |
| runtime | P0-001, P0-004 | P1-011 | — | — |
| Kubernetes | P0-004 | P1-015 | P2-017 | — |
| voice | — | P1-013 | — | — |
| LLM | — | P1-009 | — | — |
| memory | — | — | — | — |
| database | — | — | — | — |
| desktop | — | P1-008, P1-011 | — | P3-026 |
| observability | — | P1-007 | — | P3-025 |
| documentation | P0-005 | — | P2-016, P2-018, P2-019 | P3-023, P3-027 |
| testing | — | P1-014 | — | P3-024 |
| product honesty | P0-001, P0-004, P0-005 | P1-009, P1-012, P1-015 | P2-022 | — |
| build | — | P1-012 | — | — |
| repo hygiene | — | — | P2-020, P2-021 | — |

## 7. What This Audit Does Not Try to Resolve

- **Real OIDC / multi-tenant**: out of scope for v1.0 (operator chose single-user defense/portfolio).
- **Production cloud deploy** (AWS / GCP / Azure): out of scope. v1.0 is local + LAN k3s.
- **Mobile app**: `apps/android-app` is a Phase-12 scaffold. Not promoted to v1.0 demo path.
- **Advanced LLM features** (function-calling, RAG with multiple stores, tool use beyond the planner intent): explicitly future scope.

## 8. Next-Step Plan (this auditor will execute Phase 5 against this list)

Priority order (matches the user-supplied A–L):

- **A — Safety / repo hygiene**: P0-001 (commit plan), P2-020, P2-021.
- **B — Build reliability**: P1-012, plus light Maven verification commands.
- **C — Local runtime**: keep working; no destructive changes; verify scripts.
- **D — Desktop**: P1-011 (validate-on-save), P1-013 (TTS status pill).
- **E — Gateway/contracts**: P1-007 (actuator), P1-010 (CORS).
- **F — Voice**: P1-013, plus a `docs/services/voice-gateway-troubleshooting.md`.
- **G — LLM/memory**: P1-009 (clarify llm-server-py status), document model paths.
- **H — Security**: P0-002 (JWT fail-closed), P0-003 (distinct service secret), P1-006 (TTL shorten), P1-008 (desktop token at-rest), P2-019 (root SECURITY.md).
- **I — Observability**: keep working; document.
- **J — K8s**: P0-004 (canonical = infra/k8s/), P2-017 (fold internal-tls overlays).
- **K — Testing**: P1-014 (CI security workflow).
- **L — Docs/demo polish**: P0-005 (DEMO.md), P2-016 (Quickstart), P2-018 (CAPABILITIES.md), P3-024 (portfolio-smoke).

Final verdict (preliminary, refined in [JARVIS_FINAL_REPORT.md](JARVIS_FINAL_REPORT.md) after Phase 5):

- **As of audit time**: PORTFOLIO-READY with caveats (P0 items honest in docs, but auth fail-open default is the one true blocker for any external review).
- **After Phase 5**: target = DEMO-READY + PORTFOLIO-READY, with K8s production deployment marked EXPERIMENTAL until single canonical tree is fully migrated.
