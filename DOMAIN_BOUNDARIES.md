# Domain Boundaries

This file defines what each domain-facing service owns, what it may consume, and what it must not own.

## `planner-service`

Owns:
- Tasks
- Reminders
- Daily plan snapshots
- Planner-local rule-based recommendations

Consumes:
- `user-profile` planning context
- `analytics-service` aggregates
- Executor services for explicit actions

Must not own:
- User goals, habits, priorities, preferences
- Raw calendar, finance, or time-tracking records
- Hidden or fake LLM intelligence

## `life-tracker`

Owns:
- Time records
- Calendar events
- Expenses
- Budgets
- Financial goals
- Recurring transactions

Consumes:
- Delegated user context only

Must not own:
- Derived analytics dashboards
- Planner recommendations
- User personalization rules

## `analytics-service`

Owns:
- Derived analytics responses only

Consumes:
- `life-tracker` raw records

Must not own:
- Expenses, time entries, events, or budgets as source data
- User goals or profile data
- Executor actions

## `user-profile`

Owns:
- User profile
- Preferences
- Goals
- Habits
- Priorities
- Planning context read model

Consumes:
- Internal provisioning and preferences persistence

Must not own:
- Planner tasks or reminders
- Life-tracker raw facts
- Conversation memory or embedding-like storage

## `smart-home-service`

Owns:
- Smart-home action execution contract
- Current runtime device state snapshot

Consumes:
- Delegated user context
- Device transport adapters

Must not own:
- User profile or goals
- Planner semantics
- Cross-service orchestration logic

## `pc-control`

Owns:
- Desktop action contract
- Scenario execution
- Timer execution state at runtime

Consumes:
- OS/system control adapters

Must not own:
- User planning state
- User profile state
- Smart-home topology

## `security-service`

Owns:
- Authentication
- Service identity
- Delegated user context integrity

Consumes:
- Security infrastructure only

Must not own:
- Planner state
- Profile data
- Life-tracker facts

## Integration Layers

`api-gateway` and `orchestrator` are consumers and routers.

They must not:
- Become a source of truth for user or planner data
- Invent fallback user ownership semantics
- Reframe executor services as domain owners
