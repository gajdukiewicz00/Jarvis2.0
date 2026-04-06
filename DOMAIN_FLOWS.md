# Domain Flows

This file documents the real business flows that exist in code today.

## 1. Task And Reminder Flow

1. A client calls `planner-service` through the gateway or directly.
2. `planner-service` stores tasks and reminders as planner-owned state.
3. Daily planning reads planner-owned tasks for the target date or horizon.
4. The resulting plan is persisted as a planner snapshot, not as profile or life-tracker data.

Important boundary:
- Goals and habits do not originate in planner.
- Planner only consumes them from `user-profile`.

## 2. Goals/Profile To Planner Flow

1. A client writes goals, habits, priorities, or preferences into `user-profile`.
2. `user-profile` exposes a canonical `planning-context` read model.
3. `planner-service` fetches that planning context.
4. Daily planning and recommendation generation use that context as input to rule-based planning.

Important truth:
- This is personalization, not AI reasoning.
- Planner is profile-aware, but still rule-based.

## 3. Life-Tracker To Analytics Flow

1. Raw events are written into `life-tracker`.
2. `analytics-service` fetches raw finance/time/calendar data through real life-tracker APIs.
3. Analytics computes summaries, trends, and simple heuristics.
4. The result is returned as a derived view.

Important boundary:
- `analytics-service` is not the owner of raw finance/time/calendar records.
- If raw records change, `life-tracker` remains authoritative.

## 4. Planner To Notifications And Actions

1. `planner-service` generates recommendations or auto-actions from planner state plus analytics aggregates.
2. If an explicit execution is needed, planner routes to executor services such as `pc-control` or to notification channels.
3. Executor services return execution status only.

Important boundary:
- Planner may request an action.
- Planner does not become the owner of device or workstation state because of that call.

## 5. Smart-Home Execution Flow

1. A caller sends an action request to `smart-home-service`.
2. The service requires delegated user context.
3. The service validates the device/action contract and routes to the configured transport.
4. The response reports action result and current runtime state.

Important boundary:
- This is an execution service.
- Current device state is runtime-local and not a durable home-inventory source of truth.

## 6. PC Control Execution Flow

1. A caller sends an action request to `pc-control`.
2. `pc-control` validates the action type and parameters.
3. The service executes a primitive action or an in-memory scenario.
4. The response reports success, partial success, rejection, or failure.

Important boundary:
- `pc-control` is an executor, not a planner or profile service.
- Stub mode is an explicit runtime mode, not a hidden business feature.
