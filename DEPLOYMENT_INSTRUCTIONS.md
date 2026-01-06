# Инструкция по запуску Jarvis 2.0

## Быстрый старт (рекомендуется)

```bash
# Запуск всего стека через иконку приложения или скрипт
./jarvis-launch.sh

# Или только LLM+Memory стек
./scripts/jarvis-k8s-up.sh
```

**Важно:** Убедитесь, что модели находятся в `/home/kwaqa/models`:
- `h2ogpt-7b-chat-q4_k_m.gguf` (GGUF для llamacpp)
- `h2ogpt-4096-llama2-7b-chat/` (HuggingFace для transformers)

## LLM + Memory Stack

### Компоненты

| Сервис | Порт | Описание |
|--------|------|----------|
| llm-server | 5000 | GPU inference (llamacpp/transformers) |
| llm-service | 8091 | Java оркестратор с RAG |
| memory-service | 8093 | Долгосрочная память |
| embedding-service | 5001 | Векторные эмбеддинги |
| postgres-pgvector | 5432 | PostgreSQL + pgvector |

### Проверка LLM стека

```bash
# Smoke tests
./scripts/llm-smoke.sh
./scripts/memory-smoke.sh

# Проверка GPU
kubectl exec -n jarvis deploy/llm-server -- nvidia-smi
kubectl exec -n jarvis deploy/llm-server -- python3 -c "import torch; print(torch.cuda.is_available())"

# Проверка памяти (ingest + search)
# Ingest
curl -X POST http://localhost:8093/memory/ingest \
  -H "Content-Type: application/json" \
  -d '{"userId":"user1","sessionId":"s1","messages":[{"role":"user","content":"меня зовут Денис"}],"createChunks":true}'

# Search
curl -X POST http://localhost:8093/memory/search \
  -H "Content-Type: application/json" \
  -d '{"userId":"user1","query":"как меня зовут?","topK":3}'
```

### Документация LLM стека

Подробная документация: **[docs/K8S_LLM_MEMORY.md](docs/K8S_LLM_MEMORY.md)**

---

## API Gateway доступен через NodePort

**Текущий адрес:** `http://192.168.49.2:31618`

### Desktop Client конфигурация

Desktop client уже настроен на использование NodePort. При следующем запуске он будет подключаться к:
- **LoginController**: `http://192.168.49.2:31618`
- **DesktopApplication**: `http://192.168.49.2:31618/api/v1`
- **AuthService**: `http://192.168.49.2:31618`

### Проверка работоспособности

```bash
# Проверка регистрации
curl -X POST http://192.168.49.2:31618/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123","email":"test@example.com"}'

# Проверка логина
curl -X POST http://192.168.49.2:31618/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123"}'
```

### Важные изменения

1. **API Gateway исправлен:**
   - Feign клиенты используют `${services.security.url}`
   - DNS резолюция работает: `security-service.jarvis.svc.cluster.local:8088`
   - Переменная окружения `SERVICES_SECURITY_URL` настроена в Kubernetes

2. **Сервис изменён на NodePort:**
   - Это необходимо для доступа с хоста
   - При каждом редеплое сервис сбрасывается на ClusterIP
   - Нужно выполнить: `kubectl patch svc api-gateway -n jarvis -p '{"spec":{"type":"NodePort"}}'`

3. **NodePort может меняться:**
   - После редеплоя Kubernetes может назначить другой порт
   - Текущий: **31618**
   - Проверить: `kubectl get svc api-gateway -n jarvis`
   - Обновить в desktop client если изменился

### При следующем редеплое

Если запускаете `./deploy.sh` снова, выполните после него:

```bash
# 1. Изменить сервис на NodePort
kubectl patch svc api-gateway -n jarvis -p '{"spec":{"type":"NodePort"}}'

# 2. Узнать новый порт
NODE_PORT=$(kubectl get svc api-gateway -n jarvis -o jsonpath='{.spec.ports[0].nodePort}')
echo "Новый NodePort: $NODE_PORT"

# 3. Обновить конфигурацию desktop client
# Заменить 31618 на $NODE_PORT в файлах:
# - apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/controller/LoginController.kt
# - apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/DesktopApplication.kt

# 4. Пересобрать API Gateway с исправлениями
cd apps/api-gateway
mvn clean package -DskipTests
docker build -t jarvis/api-gateway:latest .
minikube image load jarvis/api-gateway:latest
kubectl delete pod -n jarvis -l app=api-gateway
```

### Альтернатива: LoadBalancer (для металлического кластера)

Если используете металлический кластер с MetalLB:

```bash
kubectl patch svc api-gateway -n jarvis -p '{"spec":{"type":"LoadBalancer"}}'
```

Тогда получите статический внешний IP.

## Запуск Desktop Client

После всех изменений просто запустите desktop client через скрипт деплоя или вручную:

```bash
mvn -f apps/desktop-client-javafx/pom.xml javafx:run
```

Логин и регистрация должны работать без ошибок `UPSTREAM_HOST_NOT_FOUND`.
