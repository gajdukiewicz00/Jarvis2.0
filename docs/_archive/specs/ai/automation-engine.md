# Automation Engine

## Purpose
Deterministic automation that complements AI planning without making AI the source of truth.

## Components
### 1) Rules Engine (deterministic)
- Executes explicit YAML rules.
- Inputs: domain events + schedules.
- Outputs: tool calls or notifications.

### 2) AI Suggestions Engine
- Reads tool outputs and proposes next actions.
- Never executes; only suggests.
- Uses LLM Orchestrator for plan generation.

## Rule Evaluation Flow
1. Collect signals (calendar events, budget status, todo backlog).
2. Match rules deterministically.
3. Emit tool calls with idempotency keys.
4. Log every rule firing (auditable).

## Safety
- Rules never call DB directly.
- All mutations go through Tool API.
- Finance actions are read-only.
