# memory-service

## 1. Name

`memory-service`

## 2. Type

Optional backend memory service.

## 3. Purpose

Stores conversation memory chunks, searches semantic context, and maintains session summaries for other AI-capable services.

## 4. Current Reality

This service is implemented and backed by PostgreSQL plus pgvector, but it is optional in both local and Kubernetes runtime paths. It depends on an external embedding worker.

## 5. Entry Points

- Spring Boot app: `org.jarvis.memory.MemoryServiceApplication`
- REST base path: `/memory`
- tool REST base path: `/api/v1/tools/memory`

## 6. Configuration

Main configuration source:

- `apps/memory-service/src/main/resources/application.yml`

Important settings include:

- server port `8093`
- PostgreSQL datasource
- `memory.embedding.service-url`
- embedding dimension `384`
- chunking and search limits

## 7. API / WebSocket Surface

REST endpoints:

- `POST /memory/ingest`
- `POST /memory/ingest/async`
- `POST /memory/search`
- `POST /memory/summarize-session`
- `GET /memory/health`
- `POST /api/v1/tools/memory/search`

No WebSocket endpoint.

## 8. Main Internal Components

- `MemoryController`
- `ToolMemoryController`
- `MemoryService`
- `MemoryDependencyStatusService`
- entities such as `ConversationMessage`, `MemoryChunk`, and `SessionSummary`

## 9. Dependencies On Other Services

- `docker/embedding-service`
- PostgreSQL with pgvector support

## 10. Data / Storage

Uses PostgreSQL with Flyway migrations and vector/search-oriented persistence for:

- conversation messages
- memory chunks
- session summaries

## 11. Security Model

- `/memory/health` is public
- the main API requires authenticated user context
- tool search uses `toolUserId`

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/memory-service -am test
```

Start optional local memory runtime:

```bash
ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

## 13. Implementation Status

Implemented, optional.

## 14. Known Gaps / Caveats

- Requires both PostgreSQL/pgvector and `embedding-service`.
- Not part of the default core runtime baseline.
