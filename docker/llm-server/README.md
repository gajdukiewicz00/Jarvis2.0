# LLM Server

Local inference worker for Jarvis.

## Runtime Role

- HTTP API consumed by `llm-service`
- Local backend: `llama.cpp`
- Expected local model format: `GGUF`
- Default local port: `15000`

## Local Runtime Contract

`scripts/runtime-up.sh` is the canonical way to run this service locally.

It expects:

- one GGUF model under `~/.jarvis/models/llm/`, or
- `JARVIS_LLM_MODEL_PATH` set in `~/.jarvis/run/local-runtime/local.env`

## Health

```bash
curl http://127.0.0.1:15000/health
```

## Direct Prompt Test

```bash
curl -X POST http://127.0.0.1:15000/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "You are Jarvis."},
      {"role": "user", "content": "Reply with one short sentence."}
    ]
  }'
```

## Important Local Defaults

- backend: `llamacpp`
- package spec: `llama-cpp-python==0.3.19`
- context window: `4096`
- batch size: `512`
- low concurrency: `CHAT_WORKERS=1`
- canonical CPU default: `N_GPU_LAYERS=0`
- verified GPU smoke profile: `N_GPU_LAYERS=-1`

## Logs

When started by the local runtime:

```text
~/.jarvis/logs/local-runtime/llm-server.log
~/.jarvis/logs/local-runtime/llm-server-python-install.log
```
