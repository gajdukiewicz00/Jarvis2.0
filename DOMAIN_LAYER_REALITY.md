# Domain Layer Reality

This document describes the runtime truth of the Jarvis domain/services layer as of 2026-03-27.
It is based on code, contracts, controllers, clients, DTOs, and tests, not on README claims.

## Definition Of Done

The domain/services layer can be called mature only if all of the following are true.

| Criterion | Status | Notes |
| --- | --- | --- |
| Each service has a clear domain role | Partial | Roles are now explainable, but some executor/runtime services still have partial contracts only. |
| No duplicate domain ownership | Partial | `user-profile` duplicate goal/habit runtime models were removed, but legacy schema leftovers still exist. |
| No fake integrations disguised as business features | Partial | Planner fake LLM endpoints and placeholder adapter paths were downgraded, but orchestrator still has `local-user` fallback outside core scope. |
| Inter-service contracts match real APIs | Partial | Planner to user-profile now uses a real planning-context contract; some executor integrations remain thin and runtime-oriented. |
| DTOs do not drift across services | Partial | Goal/habit/profile planning DTOs are now canonical in `user-profile`, but broader platform-wide executor DTO discipline is still incomplete. |
| Business rules live in the correct owner | Partial | Planner/profile/life-tracker separation is cleaner; analytics heuristics are still simplistic but live in the correct derived service. |
| Source of record vs derived vs executor boundaries are explicit | Yes | This is now documentable without hand-waving. |
| Planner does not pretend to be intelligent when it is not | Yes | Core planning is explicitly rule-based; planner LLM endpoints now return `501 NOT_IMPLEMENTED`. |
| Analytics does not pretend to own source data | Yes | Analytics remains a derived service over life-tracker data. |
| User-profile is a real personalization source | Yes | Planner now consumes `planning-context`; goals/habits/priorities are no longer decorative. |
| Life-tracker is the source of record where it should be | Yes | Finance, calendar, and time records remain owned there. |
| User-data flow can be explained honestly end-to-end | Partial | Core paths are clear; a few integration fallbacks outside core scope still weaken full end-to-end guarantees. |

Current honest assessment: the checklist is mostly satisfied for the core domain layer, but not fully satisfied for the entire surrounding integration surface.

## Domain Truth Map

| Service | Real responsibility | Owns state? | Source of truth for what? | Calls whom? | Called by whom? | Real status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `planner-service` | Owns planning artifacts and produces rule-based daily/weekly plans and recommendations from tasks plus profile/analytics inputs | Yes | Tasks, reminders, daily-plan snapshots, planner-local recommendation records | `user-profile`, `analytics-service`, `pc-control`, notification channels | `api-gateway`, `orchestrator`, direct clients | Partial but honest | Not truly intelligent; LLM behavior is optional and now explicitly not implemented in planner runtime paths |
| `life-tracker` | Records raw life events and metrics | Yes | Time records, calendar events, expenses, budgets, financial goals, recurring transactions | No core domain dependency required | `api-gateway`, `analytics-service`, other consumers | Real source-of-record service | Business facts live here; consumers should aggregate, not re-own |
| `analytics-service` | Derives summaries and heuristic insights from life-tracker data | No authoritative raw state | None for source data; only derived aggregate views | `life-tracker` | `planner-service`, `api-gateway`, clients | Real derived service | Correctly not a data owner; heuristics are simple and should stay labeled as such |
| `user-profile` | Owns personalization and long-lived user preferences for planning | Yes | Profile, preferences, goals, habits, priorities | Internal repositories and provisioning path | `planner-service`, `api-gateway`, clients | Real profile service | Duplicate runtime goal/habit models and memory-like ownership were removed from active code |
| `smart-home-service` | Executes device actions and exposes current per-user device state | Yes, transient only | Current runtime device state snapshot for the configured device catalog | Device transport adapters (`mqtt` or mock) | `api-gateway`, `orchestrator`, direct clients | Partial executor service | Device inventory is still static/in-memory; not a durable household system of record |
| `pc-control` | Executes desktop/system actions and scenarios | Minimal runtime-only state | Timer runtime state and in-memory scenario definitions only | OS/system-control adapters | `orchestrator`, `api-gateway`, planner auto-actions | Real executor service with partial runtime depth | Honest executor, not a domain owner; stub mode is explicit rather than disguised |
| `security-service` | Issues and validates identity and delegated user context | Yes | Auth principals, tokens, delegated user context | Internal security infrastructure | `api-gateway`, service-to-service flows, authenticated clients | Supporting integrity service | Important for domain integrity, but not an owner of planner/profile/life data |
| `api-gateway` | Exposes and forwards APIs | No | None | Domain services | Clients | Integration layer only | Consumer and router, not a business owner |
| `orchestrator` | Routes multi-service actions and cross-service commands | No durable domain state | None | Planner, smart-home, pc-control, llm-service, others | `api-gateway`, clients | Integration layer only | Useful consumer, but still contains some fallback behavior that should not redefine domain ownership |
