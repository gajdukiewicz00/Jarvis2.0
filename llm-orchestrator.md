# LLM Orchestrator Service

## Purpose
Single AI entry point that converts intent + context into deterministic tool calls.
It does not execute tools itself and never returns direct answers.

## Endpoint
`POST /api/v1/llm/orchestrate`

### Request
```json
{
  "sessionId": "string",
  "userId": "string",
  "intent": "string",
  "context": { "memory": "...", "todos": [], "calendar": [], "finance": {} },
  "includeMemory": true,
  "locale": "ru",
  "maxToolCalls": 4
}
```

### Response
```json
{
  "explanation": "Почему нужны эти действия",
  "toolCalls": [
    {
      "name": "create_todo",
      "arguments": {
        "title": "Купить батарейки",
        "dueDate": "2025-02-18T19:00:00"
      },
      "requiresConfirmation": false,
      "idempotencyKey": "..."
    }
  ],
  "warnings": null,
  "rawModelOutput": "{...}"
}
```

## System Prompt
- Stored in `apps/llm-service/src/main/resources/prompts/llm-orchestrator-system.txt`.
- Enforces JSON-only output.
- Prohibits hallucination and direct actions.
- Injects tool schemas via `{{TOOLS_JSON}}`.

## Tool Registry
- Source: `apps/llm-service/src/main/resources/tools/registry.json`.
- Domain-specific tool schemas also exported to:
  - `todo-tools.json`
  - `calendar-tools.json`
  - `finance-tools.json`
  - `memory-tools.json`

Tool calls never include `userId` in arguments. Tool API requires `X-User-Id` header.

## Idempotency
- Orchestrator assigns deterministic idempotency keys per tool call.
- Tool API requires `X-Idempotency-Key` for mutating actions.

## Error Handling
- If output is invalid JSON: tool_calls empty, warnings populated.
- If insufficient context: tool_calls empty + clarification question in explanation.
- Timeouts handled by llm-service retries (no retry on timeouts).

## Observability
- Uses X-Correlation-ID propagation.
- Logs tool planning decisions and parse warnings.
