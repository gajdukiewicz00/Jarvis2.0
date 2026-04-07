# Jarvis Local LLM Runbook

## Scope

This runbook covers the only supported local AI path in this repository:

- `llm-service`
- `llm-server`
- optional `memory-service` + `embedding-service`

## One-Time Setup

```bash
./scripts/setup-local.sh
./scripts/setup-ai-local.sh
./scripts/check-local-env.sh
```

Canonical local model root:

```text
~/.jarvis/models/
```

Expected model format:

- `GGUF`
- recommended baseline: `Qwen/Qwen2.5-3B-Instruct-GGUF`
- recommended quantization: `q4_k_m`

## Start

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

## Verify

Runtime status:

```bash
./scripts/runtime-status.sh
```

Health checks:

```bash
curl http://127.0.0.1:15000/health
curl http://127.0.0.1:8091/api/v1/llm/health
```

Smoke tests:

```bash
./scripts/ai-local-smoke.sh
JARVIS_AI_SKIP_SETUP=true JARVIS_SKIP_BUILD=true ./scripts/ai-gpu-smoke.sh
```

## Logs

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

## Safe Defaults

- backend: `llama.cpp`
- canonical CPU baseline: `N_GPU_LAYERS=0`
- verified GPU smoke profile on this machine: `N_GPU_LAYERS=-1`
- `llama-cpp-python==0.3.19`
- `N_CTX=4096`
- `N_BATCH=512`
- `CHAT_WORKERS=1`

## Common Failures

- `No GGUF model was found under models/llm`
  Run `./scripts/setup-ai-local.sh`.
- `Multiple GGUF models were found`
  Set `JARVIS_LLM_MODEL_PATH`.
- `llm-server` never becomes healthy
  Check `~/.jarvis/logs/local-runtime/llm-server.log`.
- `memory-service` fails on startup
  Check `embedding-service.log`, `memory-service.log`, and local Postgres logs.
