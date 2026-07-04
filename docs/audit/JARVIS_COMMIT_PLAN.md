# Jarvis 2.0 — Suggested Commit Plan

Audit reference: [JARVIS_AUDIT_REPORT.md](JARVIS_AUDIT_REPORT.md) → P0-001.

The working tree currently has 200+ modified files and 25+ untracked directories on `main`. The auditor was instructed not to commit, but reproducibility is broken until these changes land. This document lets the operator commit the in-flight work in five logical groups in ~10 minutes.

Run from repo root: `/home/kwaqa/Jarvis/Jarvis2.0`

> **Safety:** Every step uses `git add <specific paths>` (never `-A` / `.`) so nothing accidentally enters a commit. Review with `git status` and `git diff --cached --stat` between steps. Every step should also pass `git diff --cached -- '*.env*' '*secret*' '*.key' '*.pem'` returning nothing.

---

## Step 1 — New shared libraries and protocol contracts

```bash
git add libs/command-schema libs/event-schema libs/sync-protocol
git add pom.xml
git status
git commit -m "Add shared libs (command-schema, event-schema, sync-protocol) and wire them into the reactor

- libs/command-schema: pure-POJO RabbitMQ command pipeline contract
- libs/event-schema: Kafka audit event topology contract
- libs/sync-protocol: E2E sync library (X25519 / ChaCha20-Poly1305 / Ed25519)
- root pom.xml updated to include the three lib modules first"
```

## Step 2 — New reactor modules (sync-service, cloud-relay, vision-security-service, embedding-service-py, llm-server-py, android-app)

```bash
git add apps/sync-service apps/cloud-relay apps/vision-security-service
git add apps/embedding-service-py apps/llm-server-py
git add apps/android-app

# Verify nothing inside vision-security-service/dataset/ slipped in:
git diff --cached -- apps/vision-security-service/dataset/ | head

# Should return nothing (gitignore covers user images).
git commit -m "Add new modules: sync-service, cloud-relay, vision-security-service (Java); embedding-service-py, llm-server-py (Python); android-app (Gradle scaffold)

- apps/sync-service: E2E sync inbox for paired devices (ADR-0013)
- apps/cloud-relay: off-prem opaque blob forwarder (ADR-0013)
- apps/vision-security-service: workstation-local CV / owner verification (ADR-0011)
- apps/embedding-service-py: FastAPI embedding worker (multilingual-e5-small, 384 dims)
- apps/llm-server-py: legacy Python llama.cpp wrapper (deprecated, still used by local AI scripts)
- apps/android-app: Phase-12 Pass-1 mobile scaffold (Gradle, excluded from Maven reactor)"
```

## Step 3 — In-place changes to existing apps + jarvis-common autoconfig

```bash
git add apps/jarvis-common apps/api-gateway apps/voice-gateway apps/orchestrator
git add apps/llm-service apps/memory-service apps/nlp-service apps/desktop-javafx
git add apps/life-tracker apps/planner-service apps/security-service
git add apps/smart-home-service apps/user-profile apps/analytics-service apps/pc-control

# Drop any embedded local-runtime state, model files, or .env files that snuck into modules:
git diff --cached -- '**/last-run.json' '**/*.env' '**/*.key' '**/*.pem' | head

git commit -m "Update existing modules: gateway agent + audit packages, llm-service host-model-daemon wiring, memory-service obsidian + audit packages, voice-gateway voiceloop + confirmation, desktop-javafx life map + agent integration, planner / life-tracker / security / smart-home / user-profile fixes

- jarvis-common: new event bus + messaging packages
- api-gateway: agent + audit packages, MemoryProxyController, Llm ingress integration test
- voice-gateway: voiceloop + confirmation packages, readiness service updates
- orchestrator: command + voice packages
- llm-service: HostModelDaemonProperties, LocalOnlyEnforcer, IntentController + IntentClassifier
- memory-service: obsidian package, audit events (V4), memory notes (V5) migrations
- desktop-javafx: agent + life map packages, settings view stale-endpoint guard, voice tab adapter
- life-tracker: lifemap package
- vision-security-service: full implementation under org/jarvis/vision/"
```

## Step 4 — Runtime, scripts, K8s, infra, observability, secrets

```bash
git add scripts infra Makefile jarvis-launch.sh jarvis-stop.sh
git add k8s
git add .github/workflows
git add secrets/secrets.example.env

# Verify no real secrets:
git diff --cached -- secrets/ | grep -E '(password|secret|token|key)' | grep -vE '(CHANGE-ME|placeholder|example|EXAMPLE)' | head

git commit -m "Update runtime scripts, k8s manifests, infra/, CI workflow, and secrets template

- scripts/: runtime-up/down/status/smoke updated, new ai-up/down, setup-ai-local, verify-ai, verify-observability, sync-grafana-admin, seed-llm-model-pvc, k8s-smoke, p2-jarvis-loop-smoke, guards for docker-runtime regressions
- infra/k8s/: full MicroK8s production foundation (Kafka + RabbitMQ + sync-service + observability)
- infra/scripts/: model-runtime/, microk8s/, agent/ — host-model-daemon canonical Phase-3 path
- k8s/: launcher-target manifests, prod overlay, prod-release digest-pinned overlay, 20 internal-tls slice study overlays, kyverno enforce policies, host-model-daemon selectorless Service
- .github/workflows/backend-readiness.yml: release-wiring + runtime-smoke + analytics-smoke
- jarvis-launch.sh / jarvis-stop.sh: feature flags, observability bootstrap, namespace cleanup
- secrets/secrets.example.env: template only (placeholders, no real values)"
```

## Step 5 — Documentation, ADRs, audit report, target state

```bash
git add docs README.md ARCHITECTURE.md AGENTS.md
git add .mcp.json.example AGENTS.local.md.example
git add .agents .claude .codex
git add .gitignore

# Sanity check on agent dirs:
git diff --cached -- .claude .codex .agents | grep -E '(token|secret|key|password|api[_-]?key)' | head

git commit -m "Refresh documentation: COMPONENT_STATUS, RUNTIME_MODES, LEGACY_AND_CLEANUP, security docs (SECURITY/SECURITY_AUDIT/SECURITY_HARDENING_PLAN/SECRETS_POLICY/AUTH_MODEL/SECURITY_COMPONENT_STATUS), 13 ADRs, 12 phase-evidence docs, host-model-daemon + embedding-service + llm-server service pages, codex configuration map, audit report and target state

- README + ARCHITECTURE refreshed against current code (audit date 2026-05-08)
- docs/COMPONENT_STATUS.md: per-module status with evidence
- docs/RUNTIME_MODES.md: 7 runtime modes enumerated
- docs/LEGACY_AND_CLEANUP.md: drift items between k8s/ and infra/k8s/
- docs/security/: full audit + hardening plan + policies
- docs/architecture/ADR/: ADR-0001 .. ADR-0013 (runtime zones, desktop agent, docker deprecation, jarvis-prod ns, command pipeline, confirmation flow, native desktop agent, voice loop, audit backbone, obsidian memory, CV split, life map, android + cloud relay)
- docs/architecture/phase-1 .. phase-12 acceptance evidence
- docs/architecture/JARVIS_TARGET_STATE.md and docs/audit/JARVIS_AUDIT_REPORT.md: 2026-05-09 senior-engineer audit pass
- agent configs (.agents/, .claude/, .codex/) and templates (.mcp.json.example, AGENTS.local.md.example) committed
- .gitignore expanded to cover models/, secrets/, vision dataset, agent local overrides"
```

---

## Optional Step 6 — Post-commit verification

```bash
# Should be 0 modified, only the .jarvis-cutover-backup/ folder may remain untracked:
git status --short

# Verify all new modules build:
mvn -q -DskipTests -pl apps/sync-service,apps/cloud-relay,apps/vision-security-service -am package

# Verify the reactor loads all modules:
mvn -q help:evaluate -Dexpression=project.modules -DforceStdout
```

If anything goes wrong: `git reset HEAD~5` returns to pre-commit state; nothing is destroyed.

## What stays untracked on purpose

- `.jarvis-cutover-backup/` — historical migration artifact, not part of the build. Either commit it under `docs/archive/` or delete it once you've decided it's no longer needed.
- `.pytest_cache/`, `.venv-dataset/` — local Python tooling state.
- `.cursor/`, `.vscode/` — IDE preferences (covered by .gitignore).
- `logs/`, `models/*.gguf`, `~/.jarvis/` — local runtime state and model binaries (covered by .gitignore).

## Reviewer checklist after commits land

- [ ] Fresh clone in `/tmp/jarvis-fresh-check/` then `mvn -DskipTests package` succeeds.
- [ ] `git status --short` after fresh clone returns empty.
- [ ] `ls apps/sync-service apps/cloud-relay apps/vision-security-service apps/embedding-service-py apps/llm-server-py apps/android-app infra/k8s/base` all show populated directories.
- [ ] `cat docs/COMPONENT_STATUS.md docs/RUNTIME_MODES.md docs/security/SECURITY.md` open without 404.
- [ ] `git log --oneline -7` shows the five commits above plus the two pre-existing commits (`0d25e53`, `536e16b`).
