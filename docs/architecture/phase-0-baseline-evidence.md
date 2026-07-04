# Phase 0 Baseline Evidence

## Capture Window

- Date: `2026-04-28`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Baseline capture finished: `2026-04-28T20:06:40+02:00`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`

## Git State Before Phase 0 Edits

The repository was already dirty before this phase started. Existing changes included version-alignment work, release-overlay work, docs updates, and Codex workspace files. Nothing was reset or overwritten.

`git status --short --branch` at capture time:

```text
## main...origin/main
 M .gitignore
 M AGENTS.md
 M README.md
 M apps/analytics-service/pom.xml
 M apps/api-gateway/pom.xml
 M apps/desktop-javafx/pom.xml
 M apps/desktop-javafx/src/main/kotlin/org/jarvis/desktop/features/settings/SettingsView.kt
 M apps/desktop-javafx/src/main/kotlin/org/jarvis/desktop/ui/tabs/SettingsTab.kt
 M apps/jarvis-common/pom.xml
 M apps/life-tracker/pom.xml
 M apps/llm-service/pom.xml
 M apps/memory-service/pom.xml
 M apps/nlp-service/pom.xml
 M apps/orchestrator/pom.xml
 M apps/pc-control/pom.xml
 M apps/planner-service/pom.xml
 M apps/security-service/pom.xml
 M apps/smart-home-service/pom.xml
 M apps/user-profile/pom.xml
 M apps/vision-security-service/pom.xml
 M apps/voice-gateway/pom.xml
 M docs/services/api-gateway.md
 M docs/services/llm-service.md
 M jarvis-launch.sh
 M k8s/README.md
 M k8s/base/kustomization.yaml
 M k8s/overlays/prod-release/.gitignore
 M pom.xml
 M scripts/ci/check-desktop-entry.sh
 M scripts/product/jarvis-build-release.sh
 M scripts/product/jarvis-diagnostics.sh
 M scripts/product/jarvis-install.sh
 M scripts/product/jarvis-launcher.sh
 M scripts/product/jarvis-promote-images.sh
 M scripts/runtime-smoke.sh
 M scripts/runtime/common.sh
 M scripts/verify-prod.sh
?? .agents/
?? .codex/
?? .mcp.json.example
?? AGENTS.local.md.example
?? apps/api-gateway/src/test/java/org/jarvis/apigateway/integration/GatewayLlmIngressIntegrationTest.java
?? docs/codex-configuration-map.md
?? docs/diagrams/
?? k8s/overlays/prod-release/exclude-llm-stack.patch.yaml
?? k8s/overlays/prod-release/kustomization.yaml
```

## Repository Reality Snapshot

- Backend services are under `apps/` and are built by the Maven reactor from `pom.xml`.
- The current desktop/native implementation is `apps/desktop-javafx`.
- Local native agent and Codex workspace behavior lives under `.agents/skills/` and `.codex/`.
- AI/LLM components are split between Java services (`apps/llm-service`, `apps/memory-service`) and Python workers (`docker/llm-server`, `docker/embedding-service`).
- Kubernetes manifests live under `k8s/base`, `k8s/overlays/prod`, `k8s/overlays/prod-release`, and the `prod-release-internal-tls-*` overlays.
- Docker assets already exist and were not removed during baseline capture.
- Local process runtime entrypoints are `scripts/runtime-up.sh`, `scripts/runtime-down.sh`, `scripts/runtime-status.sh`, and `scripts/runtime-smoke.sh`.

## Commands Executed

### Repository inspection

- `git rev-parse HEAD`
- `git status --short --branch`
- `./scripts/runtime-status.sh`

Result:

- Pass. Repo structure and runtime entrypoints were readable.
- Initial local runtime state was stopped.

### Required targeted Maven validation

1. `mvn -pl apps/api-gateway -am test`
   Result: pass
   Summary: `65` tests, `0` failures, `0` errors, `0` skipped
   Duration: `14.227 s`
   Log: `/tmp/jarvis-phase0-baseline/api-gateway-mvn-test.log`

2. `mvn -pl apps/voice-gateway -am test`
   Result: pass
   Summary: `120` tests, `0` failures, `0` errors, `0` skipped
   Duration: `4.622 s`
   Log: `/tmp/jarvis-phase0-baseline/voice-gateway-mvn-test.log`

3. `mvn -pl apps/desktop-javafx -am test`
   Result: pass
   Summary: `247` tests, `0` failures, `0` errors, `0` skipped
   Duration: `01:57 min`
   Log: `/tmp/jarvis-phase0-baseline/desktop-javafx-mvn-test.log`

4. `mvn -pl apps/vision-security-service -am test`
   Result: pass
   Summary: `27` tests, `0` failures, `0` errors, `0` skipped
   Duration: `45.274 s`
   Log: `/tmp/jarvis-phase0-baseline/vision-security-service-mvn-test.log`

5. `mvn -pl apps/pc-control -am test`
   Result: pass
   Summary: `137` tests, `0` failures, `0` errors, `0` skipped
   Duration: `4.004 s`
   Log: `/tmp/jarvis-phase0-baseline/pc-control-mvn-test.log`

### Full reactor validation

- `mvn test`

Result:

- Pass.
- Reactor summary: `17/17` modules succeeded.
- Total time: `03:44 min`
- Log: `/tmp/jarvis-phase0-baseline/root-mvn-test.log`

Notable reactor evidence:

- `api-gateway`, `voice-gateway`, `pc-control`, `vision-security-service`, and `desktop-javafx` all passed inside the full build as well.
- Optional AI services `llm-service` and `memory-service` also passed as part of the full reactor.

### Runtime smoke

- Pre-check: `./scripts/runtime-status.sh`
- Smoke command:

```bash
JARVIS_SKIP_BUILD=true \
JARVIS_RUNTIME_SMOKE_STOP_ON_EXIT=true \
./scripts/runtime-smoke.sh
```

Result:

- Pass.
- Runtime returned to stopped state afterward.
- Log: `/tmp/jarvis-phase0-baseline/runtime-smoke.log`

## Runtime Endpoints And Behaviors Verified

The smoke run exercised the existing local runtime path and verified:

- auth bootstrap via `POST /auth/register` with fallback to `POST /auth/login`
- authenticated identity lookup via `GET /api/v1/security/auth/me`
- orchestrator desktop action dispatch via `POST /api/v1/orchestrator/execute`
- `voice-gateway` readiness via `GET /actuator/health/readiness`
- voice runtime truth via `GET /api/v1/voice/runtime`
- voice diagnostics via `GET /api/v1/voice/diagnostics`
- voice synthesis via `POST /api/v1/voice/synthesize`
- voice websocket roundtrip and timeout recovery on `/ws/voice`
- planner focus and reminder flows
- smart-home registry and action execution
- orchestrator smart-home command routing
- orchestrator LLM fallback against the local LLM smoke stub

Smoke startup evidence from `/tmp/jarvis-phase0-baseline/runtime-smoke.log`:

- `security-service` healthy
- `user-profile` healthy
- `nlp-service` healthy
- `orchestrator` healthy
- `voice-gateway` healthy
- `pc-control` healthy
- `vision-security-service` healthy
- `smart-home-service` healthy
- `life-tracker` healthy
- `analytics-service` healthy
- `api-gateway` healthy
- `planner-service` healthy
- external LLM smoke stub healthy
- `llm-service` healthy
- final status: `Local runtime smoke passed.`

## Log And Evidence Locations

- Maven and smoke command logs:
  - `/tmp/jarvis-phase0-baseline/api-gateway-mvn-test.log`
  - `/tmp/jarvis-phase0-baseline/voice-gateway-mvn-test.log`
  - `/tmp/jarvis-phase0-baseline/desktop-javafx-mvn-test.log`
  - `/tmp/jarvis-phase0-baseline/vision-security-service-mvn-test.log`
  - `/tmp/jarvis-phase0-baseline/pc-control-mvn-test.log`
  - `/tmp/jarvis-phase0-baseline/root-mvn-test.log`
  - `/tmp/jarvis-phase0-baseline/runtime-smoke.log`

- Local runtime process logs:
  - `~/.jarvis/logs/local-runtime/api-gateway.log`
  - `~/.jarvis/logs/local-runtime/voice-gateway.log`
  - `~/.jarvis/logs/local-runtime/planner-service.log`
  - `~/.jarvis/logs/local-runtime/pc-control.log`
  - `~/.jarvis/logs/local-runtime/vision-security-service.log`
  - `~/.jarvis/logs/local-runtime/llm-service.log`
  - `~/.jarvis/logs/local-runtime/llm-server-stub.log`

- Runtime artifacts:
  - `~/.jarvis/run/local-runtime/llm-capture.jsonl`
  - `~/.jarvis/run/local-runtime/pc-probe.log`
  - `~/.jarvis/run/local-runtime/voice-probe.log`
  - `~/.jarvis/run/local-runtime/voice-ws-roundtrip.log`
  - `~/.jarvis/run/local-runtime/voice-ws-timeout.log`
  - `~/.jarvis/run/last-run.json`

## Known Warnings And Non-Blocking Findings

- `runtime-smoke.sh` logged `curl: (22)` on `/auth/register` because the smoke user already existed. The script handled this by falling back to `/auth/login`, and the smoke still passed.
- Local API and voice logs showed OpenTelemetry export failures to `localhost:4318` during shutdown. This did not fail the smoke and reflects missing local OTLP collector wiring, not a gateway or voice regression.
- The pre-smoke runtime status showed `jarvis-local-postgres` in `exited` state. The smoke path safely restarted it and returned it to `exited` after cleanup.

## Baseline Conclusion

Current baseline status is green.

- Required targeted module validation passed.
- Full reactor validation passed.
- Existing local runtime smoke passed without destructive cleanup.
- Voice, gateway, desktop-related probes, `pc-control`, and `vision-security-service` remained operational on their current paths.
- The repository was already carrying unrelated user changes, so Phase 0 alignment work must remain additive and careful.
