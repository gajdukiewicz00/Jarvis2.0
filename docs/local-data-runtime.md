# Local Data Runtime

This runbook covers the three local-runtime services that back Jarvis data and recall:

- `embedding-service`: local FastAPI worker that produces multilingual E5 embeddings on CPU.
- `memory-service`: stores conversation chunks in PostgreSQL with pgvector and serves semantic recall.
- `analytics-service`: derives spending, calendar, and time summaries from `life-tracker`.

## Standalone Intent

`analytics-service` remains a separate service on purpose.

- It owns derived read models and aggregation logic rather than mutating source-of-record data.
- It depends on `life-tracker`, but it is not the same bounded context.
- Keeping it separate preserves cheap restart/debug loops for analytics without touching write paths.

`embedding-service` also remains separate on purpose.

- It is Python-model-serving code with a different dependency footprint and startup profile than the Java services.
- `memory-service` can fail fast on embedding health instead of hiding model-load problems inside the JVM.

## Canonical Local Start

Use the repo runtime scripts. For the data stack you do not need a GGUF model.

```bash
ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

Status:

```bash
ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-status.sh
```

Healthy output should show `health=ready` for every running core service plus
`embedding-service` and `memory-service`.

Optional local gateway HTTPS/WSS:

```bash
JARVIS_USE_TLS=true ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

In that mode the public local entrypoints become:

- `https://127.0.0.1:8080`
- `wss://127.0.0.1:8080/ws/voice`

This secures the local gateway hop only. The direct service ports below remain
plain HTTP unless a separate internal-TLS migration is done.

Shutdown:

```bash
ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-down.sh
```

If the local stack is already running, re-running `runtime-up.sh` preserves the
existing managed PostgreSQL container instead of recreating it under live
services. Use `runtime-down.sh` first if you explicitly want a fresh local DB.

## What Starts

With `ENABLE_MEMORY=true`, the canonical runtime starts:

- PostgreSQL in `pgvector/pgvector:pg16`
- `life-tracker`
- `analytics-service`
- `embedding-service`
- `memory-service`
- the core support services those paths need (`security-service`, `user-profile`, `nlp-service`, `orchestrator`, `voice-gateway`, `pc-control`, `smart-home-service`, `api-gateway`, `planner-service`)

## Dependencies

### `embedding-service`

- Python virtualenv managed under `~/.jarvis/venvs/embedding-service`
- `intfloat/multilingual-e5-small`
- CPU PyTorch wheels from `docker/embedding-service/requirements-local.txt`
- Docker image installs explicit CPU PyTorch wheels from `docker/embedding-service/requirements.txt`
- Hugging Face cache under `HF_HOME` / `SENTENCE_TRANSFORMERS_HOME`

### `memory-service`

- PostgreSQL
- `vector` and `pgcrypto` extensions
- `embedding-service`
- dedicated Flyway history table: `flyway_schema_history_memory`

### `analytics-service`

- PostgreSQL via `life-tracker`
- `life-tracker` HTTP API at `http://127.0.0.1:8085`
- delegated service auth headers for user-scoped requests

## Verification

Embedding health:

```bash
curl http://127.0.0.1:15001/health
```

Memory smoke:

```bash
./scripts/memory-smoke.sh
```

Analytics smoke:

```bash
./scripts/analytics-smoke.sh
```

Container image validation for the backend slice without the LLM model server:

```bash
ENABLE_LLM=false ENABLE_MEMORY=true SKIP_MVN=true ./scripts/build-images.sh --no-import
```

Backend integration smoke without the LLM path:

```bash
JARVIS_RUNTIME_SMOKE_SKIP_LLM=true JARVIS_SKIP_BUILD=true ./scripts/runtime-smoke.sh
```

HTTPS/WSS variant for the public local gateway:

```bash
JARVIS_USE_TLS=true JARVIS_RUNTIME_SMOKE_SKIP_LLM=true JARVIS_SKIP_BUILD=true ./scripts/runtime-smoke.sh
```

The analytics smoke seeds a temporary user through `life-tracker`, then verifies:

- analytics overview
- expense aggregation by category
- calendar summary
- time summary

## Logs

All local runtime logs live under:

```text
~/.jarvis/logs/local-runtime/
```

Relevant files:

- `embedding-service.log`
- `memory-service.log`
- `analytics-service.log`
- `life-tracker.log`
- `python-bootstrap.log`
- `embedding-service-python-install.log`

## Common Failure Cases

### `runtime-up.sh` complains about a missing GGUF model

Your persisted local env still has `ENABLE_LLM=true`.

Run:

```bash
ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

### `embedding-service` fails during startup

Check:

- `~/.jarvis/logs/local-runtime/embedding-service.log`
- whether the Python venv finished installing
- whether Hugging Face model download succeeded

### `memory-service` fails on dependency validation

Check:

- `http://127.0.0.1:15001/health`
- PostgreSQL reachability on `127.0.0.1:5432`
- `vector` extension availability

Useful command:

```bash
docker exec jarvis-local-postgres psql -U jarvis -d jarvis -c "select extname from pg_extension;"
```

When `embedding-service` is unavailable:

- `GET /memory/health` returns HTTP `503` with `status=degraded`
- `POST /memory/search` returns HTTP `503` with `error=EMBEDDING_SERVICE_UNAVAILABLE`

This is intentional. The search API no longer hides dependency outages behind an
empty result set.

### `memory-service` fails after other services already created tables

This was fixed by:

- dedicated Flyway table `flyway_schema_history_memory`
- `spring.flyway.baseline-version=0`

If you changed migrations locally and reused an old database, reset the local runtime:

```bash
ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-down.sh
ENABLE_LLM=false ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

### `analytics-service` returns 401/403 for internal calls

It expects delegated user context for service-authenticated requests.

Required headers on internal calls:

- `X-Service-Token`
- `X-User-Id`
- optionally `X-User-Roles`

## Test Coverage Left Behind

Validated locally with:

- `mvn -q -pl apps/memory-service test`
- `mvn -q -pl apps/analytics-service test`
- `~/.jarvis/venvs/embedding-service/bin/python -m pytest docker/embedding-service/tests/test_service.py -q`
- canonical runtime start with `ENABLE_LLM=false ENABLE_MEMORY=true`
- `./scripts/memory-smoke.sh`
- `./scripts/analytics-smoke.sh`
