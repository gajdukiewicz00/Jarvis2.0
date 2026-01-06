# Iteration 1.2.1 Micro-hotfix - Убрать CrashLoopBackOff у memory-service

**Дата:** 2026-01-05  
**Цель:** Убрать CrashLoopBackOff у memory-service (продуктовый стандарт: optional сервисы не должны быть в CrashLoopBackOff)

---

## Проблема

После Iteration 1.2:
- ✅ P4 закрыт (login работает)
- ✅ P2 закрыт (embedding-service Running)
- ❌ memory-service в CrashLoopBackOff из-за отсутствия postgres-pgvector

**Почему это проблема:**
- CrashLoopBackOff = грязный красный статус в кластере
- Ломает self-check
- Засоряет логи
- Мешает "клик по иконке → всё поднялось"

**Продуктовый критерий для optional:**
- Optional service может быть: NotDeployed / ScaledToZero / Degraded (Running)
- НЕ может быть: CrashLoopBackOff

---

## Решение

**Вариант A (выбран):** Не деплоить memory-service, если нет pgvector

**Изменения:**
- `k8s/overlays/local/memory-service.yaml`: установлен `replicas: 0` по умолчанию
- Добавлен комментарий: "Memory service is optional - scale to 0 if postgres-pgvector is not available"

**DoD:**
- ✅ Нет pod'ов memory-service в кластере
- ✅ verify перестаёт падать на CrashLoopBackOff
- ✅ core работает как сейчас

---

## Изменённые файлы

1. `k8s/overlays/local/memory-service.yaml`
   - `replicas: 0` (вместо 1)
   - Добавлен комментарий о том, как включить сервис

---

## Команды проверки

### 1. Проверка что pods отсутствуют:
```bash
kubectl -n jarvis get pods | grep memory || echo "✅ memory-service not running"
# Ожидаемый результат: ✅ memory-service not running
```

### 2. Проверка что core работает:
```bash
MINIKUBE_IP=$(minikube ip)
NODE_PORT=$(kubectl -n jarvis get svc api-gateway -o jsonpath='{.spec.ports[0].nodePort}')
curl -s -o /dev/null -w "Login status: %{http_code}\n" \
  -X POST "http://${MINIKUBE_IP}:${NODE_PORT}/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test11","password":"test11"}'
# Ожидаемый результат: Login status: 200
```

### 3. Verify pack:
```bash
./scripts/verify-iteration-1.1.sh
# Ожидаемый результат: Нет CrashLoopBackOff pods
```

### 4. Включение memory-service (когда pgvector будет доступен):
```bash
kubectl -n jarvis scale deploy/memory-service --replicas=1
```

---

## Ожидаемые результаты

После применения:
- ✅ Нет pod'ов memory-service в кластере
- ✅ verify pack не падает на CrashLoopBackOff
- ✅ core работает (login/register доступны)
- ✅ Чистый статус кластера (нет красных статусов)

---

## Риски

Нет. Это безопасное изменение - мы просто не запускаем optional сервис, который не может работать без зависимости.

---

**Готово к проверке!**

