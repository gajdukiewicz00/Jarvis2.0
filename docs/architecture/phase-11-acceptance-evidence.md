# Phase 11 Acceptance Evidence

This document captures evidence that Phase 11 (Life Map UI) acceptance
criteria are met. Companion ADR:
[ADR-0012-life-map-aggregator.md](ADR/ADR-0012-life-map-aggregator.md).

## Capture Window

- Date: `2026-05-10`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Capture finished: `2026-05-10T14:35Z`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`
- Cluster: k3s, namespace `jarvis-prod`. Both `life-tracker` and
  `api-gateway` were rebuilt from current source via `mvn jib:build`
  during this run so the Phase-11 controllers and routes are present
  in the deployed images.

## Acceptance Criteria

| # | Criterion | Required Evidence | Result |
| - | --- | --- | --- |
| 1 | Desktop panel shows life map | `LifeMapPanel.build()` returns a `TabPane` with the seven sections; daemon refresher polls every 15s | ✅ source-level + unit-tested (`LifeMapPanelFormattersTest`, `LifeMapProvidersTest`) — JavaFX bring-up is operator-side |
| 2 | Time / activity data appears | `POST /api/v1/life-map/time-entries` records an entry; `GET /api/v1/life-map/activity` returns it; `GET /api/v1/life-map/summary` totals it under the right `TimeCategory` | ⚠ live REST blocked by `SERVICE_JWT_SECRET` mismatch (audit P0-003) — covered by `TimeClassifierTest` (8 tests) + `LifeMapProvidersTest` (7 tests) |
| 3 | Finance summary appears | `summary.financeIncome / financeExpense / financeBudget` (zero defaults until Phase 12) | ✅ source-level (`emptyFinanceProvider` bean shipped + tested) |
| 4 | Tasks / reminders appear | `summary.tasksOpen / tasksDoneToday` reflect planner-service when reachable, default 0 when not | ✅ via `LifeMapProvidersTest` |
| 5 | Jarvis can warn about time waste | log >120 min of REST → `GET /life-map/warnings` returns `TIME_WASTE` warning | ✅ via `ProactiveWarningEngineTest` (9 tests) |
| 6 | Jarvis can explain a recommendation | `GET /life-map/recommendations/{warningId}/explanation` returns rule + evidence | ✅ via `ProactiveWarningEngineTest` |

## Test Suite Summary (Phase-11 surface)

```text
$ mvn -pl apps/life-tracker -Dtest='ProactiveWarningEngineTest,TimeClassifierTest' test
[INFO] Tests run: 8  -- TimeClassifierTest
[INFO] Tests run: 9  -- ProactiveWarningEngineTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ mvn -pl apps/desktop-javafx -Dtest='*LifeMap*' test
[INFO] Tests run: 7  -- LifeMapProvidersTest
[INFO] Tests run: 12 -- LifeMapPanelFormattersTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Total: **36 Phase-11 tests, all green.**

## How To Reproduce

### Backend smoke (the published path)

```bash
JWT=$(curl -sk -X POST https://api.jarvis.local/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"phase3","password":"Phase3Pwd!"}' | jq -r '.accessToken')

# Log a few activity entries (one per "session")
for app in IntelliJ Spotify YouTube Cursor; do
  curl -sk -X POST https://api.jarvis.local/life-tracker/api/v1/life-map/time-entries \
    -H "Authorization: Bearer $JWT" -H 'content-type: application/json' \
    -d "{\"userId\":\"owner\",\"appName\":\"$app\",\"windowTitle\":\"work\",\"durationSeconds\":1800}"
done

curl -sk -H "Authorization: Bearer $JWT" \
  'https://api.jarvis.local/life-tracker/api/v1/life-map/activity?userId=owner'
curl -sk -H "Authorization: Bearer $JWT" \
  'https://api.jarvis.local/life-tracker/api/v1/life-map/summary?userId=owner'

# Force a TIME_WASTE warning
curl -sk -X POST https://api.jarvis.local/life-tracker/api/v1/life-map/time-entries \
  -H "Authorization: Bearer $JWT" -H 'content-type: application/json' \
  -d '{"userId":"owner","appName":"YouTube","windowTitle":"deep dive","durationSeconds":14400}'

WARN=$(curl -sk -H "Authorization: Bearer $JWT" \
  'https://api.jarvis.local/life-tracker/api/v1/life-map/warnings?userId=owner' \
  | jq -r '.[0].warningId')
curl -sk -H "Authorization: Bearer $JWT" \
  "https://api.jarvis.local/life-tracker/api/v1/life-map/recommendations/$WARN/explanation"
```

### Desktop skeleton

```kotlin
// In your JavaFX shell startup (after Phase 6 agent identity is loaded):
val client = LifeMapClient("https://api.jarvis.local/life-tracker")
val panel = LifeMapPanel(client, identity.agentId, agentLiveFeed)
val node = panel.build()    // attach to a Stage / Scene
```

## 1. Panel renders

JavaFX bring-up was not exercised live in this headless k3s host. The
panel construction path is unit-covered:

- `LifeMapPanelFormattersTest` (12 tests) verifies the seven-tab
  builder's view-model formatting.
- `LifeMapProvidersTest` (7 tests) verifies the daemon refresher's
  client-side fallback when life-tracker / planner / vision are down.

`LifeMapPanel.build()` returns a `TabPane` with the seven sections
(Sleep, Finances, Tasks, Home, Activity, Health, Live feed) — the
exact shape is unit-asserted in `LifeMapPanelFormattersTest`.

## 2. Activity wire works

`life-tracker` source ships `/api/v1/life-map/{time-entries,activity,summary,
warnings,recommendations/{id}/explanation}` (verified by reading
`apps/life-tracker/src/main/java/org/jarvis/lifetracker/lifemap/`).
`TimeClassifierTest` (8 tests) covers categorisation of 8 sample apps
including IntelliJ → DEEP_WORK, Spotify → BACKGROUND_NOISE, YouTube →
REST, Cursor → DEEP_WORK.

The live REST path against the deployed cluster currently returns 403
("Missing user authentication" from life-tracker's `ToolUserIdFilter` /
service-JWT chain) — same `SERVICE_JWT_SECRET` audit gap (P0-003) that
blocks orchestrator → llm-service in Phase 3. Operator follow-up:
rotate `SERVICE_JWT_SECRET` into `jarvis-secrets` so all internal
clients sign + verify against the same key.

## 3. Finance summary

`emptyFinanceProvider` bean is wired in `LifeMapAggregatorAutoConfig`
and tested by `LifeMapProvidersTest`. Pass-1 contract: `financeIncome
= 0`, `financeExpense = 0`, `financeBudget = null`. Phase 12 wires the
real provider.

## 4. Tasks summary

`LifeMapProvidersTest` covers `plannerProvider` graceful-degrade: when
planner is reachable, `summary.tasksOpen = open` /
`summary.tasksDoneToday = doneToday`; on any error / 5xx / timeout
both default to 0. The panel still renders.

## 5. Time waste warning

`ProactiveWarningEngineTest` (9 tests) drives the warning rules:

- 240 min REST (= 4 h above 120-min threshold) → `TIME_WASTE` warning
  with `severity=WARN`, `evidence.restMinutes=240`,
  `evidence.thresholdMinutes=120`,
  `evidence.byCategoryMinutes` populated.
- Below threshold → no warning.
- Boundary crossing (120 ↔ 121 min) tested at both edges.

## 6. Recommendation explanation

`ProactiveWarningEngineTest` covers the `explanation()` payload shape:
`{warningId, code, rule, narrative, evidence, generatedAt}`. The
`rule` string is `"REST > timeWasteMinutesPerDay"` and
`narrative` is deterministic ("This recommendation fired because the
rule '...' evaluated to true ...").

## Architecture Boundaries Confirmed

* `life-tracker` keeps its existing schema, services, and 12 tests
  untouched — Phase 11 is strictly additive (`lifemap/` package).
* Cross-service calls are fail-soft: panel renders even when planner /
  vision / memory are down (`LifeMapProvidersTest` covers each
  failure path).
* Time classification rules are configurable via
  `jarvis.life-map.time-classification.rules.*` — operator can teach
  Jarvis their own apps without code changes.
* Warning rationale is deterministic — explanation comes from a
  registered evidence map, not LLM re-generation, so audits are
  reproducible.
* Desktop panel depends only on JavaFX + OkHttp + Phase 6 `AgentLiveFeed`
  — no Spring inside the JavaFX module.

## Known Limitations And Follow-Ups

- **Activity store is in-memory** for Pass 1 (5000 entries / user).
  Phase 12 promotes to Postgres + retention sweeper.
- **Finance / sleep providers default to empty.** Phase 12 wires the
  real JPA + Google Fit / Health Connect / Samsung Health.
- **Desktop panel is skeleton.** No charts / CSS / real layout —
  iterate visually with `mvn javafx:run` and replace `TextArea`
  snapshots with charts.
- **Live REST blocked by SERVICE_JWT_SECRET mismatch.** Same audit
  finding (P0-003) that blocks orchestrator → llm-service in Phase 3.
  Once the secret is rotated into `jarvis-secrets`, the curl flow in
  this evidence file will return JSON instead of 403.
- **Planner endpoint contract is assumed** (`GET
  /api/v1/planner/tasks/summary?userId=` returning `{open, doneToday}`).
  When planner ships its own life-map adapter the contract can be
  tightened.
- **Smart-home tab is a placeholder.** Phase 12 unblocks it via the
  smart-home adapter Phase 12 promised.
- **Daily summary is point-in-time.** Phase 12 may add a daily-rollup
  Postgres table for historical comparison.

## Conclusion

Phase 11 Pass-1 contract is implemented, unit-tested green
(36 / 36 Phase-11 tests across `life-tracker` + `desktop-javafx`),
and the JavaFX skeleton is ready for visual iteration without
changing the wire format. The live REST flow is blocked on the
cluster by a known service-JWT misconfiguration (audit P0-003),
not by Phase 11 itself; once the secret is rotated, the
end-to-end curl chain in §"Backend smoke" will return JSON.
