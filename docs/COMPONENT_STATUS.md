# Component Status

Audit date: 2026-05-08. Source of truth: `pom.xml`, module `pom.xml`, runtime
scripts, `k8s/`, `infra/k8s/`, controller/service classes. This page is a
condensed inventory; per-service detail lives under
[`docs/services/`](services/).

## Status Legend

- **active** — built, tested, deployed by canonical entry points.
- **active but optional** — built and deployed only behind a feature flag.
- **local-only** — used by `scripts/runtime-up.sh` / native host runtime; not in
  `k8s/base`.
- **k8s-only** — only deployed via the Kubernetes path.
- **experimental** — committed but not exercised by canonical paths.
- **deprecated** — superseded by another component; retained for migration.
- **legacy candidate** — looks stale, but at least one canonical path still
  references it; needs operator review before any cleanup.
- **drifted** — referenced by code/scripts but the manifest or peer is missing
  or diverged; needs a runtime fix, not a docs fix.

## Maven Reactor Modules

Source: root [`pom.xml`](../pom.xml) `<modules>` block.

| Component | Path | Type | Runtime mode | Status | Evidence |
| --- | --- | --- | --- | --- | --- |
| `jarvis-common` | [apps/jarvis-common](../apps/jarvis-common) | Spring auto-config library | build-only | active | [`AutoConfiguration.imports`](../apps/jarvis-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports) lists 6 auto-configs |
| `api-gateway` | [apps/api-gateway](../apps/api-gateway) | edge gateway (REST + WS) | local + k8s | active | port 8080 ([`application.yaml`](../apps/api-gateway/src/main/resources/application.yaml)), [`k8s/base/api-gateway/deployment.yaml`](../k8s/base/api-gateway/deployment.yaml) |
| `voice-gateway` | [apps/voice-gateway](../apps/voice-gateway) | STT/TTS + voice WS | local + k8s | active but optional | port 8081; required only when `JARVIS_REQUIRE_VOICE_GATEWAY=true` ([`jarvis-launch.sh`](../jarvis-launch.sh) line 1305) |
| `nlp-service` | [apps/nlp-service](../apps/nlp-service) | rule-based NLP | local + k8s | active | port 8082 |
| `orchestrator` | [apps/orchestrator](../apps/orchestrator) | bounded intent router | local + k8s | active | port 8083; `RabbitTopologyConfig` + Kafka audit publisher in `jarvis-common` |
| `pc-control` | [apps/pc-control](../apps/pc-control) | desktop control | local + k8s | active | port 8084; `PC_CONTROL_STUB_MODE=true` in cluster ([docs/services/pc-control.md](services/pc-control.md)) |
| `vision-security-service` | [apps/vision-security-service](../apps/vision-security-service) | local CV worker | local-only | active | port 8094; **not** in `k8s/base/kustomization.yaml` |
| `life-tracker` | [apps/life-tracker](../apps/life-tracker) | finance/calendar/time service | local + k8s | active | port 8085 |
| `analytics-service` | [apps/analytics-service](../apps/analytics-service) | read-model summaries | local + k8s | active | port 8087 |
| `planner-service` | [apps/planner-service](../apps/planner-service) | tasks/reminders | local + k8s | active (LLM endpoints not implemented) | port 8092; `LlmServiceClient` throws "Planner LLM enhancement is not implemented" — see [`LlmEnhancementService.java:33`](../apps/planner-service/src/main/java/org/jarvis/planner/service/LlmEnhancementService.java) |
| `user-profile` | [apps/user-profile](../apps/user-profile) | profile/context | local + k8s | active | port 8089 |
| `security-service` | [apps/security-service](../apps/security-service) | auth/JWT | local + k8s | active | port 8088 |
| `smart-home-service` | [apps/smart-home-service](../apps/smart-home-service) | smart-home API | local + k8s | active (static catalog) | port 8086 |
| `llm-service` | [apps/llm-service](../apps/llm-service) | authenticated AI facade | local + k8s | active but optional | port 8091; gated by `ENABLE_LLM=true` |
| `memory-service` | [apps/memory-service](../apps/memory-service) | semantic memory + pgvector | local + k8s | active but optional | port 8093; gated by `ENABLE_MEMORY=true` |
| `sync-service` | [apps/sync-service](../apps/sync-service) | E2E sync inbox for paired devices | local + k8s | active | [`k8s/base/sync-service/deployment.yaml`](../k8s/base/sync-service/deployment.yaml); cross-ref [ADR-0013](architecture/ADR/ADR-0013-android-and-cloud-relay.md) |
| `cloud-relay` | [apps/cloud-relay](../apps/cloud-relay) | off-prem opaque blob forwarder | off-prem only | active | [`k8s/cloud/cloud-relay/deployment.yaml`](../k8s/cloud/cloud-relay/deployment.yaml); not in `k8s/base` |
| `desktop-javafx` | [apps/desktop-javafx](../apps/desktop-javafx) | JavaFX desktop shell + launcher | local desktop | active | [ADR-0002](architecture/ADR/ADR-0002-desktop-javafx-native-desktop-agent.md): "not deprecated" |

## Shared Libraries

| Component | Path | Status | Notes |
| --- | --- | --- | --- |
| `command-schema` | [libs/command-schema](../libs/command-schema) | active | Pure POJO contracts; consumed by orchestrator + voice |
| `event-schema` | [libs/event-schema](../libs/event-schema) | active | Audit/event contracts |
| `sync-protocol` | [libs/sync-protocol](../libs/sync-protocol) | active | Used by sync-service + cloud-relay |

## Non-Maven Components

| Component | Path | Type | Status | Evidence |
| --- | --- | --- | --- | --- |
| `apps/android-app` | [apps/android-app](../apps/android-app) | Gradle Android module | experimental (Phase 12 Pass 1 scaffold) | Not in root `pom.xml`; `build.gradle.kts` only — APK not produced by reactor tests |
| `apps/llm-server-py` | [apps/llm-server-py](../apps/llm-server-py) | Python FastAPI llama.cpp wrapper | **deprecated** | [`apps/llm-server-py/README.md`](../apps/llm-server-py/README.md): "Phase 3 (host model daemon) supersedes this Python wrapper." |
| `apps/embedding-service-py` | [apps/embedding-service-py](../apps/embedding-service-py) | Python FastAPI embedding worker | active but optional | Built via `podman build -f Containerfile`; consumed by `memory-service` when `ENABLE_MEMORY=true` |
| Native llama.cpp host daemon | invoked via [infra/scripts/model-runtime/](../infra/scripts/model-runtime/) | host-only LLM worker | active | Phase 3 canonical inference path; routed in cluster via [`k8s/base/host-model-daemon/`](../k8s/base/host-model-daemon/) selectorless Service + manual Endpoints |
| `mosquitto` | [k8s/base/mosquitto/](../k8s/base/mosquitto/) | MQTT broker | k8s-only | Phase-1 evidence flags it as legacy retained for future smart-home integration |
| `postgres` | [k8s/base/postgres/](../k8s/base/postgres/) | datastore | local container + k8s | StatefulSet in base |
| Observability stack | [k8s/base/observability/](../k8s/base/observability/) | Prometheus / Loki / Tempo / Grafana / Alloy | k8s-only | Required by `jarvis-launch.sh` `ensure_observability_stack` |

## Two K8s Trees

The repo currently has two parallel Kubernetes trees:

| Tree | Used by | Status |
| --- | --- | --- |
| [`k8s/`](../k8s/) | `jarvis-launch.sh`, `scripts/product/jarvis-deploy-prod.sh`, `k8s/overlays/prod-release` digest-pinned artifact, `Makefile` `make launch` | active |
| [`infra/k8s/`](../infra/k8s/) | `scripts/product/jarvis-deploy-microk8s-prod.sh`, `scripts/verify-prod.sh` | active (declared canonical for MicroK8s by [`infra/k8s/README.md`](../infra/k8s/README.md), but launcher entry points still target `k8s/`) |

Both are wired into canonical scripts, so neither is dead. They have **drifted**:
`infra/k8s/base/` adds `kafka` and `rabbitmq` StatefulSets that `k8s/base/`
does not have, and several base manifests differ. See
[LEGACY_AND_CLEANUP.md](LEGACY_AND_CLEANUP.md) for the full diff list.

---

## 2026-06-06 Continuation status (done / partial / next)

Verified live against `jarvis-prod` k3s. No Git writes.

### DONE (verified this round)
- **Brain restored to host 14B (GPU).** Root cause found+fixed: `host-model-daemon`
  Endpoints had reverted to the TEST-NET placeholder `192.0.2.1` (unroutable), so
  `llm-service` could not reach the 14B. Fix: re-pointed `llm-service`
  `LLM_SERVER_URL=http://host-model-daemon...:18080` (HOST_DAEMON_ENABLED=false, simple
  OpenAI-client mode), added NetworkPolicy `llm-service-egress-host-model-daemon`
  (→10.113.0.176/32:18080), re-patched Endpoints+EndpointSlice → node IP. `POST
  /api/v1/llm/chat` returns qwen3-14b with persona. **VERIFIED 200 + reply + model.**
- **Obsidian integration** (host module `scripts/jarvis-obsidian.py`, `jarvis obsidian`
  CLI, alive-loop daily journal). Notes land in `~/JarvisVault`. **11 unit tests pass**
  (`scripts/tests/test_jarvis_obsidian.py`) — path-traversal safety, secret redaction
  (Bearer/api_key/sk- redacted before write), note/daily creation. Vault→vector index
  via `/api/v1/memory/notes`.
- **memory `/search` via gateway**: 404→200 (MemoryController dual base path).
- **/status/report**: 200. **planner LLM**: active, gracefully degrades to 503+JSON.
- config fixes: sync→orchestrator :8083, orchestrator `jarvis.memory.url`.

### PARTIAL / honest caveats
- **host-model-daemon endpoints revert recurs** — gets reset to `192.0.2.1` on cluster
  re-apply. Durable fix = run `infra/scripts/microk8s/apply-host-endpoints.sh --ip=<node>`
  after any redeploy, or re-patch:
  `kubectl -n jarvis-prod patch endpoints host-model-daemon --type=json -p '[{"op":"replace","path":"/subsets/0/addresses/0/ip","value":"10.113.0.176"}]'`.
- **Multi-model routing**: main 14B (:18080) + embedding-service + Piper TTS (:18090) +
  rule-based fast-intent (nlp-service) are live. Coding/router ports :18081/:18082 are
  configured but NOT running → `HOST_DAEMON_ENABLED` must stay `false` (true needs those
  ports and fails readiness). STT is desktop-client side (voice-gateway + JavaFX).
- **Obsidian vector index**: notes ingested to memory-notes store; the `/search` endpoint
  queries the conversation-chunk store — both embed, but search surfaces chunks (unifying
  the two stores is the next step).
- **memory-service ObsidianVaultWriter** (in-cluster) writes to pod-ephemeral `/tmp` —
  hostPath mount BLOCKED by Kyverno; the host module is the real vault writer.

### NEXT
- Unify note-store + chunk-store in `/search` so Obsidian notes surface in RAG answers.
- Make host-endpoints durable (init job / post-deploy hook).
- Remaining bug-report items: finance `occurredAt` Instant→LocalDateTime; `@Transactional`
  on wellness/finance; VOL_UP regex in EnhancedRuleBasedNlpService; TOCTOU in sync caches.

---

## 2026-06-06 (b) — TOCTOU, occurredAt, NLP, semantic-notes

### DONE + verified
- **sync-service TOCTOU fixed**: `ReplayCache.recordIfUnseen` → atomic `asMap().putIfAbsent`;
  `PairingNonceStore.consume` → atomic `asMap().remove`. (sync-service:movie2)
- **finance occurredAt**: HttpDispatchClient normalizes Instant→LocalDateTime (strips Z/offset)
  so phone finance entries no longer 400 at life-tracker. **@Transactional** on WellnessController
  health-entry (SLEEP+STEPS atomic). (life-tracker:movie7)
- **NLP VOL_UP fixed in EnhancedRuleBasedNlpService** (bare "сделай" no longer hijacks volume_up);
  verified тише→volume_down, громче→volume_up on /intent-fast. (nlp-service:movie4)
- **Android setup**: `scripts/jarvis-android-setup.sh` (read-only) verifies sync-service exposure and
  prints the exact manual NodePort+NetworkPolicy commands (guard blocks the agent from patching prod).
- Smoke 8/8, host-endpoint cluster-reachable, obsidian 11 tests — all green.

### PARTIAL (honest) — semantic Obsidian note search
- Note embeddings ARE computed+stored (fixed `MemoryEmbeddingClient`: was POSTing `{"text"}`,
  endpoint needs `{"texts":[...]}` → 422 → embeddings silently skipped; now works, verified in DB).
- The pgvector cosine query is correct — **verified directly in psql** (distance ~0.15 for synonyms).
- BUT executing it through Hibernate/JPA on this driver throws `PSQLException: No results were returned
  by the query` for the multi-column/entity native query (a known JDBC/Hibernate quirk with this pgvector
  setup). `unifiedSearchController` therefore **degrades to keyword note search**, which works for exact
  terms. Semantic CHUNK search (conversations) is unaffected and works.
- NEXT: resolve the native-query execution quirk (e.g. JdbcTemplate instead of EntityManager, or register
  a vector read type on MemoryNoteEntity.embedding) to surface semantic note hits. memory-service:movie13.

### UPDATE — semantic Obsidian note search now WORKS (memory-service:movie14)
The Hibernate "No results were returned" quirk was bypassed by running the pgvector
query via **JdbcTemplate** (plain JDBC) instead of EntityManager/Spring-Data native @Query.
VERIFIED: `/api/v1/memory/search/unified` query "лодка плавание по воде" (zero keyword
overlap) returns the "Морская прогулка" note with `noteSearchMode=semantic`. Falls back to
keyword if embeddings/JdbcTemplate are unavailable. P2 COMPLETE.

---

## 2026-06-06 (c) — reindex upsert + entity vector-read fix (memory-service:movie16)

### DONE + verified
- **Obsidian reindex is now idempotent (upsert).** The indexer sends a stable
  `source="obsidian:<vault-path>"`; `MemoryNoteService.write()` looks up an existing note
  by that source (`findFirstBySourceOrderByCreatedAtDesc`) and UPDATES it (re-embed) instead
  of inserting a duplicate. Manual notes (source "jarvis"/null) unaffected.
  VERIFIED: POST same source twice → SAME memoryId, body updated, **1 row**.
- **Root cause also fixed: `MemoryNoteEntity.embedding` could not be READ by Hibernate**
  (`PgArray.getArrayDelimiter → "No results were returned"`). Added `@Array(length=384)` +
  `@JdbcTypeCode(SqlTypes.VECTOR)` (mirroring `MemoryChunk`). Now `findById`/`update` work,
  which is what made the upsert re-embed path succeed.
- **`scripts/jarvis-android-setup.sh --dry-run`**: server-side smoke (no phone) — sync-service
  `/actuator/health`=200, `pairing/init` reachable (401 = alive, auth-gated). PASS.
- **`docs/JARVIS_FINAL_RUNBOOK.md`**: single operator runbook (start/health/endpoint/brain/memory/
  Obsidian/indexing/Android/desktop/what-not-to-enable/post-reapply).

### KNOWN (not a regression)
- ~13 historical duplicate note rows exist from pre-fix reindex testing; the upsert prevents
  NEW duplicates. Manual reversible cleanup SQL is in the runbook §8 (agent guard blocks the
  bulk prod-DB write, so it is operator-run). Vault files on disk are never touched.

### NOT changed (deliberate)
- Desktop JavaFX UI code untouched (status labels documented in runbook §13 instead) — a UI
  change cannot be visually verified headless, so editing it would risk breaking what can't be checked.

---

## 2026-06-06 (d) — P0 brain durability + RAG recall (audit follow-up)

### Brain durability — ALREADY systemd-managed (audit correction)
The prior audit reported "jarvis-llm inactive/not-found, brain runs manually". That was a
**false alarm caused by checking the wrong unit name**. The real unit is the INSTANCE
`jarvis-llm@18080.service`:
- `UnitFileState=enabled`, `ActiveState=active`, `Restart=on-failure`.
- `loginctl Linger=yes` → starts on boot without login.
- `MainPID` == the `llama-server` process listening on :18080 (i.e. systemd owns it).
- EnvironmentFile `~/.jarvis/llm-18080.env` = MODEL/HOST=0.0.0.0/CTX=8192/THREADS=6/EXTRA=`-ngl 99 -fa on -ctk q8_0 -ctv q8_0`.
- **Restart test PASSED**: `systemctl --user restart jarvis-llm@18080` → new PID 1790220 →
  `:18080/health` 503→200 in ~6s → gateway `/llm/chat` returns `model=qwen3-14b` with persona text.
No new unit was created (would have conflicted with the working instance unit).

### RAG recall — PROVEN end-to-end
- Stored a unique fabricated fact via the real API (`POST /api/v1/memory/ingest`, chunk-store):
  `RAG_TEST_20260606_DEN_SECRET_CODE = blue-lobster-731`.
- `/api/v1/memory/search` (semantic) returned the chunk.
- `POST /api/v1/llm/chat` "Какой у меня RAG_TEST_20260606_DEN_SECRET_CODE?" → reply
  **"blue-lobster-731."** A made-up code the model cannot know from training → recall came
  only from injected memory context. RAG confirmed working (llm-service → memory `/memory/search`,
  TOPK=5, MAX_TOKENS=600).
- Test fact left in chunk-store as **harmless test data** (no per-chunk delete API; bulk DB delete is forbidden).

---

## 2026-06-06 (e) — Human-facing "movie" layer audit (verify-only, no code changes)

Read-only audit of the four human-facing flows. Backend P0 (14B brain durability, RAG) was
already closed; this section grades only the movie layer. Honest verified / not-verified marks.

### Voice — PARTIAL
**Verified (live):**
- STT engine works. Host headless smoke (`scripts/jarvis-voice-smoke.sh`) transcribed the
  bundled WAV via Vosk → brain returned a correct contextual answer (exit 0).
- In-cluster STT ready: voice-gateway `/api/v1/voice/diagnostics` reports `STT_READY`, both
  `en-US` + `ru-RU` Vosk models loaded, inference self-test passed.
- TTS works: host Piper `:18090/health`=200; cluster TTS self-test valid; gateway
  `POST /api/v1/voice/synthesize` → real **49916-byte** PCM 16k mono WAV.
- voice-gateway runtime `ready`: WebSocket loopback handshake UP, 95 prerecorded assets, rabbit UP.
- Text-command→orchestrator path (`POST /api/v1/voice/command`) returns a spoken-text 200.
- Voice session lifecycle (`/sessions` start→get→end) returns 200/202.

**NOT verified / broken:**
- **Voice SESSION intent resolution is BROKEN.** `POST /api/v1/voice/sessions/{id}/utterance`
  always returns `UNKNOWN_INTENT`. Root cause: voice-gateway calls `nlp-service:8082` directly,
  but there is **no NetworkPolicy** allowing voice-gateway→nlp egress (namespace is default-deny).
  Log: `nlp-service unreachable ... Connection refused`. The "тише→volume_down" that was verified
  earlier used the *direct* api-gateway→nlp path, which is allowed — not the session path.
  Fix (next step): add `voice-gateway-egress-nlp` (egress voice-gateway→nlp:8082) +
  matching nlp ingress-from-voice-gateway.
- **HTTP STT upload (`/api/v1/voice/transcribe`) OOM-kills the voice-gateway pod** (exit 137,
  memory limit 1Gi). The Vosk decode under a 1Gi cap exceeds memory. Do not exercise this path
  until the limit is raised (~2Gi) or decode is streamed.
- Real mic capture → speaker playback: requires a physical mic/speakers; voice-gateway itself
  does not open host capture devices (it receives PCM frames from a client).
- Cosmetic: voice-gateway readiness self-probe to `api-gateway:8080` is DOWN (connection refused);
  routing through the ingress works regardless.

### Desktop JavaFX GUI — BUILD verified, UX not (needs display)
- Module **builds**; jar present (`apps/desktop-javafx/target/desktop-javafx-1.0.0.jar`, 27 MB).
- Headless unit tests: **324 run, 0 failures, 0 errors, 0 skipped**.
- 11 routes present: HOME, AI, VOICE, PC_CONTROL, SMART_HOME, LIFE, PLANNER, ANALYTICS,
  VISION_SECURITY, DIAGNOSTICS, SETTINGS. Voice + PcControl WebSocket clients, Auth, SystemControl present.
- Connects to gateway: the desktop dry-run (`scripts/e2e-desktop-dry-run.sh`) passes a SAFE
  pc-control read + orchestrator intent; `executorFound=false` confirms GUI exec needs the
  desktop WebSocket client connected.
- NOT verified: visual UX, status badges, chat/voice screens — all require a display session.
- Note: the dry-run script logs in at `/auth/login` (wrong) and sends no `Host:` header; run it
  against this ingress by exporting `JARVIS_GATEWAY_URL=https://api.jarvis.local` +
  `JARVIS_SMOKE_TOKEN=<token>` (token from `/api/v1/security/auth/login`).

### PC-control — SAFE reads verified
- Host daemon `jarvis-pc-control.service` active; :8084 returns 401 (alive, auth-guarded).
- SAFE reads via gateway all return real host state:
  `/api/v1/pc/desktop/capabilities` (x11; xdotool/wmctrl/wpctl/pactl present),
  `/volume` (`{"level":27,"muted":false,"backend":"wpctl"}`),
  `/system-info` (Ubuntu 24.04.4, den-pc, GNOME), `/window/active`, `/window/list` (real windows).
- Allowlisted actions: VOLUME_*, MUTE, MEDIA_CONTROL, OPEN_APP/URL, HOTKEY, NOTIFY, SCREENSHOT,
  LOCK_SCREEN, SYSTEM_COMMAND, SCENARIO. **Risky/state-changing actions were NOT triggered** in this audit.

### Proactive presence — BACKEND verified, audible output NOT verified
- **Host loop** `jarvis-proactive.service` active and genuinely working: every ~2.5 min it
  captures the screen, OCRs it, and the 14B brain produces accurate observations (it correctly
  described this audit session). BUT every decision logs `(not spoken: quiet/anti-spam/speak-disabled)`
  — it decides but emits **no audio** right now. Speak gate: `JARVIS_PROACTIVE_SPEAK` (default true),
  not-quiet-hours, and anti-spam `JARVIS_PROACTIVE_MIN_GAP` (600s).
- **Cluster** `ProactiveWarningScheduler` deployed in life-tracker, `@Scheduled` every 5 min
  (`jarvis.proactive.*`). It only speaks when a user has a fresh warning AND a connected voice
  WebSocket client; default user `owner` has no warnings and no client connected → silent by design.
  `/internal/voice/notify` pushes TEXT to a WebSocket client (no server-side audio); 404 if none.
- Audible proactive speaking (host or cluster) is therefore **not verified** — it needs either a
  forced host one-shot with speaking on, or a connected desktop client + a real warning.

### Net assessment
Movie-like: **PARTIAL**. Backend brain/RAG/TTS/STT engines + safe PC-control + proactive
*awareness* are real and verified. The audible/visual "presence" (spoken voice loop, GUI UX,
spoken proactive remarks) and the in-cluster voice-session intent path are **not** verified —
they need a physical mic/speakers/display or one NetworkPolicy fix. No code, no git writes,
no risky actions in this audit.

---

## 2026-06-06 (f) — Voice-unblock fixes applied (infra/script only, no service rebuild)

Acted on the §(e) blockers. Only safe infra + script changes; no service code/image rebuild.

### voice-gateway → nlp NetworkPolicy — APPLIED, network layer verified, **end-to-end still blocked by auth**
- Added two policies (source: `k8s/overlays/prod/networkpolicy-allowlist.yaml`; applied live via `kubectl apply`):
  - `voice-gateway-egress-nlp` — egress `app: voice-gateway` → `app: nlp-service` TCP 8082.
  - `nlp-ingress-from-voice-gateway` — ingress on `app: nlp-service` ← `app: voice-gateway` TCP 8082
    (namespace default-denies **both** Ingress and Egress, so both directions are required).
- Verified: in-pod `curl http://nlp-service:8082/actuator/health` from voice-gateway = **200**
  (was "Connection refused"). The network block is gone.
- **NOT fixed end-to-end:** `/api/v1/voice/sessions/{id}/utterance "сделай тише"` still returns
  `UNKNOWN_INTENT`. New root cause surfaced underneath: nlp-service now returns **403** because
  voice-gateway's `IntentResolver` sends **no `X-Service-Token`**. nlp extends `BaseSecurityConfig`
  (`ServiceJwtFilter`, requires SVC_INTERNAL). voice-gateway already mints this token for its
  pc-control/smart-home calls (`ServiceJwtProvider.createToken(serviceName, ["SVC_INTERNAL"])`) but
  not in `IntentResolver`. **Remaining fix = code + rebuild (out of this task's safe-infra scope):**
  (1) attach `X-Service-Token` in `IntentResolver`; (2) give voice-gateway a `SERVICE_JWT_SECRET`
  env (it currently has none); (3) rebuild + redeploy `voice-gateway:movieXX`.
  **Status: voice session intent — network FIXED, auth NOT fixed → not resolving yet.**

### voice-gateway OOM on /transcribe — FIXED
- Raised live deploy memory **limit 1Gi → 2Gi**, request **512Mi → 768Mi** (`kubectl set resources`;
  matches the already-documented intent in `k8s/overlays/prod/ha-core-deployments.patch.yaml`).
- Verified: rollout clean; new pod limit `2Gi`; `/voice/diagnostics` = `STT_READY` + `TTS_READY`;
  `POST /api/v1/voice/transcribe` decoded Vosk successfully (api-gateway logged `200 (802 ms)`,
  pod logged the transcript); **restart count stayed 0** across two calls; pod mem peaked **988Mi**
  (which is exactly why a 1Gi cap OOM-killed it). **STT upload OOM — FIXED.**
- Note: the *external* call via the ingress can still return a fast `502` from the outermost nginx
  on the multipart response, while the *internal* api-gateway→voice-gateway path returns 200. That
  is a pre-existing ingress-proxy/timeout quirk, unrelated to OOM; not addressed here.

### e2e-desktop-dry-run.sh — FIXED
- Fixed `scripts/lib/e2e-common.sh`: login path `/auth/login` → `/api/v1/security/auth/login`;
  added `GATEWAY_HOST` (defaults to `api.jarvis.local` for non-localhost targets, empty for
  localhost so bare-gateway mode is unchanged; explicit `JARVIS_GATEWAY_HOST` wins); `Host:` header
  now sent on login + all `e2e_api` calls; `JARVIS_SMOKE_TOKEN` still honored.
- Verified: with only `JARVIS_GATEWAY_URL=https://10.113.0.176` + `JARVIS_SMOKE_USER/PASS`, the
  script self-obtains a token, passes the SAFE pc-control read (HTTP 200) and the orchestrator
  intent call (HTTP 200) → **E2E scenario PASSED**.

### Still requires the operator's physical machine (unchanged)
- Real mic → STT → brain → TTS → **speaker** loop (needs a mic + speakers).
- Desktop JavaFX **visual UX** (needs a display session).

### Honest scoreboard after this pass
- voice session intent: **NOT fixed** (network unblocked; auth/token layer remains — needs code+rebuild).
- STT upload OOM: **FIXED**.
- desktop dry-run: **FIXED**.
- real mic/speaker: **still requires the operator**.
- GUI visual UX: **still requires the operator**.
- No git writes. No service image rebuilds. No risky PC actions. No DB changes.

---

## 2026-06-06 (g) — Voice session intent auth FIXED (code + rebuild) + honest regression note

Implemented the auth fix the §(f) note deferred. voice-gateway rebuilt and redeployed.

### voice-gateway → nlp NetworkPolicy — confirmed FIXED (from §(f))
- `voice-gateway-egress-nlp` + `nlp-ingress-from-voice-gateway` live; in-pod curl to nlp 200.

### nlp auth (intent resolution) — FIXED
- `IntentResolver` now attaches `X-Service-Token` (SVC_INTERNAL) via the already-present
  `ServiceJwtProvider` (voice-gateway already gets `SERVICE_JWT_SECRET` via `envFrom: jarvis-secrets`
  — no deployment env change needed). Token never logged; graceful fallback retained.
- Verified: `POST /voice/sessions/{id}/utterance` resolves intents — `сделай тише`→**volume_down**,
  `сделай громче`→**volume_up**, `выключи звук`→**mute** (`resolved=true`). No more `UNKNOWN_INTENT`.
  **0 × 403** in voice-gateway logs.

### orchestrator auth (voice dispatch) — FIXED (second hop, same pattern)
- Fixing nlp surfaced a second 403: `OrchestratorVoiceClient` → `/api/v1/orchestrator/voice/dispatch`.
  Applied the identical `X-Service-Token` fix there. 403 gone; the command is now created and routed.

### voice session end-to-end — SAFELY ROUTED (by-design, not auto-executed)
- The session ends `EXPIRED/TIMEOUT` because volume_down/up/mute are classified **risk=MEDIUM** and
  require **confirmation**. With no confirmation channel in a headless REST test, the orchestrator's
  `PendingConfirmationRegistry` sweeps it (decision=TIMEOUT) and **rejects** the command — host volume
  stayed **27** (unchanged). This is the safety gate working, not a bug. Audible/confirmed execution
  needs a connected client (the spoken "Shall I, sir?" → confirm flow). The confirmation gate was NOT
  bypassed.

### Build / deploy
- Image **`voice-gateway:movie3`** (Jib → localhost:5000; deployed via `kubectl set image`).
  (movie2 = nlp-only fix; movie3 = + orchestrator hop.) imagePullPolicy IfNotPresent → new tag each.
- Verified: rollout clean; single pod; **restart count 0** across all utterance tests; memory limit
  **2Gi** preserved; diagnostics `STT_READY` + `TTS_READY` + `WEBSOCKET_READY`.

### Regression check — 2 PRE-EXISTING failures, NOT caused by this change
- `./jarvis health` = READY (22 deploys, all pods Running, LLM=200 TTS=200).
- `./scripts/jarvis-smoke-verify.sh` = **6 passed, 2 failed**: `host-model-daemon endpoint` +
  `llm chat`. Cause = the **known recurring** host-model-daemon Endpoints reset to placeholder
  `192.0.2.1` → cluster brain falls back to in-cluster `llm-server:5000` → 400. Host 14B `:18080`
  itself is healthy (200). **Independent of this task:** llm-service untouched, restarts=0 since 18:18
  (before this work); voice-gateway/netpol changes don't touch that path. The voice intent path does
  not depend on it (intent uses nlp regex/fast, not the 14B).
  Fix (separate, documented): `scripts/jarvis-host-endpoint-check.sh --fix` (re-patch endpoint to node
  IP) + ensure llm-service `LLM_SERVER_URL=http://host-model-daemon...:18080`, `HOST_DAEMON_ENABLED=false`.

### Honest scoreboard
- voice-gateway → nlp NetworkPolicy: **FIXED**.
- nlp auth: **FIXED**.
- voice session intent: **FIXED** (resolves; safely routed; held at confirmation gate by design).
- real mic/speaker: **still requires the operator**.
- external `/transcribe` 502/504 via outermost nginx ingress: **still present** (internal path 200; multipart proxy timeout).
- desktop visual UX: **still requires a display**.
- brain (cluster 14B via host-model-daemon): **pre-existing regression, not from this task** — needs the documented endpoint re-patch.
- No git writes. No DB changes. No risky PC action executed (confirmation gate held). HOST_DAEMON_ENABLED left false; 18081/18082 untouched.

---

## 2026-06-06 (h) — Cluster brain restored + robust final-check

### host-model-daemon endpoint reset — RESTORED (again)
- The recurring regression: Endpoints + EndpointSlice for `host-model-daemon` had reset to the
  placeholder `192.0.2.1` → cluster `llm-service` fell back to in-cluster `llm-server:5000` → chat 400.
- Fix applied: `./scripts/jarvis-host-endpoint-check.sh --fix` → Endpoints **and** EndpointSlice now
  `10.113.0.176` (node IP); in-cluster `llm-service` pod reaches `host-model-daemon:18080/health` = 200.
- llm-service env was also wrong (`LLM_SERVER_URL=http://llm-server...:5000`); patched to the documented
  good state `LLM_SERVER_URL=http://host-model-daemon.jarvis-prod.svc.cluster.local:18080`,
  `JARVIS_HOST_DAEMON_ENABLED=false`. llm-service rolled (new pod, restart 0). **18081/18082 untouched;
  HOST_DAEMON_ENABLED stays false.**
- Verified: `POST /api/v1/llm/chat` (correct shape `{"sessionId","messages":[...]}`) →
  **model=qwen3-14b-q4_k_m.gguf**, non-empty reply ("Жив."). Smoke = **8/8**.

### Verification flow hardened
- `scripts/jarvis-smoke-verify.sh`: the host-model-daemon guard now **fails loudly with the exact fix
  command** (`./scripts/jarvis-host-endpoint-check.sh --fix` / `./scripts/jarvis-final-check.sh --repair`).
  Still read-only — it never auto-patches.
- **NEW `scripts/jarvis-final-check.sh`** — one-shot PASS/FAIL verifier. **Read-only by default**; only
  `--repair` mutates (runs the endpoint `--fix` first). Checks: jarvis health, jarvis doctor (read-only),
  host-model-daemon endpoint, llm chat (14B), voice diagnostics, voice session intent
  (`сделай тише`→volume_down, verified via the voice-gateway log since the synchronous utterance 504s at
  the gateway by design), desktop dry-run, obsidian tests, android dry-run. **Result: 10/10 PASS.**

### After any cluster re-apply
Run one of:
```
./scripts/jarvis-host-endpoint-check.sh --fix      # endpoint only
./scripts/jarvis-final-check.sh --repair           # endpoint fix + full verification
```

### Scoreboard (current)
- host-model-daemon endpoint: **RESTORED** (node IP; reachable from cluster).
- EndpointSlice: **RESTORED** (node IP).
- llm-service env: **corrected** to host-model-daemon:18080 (was llm-server:5000).
- `/api/v1/llm/chat`: **qwen3-14b-q4_k_m.gguf**, non-empty.
- smoke-verify: **8/8**. final-check: **10/10**.
- voice session intent: **still FIXED** (volume_down/up/mute; 0×403). Volume/mute execution still held
  by the confirmation gate (needs a connected client to confirm).
- voice-gateway: `movie3`, restart 0, mem 2Gi. llm-service: restart 0.
- No git writes. No DB changes. No risky PC actions. HOST_DAEMON_ENABLED=false; 18081/18082 untouched.

---

## 2026-06-07 (i) — Demo-polish builder pass

### Confirmation endpoint bug FIXED (was the gate's missing half) — `voice-gateway:movie4`
- `POST /api/v1/voice/confirmations` was returning **500** (`NoResourceFoundException`): the
  `VoiceConfirmationController` was silently **unregistered** due to `@ConditionalOnBean(RabbitTemplate.class)`
  on a `@RestController` (an unreliable anti-pattern). Removed that annotation. Now returns **202**.
- Verified end-to-end (controlled): voice `сделай тише` → `volume_down` → risk=MEDIUM → confirmation
  pending → **APPROVED (202)** → orchestrator consumes → command published to `QUEUE_AGENT_EXECUTE`.
  Host execution of the queued command is done by the **desktop JavaFX agent** (the queue consumer);
  headless it stays queued, so the demo volume change requires the desktop app running. Volume was
  read 27 → unchanged (no executor connected) → restored 27. No 403s. No restarts.

### Desktop Control Center dashboard (new, non-breaking)
- New route `CONTROL_CENTER` + `ControlCenterView` (cinematic dashboard: 8 service status cards with
  READY/CONFIRMATION REQUIRED/NEEDS OPERATOR/DEGRADED badges, "What Works Now", "Needs Human", voice
  demo checklist, run commands, nav buttons). Shell lands here by default. Existing screens untouched.
  Desktop module compiles; **324 tests, 0 failures**.

### New demo scripts
- `scripts/jarvis-demo-check.sh` — one-command READY/NOT-READY (default read-only; `--repair`;
  `--approve-volume-demo` for the controlled volume confirmation + restore). Verified **9/9 READY**.
- `scripts/jarvis-voice-demo.sh` — full spoken loop (mic → Vosk → 14B → Piper → speaker) with embedded
  troubleshooting; verified headless via the bundled WAV (STT → reply → 484KB Piper WAV).
- `docs/START_HERE.md` — owner-facing quickstart (run-first, desktop, voice, confirmation demo, fixes).

### Status after this pass
- smoke-verify **8/8**, final-check **10/10**, demo-check **READY**. voice-gateway `movie4`, restart 0, mem 2Gi.
- Confirmation flow: **endpoint fixed + proven through approval+queue**; host execution needs the desktop agent.
- Still operator hardware: mic/speakers (live voice), display (GUI + confirmed PC exec), Android phone.
- No git writes. No DB bulk changes. Only the explicitly-authorized controlled volume test ran; volume restored to 27.

---

## 2026-06-07 (k) — Autonomous builder sprint (recovery hardening + owner-ready surface)

### Incident handled: sync-service CrashLoop → restored green
- An operator paste of the long `SPRING_AUTOCONFIGURE_EXCLUDE` truncated to `=org`, then the full value
  crashed sync-service: excluding `SecurityAutoConfiguration` removed the `HttpSecurity` bean that
  actuator's `ManagementWebSecurityAutoConfiguration` still needs → APPLICATION FAILED TO START.
- Recovered: `rollout undo` + removed the junk env → clean original config, 26/26 Running, health READY.
- `scripts/fix-sync-auth.sh` fixed: adds the 4th exclude (ManagementWebSecurityAutoConfiguration) **and**
  auto-rolls-back if the new pod doesn't go ready (self-healing, keeps the cluster green).

### Recovery hardened
- `scripts/jarvis-host-endpoint-check.sh --fix` now ALSO asserts llm-service env
  (`LLM_SERVER_URL=host-model-daemon:18080`, `HOST_DAEMON_ENABLED=false`) — idempotent, one-command
  complete recovery for the recurring brain regression. Verified.

### Owner-ready demo surface
- `scripts/jarvis-demo-check.sh` — added `--verbose` + a proactive-awareness status check (now 10 checks).
- `scripts/jarvis-voice-demo.sh` — added `--sample` (bundled clip, no mic) + saves artifacts
  (transcript.txt / response.txt / answer.wav) to `/tmp/jarvis-voice-demo`.
- `scripts/jarvis-proactive-demo.sh` — NEW: one-shot proactive demo (`--dry-run` default no audio,
  `--speak-once`, `--quiet`). Verified: observes screen + reasons.
- Control Center: added **Android Sync** status card. Desktop compiles; **324 tests, 0 failures**.
- `docs/START_HERE.md` updated; `docs/FINAL_DELIVERY_REPORT.md` written.

### Final verification (all green)
- final-check **10/10** (and `--repair` 10/10), smoke **8/8**, demo-check **READY 10/10**
  (incl. controlled volume demo: APPROVED 202, volume 27→restored 27), voice-demo `--sample`
  (485KB WAV), android dry-run OK, obsidian PASS, desktop **324/0/0**, 26/26 pods, HOST_DAEMON_ENABLED=false.
- **Status: GREEN backend / YELLOW only for mic/speakers/display/phone (hardware-gated).**
- Decisions made autonomously; no git writes; no secrets; volume restored; cluster kept green throughout.
