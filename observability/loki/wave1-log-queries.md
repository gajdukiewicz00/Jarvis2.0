# Wave-1 Loki log queries

Ad-hoc LogQL snippets for the wave-1 subsystems (agent-service, media-service,
sync-service, analytics-service, memory-service, planner-service,
security-service) plus the recurring k3s host-model-daemon endpoint
regression. These back the panels in
[`observability/dashboards/wave1-logs.json`](../dashboards/wave1-logs.json) —
paste them straight into Grafana Explore (Loki datasource) or the Grafana
dashboard panel editor.

## Labels in use

Per the current Alloy pipeline
([`k8s/base/observability/alloy.yaml`](../../k8s/base/observability/alloy.yaml)):

| Label | Value |
|---|---|
| `namespace` | `jarvis-prod` |
| `job` | `jarvis-prod/pods` (pod logs) or `jarvis-prod/events` (k8s events) |
| `app` | the pod's `app` / `app.kubernetes.io/name` label, e.g. `media-service` |
| `pod`, `container`, `node` | standard k8s metadata |

> **Known drift**: the existing `config/grafana-dashboards/jarvis-logs.json`
> dashboard queries `namespace="jarvis"` / `job="jarvis/pods"`, which does not
> match the labels Alloy actually attaches (`jarvis-prod` / `jarvis-prod/pods`).
> That dashboard predates this change and is out of scope here (not touched),
> but if you're debugging "no data" on it, this mismatch is why. All new
> queries below use the labels Alloy really sets.

## 1. Media job failures

```logql
{namespace="jarvis-prod", app="media-service"} |~ `(?i)(media job .* failed|skipping unreadable media job)`
```

Grounded in real log lines: `MediaJobService` logs `"Media job {} failed: {}"`
and `FileBackedMediaJobStore` logs `"Skipping unreadable media job file ..."`.

## 2. Agent task stuck / failing

```logql
{namespace="jarvis-prod", app="agent-service"} |~ `(?i)(agent task .* failed|panic_block|unexpected swarm error)`
```

Grounded in real log lines: `AgentTaskService` logs `"Agent task {} failed: {}"`,
`AgentAudit` logs `"AGENT_AUDIT decision=PANIC_BLOCK ..."`,
`SwarmExceptionHandler` logs `"Unexpected swarm error"`.

**Caveat**: there is no explicit "task stuck" log line today — agent-service
has no watchdog/timeout scheduler that detects an in-flight task exceeding a
duration budget. This query is a best-effort investigation aid; the primary
"stuck" signal is the Prometheus alert `AgentSwarmTaskBacklogGrowing` (see
[`observability/prometheus/wave1-alerts.yml`](../prometheus/wave1-alerts.yml)),
which works with the metrics that already exist.

## 3. Android sync failing

```logql
{namespace="jarvis-prod", app="sync-service"} |~ `(?i)(dispatch failed|life-tracker dispatch failed|orchestrator dispatch failed)`
```

Grounded in real log lines from `HttpDispatchClient`: `"life-tracker dispatch
failed: {}"`, `"life-tracker health dispatch failed: {}"`, `"orchestrator
dispatch failed: {}"`.

## 4. LLM unavailable

```logql
{namespace="jarvis-prod", app="llm-service"} |~ `(?i)(connection error|timeout|daemon.unreachable|host daemon health check failed)`
```

Grounded in real log lines from `LlmClient`: `"LLM chat <- CONNECTION ERROR in
{}ms ..."`, `"LLM chat <- TIMEOUT in {}ms ..."`, `"⚠ LLM host daemon health
check failed: {}"`; and `IntentClassifier`: `"router unreachable: {}"` /
`"daemon-unreachable: {}"`.

## 5. Memory DB errors

```logql
{namespace="jarvis-prod", app="memory-service"} |~ `(?i)(sqlexception|dataaccessexception|deadlock|constraint violation|flyway)`
```

**PROPOSED pattern** — no confirmed existing log line for DB errors in
memory-service today (no explicit `log.error(...)` around JDBC/JPA calls was
found). This matches the standard Spring/JDBC exception class names
(`SQLException`, `DataAccessException`) that will appear in a stack trace on a
real DB error, plus `Flyway` for migration failures. Pairs with the
`MemoryDbConnectionPressure` Prometheus alert (real HikariCP metric) as an
early-warning companion.

## 6. Token abuse

```logql
{namespace="jarvis-prod", app=~"security-service|api-gateway"} |~ `(?i)(refresh token reuse detected|failed login attempt|login attempt for disabled user|rate limit exceeded)`
```

Grounded in real log lines: `AuthService` logs `"Refresh token reuse detected
for user {}"`, `"Failed login attempt for user: {}"`, `"Login attempt for
disabled user: {}"`; `RateLimitInterceptor` (api-gateway) logs `"Rate limit
exceeded for IP: {}"`.

## 7. k3s endpoint mismatch (host-model-daemon)

```logql
{namespace="jarvis-prod", app="llm-service"} |~ `(?i)(192\.0\.2\.1|connection error|connection refused)`
```

```logql
{job="jarvis-prod/events"} |~ `(?i)endpoint`
```

This is the documented recurring regression where the selectorless
`host-model-daemon` Endpoints/EndpointSlice resets to the RFC-5737 placeholder
`192.0.2.1` (see `docs/COMPONENT_STATUS.md`, `docs/DEPLOYMENT_CANONICAL.md`).
When it happens, llm-service falls back to the in-cluster `llm-server:5000`
path and chat requests degrade/400. Loki alone can't directly read the
Endpoints object — the two queries above are proxies (connection errors
against the resolved placeholder IP, and any k8s event mentioning
"endpoint"). The authoritative check/fix is
`./scripts/jarvis-host-endpoint-check.sh` (`--fix` to re-patch to the node
IP), also wired into the `LlmBrainLikelyUnreachable` Prometheus alert
annotation.
