# Jarvis 2.0 — Kubernetes Deployment

## Структура

```
k8s/
├── base/                           # Базовые ресурсы (namespace, configmaps)
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   └── postgres-init-configmap.yaml
├── overlays/
│   └── local/                      # Локальный деплой (GPU, hostPath volumes)
│       ├── kustomization.yaml
│       ├── pv-models.yaml          # PV для моделей (/home/kwaqa/models)
│       ├── secrets-local.yaml
│       ├── postgres-pgvector.yaml  # PostgreSQL + pgvector
│       ├── embedding-service.yaml  # CPU embedding (multilingual-e5-small)
│       ├── memory-service.yaml     # Long-term memory service
│       ├── llm-server.yaml         # GPU LLM server (llamacpp/transformers)
│       └── llm-service.yaml        # LLM orchestrator (Java)
└── dev/                            # Legacy dev manifests (не используется для LLM)
    └── ...
```

## Быстрый старт

### Через скрипт (рекомендуется)

```bash
./scripts/jarvis-k8s-up.sh             # С GPU
./scripts/jarvis-k8s-up.sh --no-gpu    # Без GPU (CPU fallback)
./scripts/jarvis-k8s-up.sh --build     # Пересобрать Docker образы
```

### Через kustomize

```bash
kubectl apply -k k8s/overlays/local/
```

### Через jarvis-launch.sh

```bash
./jarvis-launch.sh   # Полный деплой + Desktop Client
```

## Компоненты

| Компонент | Порт | Ресурсы | Описание |
|-----------|------|---------|----------|
| postgres-pgvector | 5432 | 512Mi RAM | PostgreSQL 16 + pgvector 0.7.4 |
| embedding-service | 5001 | 2Gi RAM, CPU | multilingual-e5-small (384 dims) |
| memory-service | 8093 | 768Mi RAM | Spring Boot, ingest/search API |
| llm-server | 5000 | 14Gi RAM, 1 GPU | llamacpp/transformers inference |
| llm-service | 8091 | 1Gi RAM | Spring Boot, RAG orchestration |

## Проверка

```bash
# Статус подов
kubectl get pods -n jarvis

# GPU проверка
kubectl exec -n jarvis deploy/llm-server -- nvidia-smi
kubectl exec -n jarvis deploy/llm-server -- python3 -c "import torch; print(torch.cuda.is_available())"

# Smoke tests
./scripts/llm-smoke.sh
./scripts/memory-smoke.sh
```

## Environment Variables

### llm-server

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_BACKEND` | `llamacpp` | `llamacpp` (GGUF) или `transformers` (HF) |
| `N_GPU_LAYERS` | `35` | Слоёв на GPU (для 12GB VRAM) |
| `N_CTX` | `4096` | Размер контекста |
| `MAX_NEW_TOKENS` | `700` | Максимум генерируемых токенов |

### memory-service

| Variable | Default | Description |
|----------|---------|-------------|
| `MEMORY_TOPK` | `5` | Результатов поиска |
| `MEMORY_MAX_TOKENS` | `600` | Лимит токенов контекста |

## Модели

Модели должны быть в `/home/kwaqa/models`:

```
/home/kwaqa/models/
├── h2ogpt-7b-chat-q4_k_m.gguf           # GGUF (рекомендуется)
└── h2ogpt-4096-llama2-7b-chat/          # HuggingFace
```

## Troubleshooting

```bash
# Логи
kubectl logs -n jarvis deploy/llm-server --tail=100
kubectl logs -n jarvis deploy/memory-service --tail=100

# Описание пода
kubectl describe pod -n jarvis -l app=llm-server

# pgvector проверка
kubectl exec -n jarvis sts/postgres-pgvector -- psql -U jarvis -d jarvis_memory \
    -c "SELECT extname FROM pg_extension WHERE extname = 'vector';"
```

## Документация

- [K8S_LLM_MEMORY.md](../docs/K8S_LLM_MEMORY.md) — полная документация LLM+Memory стека
- [DEPLOYMENT_INSTRUCTIONS.md](../DEPLOYMENT_INSTRUCTIONS.md) — общие инструкции деплоя
