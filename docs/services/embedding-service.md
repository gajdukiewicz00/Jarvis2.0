# embedding-service

## 1. Name

`docker/embedding-service`

## 2. Type

Python FastAPI embedding service.

## 3. Purpose

Generates vector embeddings used by `memory-service`.

## 4. Current Reality

This is a real runtime component but, like `llm-server`, it is outside the Maven reactor and is started by runtime/Docker paths. It is optional because `memory-service` itself is optional.

## 5. Entry Points

- Python app entry: `docker/embedding-service/app/main.py`
- FastAPI app object: `app`

## 6. Configuration

Main configuration source:

- `docker/embedding-service/app/config.py`

Important settings include:

- `MODEL_NAME`, default `intfloat/multilingual-e5-small`
- `EMBEDDING_DIM`, default `384`
- `PORT`, default `5001`
- cache size/TTL
- request and text size limits

The local runtime scripts expose it on port `15001`.

## 7. API / WebSocket Surface

HTTP endpoints:

- `GET /health`
- `POST /embed`
- `POST /embed/single`
- `GET /`
- `POST /cache/clear`
- `GET /stats`

No WebSocket endpoint.

## 8. Main Internal Components

- `embedder`
- config/validation in `app/config.py`
- FastAPI handlers in `app/main.py`

## 9. Dependencies On Other Services

- consumed by `memory-service`

## 10. Data / Storage

- no database
- in-memory embedding cache

## 11. Security Model

No auth layer was confirmed in this service itself. It should be treated as an internal/private runtime component.

## 12. How To Run / Test

Supported repo runtime path:

```bash
ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

Python-side tests exist:

- `docker/embedding-service/tests/test_service.py`

## 13. Implementation Status

Implemented, optional.

## 14. Known Gaps / Caveats

- Not part of the Maven reactor.
- Its usefulness is tied to `memory-service`; there is no broader embedding API in the repo.
