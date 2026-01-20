# verify-ai

## Purpose
Validates AI integration invariants:
- Required AI docs and tool schemas exist.
- System prompt injects tool registry.
- Tool registry contains required tool names.
- Tool controllers require idempotency for mutations.
- Tool controllers enforce `X-User-Id` via `toolUserId` attribute.
- Tooling idempotency cleanup is scheduled.
- LLM orchestrator code does not embed DB/HTTP access.
- No hardcoded `/usr/bin/bash` or secret-like strings in tracked files.

## Usage
```bash
scripts/verify-ai.sh
```

## Failure Modes
- Missing docs or tool schema files.
- Tool registry out of sync.
- Orchestrator importing DB/HTTP clients.
