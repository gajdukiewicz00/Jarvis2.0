# Iteration 1.3 - Product UX: Zero Red, Zero Scary Errors

**Дата:** 2026-01-05  
**Цель:** Запуск через иконку = чистый лог, всё зелёное, без пугающих ошибок/legacy

---

## Проблемы

### 1. Desktop: "полу-TLS" режим
- **Проблема:** Можно запустить Desktop с TLS=true, но API по http:// (или наоборот)
- **Риск:** Ломает ожидания, куки/JWT, дебаг становится адом

### 2. LLM build failed в логах
- **Проблема:** При ENABLE_LLM=false скрипт всё равно пытается собрать llm-server и показывает "Failed"
- **Риск:** Пугает пользователя, хотя это не критично

### 3. Memory stack не feature-flagged
- **Проблема:** postgres-pgvector и memory-service применяются всегда (replicas: 0)
- **Риск:** Лишние ресурсы в кластере, сложнее управление

---

## Решения

### 1. Desktop: запрет "mixed TLS"

**Изменения:**
- `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt`:
  - Добавлен fail-fast: если `JARVIS_USE_TLS=true` и API URL не `https://` → краш на старте
  - Если `JARVIS_USE_TLS=false`, но указан домен `*.jarvis.local` → fail с понятным сообщением

**DoD:**
- ✅ Невозможно запустить Desktop в "смешанном" режиме
- ✅ Понятные сообщения об ошибках

---

### 2. LLM build: conditional (no FAIL by default)

**Изменения:**
- `jarvis-launch.sh`:
  - `ENABLE_LLM=false` по умолчанию (вместо true)
  - `ENABLE_MEMORY=false` по умолчанию (новый флаг)
  - Если `ENABLE_LLM=false` → не собирать LLM образы вообще
  - Если сборка llm-server падает и `ENABLE_GPU=false` → показывать "SKIPPED (no GPU/runtime)" вместо "Failed"

**DoD:**
- ✅ При дефолтном запуске (core-only) в логе нет строк вида "Failed to build ..."
- ✅ LLM сборка пропускается с чётким "SKIPPED" если не нужна

---

### 3. Memory stack: feature-flagged deploy

**Изменения:**
- `jarvis-launch.sh`:
  - Условное применение LLM/Memory манифестов через флаги
  - `ENABLE_MEMORY=true` → применяются postgres-pgvector и memory-service
  - `ENABLE_LLM=true` → применяются embedding-service, llm-server, llm-service
  - `k8s/overlays/prod/kustomization.yaml`: закомментированы LLM/Memory ресурсы (применяются условно через скрипт)

**DoD:**
- ✅ По умолчанию memory/pgvector вообще не деплоятся
- ✅ Меньше ручных "а включи реплику"

---

## Изменённые файлы

1. `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt`
   - Добавлен fail-fast для запрета "полу-TLS" режима

2. `jarvis-launch.sh`
   - `ENABLE_LLM=false` по умолчанию
   - `ENABLE_MEMORY=false` по умолчанию (новый флаг)
   - Условное применение LLM/Memory манифестов
   - Улучшена обработка ошибок сборки (SKIPPED вместо Failed)

3. `k8s/overlays/prod/kustomization.yaml`
   - Закомментированы LLM/Memory ресурсы (применяются условно)

---

## Команды проверки

### 1. Проверка Desktop TLS fail-fast:
```bash
# Должен крашиться с понятным сообщением
JARVIS_USE_TLS=true JARVIS_API_BASE_URL=http://localhost:8080 java -jar desktop-client-javafx.jar
# Ожидаемый результат: IllegalStateException с понятным сообщением

# Должен крашиться если jarvis.local без TLS
JARVIS_USE_TLS=false JARVIS_API_BASE_URL=http://api.jarvis.local java -jar desktop-client-javafx.jar
# Ожидаемый результат: IllegalStateException с понятным сообщением
```

### 2. Проверка LLM build (default):
```bash
./jarvis-launch.sh
# Ожидаемый результат: Нет строк "Failed to build jarvis/llm-server"
# Ожидаемый результат: "SKIPPED (ENABLE_LLM=false, ENABLE_MEMORY=false)"
```

### 3. Проверка Memory stack (default):
```bash
kubectl -n jarvis get statefulset postgres-pgvector 2>&1
# Ожидаемый результат: not found (не деплоится по умолчанию)

kubectl -n jarvis get deploy memory-service 2>&1
# Ожидаемый результат: not found (не деплоится по умолчанию)
```

### 4. Проверка с ENABLE_MEMORY=true:
```bash
ENABLE_MEMORY=true ./jarvis-launch.sh
# Ожидаемый результат: postgres-pgvector и memory-service деплоятся
```

### 5. Final verify:
```bash
kubectl -n jarvis get pods
./scripts/verify-iteration-1.1.sh
# Ожидаемый результат: Всё зелёное, нет красных статусов
```

---

## Ожидаемые результаты

После применения:
- ✅ Desktop не запускается в "смешанном" TLS режиме (fail-fast)
- ✅ При дефолтном запуске нет "Failed" в логах
- ✅ Memory/pgvector не деплоятся по умолчанию
- ✅ Чистый лог при запуске через иконку
- ✅ Всё зелёное, без пугающих ошибок

---

## Включение LLM/Memory (когда понадобится):
```bash
# LLM stack
ENABLE_LLM=true ./jarvis-launch.sh

# Memory stack (требует embedding-service, автоматически включает ENABLE_LLM)
ENABLE_MEMORY=true ./jarvis-launch.sh

# Оба
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

---

**Готово к проверке!**


