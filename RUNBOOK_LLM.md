# Jarvis 2.0 LLM Runbook

## 1. Overview

The LLM subsystem consists of:
- **llm-server** (Python/FastAPI): Runs the h2oGPT-7B model. Supports CPU/GPU.
- **llm-service** (Java/Spring): Proxy, session management, prompt engineering.
- **Orchestrator**: Uses `llm-service` as a fallback for unknown intents.

### LLM Feature Flag

**LLM is disabled by default** for stability. The orchestrator uses rule-based responses for unknown intents unless LLM is explicitly enabled.

| Env Variable | Default | Description |
|--------------|---------|-------------|
| `JARVIS_LLM_ENABLED` | `false` | Enable LLM for unknown intents |
| `JARVIS_LLM_TIMEOUT_SECONDS` | `10` | Timeout for LLM calls |
| `JARVIS_LLM_CB_FAILURE_THRESHOLD` | `3` | Circuit breaker: failures before disable |
| `JARVIS_LLM_CB_RESET_SECONDS` | `60` | Circuit breaker: cooldown period |

### Circuit Breaker Behavior

1. If LLM times out or errors N times consecutively → circuit opens
2. While circuit is open → orchestrator returns rule-based phrase, logs `LLM_CIRCUIT_OPEN`
3. After cooldown period → circuit closes, LLM calls resume

## 2. Prerequisites

- NVIDIA GPU with drivers installed (for GPU mode).
- Docker with NVIDIA Container Toolkit (for local GPU support).
- Kubernetes cluster with NVIDIA device plugin (for K8s GPU).

**Note**: LLM works on CPU too (slower, ~5-30s per response).

## 3. Running Locally (Docker)

### Build llm-server
```bash
cd docker/llm-server
docker build -t jarvis/llm-server:latest .
```

### Run llm-server
```bash
# With GPU (requires nvidia-container-toolkit)
docker run -d --gpus all -p 5000:5000 --name llm-server jarvis/llm-server:latest

# Without GPU (CPU mode, slow)
docker run -d -p 5000:5000 -e DEVICE=cpu --name llm-server jarvis/llm-server:latest
```

### Check Status
```bash
curl http://localhost:5000/health
# Response: {"status":"healthy", "device":"cuda", ...}
```

## 4. Running in Kubernetes (dev/minikube)

1) Подготовка моделей  
   Если minikube на Docker/Podman, смонтируйте модели с хоста в `/models` (именно этот путь используется в манифесте). Mount должен оставаться запущенным в отдельном терминале:  
   ```bash
   minikube mount /home/kwaqa/models:/models
   ```
   Убедитесь, что внутри ноды путь `/models` содержит директорию модели (`h2ogpt-4096-llama2-7b-chat`).

2) Применить манифесты стека LLM  
   ```bash
   kubectl apply -f k8s/dev/services/llm-stack.yaml -n jarvis
   # llm-server может грузиться долго, ждём старта и readiness
   kubectl wait --for=condition=ready pod -l app=llm-server -n jarvis --timeout=900s
   kubectl wait --for=condition=ready pod -l app=llm-service -n jarvis --timeout=300s
   ```

3) (Опционально) port-forward для локальной проверки  
   ```bash
   kubectl -n jarvis port-forward svc/llm-service 8091:8091
   ```

4) GPU режим  
   - Хост: драйвер NVIDIA + nvidia-container-toolkit.  
   - Кластер: nvidia device plugin (`kubectl get ds -n kube-system | grep nvidia`).  
   - В манифесте: установить `DEVICE=cuda` и добавить лимит `nvidia.com/gpu: 1` в llm-server.  
   - Проверка: `kubectl logs -n jarvis deploy/llm-server | grep "GPU available"`.

## 5. Verification

### Smoke через скрипт
```bash
# В кластере (ClusterIP, скрипт смонтирован в под из ConfigMap):
kubectl -n jarvis exec deploy/llm-service -- \
  /bin/sh -lc 'LLM_SERVICE_URL=http://llm-service:8091 /scripts/llm-smoke.sh'

# При port-forward:
LLM_SERVICE_URL=http://localhost:8091 ./scripts/llm-smoke.sh
```
Скрипт делает health, два чата в одной сессии и проверяет, что кодовое слово вспомнилось.

### Оркестратор fallback (опционально)
```bash
curl -X POST http://localhost:8080/api/v1/orchestrator/execute \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Tell me a joke about Java",
    "language": "en",
    "correlationId": "test-123"
  }'
```
Ожидается: Orchestrator -> llm-service -> llm-server, в ответе сгенерированный текст.

## 6. Troubleshooting (частое)

- `Model path does not exist`: проверьте `minikube mount ...:/models` и что модель лежит в `/models/h2ogpt-4096-llama2-7b-chat`.  
- Вечный CrashLoop на llm-server: не хватило времени на загрузку модели — увеличьте startupProbe/failureThreshold или убедитесь, что модель доступна.  
- `GPU available: False`: проверьте nvidia plugin и лимиты GPU.  
- llm-service 503/timeout: проверьте соединение до `http://llm-server:5000/health` из пода llm-service:  
  ```bash
  kubectl -n jarvis exec deploy/llm-service -- curl -sS http://llm-server:5000/health
  ```  
- Orchestrator отвечает фразой “не понимаю”: llm-service недоступен или выключен флаг jarvis.llm.enabled.
