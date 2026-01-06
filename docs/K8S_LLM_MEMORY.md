# Jarvis 2.0 — Kubernetes LLM + Memory Stack

## Обзор

Документ описывает деплой LLM и Memory стека в Kubernetes:

- **llm-server** — Python FastAPI сервер с GPU (llama.cpp / transformers)
- **llm-service** — Java Spring Boot оркестратор (RAG, персонализация)
- **memory-service** — Java Spring Boot сервис долгосрочной памяти
- **embedding-service** — Python FastAPI сервис эмбеддингов (CPU)
- **postgres-pgvector** — PostgreSQL с расширением pgvector

```
┌──────────────────────────────────────────────────────────────────┐
│                        User / Voice Gateway                       │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│                        llm-service (8091)                         │
│  • Персонализированные промпты                                   │
│  • Token budget management                                        │
│  • RAG: поиск в памяти + подмешивание контекста                  │
└────────────────────────────────┬─────────────────────────────────┘
                 ┌───────────────┼───────────────┐
                 │               │               │
                 ▼               ▼               ▼
┌────────────────────┐  ┌────────────────┐  ┌────────────────────┐
│  llm-server (5000) │  │ memory-service │  │ user-profile       │
│  • GPU inference   │  │     (8093)     │  │   (optional)       │
│  • llamacpp/torch  │  │  • ingest      │  └────────────────────┘
│  • GGUF Q4_K_M     │  │  • search      │
└────────────────────┘  │  • summarize   │
                        └───────┬────────┘
                                │
                 ┌──────────────┼──────────────┐
                 │                             │
                 ▼                             ▼
┌────────────────────────┐     ┌────────────────────────────────┐
│ embedding-service(5001)│     │ postgres-pgvector (5432)       │
│ • multilingual-e5-small│     │ • pgvector extension           │
│ • 384 dimensions       │     │ • memory_chunk (vector(384))   │
│ • CPU only             │     │ • conversation_message         │
└────────────────────────┘     │ • session_summary              │
                               └────────────────────────────────┘
```

## Требования

### Hardware
- **GPU**: NVIDIA RTX 5070 (12GB VRAM) или аналог
- **RAM**: 16GB+ (рекомендуется 32GB)
- **Storage**: 50GB+ для моделей

### Software
- Kubernetes cluster (minikube/k3s/kind/managed)
- kubectl
- NVIDIA GPU operator (для GPU в k8s)
- Docker с NVIDIA runtime

### Модели

Модели должны лежать в `/home/kwaqa/models`:

```
/home/kwaqa/models/
├── h2ogpt-4096-llama2-7b-chat/    # HuggingFace формат (transformers backend)
│   ├── config.json
│   ├── pytorch_model.bin
│   └── ...
└── h2ogpt-7b-chat-q4_k_m.gguf     # GGUF формат (llamacpp backend, рекомендуется)
```

## Быстрый старт

### 1. Подготовка

```bash
# Проверить кластер
kubectl cluster-info

# Проверить GPU в кластере
kubectl get nodes -o jsonpath='{.items[*].status.allocatable}' | grep nvidia

# Проверить модели
ls -la /home/kwaqa/models/
```

### 2. Сборка образов (опционально)

```bash
# Для minikube - использовать его Docker daemon
eval $(minikube docker-env)

# Собрать Java сервисы
mvn clean package -pl apps/memory-service,apps/llm-service -am -DskipTests

# Собрать Docker образы
docker build -t jarvis/llm-server:latest -f docker/llm-server/Dockerfile docker/llm-server/
docker build -t jarvis/embedding-service:latest -f docker/embedding-service/Dockerfile docker/embedding-service/
docker build -t jarvis/memory-service:latest -f apps/memory-service/Dockerfile apps/memory-service/
docker build -t jarvis/llm-service:latest -f apps/llm-service/Dockerfile apps/llm-service/
```

### 3. Деплой

```bash
# Автоматический деплой (рекомендуется)
./scripts/jarvis-k8s-up.sh

# Или вручную через kustomize
kubectl apply -k k8s/overlays/local/

# Без GPU (CPU fallback)
./scripts/jarvis-k8s-up.sh --no-gpu
```

### 4. Проверка

```bash
# Статус подов
kubectl get pods -n jarvis

# Port-forward для локального доступа
kubectl port-forward -n jarvis svc/llm-server 5000:5000 &
kubectl port-forward -n jarvis svc/llm-service 8091:8091 &
kubectl port-forward -n jarvis svc/memory-service 8093:8093 &
kubectl port-forward -n jarvis svc/embedding-service 5001:5001 &

# Smoke tests
./scripts/llm-smoke.sh
./scripts/memory-smoke.sh
```

## Конфигурация

### Environment Variables

#### llm-server
| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_BACKEND` | `llamacpp` | Backend: `llamacpp` или `transformers` |
| `GGUF_MODEL_PATH` | `/models/h2ogpt-7b-chat-q4_k_m.gguf` | Путь к GGUF модели |
| `MODEL_PATH` | `/models/h2ogpt-4096-llama2-7b-chat` | Путь к HF модели |
| `N_GPU_LAYERS` | `35` | Количество слоёв на GPU (llamacpp) |
| `N_CTX` | `4096` | Размер контекста |
| `MAX_NEW_TOKENS` | `700` | Максимум токенов генерации |
| `DEVICE` | `cuda` | Устройство: `cuda` или `cpu` |

#### memory-service
| Variable | Default | Description |
|----------|---------|-------------|
| `EMBEDDING_SERVICE_URL` | `http://embedding-service:5001` | URL embedding сервиса |
| `MEMORY_TOPK` | `5` | Количество результатов поиска |
| `MEMORY_MAX_TOKENS` | `600` | Лимит токенов в контексте |
| `CHUNK_SIZE` | `500` | Размер чанков |

#### llm-service
| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_SERVER_URL` | `http://llm-server:5000` | URL LLM сервера |
| `MEMORY_SERVICE_URL` | `http://memory-service:8093` | URL memory сервиса |
| `MEMORY_ENABLED` | `true` | Включить RAG |
| `LLM_BUDGET_MEMORY` | `600` | Бюджет токенов для памяти |
| `LLM_BUDGET_HISTORY` | `1400` | Бюджет токенов для истории |

### Настройка VRAM

Для 12GB VRAM (RTX 5070) рекомендуется:

```yaml
# llm-server settings
LLM_BACKEND: llamacpp
N_GPU_LAYERS: 35       # ~8GB VRAM
N_CTX: 4096
MAX_NEW_TOKENS: 700
```

Для экономии VRAM можно уменьшить `N_GPU_LAYERS`:
- `35` — ~8GB VRAM (рекомендуется)
- `25` — ~6GB VRAM
- `15` — ~4GB VRAM

## API Endpoints

### llm-server (5000)

```bash
# Health check
GET /health

# Chat completion
POST /api/v1/llm/chat
{
    "messages": [
        {"role": "user", "content": "Привет!"}
    ],
    "max_tokens": 512,
    "temperature": 0.7
}
```

### memory-service (8093)

```bash
# Health check
GET /memory/health

# Ingest messages
POST /memory/ingest
{
    "userId": "user1",
    "sessionId": "session1",
    "messages": [
        {"role": "user", "content": "Меня зовут Денис"},
        {"role": "assistant", "content": "Приятно познакомиться, Денис!"}
    ],
    "createChunks": true
}

# Search memory
POST /memory/search
{
    "userId": "user1",
    "query": "как меня зовут?",
    "topK": 5,
    "maxTokens": 600
}

# Summarize session
POST /memory/summarize-session
{
    "userId": "user1",
    "sessionId": "session1"
}
```

### embedding-service (5001)

```bash
# Health check
GET /health

# Generate embeddings
POST /embed
{
    "texts": ["Привет", "Как дела?"]
}
```

## Troubleshooting

### GPU не видна в контейнере

```bash
# Проверить NVIDIA driver на хосте
nvidia-smi

# Проверить NVIDIA device plugin в кластере
kubectl get pods -n kube-system | grep nvidia

# Для minikube
minikube addons enable nvidia-device-plugin

# Логи llm-server
kubectl logs -n jarvis deploy/llm-server | grep -i cuda
```

### Модель не загружается

```bash
# Проверить PV/PVC
kubectl get pv,pvc -n jarvis

# Проверить что модель доступна в поде
kubectl exec -n jarvis deploy/llm-server -- ls -la /models/

# Логи загрузки модели
kubectl logs -n jarvis deploy/llm-server --tail=200
```

### Memory service не находит факты

```bash
# Проверить pgvector extension
kubectl exec -n jarvis sts/postgres-pgvector -- psql -U jarvis -d jarvis_memory \
    -c "SELECT extname FROM pg_extension WHERE extname = 'vector';"

# Проверить таблицы
kubectl exec -n jarvis sts/postgres-pgvector -- psql -U jarvis -d jarvis_memory \
    -c "\dt"

# Проверить чанки
kubectl exec -n jarvis sts/postgres-pgvector -- psql -U jarvis -d jarvis_memory \
    -c "SELECT id, user_id, chunk_text FROM memory_chunk LIMIT 5;"
```

### Out of Memory (OOM)

```bash
# Уменьшить N_GPU_LAYERS в llm-server deployment
kubectl set env deploy/llm-server -n jarvis N_GPU_LAYERS=25

# Или переключиться на CPU
kubectl set env deploy/llm-server -n jarvis DEVICE=cpu LLM_BACKEND=transformers
```

## Мониторинг

```bash
# Все поды
kubectl get pods -n jarvis -w

# Логи в реальном времени
kubectl logs -n jarvis deploy/llm-server -f
kubectl logs -n jarvis deploy/memory-service -f

# Ресурсы
kubectl top pods -n jarvis

# GPU utilization (в поде)
kubectl exec -n jarvis deploy/llm-server -- nvidia-smi
```

## Файловая структура

```
k8s/
├── base/
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   └── postgres-init-configmap.yaml
└── overlays/
    └── local/
        ├── kustomization.yaml
        ├── pv-models.yaml              # hostPath PV для моделей
        ├── secrets-local.yaml
        ├── postgres-pgvector.yaml      # StatefulSet + Service
        ├── embedding-service.yaml      # Deployment + Service
        ├── memory-service.yaml         # Deployment + Service
        ├── llm-server.yaml             # Deployment + Service (GPU)
        └── llm-service.yaml            # Deployment + Service
```

## Definition of Done

Стек считается работающим когда:

1. ✅ `kubectl get pods -n jarvis` — все поды Running/Ready
2. ✅ `nvidia-smi` работает в llm-server поде
3. ✅ `torch.cuda.is_available() == True` в llm-server поде
4. ✅ `/health` на llm-server отвечает 200
5. ✅ Memory ingest+search работает:
   - Ingest: "меня зовут Денис"
   - Search: "как меня зовут?" → возвращает "Денис"
6. ✅ llm-service использует память в ответах (видно в логах)

