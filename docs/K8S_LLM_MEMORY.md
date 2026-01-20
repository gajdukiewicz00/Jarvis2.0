# LLM + Memory on Kubernetes (prod-only)

## Deploy

```bash
# LLM only
ENABLE_LLM=true ./jarvis-launch.sh

# LLM + Memory
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

## Models

Place models in `~/.jarvis/models`:

```
~/.jarvis/models/
├── h2ogpt-7b-chat-q4_k_m.gguf
└── h2ogpt-4096-llama2-7b-chat/
```

## Smoke Tests

```bash
kubectl -n jarvis port-forward svc/llm-service 8091:8091
LLM_SERVICE_URL=http://localhost:8091 ./scripts/llm-smoke.sh

kubectl -n jarvis port-forward svc/memory-service 8093:8093
MEMORY_SERVICE_URL=http://localhost:8093 ./scripts/memory-smoke.sh
```

## Notes

- Namespace: `jarvis`
- Secrets: local only (`~/.jarvis/secrets/secrets.env`)
- TLS: local CA in `~/.jarvis/tls`
