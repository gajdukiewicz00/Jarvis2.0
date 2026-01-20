# Jarvis 2.0 LLM Runbook (prod-only)

## 1. Overview

The LLM subsystem consists of:
- **llm-server** (Python/FastAPI): Runs the h2oGPT-7B model. Supports CPU/GPU.
- **llm-service** (Java/Spring): Proxy, session management, prompt engineering.
- **Orchestrator**: Uses `llm-service` as a fallback for unknown intents.

### LLM Feature Flag

LLM is disabled by default.

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `JARVIS_LLM_ENABLED` | `false` | Enable LLM for unknown intents |
| `JARVIS_LLM_TIMEOUT_SECONDS` | `10` | Timeout for LLM calls |
| `JARVIS_LLM_CB_FAILURE_THRESHOLD` | `3` | Circuit breaker failures |
| `JARVIS_LLM_CB_RESET_SECONDS` | `60` | Circuit breaker cooldown |

## 2. Prerequisites

- NVIDIA GPU with drivers installed (optional)
- Kubernetes cluster with NVIDIA device plugin (for GPU)

LLM works on CPU too (slower).

## 3. Deploy (Kubernetes)

```bash
# Enable LLM stack
ENABLE_LLM=true ./jarvis-launch.sh

# Enable LLM + Memory
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh

# CPU fallback
ENABLE_LLM=true ENABLE_GPU=false ./jarvis-launch.sh
```

### Models

Place models in `~/.jarvis/models`:

```
~/.jarvis/models/
├── h2ogpt-7b-chat-q4_k_m.gguf
└── h2ogpt-4096-llama2-7b-chat/
```

## 4. Verification

### Smoke test (port-forward)

```bash
kubectl -n jarvis port-forward svc/llm-service 8091:8091
LLM_SERVICE_URL=http://localhost:8091 ./scripts/llm-smoke.sh
```

### AI tool acceptance (internal-only)

Tool endpoints are internal-only. Acceptance runs through port-forward.

```bash
kubectl -n jarvis port-forward svc/api-gateway 8080:8080
export JARVIS_API_BASE_URL="http://localhost:8080"
export JARVIS_USER_ID="acceptance-user"
scripts/acceptance-ai.sh
```

Ingress should block tool routes without JWT:

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -d '{}' \
  https://api.jarvis.local/api/v1/tools/todo/list
```

Expected: `401` or `403`.

### Orchestrator fallback

```bash
curl -X POST https://api.jarvis.local/api/v1/orchestrator/execute \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Tell me a joke about Java",
    "language": "en",
    "correlationId": "test-123"
  }'
```

## 5. Troubleshooting

- `Model path does not exist`: ensure the model is under `~/.jarvis/models`.
- `GPU available: False`: check NVIDIA driver and device plugin in cluster.
- `llm-service` 503/timeout: check `http://llm-server:5000/health` from inside the pod.

## 6. Desktop launcher invariant

- Desktop entry is install-only and idempotent.
- Single file location: `~/.local/share/applications/jarvis.desktop` (Name: Jarvis).
- Installer removes legacy/duplicate entries in standard user/system locations.
- Runtime services and Launcher UI do not create or modify `.desktop` files.
