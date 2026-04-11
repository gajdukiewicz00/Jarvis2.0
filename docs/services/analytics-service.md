# analytics-service

## 1. Name

`analytics-service`

## 2. Type

Backend analytics/read-model service.

## 3. Purpose

Provides aggregated analytics derived from `life-tracker` data without owning the source-of-record write path.

## 4. Current Reality

This service is implemented and primarily acts as an upstream consumer of `life-tracker`. It computes summaries and chart-oriented responses rather than storing its own analytical warehouse.

## 5. Entry Points

- Spring Boot app: `org.jarvis.analytics.AnalyticsApplication`
- REST base path: `/api/v1/analytics`

## 6. Configuration

Main configuration source:

- `apps/analytics-service/src/main/resources/application.yml`

Important settings include:

- server port `8087`
- `jarvis.life-tracker.url`
- Feign timeouts and circuit breaker support
- service JWT / JWT settings

## 7. API / WebSocket Surface

REST endpoints:

- `GET /api/v1/analytics/overview`
- `GET /api/v1/analytics/raw/expenses`
- `GET /api/v1/analytics/expenses/by-month`
- `GET /api/v1/analytics/expenses/by-category`
- `GET /api/v1/analytics/expenses/trend`
- `GET /api/v1/analytics/raw/time-records`
- `GET /api/v1/analytics/time/summary`
- `GET /api/v1/analytics/calendar/summary`
- `GET /api/v1/analytics/habits/sleep-average`
- `GET /api/v1/analytics/habits/weekly-overtime`

No WebSocket endpoint.

## 8. Main Internal Components

- `AnalyticsController`
- `AnalyticsService`
- `LifeTrackerClient`
- `TokenValidationFilter`

## 9. Dependencies On Other Services

- `life-tracker`

## 10. Data / Storage

No dedicated database was confirmed from the module configuration. The service reads upstream data from `life-tracker` and computes responses in memory.

## 11. Security Model

Uses the shared base security config and adds `TokenValidationFilter` after `GatewayAuthFilter`.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/analytics-service -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Strongly upstream-dependent on `life-tracker`.
- `/overview` is intentionally designed to return partial data when some upstream calls fail.
