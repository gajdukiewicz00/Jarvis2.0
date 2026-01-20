# Finance Service API (Read-Safe for AI)

## Domain Model
Transactions (expense table):
- id
- userId
- amount / currency
- category
- description
- type (EXPENSE|INCOME)
- merchant / paymentMethod
- occurredAt
- source (MANUAL|AI|AUTOMATION)
- createdAt / updatedAt

Budgets:
- category, limitAmount, period, currency

Goals:
- name, targetAmount, currentAmount, targetDate, status

Recurring:
- interval, nextRun, active

## Core REST Endpoints (life-tracker)
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

## Tool API Endpoints (read-only)
Base: `/api/v1/tools/finance`
- `POST /transactions`
- `POST /summary`
- `POST /analysis`
- `POST /budget-status`

Tool requests must include `X-User-Id` header. Payloads must not include `userId`.

## Safety Guarantees
- Tool API exposes analytics only.
- No tool can change balances or perform payments.

## Tool Schemas
See `finance-tools.json`.
