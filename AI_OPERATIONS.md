# AI Operations

## Canonical Bootstrap

```bash
./scripts/setup-ai-local.sh
```

What it prepares:

- `~/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf`
- `~/.jarvis/models/embeddings/intfloat-multilingual-e5-small`
- Python venv for `llm-server`
- Python venv for `embedding-service`
- local PostgreSQL image `pgvector/pgvector:pg16`
- refreshed `~/.jarvis/run/local-runtime/local.env`
- GPU readiness marker path `~/.jarvis/run/local-runtime/ai-gpu-status.json`
- canonical CPU default baseline `N_GPU_LAYERS=0`
- GPU-capable `llama-cpp-python==0.3.19` wheel in the managed `llm-server` venv

## Canonical Runtime

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

Canonical local ports:

- `llm-server`: `15000`
- `embedding-service`: `15001`
- `llm-service`: `8091`
- `memory-service`: `8093`

## Truth Endpoints

Direct service checks:

```bash
curl http://127.0.0.1:15000/health
curl http://127.0.0.1:15001/health
curl http://127.0.0.1:8091/api/v1/llm/health
```

Authenticated AI runtime truth:

```bash
curl -H "Authorization: Bearer <service-or-user-token>" \
  http://127.0.0.1:8091/api/v1/llm/runtime
```

Key runtime truth fields to check:

- `llm.configuredDevicePath`
- `llm.effectiveDevicePath`
- `llm.configuredGpuLayers`
- `llm.effectiveGpuLayers`
- `gpu.readinessStatus`
- `gpu.canonicalCpuBaseline`

## Canonical Smoke

```bash
./scripts/ai-local-smoke.sh
```

The smoke is expected to prove:

1. canonical model artifacts exist
2. runtime comes up
3. `/api/v1/llm/runtime` reports `fullLocalAiReadiness=true`
4. direct embedding works
5. memory ingest works
6. semantic memory search works
7. `llm-service` uses the configured model
8. long-term recall works across LLM sessions
9. orchestrator consumer path reaches the same AI stack
10. `runtime-status.sh` remains `ready` after smoke because Python AI workers stay detached

## GPU Smoke

```bash
JARVIS_AI_SKIP_SETUP=true JARVIS_SKIP_BUILD=true ./scripts/ai-gpu-smoke.sh
```

The GPU smoke is expected to prove:

1. `llm-server` starts with `effective_device=cuda`
2. `effective_n_gpu_layers` is non-zero
3. direct `llm-server` chat works on GPU
4. `llm-service` runtime truth reports GPU as the current mode during the smoke
5. `llm-service` chat works during GPU mode
6. the runtime stays healthy after the GPU requests
7. `~/.jarvis/run/local-runtime/ai-gpu-status.json` is written with `status=verified`
8. the script restores the canonical CPU baseline before exiting

## Operator Quick Checks

```bash
./scripts/runtime-status.sh
./scripts/check-local-env.sh
```

Logs:

- `~/.jarvis/logs/local-runtime/llm-server.log`
- `~/.jarvis/logs/local-runtime/llm-service.log`
- `~/.jarvis/logs/local-runtime/embedding-service.log`
- `~/.jarvis/logs/local-runtime/memory-service.log`
- `~/.jarvis/logs/local-runtime/llm-server-python-install.log`
- `~/.jarvis/logs/local-runtime/embedding-service-python-install.log`
