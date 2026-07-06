# Jarvis 2.0 — Observability: Wave-1 Subsystems

Grafana dashboards, Loki log queries, and Prometheus alert rules for the
roadmap wave-1 subsystems: **agent-service** (swarm tasks), **media-service**
(media jobs), **sync-service** (Android sync + bank drafts),
**analytics-service** (analytics jobs), **memory-service** (memory
cleanup/expiry), **planner-service** (reschedules), and **security-service**
(token revocations).

This is config/dashboards only — no metric-emitting Java code was added here.
Where a subsystem doesn't emit a metric yet, the panel/alert is still added
with the **expected** metric name and a note, so the dashboard "lights up"
the moment the instrumentation ships (see [Follow-up instrumentation](#follow-up-instrumentation-needed)).

## Layout

```
observability/
├── README.md                              (this file)
├── dashboards/                            8 Grafana dashboard JSON files
│   ├── wave1-agent-swarm.json
│   ├── wave1-media-jobs.json
│   ├── wave1-sync-android-bank.json
│   ├── wave1-analytics-jobs.json
│   ├── wave1-memory-cleanup.json
│   ├── wave1-planner-reschedules.json
│   ├── wave1-security-token-revocations.json
│   └── wave1-logs.json                    (Loki panels for §Log queries below)
├── loki/
│   └── wave1-log-queries.md               LogQL snippets, one per investigation scenario
└── prometheus/
    └── wave1-alerts.yml                   documentation/review copy of the new alert rules
                                            (the rules that actually run are mirrored into
                                            k8s/base/observability/prometheus.yaml and
                                            infra/k8s/base/observability/prometheus.yaml)
```

## Dashboards

Each per-service dashboard follows the same shape as the existing
`config/grafana-dashboards/jarvis-overview.json`: a `${DS_PROMETHEUS}`
datasource template variable, a top markdown panel stating which metrics are
**REAL** (already emitted) vs **PROPOSED** (not yet emitted), subsystem panels
using the metric names below, and a standard row of HTTP rate / HTTP 5xx rate
/ JVM heap / JVM threads panels (every Spring Boot Actuator service exposes
`http_server_requests_seconds_*` and `jvm_*` — no new code needed for those).

| Dashboard (uid) | Subsystem | Status |
|---|---|---|
| `jarvis-wave1-agent-swarm` | agent-service swarm tasks | **REAL** — `swarm_tasks_total{state}` / `swarm_task_duration_seconds` already implemented (`SwarmMetrics.java`) |
| `jarvis-wave1-media-jobs` | media-service jobs | PROPOSED — `media_jobs_total{type,status}`, `media_job_duration_seconds` |
| `jarvis-wave1-sync-android-bank` | sync-service Android sync + bank drafts | PROPOSED — `sync_events_total{direction,status}`, `sync_bank_drafts_total{confidence,stored}` |
| `jarvis-wave1-analytics-jobs` | analytics-service jobs | PROPOSED — `analytics_jobs_total{type,status}`, `analytics_job_duration_seconds` |
| `jarvis-wave1-memory-cleanup` | memory-service TTL cleanup | PROPOSED metrics + **REAL** HikariCP panel as a stand-in DB-health signal |
| `jarvis-wave1-planner-reschedules` | planner-service reschedules | PROPOSED — `planner_reschedules_total{trigger}`, `planner_reschedule_deferred_tasks_total` |
| `jarvis-wave1-security-token-revocations` | security-service revocations | PROPOSED — `security_token_revocations_total{scope,reason}` |
| `jarvis-wave1-logs` | cross-cutting log investigation | Loki panels, see [loki/wave1-log-queries.md](loki/wave1-log-queries.md) |

### How the existing two dashboards are provisioned (for context)

`config/grafana-dashboards/jarvis-overview.json` and `jarvis-logs.json` are
pushed into Grafana by `scripts/provision-grafana-dashboards.sh`, which calls
the Grafana HTTP API (`POST /api/dashboards/db`) for two **hardcoded** file
names into a folder with `folderUid: jarvis-observability`. It is not a
generic "import everything in this directory" loop.

### How to provision the wave-1 dashboards today

`scripts/` and `config/` were out of scope for this change (this task's
write access was limited to `observability/` and k8s dashboard/alert config),
so the wave-1 JSON lives in `observability/dashboards/` instead of
`config/grafana-dashboards/`, and nothing imports it automatically yet. Two
ways to get them into Grafana:

1. **Manual import (works today, no code change)**: `curl` the same
   `POST /api/dashboards/db` endpoint the script uses, e.g.:
   ```bash
   curl -sS https://grafana.jarvis.local/api/dashboards/db \
     -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
     -H 'Content-Type: application/json' \
     --data "$(python3 -c '
   import json
   d = json.load(open("observability/dashboards/wave1-agent-swarm.json"))
   print(json.dumps({"dashboard": d, "folderUid": "jarvis-wave1", "overwrite": True}))
   ')"
   ```
   Repeat per file (use a distinct `folderUid`, e.g. `jarvis-wave1`, so this
   doesn't collide with the existing `jarvis-observability` folder used by
   the script). The Grafana UI's "Import dashboard" (paste JSON) works too.
2. **Follow-up (recommended for permanence)**: extend
   `scripts/provision-grafana-dashboards.sh` to loop over
   `observability/dashboards/*.json` (or copy these files into
   `config/grafana-dashboards/` and add matching `import_dashboard` calls) —
   this is a small script change outside this task's write scope.

## Loki log queries

See [`loki/wave1-log-queries.md`](loki/wave1-log-queries.md) for all seven
requested scenarios (media job failures, agent task stuck, Android sync
failing, LLM unavailable, memory DB errors, token abuse, k3s endpoint
mismatch), each grounded in a real log line found in the corresponding
service's source where one exists, and clearly marked as a best-effort/
proposed pattern where it doesn't. The same queries back the panels in
`dashboards/wave1-logs.json`.

## Prometheus alert rules

New alert rule groups: `jarvis_media`, `jarvis_agent_swarm`,
`jarvis_llm_brain`, `jarvis_memory`, `jarvis_gateway`, `jarvis_kubernetes` (9
rules total). Documented/reviewable at
[`prometheus/wave1-alerts.yml`](prometheus/wave1-alerts.yml).

**These are also mirrored into the ConfigMaps that Prometheus actually
loads** — `k8s/base/observability/prometheus.yaml` and
`infra/k8s/base/observability/prometheus.yaml` (`alerts.yml` key, appended
after the existing `jarvis_llm` group). This repo's Prometheus config points
at a single `rule_files: [/etc/prometheus/alerts.yml]` with no
rules-directory glob, so there is no way to load alert rules without editing
that embedded block — unlike the dashboards, these rules are wired for real,
not just documented. **Keep the three copies in sync if you edit any one of
them.**

| Alert | Group | Status | Notes |
|---|---|---|---|
| `MediaJobFailureRateHigh` | `jarvis_media` | PROPOSED | needs `media_jobs_total` |
| `AgentSwarmTaskBacklogGrowing` | `jarvis_agent_swarm` | **REAL** | proxy for "stuck" via backlog growth |
| `AgentSwarmCompletedTaskDurationHigh` | `jarvis_agent_swarm` | **REAL** | only sees completed-task duration, not in-flight age |
| `LlmBrainLikelyUnreachable` | `jarvis_llm_brain` | **REAL** | uses `llm_fallback_used_total`; annotation points at `scripts/jarvis-host-endpoint-check.sh --fix` |
| `LlmBrainUnreachable` | `jarvis_llm_brain` | PROPOSED | needs a `llm_brain_reachable` gauge |
| `MemoryDbConnectionPressure` | `jarvis_memory` | **REAL** | standard HikariCP binder metric |
| `MemoryDbErrorsElevated` | `jarvis_memory` | PROPOSED | needs `memory_db_errors_total` |
| `JarvisGatewayHigh5xxRate` | `jarvis_gateway` | **REAL** | gateway-scoped, `severity: critical` (vs the existing generic `JarvisHigh5xxRate`'s `warning`) |
| `JarvisPodNotReady` | `jarvis_kubernetes` | PROPOSED (infra) | needs kube-state-metrics deployed + scraped (not currently in this stack) |

## Metric names — full status table

Naming follows the existing convention in this repo (Micrometer dot-names,
e.g. `swarm.tasks` → Prometheus `swarm_tasks_total`; `llm.chat.latency` →
`llm_chat_latency_seconds_{count,sum,max}`).

| Service | Meter (dot-name) | Prometheus name | Type | Status |
|---|---|---|---|---|
| agent-service | `swarm.tasks` | `swarm_tasks_total{state}` | Counter | **REAL** (`SwarmMetrics.java`) |
| agent-service | `swarm.task.duration` | `swarm_task_duration_seconds_{count,sum,max}` | Timer | **REAL** (`SwarmMetrics.java`) |
| media-service | `media.jobs` | `media_jobs_total{type,status}` | Counter | PROPOSED |
| media-service | `media.job.duration` | `media_job_duration_seconds_{count,sum,max}` | Timer | PROPOSED |
| sync-service | `sync.events` | `sync_events_total{direction,status}` | Counter | PROPOSED |
| sync-service | `sync.bank.drafts` | `sync_bank_drafts_total{confidence,stored}` | Counter | PROPOSED — ownership unclear, see note below |
| analytics-service | `analytics.jobs` | `analytics_jobs_total{type,status}` | Counter | PROPOSED |
| analytics-service | `analytics.job.duration` | `analytics_job_duration_seconds_{count,sum,max}` | Timer | PROPOSED |
| memory-service | `memory.cleanup.runs` | `memory_cleanup_runs_total{status}` | Counter | PROPOSED |
| memory-service | `memory.cleanup.expired` | `memory_cleanup_expired_total` | Counter | PROPOSED |
| memory-service | `memory.cleanup.duration` | `memory_cleanup_duration_seconds_{count,sum,max}` | Timer | PROPOSED |
| memory-service | `memory.db.errors` | `memory_db_errors_total{operation}` | Counter | PROPOSED |
| planner-service | `planner.reschedules` | `planner_reschedules_total{trigger}` | Counter | PROPOSED |
| planner-service | `planner.reschedule.deferred.tasks` | `planner_reschedule_deferred_tasks_total` | Counter | PROPOSED |
| security-service | `security.token.revocations` | `security_token_revocations_total{scope,reason}` | Counter | PROPOSED |
| llm-service | (new) `llm.brain.reachable` | `llm_brain_reachable` | Gauge (0/1) | PROPOSED — for `LlmBrainUnreachable` alert |

**Bank drafts ownership note**: `BankNotificationParser` /
`BankNotificationController` (the code that actually parses bank push
notifications into transaction drafts) live in **`life-tracker`**, not
`sync-service`. The task described this as a sync-service concern; confirm
the real owning service before wiring `sync_bank_drafts_total` so the panel
points at the right pod.

## Follow-up instrumentation needed

To make the PROPOSED metrics above real (separate task — no Java was
touched here):

1. `media-service`: emit `media.jobs` (Counter, tags `type`, `status`) and
   `media.job.duration` (Timer) from `MediaJobService` state transitions,
   mirroring `SwarmMetrics.java`.
2. `sync-service` (or `life-tracker`, see ownership note): emit
   `sync.events` and `sync.bank.drafts`.
3. `analytics-service`: emit `analytics.jobs` and `analytics.job.duration`.
4. `memory-service`: emit `memory.cleanup.runs`, `memory.cleanup.expired`,
   `memory.cleanup.duration` from `MemoryExpiryCleanupService`; emit
   `memory.db.errors` around JDBC/JPA exception paths.
5. `planner-service`: emit `planner.reschedules` and
   `planner.reschedule.deferred.tasks` from `RescheduleService.rescheduleWhenTired(...)`.
6. `security-service`: emit `security.token.revocations` from
   `TokenRevocationService` (and the automatic revoke paths in `AuthService`).
7. `llm-service`: emit a `llm.brain.reachable` gauge (0/1) from the existing
   host-daemon health check in `LlmClient` (`"LLM host daemon health check
   failed"` log path), to back the `LlmBrainUnreachable` alert.

## RED (Rate/Errors/Duration) dashboards and alert (added after wave-1 metrics shipped)

Once the wave-1 PROPOSED metrics above actually shipped in code (`MediaJobMetrics`,
`SyncMetrics`, `MemoryMetrics`, `AnalyticsMetrics`, `PlannerMetrics`,
`SecurityMetrics` — all REAL now), a second pass added one **RED-framed**
Grafana dashboard per service plus the missing "restart storm" alert:

| Dashboard (uid) | Notes |
|---|---|
| `jarvis-red-media-service` | `dashboards/red-media-service.json` |
| `jarvis-red-sync-service` | `dashboards/red-sync-service.json` — flags a real scrape gap, see below |
| `jarvis-red-memory-service` | `dashboards/red-memory-service.json` |
| `jarvis-red-analytics-service` | `dashboards/red-analytics-service.json` |
| `jarvis-red-planner-service` | `dashboards/red-planner-service.json` — no domain Timer/error counter; Errors row uses deferred-tasks as a backlog proxy |
| `jarvis-red-security-service` | `dashboards/red-security-service.json` — `security_login_failures_total` is the real domain Errors signal |

Each dashboard has a Rate row (HTTP request rate + the service's domain event
counter), an Errors row (HTTP 5xx rate + the service's domain failure/error
tag where one exists), and a Duration row (HTTP latency avg + the service's
domain Timer where one exists — media-service, memory-service, and
analytics-service have one; sync-service, planner-service, and
security-service only have Counters today, so their Duration row falls back
to HTTP latency with a text panel noting the gap).

The task also asked for alert rules covering: high 5xx rate, high p99
latency, pod restart storms, and a service being down (`up==0`). Three of
those four are **already satisfied** for these six services by the existing
generic `JarvisHigh5xxRate`, `JarvisHighLatencyP99`, and `JarvisServiceDown`
rules (they aggregate `by (app)` / `by (app, le)` across every
`job="jarvis-actuator"` target, not one service, so duplicating them
per-service would just be copy-paste drift). The one genuine gap — repeated
restarts ("storms"), as opposed to the existing single-recent-restart check —
is filled by a new `JarvisPodRestartStorm` rule in the `jarvis_availability`
group. Documented/reviewable at
[`prometheus/red-service-alerts.yml`](prometheus/red-service-alerts.yml);
mirrored into the live ConfigMap at
`infra/k8s/base/observability/prometheus.yaml` only (the canonical,
non-frozen tree — see `docs/DEPLOYMENT_CANONICAL.md`). `k8s/base/observability/prometheus.yaml`
is intentionally left untouched: it's frozen by convention and enforced by
`scripts/guards/reject-legacy-k8s-edits.sh`.

**Known gap found while building the sync-service dashboard**:
`apps/sync-service/src/main/resources/application.yml` does not include
`prometheus` in `management.endpoints.web.exposure.include` (only
`health,info,metrics`) and does not set `management.metrics.tags.application`,
unlike the other five services here. Until that's fixed (out of this task's
write scope — it's an `apps/sync-service` config change, not observability),
Prometheus's scrape of sync-service's `/actuator/prometheus` 404s,
`up{app="sync-service"}` reads 0 permanently, and `JarvisServiceDown` fires as
a standing false positive for sync-service specifically.

## Known gaps found during this work (not fixed here — out of write-scope)

These were discovered while grounding the dashboards/alerts in the real
scrape/network config, and block the **agent-swarm**, **media-jobs**, and
**android-sync** dashboards from showing any data even once/if their metrics
are instrumented. Fixing them touches service deployment manifests and
NetworkPolicy outside the observability directory, so they're flagged here
rather than changed:

1. **No Prometheus scrape annotations at all** on `agent-service`,
   `media-service`, and `sync-service` (`k8s/base/{agent-service,media-service,
   sync-service}/deployment.yaml` have no `prometheus.io/scrape` /
   `prometheus.io/port` pod annotations, unlike `memory-service`,
   `analytics-service`, `planner-service`, `security-service`, which do). This
   means Prometheus's `kubernetes_sd_configs` + `keep`-on-scrape-annotation
   relabel rule never selects these three services as scrape targets today —
   `swarm_tasks_total` is real and implemented in code, but Prometheus isn't
   collecting it.
2. Even after (1) is fixed, `k8s/base/observability/networkpolicy.yaml`'s
   `prometheus-egress` policy only allows egress to ports 8080–8089, 8091–8093,
   8443 — it's missing **8090** (agent-service) and **8095** (media-service and
   sync-service, which both use 8095).
3. Same file's `jarvis-services-ingress-from-prometheus` policy's app
   allowlist doesn't include `agent-service`, `media-service`, or
   `sync-service` either (it has `analytics-service`, `security-service`,
   `planner-service`, `memory-service`, and others, but not these three).

Net effect: today, `AgentSwarmTaskBacklogGrowing` /
`AgentSwarmCompletedTaskDurationHigh` and the `wave1-agent-swarm` dashboard
will show "no data" despite `swarm_tasks_total` existing in code, until (1)–(3)
are fixed. `infra/k8s/base/observability/` has the identical gap (its
`networkpolicy.yaml` is byte-identical to the `k8s/` copy).
