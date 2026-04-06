# Jarvis Local LLM Runbook

## Overview

The local LLM stack is:

- `llm-server` - Python FastAPI inference worker using the repo's `llama.cpp` backend
- `llm-service` - Java orchestration layer used by chat, dialog, and orchestrator fallback
- `memory-service` + `embedding-service` - canonical long-term memory path for local mode

The source-of-truth doc set is:

- `AI_LAYER_REALITY.md`
- `AI_ARCHITECTURE.md`
- `AI_OPERATIONS.md`
- `AI_GAPS.md`

The canonical local model root is `~/.jarvis/models/`.

## Recommended Model

- Format: `GGUF`
- Baseline: `Qwen/Qwen2.5-3B-Instruct-GGUF`
- Quantization: `q4_k_m`
- Canonical file: `qwen2.5-3b-instruct-q4_k_m.gguf`

## One-Time Prep

```bash
./scripts/setup-local.sh
./scripts/setup-ai-local.sh
./scripts/check-local-env.sh
```

## Start

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

## Status And Verification

Check runtime status:

```bash
./scripts/runtime-status.sh
```

Check inference health:

```bash
curl http://127.0.0.1:15000/health
curl http://127.0.0.1:8091/api/v1/llm/health
```

Run the canonical e2e AI smoke:

```bash
./scripts/ai-local-smoke.sh
```

Run the GPU verification smoke:

```bash
JARVIS_AI_SKIP_SETUP=true JARVIS_SKIP_BUILD=true ./scripts/ai-gpu-smoke.sh
```

## Logs

Local runtime logs live in:

```text
~/.jarvis/logs/local-runtime/
```

Most useful files:

- `llm-server.log`
- `llm-service.log`
- `embedding-service.log`
- `memory-service.log`
- `llm-server-python-install.log`
- `embedding-service-python-install.log`

## Safe Local Defaults

The local runtime now defaults to:

- `llama.cpp` backend
- `Qwen2.5 3B Instruct q4_k_m`
- `llama-cpp-python==0.3.19`
- `N_CTX=4096`
- `N_BATCH=512`
- `CHAT_WORKERS=1`
- moderate CPU thread usage
- canonical CPU baseline `N_GPU_LAYERS=0`
- separately verified GPU smoke profile `N_GPU_LAYERS=-1` on `NVIDIA GeForce RTX 5070`

These settings are meant for a single desktop machine that still needs to stay usable for coding and normal desktop work.

## Common Failures

- `No GGUF model was found under models/llm`
  - Run `./scripts/setup-ai-local.sh`
- `Multiple GGUF models were found`
  - Set `JARVIS_LLM_MODEL_PATH` explicitly
- `llm-server` health never becomes healthy
  - Check `~/.jarvis/logs/local-runtime/llm-server.log`
- `llama-cpp-python` install/import failure
  - Check `~/.jarvis/logs/local-runtime/llm-server-python-install.log`
- GPU smoke fails or GPU readiness is not verified
  - Check `JARVIS_LLAMACPP_PACKAGE_SPEC` in `~/.jarvis/run/local-runtime/local.env`
  - Re-run `./scripts/ai-gpu-smoke.sh`
  - If GPU smoke does not pass, stay on the canonical CPU baseline (`N_GPU_LAYERS=0`)
- `memory-service` fails on startup
  - Check `embedding-service.log`, `memory-service.log`, and the managed pgvector container logs via `docker logs jarvis-local-postgres`
