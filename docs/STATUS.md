# Jarvis 2.0 — Status (2026-07-05)

Single-page answer to "what actually works right now, what is code but not
deployed, what is a mock, and what needs hardware." This page is scoped to the
**current wave** (80 commits landed this session: full-reactor unit-test
coverage work + eight new "wave-1" feature sets). It complements, and does not
replace, the wider backlog snapshot in
[`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md)
(708-story tally, not re-scored this wave) and the older
[`docs/CAPABILITIES.md`](CAPABILITIES.md) / [`docs/COMPONENT_STATUS.md`](COMPONENT_STATUS.md)
matrices (2026-05-09 audit date).

## Legend

| Tag | Meaning |
| --- | --- |
| **DONE** | Deployed and verified — either in the live k3s cluster (`jarvis-prod`) or, for host-only pieces, running on the host this session. |
| **CODE-ONLY-NOT-DEPLOYED** | Code-complete and unit-tested this wave, but the **running cluster image predates it** — the feature exists on disk and passes `mvn test`, but nobody has rebuilt+redeployed the image yet. `./jarvis update <service>` closes this gap per service. |
| **MOCK** | A real interface/abstraction exists, but the wired-in implementation is a deterministic stand-in, not the real integration. |
| **HARDWARE-GATED** | The code path is real; exercising it live needs physical hardware this repo cannot provide by itself (a phone, a microphone/speakers, a camera, an MQTT/Home-Assistant bridge). |

A row can carry more than one tag (e.g. media-service is both
CODE-ONLY-NOT-DEPLOYED for its new real providers *and* MOCK for what the
deployed image actually runs today).

## Cluster health snapshot (verified this session)

- `jarvis-prod` namespace: **28/28 pods Ready**.
- `api-gateway` `/actuator/health` → `UP`.
- Qwen3-14B brain answers `POST /api/v1/llm/chat` (`model=qwen3-14b-q4_k_m.gguf`).
- `./jarvis doctor` — all checks green (k3s service+API, node Ready, all
  Deployments Ready, host-model-daemon endpoint == node IP, gateway health,
  brain reachability).
- `./jarvis smoke-e2e` — **4/4** (login, LLM chat, memory search, planner).
- `./jarvis drift-check` — **18/18** live image tags match
  `infra/k8s/overlays/prod/kustomization.yaml` (the sole canonical tree; see
  [`docs/DEPLOYMENT_CANONICAL.md`](DEPLOYMENT_CANONICAL.md)).
- `scripts/jarvis-oneclick.sh` (the desktop icon's `Exec=`) self-heals a stale
  k3s `node-ip`, repoints the `host-model-daemon` Endpoints/EndpointSlice at
  the current node IP, recovers stale pods via targeted `kubectl delete pod`
  (never a blind re-apply), and only then opens the desktop shell.

These checks are read-only and safe to re-run at any time; none of them mutate
the cluster.

## Test coverage snapshot

Full-reactor JaCoCo pass this wave. **18 of 20 tracked Maven/Gradle modules are
≥80% line coverage**; the two exceptions are UI-runtime ceilings, not gaps in
business-logic testing:

| Module | Line coverage | Note |
| --- | ---: | --- |
| `security-service` | 97% | |
| `user-profile` | 97% | |
| `sync-service` | 97% | |
| `analytics-service` | 97% | |
| `smart-home-service` | 96% | |
| `nlp-service` | 93% | |
| `jarvis-common` | 93% | |
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
| **`desktop-javafx`** | **48%** | JavaFX/Monocle headless UI ceiling — most uncovered lines are scene-graph wiring, not business logic. Raised from 22% this wave. |
| **`android-app`** | **30%** | Android instrumentation ceiling (Gradle module, outside the Maven reactor). Raised from an untested baseline this wave. |

CI runs the full `mvn -fae test` reactor plus a JaCoCo summary on every push/PR
to `main` — see [`.github/workflows/reactor-tests.yml`](../.github/workflows/reactor-tests.yml)
and [`scripts/ci/jacoco-coverage-summary.sh`](../scripts/ci/jacoco-coverage-summary.sh).

## Per-subsystem status

| Subsystem | Status | Notes |
| --- | --- | --- |
| Core platform (`security-service`, `api-gateway`, `voice-gateway`, `nlp-service`, `orchestrator`, `user-profile`) | **DONE** | Deployed, healthy, JWT auth end to end. |
| `host-model-daemon` (Qwen3-14B, GPU) + `host-tts-daemon` (Piper neural TTS) | **DONE** | Both `systemd --user` services with `loginctl` linger — survive reboot without a login session. |
| `memory-service` — baseline (pgvector semantic search, Obsidian unified search) | **DONE** | Deployed, used by `./jarvis smoke-e2e`. |
| `memory-service` — wave-1 (typed scopes, dedup, TTL, export/import) | **CODE-ONLY-NOT-DEPLOYED** | Unit-tested (90% line coverage); running image predates it. |
| `planner-service` — baseline (tasks, reminders, energy-aware ranking) | **DONE** | Deployed. |
| `planner-service` — wave-1 (recurring tasks, deadline pressure, plan modes, reschedule-when-tired) | **CODE-ONLY-NOT-DEPLOYED** | See `RecurringTaskGenerator`, `PlanModeService`, `RescheduleService`. LLM-enhancement endpoints still return `501` (unchanged). |
| `life-tracker` — baseline (finance, calendar, time tracking) | **DONE** | Deployed. |
| `life-tracker` — wave-1 (weight/mood/habit trackers, CSV import/export, rollups) | **CODE-ONLY-NOT-DEPLOYED** | `WellnessService`, `RollupService`. Also owns `BankNotificationController` (parser used by the Android bank-push feature, see below). |
| `analytics-service` — baseline (finance/calendar summaries) | **DONE** | Deployed. |
| `analytics-service` — wave-1 (forecast, correlation, anomaly detection, reports) | **CODE-ONLY-NOT-DEPLOYED** | `InsightService`, `CorrelationService`, `AnomalyDetectionService`. |
| `security-service` — baseline (JWT issuance, BCrypt, refresh rotation) | **DONE** | Deployed. |
| `security-service` — wave-1 (OWNER/GUEST/SERVICE roles, per-jti access-token revocation, session timeout, audit log) | **CODE-ONLY-NOT-DEPLOYED** | `TokenRevocationService`, `AuditService`. Closes the "no per-jti revocation" gap noted in the older `CAPABILITIES.md` row 50. |
| `agent-service` — baseline (role swarm, task queue) | **DONE** | Already deployed with a movie-tagged image (a "bonus module" beyond the original backlog — see the 2026-07-04 reconciliation §c). |
| `agent-service` — wave-1 (Postgres-backed task store, git-worktree sandbox, real TESTER-role executor with an allowlist, patch proposals) | **CODE-ONLY-NOT-DEPLOYED** | `JpaAgentTaskStore`/`PostgresTaskStoreAutoConfiguration`, `TesterAgentExecutor`, `AgentActionGuard`. Global panic kill-switch (`POST /api/v1/agent/panic`) is already live at the gateway. |
| `smart-home-service` — baseline (static device catalog, in-memory state) | **DONE** | Deployed. |
| `smart-home-service` — wave-1 (sensor readings, device groups, rooms, scene history, automation rules) | **CODE-ONLY-NOT-DEPLOYED** | `SmartHomeSensorService`, `SmartHomeGroupService`, `SmartHomeRoomService`, `SmartHomeAutomationRuleRegistry`. |
| `smart-home-service` — real device actuation | **HARDWARE-GATED** (unchanged) | No Zigbee2MQTT/Home Assistant/MQTT bridge wired yet; `mosquitto` broker exists in-cluster but nothing publishes to real devices. |
| `media-service` — pipeline wiring (job queue, ffmpeg extraction/mux, artifact download) | **DONE**, real | `RealFFmpegClient`/`RealFFprobeClient` exist and are used; async job model is real (Postgres-backed jobs, HTTP 202 + poll pattern). |
| `media-service` — ASR / translation / TTS content | **MOCK** (default) | `MockAsrProvider` and `MockTranslationProvider` are the only providers wired in by default; `NeutralRussianTtsProvider`'s own javadoc says "placeholder synthesis" and it writes a `MOCK-TTS voice=...` marker file, not audio. |
| `media-service` — wave-1 real providers (`WhisperCppAsrProvider`, `LlmTranslationProvider`) | **CODE-ONLY-NOT-DEPLOYED + HARDWARE-GATED** | Selected via `media.*.mode` properties; real ASR needs a Whisper.cpp binary + model file, real translation needs `llm-service` reachable, on whatever host runs `media-service`. No real dubbing/TTS provider exists yet at all (mock-only, no code-only alternative). |
| `android-app` — bank-push `NotificationListener`, OTP-block, notification sanitizer, finance draft | **CODE-ONLY-NOT-DEPLOYED + HARDWARE-GATED** | 7 new Kotlin test suites (`BankNotificationListenerServiceTest`, `OtpGuardTest`, `NotificationSanitizerTest`, etc.), 30% module coverage. No APK has been installed on a physical device this wave. The server-side parser (`POST /api/v1/life/finance/parse-notification`) is deployable and demoable without a phone; the on-device capture path is not. |
| `desktop-javafx` | **DONE** | 48% line coverage via headless JavaFX (Monocle) — a UI-runtime ceiling, not a business-logic gap. Needs a graphical `DISPLAY` to run at all; see [`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md) for the headless proxy. |
| `vision-security-service` | **DONE, HARDWARE-GATED** | Real OCR/screen-context/CV code; host-only, needs a webcam, never deployed to `k8s/base`. |
| `pc-control` | **DONE (host) / stub (cluster)** | Real control (keyboard/mouse/volume/hotkeys) on the host; `PC_CONTROL_STUB_MODE=true` in the cluster by design. |
| Voice loop (Vosk STT + Piper TTS) | **DONE, HARDWARE-GATED for a live take** | Code path is real end to end; `scripts/jarvis-voice-demo.sh --sample`/`--wav` exercises the same pipeline with no microphone. |
| Observability (Prometheus/Loki/Tempo/Grafana/Alloy) | **DONE** | Deployed, k8s-only. |
| CI (`reactor-tests.yml`) | **DONE** | Full `mvn -fae test` reactor + JaCoCo summary on every push/PR to `main`. |

## What's explicitly mock or scaffold today (honesty check)

- **Media ASR** — `MockAsrProvider` only; `WhisperCppAsrProvider` exists but is not the default.
- **Media translation** — `MockTranslationProvider` only; `LlmTranslationProvider` exists but is not the default.
- **Media TTS/dubbing** — `NeutralRussianTtsProvider` is a placeholder; it writes a text marker, not audio. No real provider exists yet.
- **Smart-home real device control** — still a static catalog + in-memory state; wave-1 sensors/groups/rooms/rules operate on that same in-memory model, not real hardware.
- **Planner LLM-enhancement** — `LlmEnhancementService` endpoints still return `501 Not Implemented` (unchanged this wave).
- **`android-app`** — Phase 12 Gradle scaffold, excluded from the Maven reactor; no reactor build produces an APK, and no APK has been installed on a device this wave.
- **Internal mTLS**, **image signing (cosign)**, **SBOM generation**, **SAST in CI** — still `⏳ planned` per `docs/CAPABILITIES.md`; unchanged this wave.

## Cross-references

- [`README.md`](../README.md) — front-door overview and quickstart.
- [`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md) — runnable walk-through of every item above.
- [`docs/DEPLOYMENT_CANONICAL.md`](DEPLOYMENT_CANONICAL.md) — the one deployment/recovery path.
- [`docs/CAPABILITIES.md`](CAPABILITIES.md) / [`docs/COMPONENT_STATUS.md`](COMPONENT_STATUS.md) — broader, older capability matrices (2026-05-09 audit date); this page is the current wave's delta on top of them.
- [`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md) — the 708-story backlog tally (not re-scored this wave).
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — runtime topology, updated this wave with `agent-service`/`media-service`.
