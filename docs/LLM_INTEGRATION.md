# LLM Integration Guide

Jarvis local LLM support is now local-runtime first.

The source-of-truth docs are:

- `AI_LAYER_REALITY.md`
- `AI_ARCHITECTURE.md`
- `AI_OPERATIONS.md`
- `AI_GAPS.md`

The canonical local path is:

1. `scripts/runtime-up.sh`
2. managed `llm-server` on `http://127.0.0.1:15000`
3. `llm-service` on `http://127.0.0.1:8091`
4. canonical local memory stack (`embedding-service` + `memory-service`)

For the operational runbook, model recommendations, verification commands, and
troubleshooting, use [`RUNBOOK_LLM.md`](../RUNBOOK_LLM.md).

## Local Architecture

```text
desktop / clients
        |
        v
api-gateway (:8080)
        |
        v
llm-service (:8091)
        |
        v
llm-server (:15000, FastAPI + llama.cpp)
        |
        v
single local GGUF model
```

When memory is enabled, `memory-service` uses `embedding-service` plus local
Postgres with pgvector. This is optional for pure LLM chat, but supported by the
same local runtime scripts.

## Model Contract

- Runtime backend: `llama.cpp`
- Expected model format: `GGUF`
- Recommended baseline: `Qwen/Qwen2.5-3B-Instruct-GGUF`
- Recommended quantization: `Q4_K_M`
- Runtime package spec: `llama-cpp-python==0.3.19`
- Canonical local default baseline: `N_GPU_LAYERS=0`
- Verified GPU smoke profile on this machine: `N_GPU_LAYERS=-1`
- Canonical model directory: `~/.jarvis/models/llm/`
- Canonical override: `JARVIS_LLM_MODEL_PATH` in `~/.jarvis/run/local-runtime/local.env`

The managed local runtime intentionally fails fast if no model is present or if
multiple GGUF files exist without an explicit `JARVIS_LLM_MODEL_PATH`.

## APIs

`llm-service` continues to expose the stable Jarvis contract:

- `POST /api/v1/llm/chat`
- `GET /api/v1/llm/health`
- `DELETE /api/v1/llm/session/{sessionId}`

`llm-service` talks to the local inference backend over:

- `POST /api/v1/llm/chat`
- `GET /health`

on the Python `llm-server`.

## Local Commands

```bash
./scripts/setup-local.sh
./scripts/setup-ai-local.sh
./scripts/check-local-env.sh
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
./scripts/runtime-status.sh
./scripts/ai-local-smoke.sh
./scripts/ai-gpu-smoke.sh
```

## Kubernetes

Kubernetes remains available for heavier deployments, but it is no longer the
primary path for local LLM usage. For single-machine development, use the local
runtime flow above.
