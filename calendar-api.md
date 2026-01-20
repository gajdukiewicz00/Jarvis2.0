# Calendar Service API

## Domain Model
Fields:
- id
- userId
- title / description
- startTime / endTime / allDay
- location
- timezone
- recurrenceRule (RFC 5545 subset)
- recurrenceUntil
- source (MANUAL|AI|AUTOMATION)
- createdAt / updatedAt

## Core REST Endpoints (life-tracker)
- `POST /api/v1/life/calendar/event`
- `PUT /api/v1/life/calendar/event/{id}`
- `GET /api/v1/life/calendar/events?userId=...&from=...&to=...`

## Tool API Endpoints
Base: `/api/v1/tools/calendar`
- `POST /create` (requires `X-Idempotency-Key`)
- `POST /move` (requires `X-Idempotency-Key`)
- `POST /list`
- `POST /free-slot`

Tool requests must include `X-User-Id` header. Payloads must not include `userId`.

## Conflict Handling
- CalendarService detects overlapping time slots.
- On conflict: 409 with `conflicts` payload.

## Recurrence
- Supports `FREQ=DAILY|WEEKLY|MONTHLY`, optional `INTERVAL`, `COUNT`, `UNTIL`.
- Recurrences expanded for conflict detection and free slot search (bounded).

## Tool Schemas
See `calendar-tools.json`.
