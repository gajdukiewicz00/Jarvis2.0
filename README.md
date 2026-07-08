# Jarvis 2.0

[![Reactor Test Suite](https://github.com/gajdukiewicz00/Jarvis2.0/actions/workflows/reactor-tests.yml/badge.svg?branch=main)](https://github.com/gajdukiewicz00/Jarvis2.0/actions/workflows/reactor-tests.yml)
[![Quality Gate](https://github.com/gajdukiewicz00/Jarvis2.0/actions/workflows/quality.yml/badge.svg?branch=main)](https://github.com/gajdukiewicz00/Jarvis2.0/actions/workflows/quality.yml)

A **local-first, single-user personal AI assistant** styled after the film J.A.R.V.I.S.: a
Java 21 / Spring Boot microservice backend running on a **k3s** cluster (`jarvis-prod`), a
JavaFX desktop shell, a real voice loop (Vosk STT + **Piper** neural TTS), a local
**Qwen3-14B** brain on GPU via `host-model-daemon` (llama.cpp), semantic memory
(PostgreSQL + pgvector), and full observability (Prometheus + Loki + Tempo + Grafana + Alloy).

This README is written to be honest about maturity, not aspirational. For the
authoritative, per-subsystem breakdown (what's deployed, what's code-only, what's
mock, what needs hardware) see **[docs/STATUS.md](docs/STATUS.md)** — this file is a
front-door summary of it.

## Status Snapshot (2026-07-08)

- **Maven reactor:** 23 modules — 3 shared libraries (`libs/command-schema`,
  `libs/event-schema`, `libs/sync-protocol`) + 20 modules under `apps/`: 18 deployable
  Spring Boot services (including `cloud-relay`, off-prem only) plus the shared
  `jarvis-common` library and the `desktop-javafx` UI shell. `apps/android-app/` is a
  separate Gradle project, excluded from the Maven reactor. Source of truth: `pom.xml`.
- **Cluster (`jarvis-prod`, k3s):** last full live verification pass **2026-07-06** —
  28/28 pods Running, 24/24 Deployments Ready, `api-gateway` `/actuator/health` → `UP`,
  the Qwen3-14B brain answers `/api/v1/llm/chat`, `./jarvis doctor` all green,
  `./jarvis drift-check` **18/18** image tags matched the pinned overlay, `./jarvis
  smoke-e2e` **4/4** (login, LLM chat, memory search, planner). Full transcript:
  [docs/LIVE_VERIFICATION.md](docs/LIVE_VERIFICATION.md).
- **Prometheus scrape coverage:** the `agent-service`/`media-service`/`sync-service`
  scrape gap flagged in that same 2026-07-06 pass has since been closed (NetworkPolicy
  allowlist + `prometheus.io/scrape` patches applied) — **17/17** `jarvis-actuator`
  targets report `up`. Re-verify with `./scripts/verify-observability.sh`.
- **Deploy image tags** are pinned **per service** in
  [`infra/k8s/overlays/prod/kustomization.yaml`](infra/k8s/overlays/prod/kustomization.yaml)
  — currently a mix of `:w6`/`:w7`/`:w8` (plus `llm-server:local`), bumped most recently
  2026-07-07 (`api-gateway`/`voice-gateway`/`security-service` → `:w8` for a token-masking
  and status/voice fix). The overlay bump has **not yet been re-confirmed against a fresh
  live snapshot** — run `./jarvis doctor && ./jarvis drift-check` before trusting this as
  current-second truth.
- **Brain + voice:** `host-model-daemon` (llama.cpp, Qwen3-14B, port `18080`) and
  `host-tts-daemon` (Piper neural TTS, port `18090`) run as `systemd --user` services with
  `loginctl` linger, so they survive reboot without a login session. STT is Vosk, inside
  `voice-gateway`.
- **`vision-security-service` runs as a HOST process on `:8094`, not a k8s pod.** It is
  never deployed by `k8s/base` — it needs a physical webcam and stays workstation-local.
- **Media pipeline:** the job queue and `ffmpeg`/`ffprobe` extraction/mux are real
  (Postgres-backed async jobs). ASR/translation/dubbing default to **MOCK** providers
  (`MockAsrProvider`, `MockTranslationProvider`, a placeholder TTS marker writer). Real
  `WhisperCppAsrProvider`/`LlmTranslationProvider` exist behind `media.*.mode` flags but
  need the opt-in `real-media-image` Maven profile (see `apps/media-service/README.md`) —
  no real dubbing/TTS provider exists at all yet.
- **CI exists but isn't gating anything yet.** 8 GitHub Actions workflows live under
  [`.github/workflows/`](.github/workflows/) (reactor tests, quality gate, security/build
  checks, k8s-tree drift, image-build validation, image signing, desktop-entry guard,
  backend readiness). **Actions must be enabled and branch protection configured in the
  GitHub UI** before any of this actually blocks a merge — nothing here does that
  automatically today.
- **Known issues:** a 2026-07-06 adversarial multi-agent audit
  ([docs/audit/2026-07-06-bug-hunt.md](docs/audit/2026-07-06-bug-hunt.md)) found 56
  confirmed bugs (12 CRITICAL / 24 HIGH / 19 MEDIUM / 1 LOW). The three highest-severity
  findings (revoke-all not advancing the session floor, inverted PII-redaction polarity,
  the panic kill-switch missing PC/agent routes) have since been fixed in follow-up
  commits; the remaining backlog has not been fully reconciled against this pass.

## Feature Maturity Matrix

Mirrored directly from the desktop Control Center's own source of truth —
[`FeatureMaturityRegistry`](apps/desktop-javafx/src/main/kotlin/org/jarvis/desktop/features/controlcenter/FeatureMaturity.kt).
Every feature tile in the running desktop app renders one of these five badges next to
its "Open" button, so the app itself makes the same honesty claims as this table.

| Maturity | Meaning |
| --- | --- |
| **READY** | Works end-to-end against real services; safe to demo unattended. |
| **BETA** | Functions today but is still rough, partially wired, or lightly tested. |
| **MOCK DATA** | UI and flow exist, but the data or execution behind it is synthetic/stubbed. |
| **EXPERIMENTAL** | Present for exploration only — behavior may change or regress without notice. |
| **UNAVAILABLE** | Requires a prerequisite the current runtime doesn't have (e.g. a paired phone). |

| Feature (Control Center tile) | Maturity | Reality |
| --- | --- | --- |
| Brain / AI Chat | **READY** | Qwen3-14B via `host-model-daemon`, deployed, exercised by `smoke-e2e`. |
| Memory | **READY** | pgvector semantic search + unified Obsidian search, deployed. |
| Finance | **READY** | `life-tracker` finance CRUD, deployed. |
| Planner | **READY** | Tasks/reminders/energy-aware ranking, deployed. (LLM-enhancement endpoints still `501`, see below.) |
| Analytics | **READY** | `analytics-service` finance/calendar summaries, deployed. |
| PC Control | **READY** | Real control (keyboard/mouse/volume/hotkeys) on the host; intentionally stubbed in the cluster (`PC_CONTROL_STUB_MODE=true`). |
| Voice Commands (help) | **READY** | Static reference screen listing supported voice phrases. |
| Diagnostics | **READY** | Live service/log/health view. |
| AI Runtime | **READY** | AI runtime status and configuration. |
| Service Status | **READY** | Cluster/pod health view. |
| Settings | **READY** | App settings. |
| Voice Control | **BETA** | Real Vosk STT + Piper TTS pipeline end to end; a *live* take needs a mic + speakers (hardware-gated) — `scripts/jarvis-voice-demo.sh --sample`/`--wav` exercises the same pipeline without either. |
| Life | **BETA** | Wellness/habit tracking; wave-1 CSV import/export + rollups are code-complete and unit-tested but **not yet redeployed**. |
| Insights | **BETA** | Forecast/correlation/anomaly-detection wave-1 features are code-complete but **not yet redeployed**. |
| Security / Privacy | **BETA** | Auth roles UI; wave-1 OWNER/GUEST/SERVICE roles + per-jti revocation + audit log are code-complete but **not yet redeployed**. |
| Sessions & Audit | **BETA** | Same wave-1 gap as above. |
| Finance Review | **BETA** | Bank-notification review inbox; the server-side parser is deployable and demoable today, but the on-device Android capture path needs a physical phone (hardware-gated). |
| Agent Swarm | **BETA** | Role swarm + task queue deployed; the wave-1 Postgres task store, git-worktree sandbox, real TESTER executor, and patch proposals are code-complete but **not yet redeployed**. Global panic kill-switch (`POST /api/v1/agent/panic`) is already live. |
| Smart Home | **BETA** | Static device catalog + in-memory state; no MQTT/Home-Assistant bridge wired to real devices yet. |
| Vision Security / CV | **BETA** | Real OCR/screen-context/CV code; runs as a **host-only process on `:8094`**, needs a physical webcam, never deployed to `k8s/base`. |
| Media Jobs | **MOCK DATA** | Job queue + `ffmpeg`/`ffprobe` are real; ASR/translation/dubbing default to mock providers (see Status Snapshot above). |
| Proactive | **EXPERIMENTAL** | Observes and reasons, but the full proactive speech loop is unproven end-to-end (needs speakers). |
| Sync / Pairing | **UNAVAILABLE** | Requires a paired Android phone the current runtime does not have. |

Any Control Center route not listed above defaults to **BETA** — the registry's own safe
middle ground for anything not yet explicitly classified.

## How To Launch

**Desktop icon (recommended):** double-click **Jarvis** — wired to
`scripts/jarvis-oneclick.sh` via `scripts/jarvis.desktop`. It starts k3s if needed,
self-heals the `host-model-daemon` endpoint and any stale pods (never a blind
`kubectl apply`), waits for the gateway to report healthy, then opens the desktop
Control Center.

**Same thing by hand:**

```bash
./scripts/jarvis-oneclick.sh
```

**Headless verification** (no GUI):

```bash
./jarvis doctor       # GPU, k3s, node, deployments, host-model-daemon endpoint, gateway
./jarvis status       # pods/deployments/node IP/host brain+voice health at a glance
./jarvis drift-check  # live image tags vs infra/k8s/overlays/prod (canonical)
./jarvis smoke-e2e    # login, LLM chat, memory search, planner — PASS/FAIL
```

**Login:** username **`test1111`**, password **`test1111`** — the repo's standing local
test account, seeded with role **`OWNER`** (the default role when none is specified; see
`AuthService`/`BootstrapAdminInitializer`). It has access to every route, including
OWNER-gated admin/audit endpoints.

```bash
NODE_IP="$(sudo k3s kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
curl -sk -H 'Host: api.jarvis.local' -X POST "https://${NODE_IP}/api/v1/security/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"test1111","password":"test1111"}'
```

Full runnable demo script (voice, memory recall, agent swarm dry run, media subtitles,
analytics insight, planner reschedule, smart-home scene, panic button):
[docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md).

## How To Deploy

The canonical Kubernetes source of truth is **`infra/k8s/overlays/prod`** — nothing
else. Full rationale, the "what NOT to do" list, and the post-reboot runbook:
[docs/DEPLOYMENT_CANONICAL.md](docs/DEPLOYMENT_CANONICAL.md).

```bash
./scripts/product/jarvis-deploy-microk8s-prod.sh   # canonical deploy
./scripts/verify-prod.sh                           # canonical post-deploy verification
```

Current per-service image tags (from `infra/k8s/overlays/prod/kustomization.yaml`,
18 pinned entries — matches the `drift-check` count above):

| Service | Tag | Service | Tag |
| --- | --- | --- | --- |
| `api-gateway` | `w8` | `orchestrator` | `w6` |
| `security-service` | `w8` | `user-profile` | `w6` |
| `voice-gateway` | `w8` | `planner-service` | `w6` |
| `smart-home-service` | `w8` | `sync-service` | `w7` |
| `pc-control` | `w7` | `llm-service` | `w6` |
| `media-service` | `w7` | `llm-server` | `local` |
| `agent-service` | `w7` | `embedding-service` | `w6` |
| `life-tracker` | `w6` | `memory-service` | `w6` |
| `analytics-service` | `w6` | `nlp-service` | `w6` |

Reboot recovery is **surgical, never a blind re-apply**: `kubectl apply -k` re-renders
kustomize and can silently revert these pins back to whatever `newTag:` says on disk.
Use `./jarvis reboot-verify` then, if needed,
`scripts/product/jarvis-recover-after-reboot.sh` (targeted `kubectl delete pod` only).

`k8s/` (the original tree) is **frozen** — quarantined by
[`scripts/guards/reject-legacy-k8s-edits.sh`](scripts/guards/reject-legacy-k8s-edits.sh)
— and kept only because the digest-pinned `k8s/overlays/prod-release/` release vehicle
still targets it. See [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md) for all seven
supported runtime modes.

## What Needs Hardware (honest gaps)

| Capability | Needs | Without it |
| --- | --- | --- |
| Vision-security (owner verification, screen understanding) | A physical webcam; host-only, never in `k8s/base` | Service simply isn't reachable |
| Voice round trip, live take | A microphone + speakers | `scripts/jarvis-voice-demo.sh --sample`/`--wav` exercises the same STT/TTS pipeline with neither |
| Android bank-notification capture (on-device) | A physical Android phone | The server-side parser (`POST /api/v1/life/finance/parse-notification`) is demoable without one |
| Sync / Pairing (E2E device sync) | A paired Android phone | Route is present but functionally `UNAVAILABLE` |
| Smart-home real device actuation | An MQTT/Home-Assistant bridge (none wired) | Catalog/scene API is real; nothing actually switches a real light |
| Media real ASR/translation/dubbing | The opt-in `real-media-image` Maven profile + Whisper.cpp binaries/models | Ships with mock ASR/translation/TTS by default |

## Module & Service Catalog

| Component | Runtime | Status |
| --- | --- | --- |
| `jarvis-common` | build-only shared library | implemented |
| `api-gateway` | k3s | implemented, deployed (`:w8`) |
| `voice-gateway` | k3s | implemented, deployed (`:w8`) |
| `nlp-service` | k3s | implemented, rule-based, deployed (`:w6`) |
| `orchestrator` | k3s | partial (bounded intent router), deployed (`:w6`) |
| `pc-control` | k3s + host | real on host; stubbed in cluster by design, deployed (`:w7`) |
| `vision-security-service` | **host only** | implemented, real CV; never deployed to `k8s/base` |
| `life-tracker` | k3s | implemented; wave-1 wellness/CSV code-only-not-deployed (`:w6`) |
| `analytics-service` | k3s | implemented; wave-1 forecast/anomaly code-only-not-deployed (`:w6`) |
| `planner-service` | k3s | implemented; LLM-enhancement endpoints return `501`; wave-1 recurring/reschedule code-only-not-deployed (`:w6`) |
| `user-profile` | k3s | implemented, deployed (`:w6`) |
| `security-service` | k3s | implemented; wave-1 roles/jti-revocation code-only-not-deployed (`:w8`) |
| `smart-home-service` | k3s | implemented, static catalog; wave-1 sensors/groups/rules code-only-not-deployed (`:w8`) |
| `agent-service` | k3s | implemented, deployed; wave-1 Postgres task store code-only-not-deployed (`:w7`) |
| `media-service` | k3s | implemented, deployed; ASR/translation/TTS mock by default (`:w7`) |
| `llm-service` | k3s | implemented, fronts `host-model-daemon` (`:w6`) |
| `memory-service` | k3s | implemented; wave-1 scopes/dedup/TTL code-only-not-deployed (`:w6`) |
| `sync-service` | k3s | implemented, deployed (`:w7`) |
| `cloud-relay` | off-prem only | implemented, opaque blob forwarder |
| `desktop-javafx` | local desktop | implemented; unified launcher + shell |
| `apps/android-app` | Android device | Phase-12 Gradle scaffold, **excluded from the Maven reactor**; bank-push capture code+unit-tested, not installed on a device |
| `host-model-daemon` | **host only** | native llama.cpp inference (Qwen3-14B), `systemd --user`, port `18080` |
| `host-tts-daemon` | **host only** | native Piper neural TTS, `systemd --user`, port `18090` |
| `embedding-service` | k3s | implemented (`:w6`) |
| `postgres` / `postgres-pgvector` | k3s | implemented infra |
| `mosquitto` | k3s | implemented infra (MQTT broker; no real device bridge yet) |
| Observability (Prometheus/Loki/Tempo/Grafana/Alloy) | k3s | implemented infra, deployed |

Per-service docs live under [`docs/services/`](docs/services/). Full inventory with
evidence: [docs/COMPONENT_STATUS.md](docs/COMPONENT_STATUS.md).

## Local Runtime Defaults

For running the stack as local native processes instead of k3s (see
[docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md), Mode 1). Ports come from
`scripts/runtime/common.sh`.

| Component | Port | Component | Port |
| --- | --- | --- | --- |
| `api-gateway` | `8080` | `security-service` | `8088` |
| `voice-gateway` | `8081` | `user-profile` | `8089` |
| `nlp-service` | `8082` | `llm-service` | `8091` |
| `orchestrator` | `8083` | `planner-service` | `8092` |
| `pc-control` | `8084` | `memory-service` | `8093` |
| `life-tracker` | `8085` | `vision-security-service` | `8094` |
| `smart-home-service` | `8086` | `host-model-daemon` | `18080` |
| `analytics-service` | `8087` | `embedding-service` | `15001` |

```bash
./scripts/check-local-env.sh    # one-time environment check
./scripts/setup-local.sh        # one-time setup (STT model, secrets, ~/.jarvis/)
./scripts/runtime-up.sh         # start Postgres (podman) + Spring Boot services
./scripts/product/jarvis-desktop-launch.sh
./scripts/runtime-down.sh
```

## Build And Verification

```bash
mvn test                                    # default: Docker-free unit + Spring slice tests
mvn verify -Pintegration                    # Testcontainers/Docker-backed tests
mvn verify -Pmicrok8s                       # tests tagged @Tag("microk8s")
mvn -pl apps/<module> -am test              # single module + its dependencies
```

```bash
./scripts/runtime-status.sh
./scripts/runtime-smoke.sh
./scripts/verify-observability.sh
./scripts/guards/reject-new-docker-runtime-files.sh --all
```

CI (`.github/workflows/`): `reactor-tests.yml` runs the full `mvn -fae test` reactor +
JaCoCo summary; `quality.yml` runs checkstyle/PMD/SpotBugs (SpotBugs is the one hard
gate — checkstyle/PMD are advisory-only by the root `pom.xml`'s own design) plus a
dependency vulnerability scan; `security-and-build.yml`, `k8s-tree-drift.yml`,
`build-images.yml`, `prod-image-sign.yml`, `desktop-entry-guard.yml`, and
`backend-readiness.yml` round out the set. **None of this is enforced yet** — GitHub
Actions and branch protection need to be turned on in the repository's GitHub settings
for these to actually gate a merge.

## Security

Jarvis is designed for trusted **local / LAN** use, not unrestricted public exposure —
see [docs/security/SECURITY_HARDENING_PLAN.md](docs/security/SECURITY_HARDENING_PLAN.md)
for the open hardening backlog.

Enforced today:

- JWT auth (BCrypt, signed access + refresh, server-side refresh rotation with reuse
  detection) via `security-service`.
- Internal service-to-service auth via `X-Service-Token`; downstream services trust
  `X-User-*` headers only behind a valid service JWT.
- TLS-only ingress (`force-ssl-redirect`); three exposed hostnames
  (`api.jarvis.local`, `voice.jarvis.local`, `grafana.jarvis.local`).
- Pod-Security Restricted profile (`runAsNonRoot`, `readOnlyRootFilesystem`, all
  capabilities dropped, `RuntimeDefault` seccomp) enforced by Kyverno policies.
- Secrets sourced from `Secret/jarvis-secrets`, never embedded in ConfigMaps.

Local-only, must never be exposed publicly: `pc-control` real-control mode,
`vision-security-service` (owner-verification camera), the host LLM daemon.

Reference docs: [docs/security/SECURITY.md](docs/security/SECURITY.md),
[docs/security/SECURITY_AUDIT.md](docs/security/SECURITY_AUDIT.md),
[docs/security/AUTH_MODEL.md](docs/security/AUTH_MODEL.md),
[docs/security/SECRETS_POLICY.md](docs/security/SECRETS_POLICY.md),
[docs/audit/2026-07-06-bug-hunt.md](docs/audit/2026-07-06-bug-hunt.md).

## Documentation Map

- [docs/STATUS.md](docs/STATUS.md) — the authoritative per-subsystem status page.
- [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) — runnable walk-through of every capability above.
- [docs/DEPLOYMENT_CANONICAL.md](docs/DEPLOYMENT_CANONICAL.md) — the one deploy/recover path.
- [docs/LIVE_VERIFICATION.md](docs/LIVE_VERIFICATION.md) — raw output from the last live acceptance passes.
- [ARCHITECTURE.md](ARCHITECTURE.md) — runtime topology and data flows.
- [docs/COMPONENT_STATUS.md](docs/COMPONENT_STATUS.md) / [docs/CAPABILITIES.md](docs/CAPABILITIES.md) — broader capability matrices (2026-05-09 audit date; STATUS.md is the current wave's delta on top of these).
- [docs/RUNTIME_MODES.md](docs/RUNTIME_MODES.md) — all seven supported runtime modes.
- [docs/architecture/](docs/architecture/) — spec, ADRs, phase acceptance evidence.
- [docs/security/](docs/security/) — security model, audit, hardening plan.
- [apps/android-app/README.md](apps/android-app/README.md) — mobile scaffold.
- Per-service docs: [docs/services/](docs/services/).
