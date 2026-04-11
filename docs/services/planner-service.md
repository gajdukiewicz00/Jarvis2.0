# planner-service

## 1. Name

`planner-service`

## 2. Type

Backend planning service.

## 3. Purpose

Stores tasks and reminders, generates daily/weekly planning views, exposes planner analytics, and triggers planner-related actions and notifications.

## 4. Current Reality

Core planner functionality is implemented on PostgreSQL. LLM-related code exists, but the planner-owned LLM endpoints are explicitly not implemented and instruct callers to use `llm-service` directly.

## 5. Entry Points

- Spring Boot app: `org.jarvis.planner.PlannerServiceApplication`
- REST base paths:
  - `/api/v1/planner`
  - `/api/v1/planner/tasks`
  - `/api/v1/planner/reminders`
  - `/api/v1/planner/actions`
  - `/api/v1/planner/analytics`
  - `/api/v1/planner/llm`
  - `/api/v1/tools/todo`
  - `/internal/planner`

## 6. Configuration

Main configuration source:

- `apps/planner-service/src/main/resources/application.yml`

Important settings include:

- server port `8092`
- PostgreSQL datasource and planner schema
- service URLs for `api-gateway`, `analytics-service`, `user-profile`, `llm-service`, and `voice-gateway`
- scheduler/auto-action toggles
- `planner.llm.enabled`, default `false`

## 7. API / WebSocket Surface

Planner endpoints:

- `GET /api/v1/planner/daily`
- `GET /api/v1/planner/weekly`
- `GET /api/v1/planner/recommendations`
- `GET /api/v1/planner/health`

Task endpoints:

- `GET /api/v1/planner/tasks`
- `POST /api/v1/planner/tasks`
- `PUT /api/v1/planner/tasks/{id}`
- `DELETE /api/v1/planner/tasks/{id}`
- `PATCH /api/v1/planner/tasks/{id}/complete`

Reminder endpoints:

- `GET /api/v1/planner/reminders`
- `GET /api/v1/planner/reminders/upcoming`
- `POST /api/v1/planner/reminders`

Auto-action endpoints:

- `POST /api/v1/planner/actions/focus-mode`
- `POST /api/v1/planner/actions/music`
- `POST /api/v1/planner/actions/pomodoro`
- `POST /api/v1/planner/actions/break`

Planner analytics endpoints:

- `GET /api/v1/planner/analytics/habits`
- `GET /api/v1/planner/analytics/insights`

Tool endpoints:

- `POST /api/v1/tools/todo/create`
- `POST /api/v1/tools/todo/update`
- `POST /api/v1/tools/todo/complete`
- `POST /api/v1/tools/todo/list`

Internal endpoint:

- `POST /internal/planner/voice-notify`

Conditional LLM endpoints that currently return `501 Not Implemented` when enabled:

- `POST /api/v1/planner/llm/generate-document`
- `POST /api/v1/planner/llm/parse-task`

No WebSocket endpoint.

## 8. Main Internal Components

- `DailyPlanGenerator`
- `WeeklyPlanGenerator`
- `RecommendationEngine`
- `TaskService`
- `ReminderService`
- `ToolRequestService`
- planner notification clients for desktop and voice

## 9. Dependencies On Other Services

- `analytics-service`
- `user-profile`
- `voice-gateway`
- `api-gateway`
- `llm-service` for optional/non-core integrations

## 10. Data / Storage

Uses PostgreSQL with Flyway migrations and the `planner` schema. Persisted domains include tasks and reminders.

## 11. Security Model

Protected by the standard shared security model with authenticated user context.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/planner-service -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Partially implemented.

## 14. Known Gaps / Caveats

- Planner-owned LLM endpoints are placeholders that return `501 Not Implemented`.
- Auto-actions scheduler is disabled by default.
- Recommendation and notification quality depends on downstream service availability.
