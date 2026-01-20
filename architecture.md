# Jarvis2.0 AI Architecture (Prod-Only)

## Core Principles
- AI is not a source of truth.
- AI has no direct DB, Kafka, or HTTP access.
- All actions go through Tool API.
- Domain services are deterministic, auditable, and reproducible.

## Components
### 1) LLM Orchestrator (llm-service)
- Entry point for AI planning.
- Input: user intent + context (memory + current data).
- Output: tool_calls + explanation only (no direct answers).
- System prompt enforces constitution and JSON-only output.

### 2) Tool API (deterministic execution layer)
- Canonical tool contracts with JSON schema.
- Idempotent for all mutating operations (X-Idempotency-Key).
- Tool calls map to domain services without AI logic inside.

### 3) Domain Services
- Todo (planner-service)
- Calendar (life-tracker)
- Finance (life-tracker)

Each service:
- Enforces domain constraints.
- Records audit metadata (source, timestamps, user).
- Exposes Tool endpoints + classic REST endpoints.

### 4) Automation Engine
- Deterministic rules engine (YAML rules).
- AI suggestion engine (non-binding recommendations).
- Produces tool calls or recommendations without executing outside Tool API.

## High-Level Flow
1. Client → LLM Orchestrator (/api/v1/llm/orchestrate).
2. LLM Orchestrator returns tool_calls + explanation.
3. Deterministic executor calls Tool API endpoints.
4. Domain services execute and persist data.
5. Audit logs + idempotency records stored per service.

## Security & Safety
- No secrets in repo.
- HTTPS termination at ingress-nginx.
- X-Idempotency-Key required for tool mutations.
- Finance tools are read-only.
- Calendar tools require explicit confirmation.

## Deployment (Prod-Only)
- jarvis-launch.sh → k3s → ingress-nginx → HTTPS → services.
- Secrets created locally and applied via scripts.
