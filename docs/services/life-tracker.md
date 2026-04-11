# life-tracker

## 1. Name

`life-tracker`

## 2. Type

Backend data service.

## 3. Purpose

Stores and serves personal finance, calendar, and time-tracking data. This is the main source-of-record service for those domains.

## 4. Current Reality

The service is implemented and backed by PostgreSQL with Flyway migrations. It exposes both user-facing APIs and tool-oriented endpoints used by higher-level AI/workflow components.

## 5. Entry Points

- Spring Boot app: `org.jarvis.lifetracker.LifeTrackerApplication`
- REST base paths:
  - `/api/v1/life/finance`
  - `/api/v1/life/time`
  - `/api/v1/life/calendar`
  - `/api/v1/tools/finance`
  - `/api/v1/tools/calendar`

## 6. Configuration

Main configuration source:

- `apps/life-tracker/src/main/resources/application.yaml`

Important settings include:

- server port `8085`
- PostgreSQL datasource
- Flyway migrations `V1` through `V7`
- service JWT / JWT integration

## 7. API / WebSocket Surface

Finance endpoints:

- `POST /api/v1/life/finance/transaction`
- `GET /api/v1/life/finance/transactions`
- `GET /api/v1/life/finance/summary/month`
- `GET /api/v1/life/finance/analysis/spending`
- `GET /api/v1/life/finance/budget/status`
- `POST /api/v1/life/finance/budget`
- `GET /api/v1/life/finance/budgets`
- `POST /api/v1/life/finance/goal`
- `GET /api/v1/life/finance/goals`
- `POST /api/v1/life/finance/recurring`
- `GET /api/v1/life/finance/recurring`
- `POST /api/v1/life/finance/expenses`
- `GET /api/v1/life/finance/expenses`

Time endpoints:

- `POST /api/v1/life/time/start`
- `POST /api/v1/life/time/stop`
- `GET /api/v1/life/time/records`

Calendar endpoints:

- `POST /api/v1/life/calendar/event`
- `PUT /api/v1/life/calendar/event/{id}`
- `GET /api/v1/life/calendar/events`

Tool endpoints:

- `POST /api/v1/tools/finance/transactions`
- `POST /api/v1/tools/finance/summary`
- `POST /api/v1/tools/finance/analysis`
- `POST /api/v1/tools/finance/budget-status`
- `POST /api/v1/tools/calendar/create`
- `POST /api/v1/tools/calendar/move`
- `POST /api/v1/tools/calendar/list`
- `POST /api/v1/tools/calendar/free-slot`

No WebSocket endpoint.

## 8. Main Internal Components

- `FinanceService`
- `CalendarService`
- `DTOMapper`
- tool idempotency support in `ToolRequestService`
- repositories for expenses, budgets, goals, recurring transactions, calendar events, time records, and active time records

## 9. Dependencies On Other Services

No mandatory downstream Jarvis service dependency for core storage behavior.

## 10. Data / Storage

Stored in PostgreSQL with Flyway-managed schema/data evolution. Main persisted domains include:

- expenses and transactions
- budgets
- financial goals
- recurring transactions
- calendar events
- active and completed time records

## 11. Security Model

- authenticated user identity is required for the main REST API
- tool endpoints use `toolUserId` request attributes and idempotency keys where implemented

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/life-tracker -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- No external calendar provider integration was confirmed from code.
- Finance/time/calendar data is local application data, not a replicated external system of record.
