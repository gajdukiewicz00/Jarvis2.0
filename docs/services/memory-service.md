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
- `GET /api/v1/memory/cv/screen-context/recent?userId=&limit=` — recent CV
  screen-context observations (newest first; no raw image bytes)
- `GET /api/v1/memory/cv/screen-context/{id}` — one observation's metadata

No WebSocket endpoint.

## 7a. CV screen-context consumer

`memory-service` consumes the **`jarvis.cv.screen_context.created`** Kafka topic
(produced by `vision-security-service`) and persists each observation into the
`screen_context_observation` table (Flyway `V7`).

- Component: `org.jarvis.memory.cv.ScreenContextEventConsumer` →
  `ScreenContextPersistenceService` → `ScreenContextObservationRepository`.
- Config (`jarvis.memory.cv.*`): `enabled` (default true), `topic`,
  `consumer-group` (default `cv-screen-context-projector`),
  `store-raw-screenshot` (default true), `max-screenshot-bytes` (default 8 MiB),
  `embed` (default true).
- **Persisted:** userId, capturedAt, display server, active window/process,
  semantic tags, OCR text + blocks, UI/object detections, screenshot path,
  engine/language, success/error.
- **Optional raw screenshot bytes:** stored only when `store-raw-screenshot` is
  on AND the file is readable by this process AND within the size cap. In a
  clustered deployment the consumer pod cannot read the producer host's file
  (ADR-0011 keeps raw frames host-local), so only the path is kept there.
- **Optional embedding:** the OCR text is embedded via the local
  embedding-service into the `embedding vector(384)` column for future semantic
  recall. A missing/failing embedding-service degrades gracefully — the row is
  still persisted with a null embedding.
- **Idempotent:** a derived key (userId + capturedAt + screenshotPath) dedupes
  redelivered records. No silent data loss; malformed payloads are logged and
  acked so the partition never blocks.

See the full contract in
[docs/architecture/cv-screen-context-event.md](../architecture/cv-screen-context-event.md).

## 8. Main Internal Components

- `MemoryController`
- `ToolMemoryController`
- `MemoryService`
- `MemoryDependencyStatusService`
- entities such as `ConversationMessage`, `MemoryChunk`, and `SessionSummary`

## 9. Dependencies On Other Services

- [`apps/embedding-service-py`](../../apps/embedding-service-py/) (formerly `docker/embedding-service` — that tree was retired; see [docs/services/embedding-service.md](embedding-service.md))
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
