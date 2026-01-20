# Todo Service API

## Domain Model
Fields:
- id
- userId
- title
- description
- dueDate (mapped to DB column `deadline`)
- priority (LOW|MEDIUM|HIGH|URGENT)
- status (TODO|IN_PROGRESS|DONE|CANCELLED)
- tags (string[])
- source (MANUAL|AI)
- createdAt / updatedAt / completedAt
- createdBy / updatedBy (audit)

## Core REST Endpoints (planner-service)
- `GET /api/v1/planner/tasks?userId=...&status=...`
- `POST /api/v1/planner/tasks`
- `PUT /api/v1/planner/tasks/{id}`
- `PATCH /api/v1/planner/tasks/{id}/complete`

## Tool API Endpoints
Base: `/api/v1/tools/todo`
- `POST /create` (requires `X-Idempotency-Key`)
- `POST /update` (requires `X-Idempotency-Key`)
- `POST /list`
- `POST /complete` (requires `X-Idempotency-Key`)

Tool requests must include `X-User-Id` header. Payloads must not include `userId`.

## Idempotency
- Mutating tool calls must include `X-Idempotency-Key`.
- Reused key with different payload returns 409.

## Audit
- `source` indicates AI vs manual.
- `createdBy` / `updatedBy` stored on each mutation.

## Tool Schemas
See `todo-tools.json`.
