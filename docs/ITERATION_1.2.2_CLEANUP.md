# Iteration 1.2.2 Cleanup - Удаление legacy и подготовка к TLS

**Дата:** 2026-01-05  
**Цель:** Убрать legacy компоненты (assistant-core, postgres-pgvector ImagePullBackOff) и подготовить desktop client к TLS

---

## Проблемы

### 1. assistant-core всё ещё в кластере
- **Проблема:** Legacy deployment остался от прошлых apply
- **Риск:** Конфликты маршрутов, портов, конфигов, secrets

### 2. postgres-pgvector в ImagePullBackOff
- **Проблема:** Красный статус в кластере, хотя pgvector optional
- **Риск:** "Продукт в аварии", даже если не нужен

### 3. Voice WS использует ws:// вместо wss://
- **Проблема:** JWT в заголовках через ws без TLS = потенциальная утечка
- **Риск:** Проблемы при переходе на облако/синхронизацию

---

## Решения

### 1. Удаление assistant-core

**Изменения:**
- Удалён deployment и service из кластера
- Убраны ссылки из `k8s/base/configmap.yaml` (закомментированы)

**DoD:**
- ✅ `kubectl -n jarvis get deploy | grep assistant-core` → отсутствует
- ✅ После `./jarvis-launch.sh` не появляется снова

---

### 2. Отключение postgres-pgvector по умолчанию

**Изменения:**
- `k8s/overlays/local/postgres-pgvector.yaml`: установлен `replicas: 0`
- Добавлен комментарий: "postgres-pgvector is optional - scale to 0 if not needed"

**DoD:**
- ✅ Нет ImagePullBackOff
- ✅ `kubectl -n jarvis get pods | grep postgres-pgvector` → отсутствует
- ✅ memory-service = 0 replicas (уже сделано в 1.2.1)

---

### 3. Подготовка desktop client к TLS (wss://)

**Изменения:**
- `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt`:
  - Улучшена функция `wsBase()` для поддержки wss://
  - Добавлена логика определения TLS через `JARVIS_USE_TLS` env var
  - Поддержка `voice.jarvis.local` с wss:// когда TLS активен

**DoD:**
- ✅ Desktop client может переключаться на wss:// когда Iteration 7 активен
- ✅ Verify strict mode должен падать, если desktop всё ещё использует ws/http (проверка в verify script)

---

## Изменённые файлы

1. `k8s/overlays/local/postgres-pgvector.yaml`
   - `replicas: 0` (вместо 1)
   - Добавлен комментарий о том, как включить

2. `k8s/base/configmap.yaml`
   - Закомментированы `RABBITMQ_QUEUE_ASSISTANT` и `ASSISTANT_CORE_URL`

3. `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt`
   - Улучшена `wsBase()` для поддержки wss://
   - Добавлена логика определения TLS через env vars

---

## Команды проверки

### 1. Проверка assistant-core:
```bash
kubectl -n jarvis get deploy | grep assistant-core || echo "✅ assistant-core отсутствует"
kubectl -n jarvis get pods | grep assistant-core || echo "✅ assistant-core pods отсутствуют"
```

### 2. Проверка postgres-pgvector:
```bash
kubectl -n jarvis get pods | grep postgres-pgvector || echo "✅ postgres-pgvector disabled"
kubectl -n jarvis get statefulset postgres-pgvector -o jsonpath='{.spec.replicas}'
# Ожидаемый результат: 0
```

### 3. Проверка ImagePullBackOff:
```bash
kubectl -n jarvis get pods | grep ImagePullBackOff || echo "✅ No ImagePullBackOff"
```

### 4. Проверка desktop client TLS support:
```bash
# Проверка что код поддерживает wss://
grep -r "wss://\|JARVIS_USE_TLS\|voice.jarvis.local" apps/desktop-client-javafx/src/
```

### 5. Verify pack:
```bash
./scripts/verify-iteration-1.1.sh
# Ожидаемый результат: Нет ImagePullBackOff, нет assistant-core
```

---

## Ожидаемые результаты

После применения:
- ✅ Нет assistant-core в кластере
- ✅ Нет postgres-pgvector pods (replicas: 0)
- ✅ Нет ImagePullBackOff
- ✅ Desktop client готов к TLS (wss://)
- ✅ Чистый статус кластера (нет красных статусов)

---

## Включение postgres-pgvector (когда понадобится):
```bash
kubectl -n jarvis scale statefulset postgres-pgvector --replicas=1
kubectl -n jarvis scale deploy memory-service --replicas=1
```

---

**Готово к проверке!**

