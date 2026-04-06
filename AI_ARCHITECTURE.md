# AI Architecture

## Canonical Flow

```text
api-gateway / consumers
        |
        v
llm-service (:8091)
        | \
        |  \--> memory-service (:8093) --> embedding-service (:15001)
        |                     |
        |                     \--> PostgreSQL + pgvector (:5432)
        v
llm-server (:15000)
        |
        v
Qwen2.5-3B-Instruct GGUF (llama.cpp current mode; CPU default, GPU smoke profile verified)
```

## Canonical Contracts

| Component | Responsibility | Canonical contract | Truth boundary |
| --- | --- | --- | --- |
| `llm-server` | local GGUF inference | `GET /health`, `POST /api/v1/llm/chat` | actual model/backend/device |
| `llm-service` | Jarvis AI orchestration | `GET /api/v1/llm/runtime`, `GET /api/v1/llm/health`, `POST /api/v1/llm/chat` | configured vs effective AI stack |
| `embedding-service` | local embeddings | `GET /health`, `POST /embed`, `POST /embed/single` | actual embedding model |
| `memory-service` | semantic recall + storage | `GET /memory/health`, `POST /memory/ingest`, `POST /memory/search` | pgvector + embedding dependency state |
| `orchestrator` | consumer of AI chat path | `POST /api/v1/orchestrator/execute` | consumer path only, not AI truth |

## Canonical Models

- LLM: `Qwen/Qwen2.5-3B-Instruct-GGUF`
- Quantization: `q4_k_m`
- Embeddings: `intfloat/multilingual-e5-small`
- Runtime package spec: `llama-cpp-python==0.3.19`
- Canonical local default baseline: `N_GPU_LAYERS=0`
- Verified GPU smoke profile on this machine: `N_GPU_LAYERS=-1` -> `effective_n_gpu_layers=36`

Why this baseline:

- fits a single workstation comfortably
- rootless bootstrap is practical
- multilingual enough for Jarvis’ Russian-first behavior
- small enough for reproducible local smoke
- strong enough to validate LLM + memory integration honestly
- GPU smoke stays separate from the canonical CPU default so the workstation always has a known-good fallback

## Contract Rules

- If `memory.enabled=true`, `llm-service` must not silently pretend memory is fine.
- If `memory-service` cannot do semantic retrieval, the degraded reason must be explicit.
- `dialog` and `chat` must not return fake success bodies when the LLM path is unavailable.
- `fullLocalAiReadiness=true` only when the entire canonical local stack is actually available.
- GPU readiness must be reported separately from the current runtime mode; a CPU current mode can still carry a verified GPU smoke status.
