# Jarvis 2.0 — Status (2026-07-08)

Single-page answer to "what actually works right now, what is code but not deployed,
what is a mock, and what needs hardware." This refreshes the 2026-07-05 version of this
page against commits landed through 2026-07-08 (image-tag bumps, an adversarial
security/bug-hunt pass and its follow-up fixes, a Prometheus scrape-gap fix, and E2E
test-harness rework). It complements, and does not replace, the wider backlog snapshot in
[`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md)
(708-story tally, not re-scored this wave) and the older
[`docs/CAPABILITIES.md`](CAPABILITIES.md) / [`docs/COMPONENT_STATUS.md`](COMPONENT_STATUS.md)
matrices (2026-05-09 audit date).

## Legend

| Tag | Meaning |
| --- | --- |
| **DONE** | Deployed and verified — either in the live k3s cluster (`jarvis-prod`) or, for host-only pieces, running on the host this session. |
| **CODE-ONLY-NOT-DEPLOYED** | Code-complete and unit-tested, but the **running cluster image predates it** — the feature exists on disk and passes `mvn test`, but nobody has rebuilt+redeployed the image yet. `./jarvis update <service>` closes this gap per service. |
| **MOCK** | A real interface/abstraction exists, but the wired-in implementation is a deterministic stand-in, not the real integration. |
| **HARDWARE-GATED** | The code path is real; exercising it live needs physical hardware this repo cannot provide by itself (a phone, a microphone/speakers, a camera, an MQTT/Home-Assistant bridge). |

A row can carry more than one tag (e.g. `media-service` is both
CODE-ONLY-NOT-DEPLOYED for its new real providers *and* MOCK for what the deployed image
actually runs today).

## Feature Maturity Matrix (source of truth: the desktop app itself)

The JavaFX Control Center classifies every navigable feature via
[`FeatureMaturityRegistry`](../apps/desktop-javafx/src/main/kotlin/org/jarvis/desktop/features/controlcenter/FeatureMaturity.kt),
and renders the matching badge on every feature tile. This is the same table the running
app shows an operator — it is reproduced here verbatim so this doc can't drift from what
ships.

| Route | Maturity | Notes |
| --- | --- | --- |
| Brain / AI Chat | READY | Qwen3-14B via `host-model-daemon`; exercised by `smoke-e2e`. |
| Memory | READY | pgvector semantic search + unified Obsidian search. |
| Finance | READY | `life-tracker` finance CRUD. |
| Planner | READY | Tasks/reminders/energy-aware ranking. LLM-enhancement endpoints still `501`. |
| Analytics | READY | `analytics-service` finance/calendar summaries. |
| PC Control | READY | Real on host; stubbed in cluster (`PC_CONTROL_STUB_MODE=true`) by design. |
| Voice Commands (help) | READY | Static reference of supported voice phrases. |
| Diagnostics | READY | Live service/log/health view. |
| AI Runtime | READY | AI runtime status/config. |
| Service Status | READY | Cluster/pod health view. |
| Settings | READY | App settings. |
| Voice Control | BETA | Real Vosk STT + Piper TTS; a *live* take is hardware-gated (mic/speakers). `--sample`/`--wav` fallback needs neither. |
| Life | BETA | Wave-1 wellness/CSV import-export/rollups code-only-not-deployed. |
| Analytics Insights | BETA | Wave-1 forecast/correlation/anomaly-detection code-only-not-deployed. |
| Security / Privacy | BETA | Wave-1 OWNER/GUEST/SERVICE roles + jti revocation + audit code-only-not-deployed. |
| Security Sessions & Audit | BETA | Same wave-1 gap. |
| Finance Review Inbox | BETA | Server-side bank-notification parser is deployable; on-device Android capture is hardware-gated. |
| Agent Swarm | BETA | Role swarm deployed; wave-1 Postgres task store/git-worktree sandbox/TESTER executor code-only-not-deployed. Global panic kill-switch already live. |
| Smart Home | BETA | Static device catalog + in-memory state; no MQTT/Home-Assistant bridge to real devices. |
| Vision Security / CV | BETA | Real OCR/screen-context/CV; **host-only process on `:8094`**, needs a webcam, never in `k8s/base`. |
| Media Jobs | MOCK DATA | Real job queue + `ffmpeg`/`ffprobe`; ASR/translation/TTS default to mock providers. |
| Proactive | EXPERIMENTAL | Observes + reasons; full proactive speech loop unproven end-to-end (needs speakers). |
| Sync / Pairing | UNAVAILABLE | Needs a paired Android phone the current runtime does not have. |

Any route not in the registry's map defaults to **BETA** (the registry's documented
safe middle ground for anything not yet explicitly classified).

## Cluster health snapshot

**Last full live acceptance pass: 2026-07-06** (see
[`docs/LIVE_VERIFICATION.md`](LIVE_VERIFICATION.md) for the raw transcript):

- `jarvis-prod` namespace: **28/28 pods Running, 24/24 Deployments Ready**.
- `api-gateway` `/actuator/health` → `UP`.
- Qwen3-14B brain answers `POST /api/v1/llm/chat` (`model=qwen3-14b-q4_k_m.gguf`).
- `./jarvis doctor` — all checks green (k3s service+API, node Ready, all Deployments
  Ready, host-model-daemon endpoint == node IP, gateway health, brain reachability).
- `./jarvis smoke-e2e` — **4/4** (login, LLM chat, memory search, planner).
- `./jarvis drift-check` — **18/18** live image tags matched
  `infra/k8s/overlays/prod/kustomization.yaml` at that pass (the sole canonical tree;
  see [`docs/DEPLOYMENT_CANONICAL.md`](DEPLOYMENT_CANONICAL.md)).

**Since that pass (through 2026-07-08):**

- `api-gateway`, `voice-gateway`, and `security-service` were bumped to image tag
  `:w8` in the overlay (2026-07-07, token-masking + status/voice fixes). This bump has
  **not yet been re-confirmed against a fresh live snapshot** in this doc — re-run
  `./jarvis doctor && ./jarvis drift-check` before treating the cluster as
  current-second-verified.
- The Prometheus scrape gap flagged in the 2026-07-06 pass (`agent-service`,
  `media-service`, `sync-service` had no `prometheus.io/scrape` annotation and were
  missing from the `jarvis-services-ingress-from-prometheus` NetworkPolicy allowlist)
  has since been closed — the operator applied
  `infra/k8s/overlays/prod/networkpolicy-allowlist.yaml` plus the per-service scrape
  patches. Current count: **17/17** `jarvis-actuator` Prometheus targets report `up`.
  Re-verify with `./scripts/verify-observability.sh` or a direct
  `/api/v1/targets?state=active` query against the in-cluster Prometheus Service.
- A 2026-07-06 adversarial multi-agent security/code-quality pass
  ([`docs/audit/2026-07-06-bug-hunt.md`](audit/2026-07-06-bug-hunt.md); 19 reviewer
  agents + 19 adversarial verifiers, 77 agents total) found **56 confirmed bugs** (12
  CRITICAL / 24 HIGH / 19 MEDIUM / 1 LOW). The three CRITICAL findings have follow-up
  fix commits already landed:
  - `revokeAllRefreshTokens` now advances `tokensValidFrom`, so revoke-all actually
    invalidates already-issued access tokens — commit `fix(security): change-password/
    account-disable now advances tokensValidFrom (revokes already-issued access
    tokens)`.
  - `LogSanitizer`'s PII-redaction polarity was inverted (default config logged raw
    PII) — commit `fix(jarvis-common): un-invert LogSanitizer PII flag (default now
    REDACTS)`.
  - The panic kill-switch's route allowlist missed `/api/v1/pc/**` and
    `/api/v1/agents/**` — commit `fix(api-gateway): panic blocks PC/agent action
    routes + admin-only panic/kill-switch + 403 on denial`.
  The remaining 53 findings (24 HIGH / 19 MEDIUM / 1 LOW) are an **open backlog**, not
  reconciled item-by-item against this pass — treat the bug-hunt doc as the current
  source of truth for what's still outstanding.

These checks are read-only and safe to re-run at any time; none of them mutate the
cluster.

## Test coverage snapshot (as measured 2026-07-05, not re-run this pass)

Full-reactor JaCoCo pass. **18 of 20 tracked Maven/Gradle modules were ≥80% line
coverage**; the two exceptions are UI-runtime ceilings, not gaps in business-logic
testing:

| Module | Line coverage | Note |
| --- | ---: | --- |
| `security-service` | 97% | |
| `user-profile` | 97% | |
| `sync-service` | 97% | |
| `analytics-service` | 97% | |
| `smart-home-service` | 96% | |
| `nlp-service` | 93% | |
| `jarvis-common` | 93% | Raised to 93% after this snapshot; see coverage-raise commit for `jarvis-common`. |
| `cloud-relay` | 94% | |
| `orchestrator` | 91% | |
| `agent-service` | 90% | |
| `life-tracker` | 90% | |
| `memory-service` | 90% | |
| `planner-service` | 87% | |
| `vision-security-service` | 86% | |
| `api-gateway` | 84% | |
| `voice-gateway` | 84% | |
| `pc-control` | 84% | |
| `llm-service` | 84% | |
| **`desktop-javafx`** | **48%** | JavaFX/Monocle headless UI ceiling — most uncovered lines are scene-graph wiring, not business logic. |
| **`android-app`** | **30%** | Android instrumentation ceiling (Gradle module, outside the Maven reactor). |

CI runs the full `mvn -fae test` reactor plus a JaCoCo summary on every push/PR to
`main` — see [`.github/workflows/reactor-tests.yml`](../.github/workflows/reactor-tests.yml)
and [`.github/workflows/quality.yml`](../.github/workflows/quality.yml) (checkstyle/PMD
advisory-only; SpotBugs is the one hard-failing gate). **GitHub Actions and branch
protection must be enabled in the GitHub UI** for either workflow to actually gate a
merge — as of this pass neither is confirmed enabled.

## Per-subsystem status

| Subsystem | Status | Notes |
| --- | --- | --- |
| Core platform (`security-service`, `api-gateway`, `voice-gateway`, `nlp-service`, `orchestrator`, `user-profile`) | **DONE** | Deployed, healthy, JWT auth end to end. |
| `host-model-daemon` (Qwen3-14B, GPU) + `host-tts-daemon` (Piper neural TTS) | **DONE** | Both `systemd --user` services with `loginctl` linger — survive reboot without a login session. |
| `memory-service` — baseline (pgvector semantic search, Obsidian unified search) | **DONE** | Deployed, used by `./jarvis smoke-e2e`. |
| `memory-service` — wave-1 (typed scopes, dedup, TTL, export/import) | **CODE-ONLY-NOT-DEPLOYED** | Unit-tested; running image predates it. |
| `planner-service` — baseline (tasks, reminders, energy-aware ranking) | **DONE** | Deployed. |
| `planner-service` — wave-1 (recurring tasks, deadline pressure, plan modes, reschedule-when-tired) | **CODE-ONLY-NOT-DEPLOYED** | See `RecurringTaskGenerator`, `PlanModeService`, `RescheduleService`. LLM-enhancement endpoints still return `501`. |
| `life-tracker` — baseline (finance, calendar, time tracking) | **DONE** | Deployed. |
| `life-tracker` — wave-1 (weight/mood/habit trackers, CSV import/export, rollups) | **CODE-ONLY-NOT-DEPLOYED** | `WellnessService`, `RollupService`. Also owns `BankNotificationController` (parser used by the Android bank-push feature, see below). |
| `analytics-service` — baseline (finance/calendar summaries) | **DONE** | Deployed. |
| `analytics-service` — wave-1 (forecast, correlation, anomaly detection, reports) | **CODE-ONLY-NOT-DEPLOYED** | `InsightService`, `CorrelationService`, `AnomalyDetectionService`. |
| `security-service` — baseline (JWT issuance, BCrypt, refresh rotation) | **DONE** | Deployed (`:w8`); revoke-all session-floor bug fixed this pass. |
| `security-service` — wave-1 (OWNER/GUEST/SERVICE roles, per-jti access-token revocation, session timeout, audit log) | **CODE-ONLY-NOT-DEPLOYED** | `TokenRevocationService`, `AuditService`. |
| `agent-service` — baseline (role swarm, task queue) | **DONE** | Deployed (`:w7`). |
| `agent-service` — wave-1 (Postgres-backed task store, git-worktree sandbox, real TESTER-role executor with an allowlist, patch proposals) | **CODE-ONLY-NOT-DEPLOYED** | `JpaAgentTaskStore`/`PostgresTaskStoreAutoConfiguration`, `TesterAgentExecutor`, `AgentActionGuard`. Global panic kill-switch (`POST /api/v1/agent/panic`) live at the gateway; route-coverage bug fixed this pass. |
| `smart-home-service` — baseline (static device catalog, in-memory state) | **DONE** | Deployed (`:w8`). |
| `smart-home-service` — wave-1 (sensor readings, device groups, rooms, scene history, automation rules) | **CODE-ONLY-NOT-DEPLOYED** | `SmartHomeSensorService`, `SmartHomeGroupService`, `SmartHomeRoomService`, `SmartHomeAutomationRuleRegistry`. |
| `smart-home-service` — real device actuation | **HARDWARE-GATED** | No Zigbee2MQTT/Home Assistant/MQTT bridge wired yet; `mosquitto` broker exists in-cluster but nothing publishes to real devices. |
| `media-service` — pipeline wiring (job queue, ffmpeg extraction/mux, artifact download) | **DONE**, real | `RealFFmpegClient`/`RealFFprobeClient` exist and are used; async job model is real (Postgres-backed jobs, HTTP 202 + poll pattern). Deployed (`:w7`). |
| `media-service` — ASR / translation / TTS content | **MOCK** (default) | `MockAsrProvider` and `MockTranslationProvider` are the only providers wired in by default; the placeholder TTS provider writes a `MOCK-TTS voice=...` marker file, not audio. |
| `media-service` — wave-1 real providers (`WhisperCppAsrProvider`, `LlmTranslationProvider`) | **CODE-ONLY-NOT-DEPLOYED + HARDWARE-GATED** | Selected via `media.*.mode` properties; needs the opt-in `real-media-image` Maven profile plus a Whisper.cpp binary + model file. No real dubbing/TTS provider exists at all yet. |
| `android-app` — bank-push `NotificationListener`, OTP-block, notification sanitizer, finance draft | **CODE-ONLY-NOT-DEPLOYED + HARDWARE-GATED** | Kotlin test suites cover it (30% module coverage). No APK has been installed on a physical device. The server-side parser (`POST /api/v1/life/finance/parse-notification`) is deployable and demoable without a phone; the on-device capture path is not. |
| `desktop-javafx` | **DONE** | 48% line coverage via headless JavaFX (Monocle) — a UI-runtime ceiling, not a business-logic gap. Needs a graphical `DISPLAY` to run at all; see [`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md) for the headless proxy. |
| `vision-security-service` | **DONE, HARDWARE-GATED** | Real OCR/screen-context/CV code; **host-only process on `:8094`**, needs a webcam, never deployed to `k8s/base`. |
| `pc-control` | **DONE (host) / stub (cluster)** | Real control (keyboard/mouse/volume/hotkeys) on the host; `PC_CONTROL_STUB_MODE=true` in the cluster by design. Deployed (`:w7`). |
| Voice loop (Vosk STT + Piper TTS) | **DONE, HARDWARE-GATED for a live take** | Code path is real end to end; `scripts/jarvis-voice-demo.sh --sample`/`--wav` exercises the same pipeline with no microphone. |
| Observability (Prometheus/Loki/Tempo/Grafana/Alloy) | **DONE** | Deployed, k8s-only. Scrape coverage now 17/17 `jarvis-actuator` targets (gap closed this pass, see above). |
| CI (`reactor-tests.yml`, `quality.yml`, + 6 more workflows) | **PRESENT, NOT ENFORCED** | Workflows exist and run on push/PR to `main`; GitHub Actions + branch protection still need to be enabled in the repo's GitHub settings to actually gate merges. |

## What's explicitly mock or scaffold today (honesty check)

- **Media ASR** — `MockAsrProvider` only; `WhisperCppAsrProvider` exists but is not the default.
- **Media translation** — `MockTranslationProvider` only; `LlmTranslationProvider` exists but is not the default.
- **Media TTS/dubbing** — placeholder provider only; it writes a text marker, not audio. No real provider exists yet.
- **Smart-home real device control** — still a static catalog + in-memory state; wave-1 sensors/groups/rooms/rules operate on that same in-memory model, not real hardware.
- **Planner LLM-enhancement** — `LlmEnhancementService` endpoints still return `501 Not Implemented`.
- **`android-app`** — Phase-12 Gradle scaffold, excluded from the Maven reactor; no reactor build produces an APK, and no APK has been installed on a device.
- **Internal mTLS**, **image signing (cosign)**, **SBOM generation**, **SAST in CI** — still `⏳ planned` per `docs/CAPABILITIES.md`.
- **CI enforcement** — workflows exist but Actions/branch protection are not confirmed enabled in the GitHub UI; nothing currently blocks a merge on a failing check.

## Cross-references

- [`README.md`](../README.md) — front-door overview and quickstart.
- [`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md) — runnable walk-through of every item above.
- [`docs/DEPLOYMENT_CANONICAL.md`](DEPLOYMENT_CANONICAL.md) — the one deployment/recovery path.
- [`docs/LIVE_VERIFICATION.md`](LIVE_VERIFICATION.md) — raw transcripts from the 2026-07-05/06 acceptance passes.
- [`docs/audit/2026-07-06-bug-hunt.md`](audit/2026-07-06-bug-hunt.md) — the adversarial security/quality audit referenced above.
- [`docs/CAPABILITIES.md`](CAPABILITIES.md) / [`docs/COMPONENT_STATUS.md`](COMPONENT_STATUS.md) — broader, older capability matrices (2026-05-09 audit date); this page is the current wave's delta on top of them.
- [`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md) — the 708-story backlog tally (not re-scored this wave).
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — runtime topology, includes `agent-service`/`media-service`.
