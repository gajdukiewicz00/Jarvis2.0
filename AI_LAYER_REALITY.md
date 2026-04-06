# AI Layer Reality

This file is the source of truth for Jarvis AI/LLM/memory maturity. It is based
on real runtime scripts, code paths, and smoke verification targets. It is not
based on README claims or repo-local placeholder layouts.

## Canonical Local AI Stack

Jarvis supports one canonical local AI stack for this workstation class:

- `llm-service`
- `llm-server`
- `memory-service`
- `embedding-service`
- local PostgreSQL in `pgvector/pgvector:pg16`
- one GGUF model under `~/.jarvis/models/llm/`
- one embedding model snapshot under `~/.jarvis/models/embeddings/`

Canonical stack id:

```text
qwen2.5-3b-instruct-q4_k_m+multilingual-e5-small+llamacpp+pgvector
```

## Canonical Artifacts

- LLM model id: `Qwen/Qwen2.5-3B-Instruct-GGUF`
- LLM model file: `qwen2.5-3b-instruct-q4_k_m.gguf`
- LLM model path: `~/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf`
- Embedding model id: `intfloat/multilingual-e5-small`
- Embedding model path: `~/.jarvis/models/embeddings/intfloat-multilingual-e5-small`
- Supported inference path: `llama.cpp` via `llama-cpp-python==0.3.19`
- Canonical local default baseline on this machine: CPU (`N_GPU_LAYERS=0`)
- Separately verified GPU smoke profile on this machine: `N_GPU_LAYERS=-1` with `effective_n_gpu_layers=36` on `NVIDIA GeForce RTX 5070`

## Bootstrap / Runtime / Smoke

- Bootstrap: `./scripts/setup-ai-local.sh`
- Runtime up: `ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh`
- Runtime truth: `GET /api/v1/llm/runtime` on `llm-service`
- Smoke: `./scripts/ai-local-smoke.sh`
- GPU smoke: `./scripts/ai-gpu-smoke.sh`

## Runtime Truth

`llm-service` is the AI truth boundary.

It exposes:

- `GET /api/v1/llm/health`
- `GET /api/v1/llm/runtime`

The runtime status is expected to show:

- configured vs effective LLM provider/model
- configured vs effective device path
- configured vs effective `n_gpu_layers`
- configured vs effective embedding provider/model
- availability and degraded reason
- separate GPU readiness status from `~/.jarvis/run/local-runtime/ai-gpu-status.json`
- canonical CPU baseline details
- pgvector/database status
- canonical local AI stack id
- `fullLocalAiReadiness=true|false`

## Verified vs Partial Boundary

Verified target:

- local bootstrap downloads real model artifacts into canonical paths
- runtime starts with the canonical stack only
- canonical local `llm-server` defaults to `N_GPU_LAYERS=0`
- `ai-local-smoke.sh` proves the canonical CPU baseline
- `ai-gpu-smoke.sh` proves the verified GPU profile (`llama-cpp-python==0.3.19`, `N_GPU_LAYERS=-1`)
- embedding uses the local embedding snapshot, not a repo id string
- memory retrieval uses pgvector-backed semantic search
- `llm-service` no longer hides memory outages behind empty context
- dialog path no longer returns fake success replies when LLM is down
- smokes prove direct AI path and orchestrator consumer path

Still intentionally optional:

- Kubernetes LLM deployment overlays
- non-canonical external LLM providers
- alternative local model families

## Known Legacy Drift That This Layer Replaces

The old drift this layer is intended to eliminate:

- repo-local `models/llm` vs `~/.jarvis/models/llm`
- manual “put a GGUF somewhere” setup
- `llm-server` on legacy local port `5000`
- `embedding-service` on legacy local port `5001`
- shallow `/api/v1/llm/health` that did not reflect memory readiness
- fake dialog fallback responses that masked missing LLM availability
