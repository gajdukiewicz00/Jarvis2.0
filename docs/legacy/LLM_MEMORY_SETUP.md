# Jarvis 2.0 — LLM + Long-Term Memory Setup

## Архитектура

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              JARVIS 2.0                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐          ┌──────────────┐          ┌─────────────────┐    │
│  │ llm-service │  ──────▶ │  llm-server  │          │ embedding-service│   │
│  │   (8091)    │          │    (5000)    │          │     (5001)       │   │
│  │   Java      │          │   Python     │          │    Python        │   │
│  └──────┬──────┘          │  GPU (RTX)   │          │    CPU           │   │
│         │                 └──────────────┘          └────────┬─────────┘   │
│         │                                                    │              │
│         │                 ┌──────────────┐                   │              │
│         └───────────────▶ │memory-service│ ◀─────────────────┘              │
│                           │    (8093)    │                                  │
│                           │    Java      │                                  │
│                           └──────┬───────┘                                  │
│                                  │                                          │
│                                  ▼                                          │
│                    ┌─────────────────────────────┐                          │
│                    │  PostgreSQL + pgvector      │                          │
│                    │         (5432)              │                          │
│                    │                             │                          │
│                    │  • conversation_message     │                          │
│                    │  • memory_chunk (vectors)   │                          │
│                    │  • session_summary          │                          │
│                    └─────────────────────────────┘                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Компоненты

### 1. LLM Server (`docker/llm-server`)

- **Порт**: 5000
- **GPU**: RTX 5070 (12GB VRAM)
- **Backends**:
  - `transformers` — HuggingFace (по умолчанию)
  - `llamacpp` — llama.cpp с GGUF моделями (быстрее)

### 2. Embedding Service (`docker/embedding-service`)

- **Порт**: 5001
- **Модель**: `intfloat/multilingual-e5-small` (384 dimensions)
- **CPU**: Не требует GPU

### 3. Memory Service (`apps/memory-service`)

- **Порт**: 8093
- **База**: PostgreSQL + pgvector

---

## Быстрый старт

### 1. Подготовка моделей

```bash
# Создать директорию для моделей
mkdir -p ~/models

# Скачать HuggingFace модель (для transformers backend)
# Модель h2ogpt-4096-llama2-7b-chat уже должна быть в ~/models/

# Для llama.cpp backend - конвертировать в GGUF:
# См. раздел "Конвертация в GGUF" ниже
```

### 2. Запуск

```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0

# Собрать Java сервисы
mvn clean package -DskipTests

# Запустить всё
docker compose up -d

# Проверить статус
docker compose ps

# Смотреть логи LLM
docker compose logs -f llm-server
```

### 3. Проверка GPU

```bash
# Внутри контейнера
docker compose exec llm-server nvidia-smi

# Python проверка
docker compose exec llm-server python3 -c "import torch; print('CUDA:', torch.cuda.is_available())"
```

### 4. Тестирование LLM

```bash
# Health check
curl http://localhost:5000/health | jq

# Chat request
curl -X POST http://localhost:5000/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "Ты — Jarvis, персональный ассистент. Отвечай на русском."},
      {"role": "user", "content": "Привет! Как дела?"}
    ],
    "max_tokens": 256,
    "temperature": 0.7
  }' | jq
```

### 5. Тестирование Memory

```bash
# Embedding service health
curl http://localhost:5001/health | jq

# Memory service health
curl http://localhost:8093/memory/health | jq

# Ingest messages
curl -X POST http://localhost:8093/memory/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user1",
    "sessionId": "session1",
    "messages": [
      {"role": "user", "content": "Мой любимый цвет — синий"},
      {"role": "assistant", "content": "Понял, запомнил что твой любимый цвет синий!"}
    ]
  }' | jq

# Search memory
curl -X POST http://localhost:8093/memory/search \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user1",
    "query": "какой мой любимый цвет?",
    "topK": 5
  }' | jq
```

---

## Конфигурация через ENV

### LLM Server

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `LLM_BACKEND` | `transformers` | Backend: `transformers` или `llamacpp` |
| `MODEL_PATH` | `/models/h2ogpt-4096-llama2-7b-chat` | Путь к HF модели |
| `GGUF_MODEL_PATH` | `/models/model.gguf` | Путь к GGUF модели |
| `MAX_NEW_TOKENS` | `512` | Максимум токенов на генерацию |
| `MAX_GENERATION_SECONDS` | `120` | Таймаут генерации |
| `N_GPU_LAYERS` | `-1` | Слои на GPU (-1 = все) |
| `N_CTX` | `4096` | Размер контекста |
| `LLM_QUANT` | `none` | Квантизация: `none`, `4bit`, `8bit` |
| `ENABLE_STREAMING` | `true` | SSE streaming |

### Memory Service

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `MEMORY_TOPK` | `5` | Топ-K чанков при поиске |
| `MEMORY_MAX_TOKENS` | `600` | Макс токенов из памяти |
| `CHUNK_SIZE` | `500` | Размер чанка (символы) |
| `EMBEDDING_SERVICE_URL` | `http://embedding-service:5001` | URL embedding сервиса |

---

## Конвертация в GGUF (для llama.cpp)

```bash
# 1. Клонировать llama.cpp
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp

# 2. Конвертировать HF модель в GGUF
python3 convert_hf_to_gguf.py ~/models/h2ogpt-4096-llama2-7b-chat \
  --outfile ~/models/h2ogpt-7b-chat-f16.gguf \
  --outtype f16

# 3. Квантизировать (рекомендуется Q4_K_M для баланса скорость/качество)
./llama-quantize ~/models/h2ogpt-7b-chat-f16.gguf \
  ~/models/h2ogpt-7b-chat-q4_k_m.gguf Q4_K_M

# 4. Обновить docker-compose.yml:
# GGUF_MODEL_PATH=/models/h2ogpt-7b-chat-q4_k_m.gguf
# LLM_BACKEND=llamacpp
```

---

## Переключение Backend

### Transformers (по умолчанию)
```bash
# В .env или docker-compose.yml
LLM_BACKEND=transformers
MODEL_PATH=/models/h2ogpt-4096-llama2-7b-chat
```

### llama.cpp (быстрее)
```bash
# В .env или docker-compose.yml
LLM_BACKEND=llamacpp
GGUF_MODEL_PATH=/models/h2ogpt-7b-chat-q4_k_m.gguf
N_GPU_LAYERS=-1  # все слои на GPU
```

---

## Troubleshooting

### GPU не видится

```bash
# Проверить драйвер на хосте
nvidia-smi

# Проверить nvidia-container-toolkit
docker run --rm --gpus all nvidia/cuda:12.4.1-runtime-ubuntu22.04 nvidia-smi

# Если ошибка, установить:
# https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html
```

### Out of Memory (OOM)

```bash
# Уменьшить MAX_NEW_TOKENS
MAX_NEW_TOKENS=256

# Или использовать 4-bit quantization
LLM_QUANT=4bit

# Или перейти на llama.cpp с Q4_K_M квантом
LLM_BACKEND=llamacpp
GGUF_MODEL_PATH=/models/h2ogpt-7b-chat-q4_k_m.gguf
```

### pgvector не работает

```bash
# Проверить расширение
docker compose exec postgres psql -U jarvis -d jarvis_memory -c "SELECT * FROM pg_extension WHERE extname='vector';"

# Если нет, создать вручную:
docker compose exec postgres psql -U jarvis -d jarvis_memory -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### Медленная генерация

1. Проверить что GPU используется:
   ```bash
   docker compose exec llm-server nvidia-smi
   ```

2. Уменьшить размер контекста:
   ```bash
   N_CTX=2048
   ```

3. Переключиться на llama.cpp:
   ```bash
   LLM_BACKEND=llamacpp
   ```

---

## Порты

| Сервис | Порт | Описание |
|--------|------|----------|
| llm-server | 5000 | LLM inference (GPU) |
| embedding-service | 5001 | Embeddings (CPU) |
| memory-service | 8093 | Memory API |
| postgres | 5432 | PostgreSQL + pgvector |
| api-gateway | 8080 | Main API gateway |
| llm-service | 8091 | Java LLM wrapper |

---

## Следующие шаги

1. **RAG интеграция**: Подмешивание памяти в промпт LLM
2. **Session summaries**: LLM-генерация резюме сессий
3. **Streaming**: SSE для голосового интерфейса
4. **E2E тесты**: Полный цикл диалога с памятью


