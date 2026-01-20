# Iteration 1: Критические фиксы (P1, P2, P4) + Legacy Cleanup

**Дата:** 2025-01-27  
**Статус:** В процессе

---

## ✅ Выполнено

### P1: Удаление assistant-core
- ✅ Удалён `"assistant-core"` из списка сборки в `jarvis-launch.sh:249`
- ✅ Перемещён `k8s/dev/services/assistant-core.yaml` → `docs/legacy/k8s-dev/`

### P2: ImagePullBackOff
- ✅ `k8s/overlays/prod/embedding-service.yaml`: изменён `imagePullPolicy: IfNotPresent` → `Never`
- ✅ `k8s/overlays/prod/postgres-pgvector.yaml`: добавлен `imagePullPolicy: IfNotPresent`

**Примечание:** Образы должны собираться в minikube docker-env (уже есть в `jarvis-launch.sh:233`)

### P4: INTERNAL_ERROR auth (хардкод паролей)
- ✅ `apps/security-service/src/main/resources/application.yml`: убран хардкод `jarvis123`
- ✅ `apps/life-tracker/src/main/resources/application.yaml`: убран хардкод
- ✅ `apps/user-profile/src/main/resources/application.yaml`: убран хардкод
- ✅ `apps/planner-service/src/main/resources/application.yml`: убран хардкод
- ✅ `apps/memory-service/src/main/resources/application.yml`: убран хардкод
- ✅ Все `application-docker.yml`: убраны хардкоды

---

## 📝 Изменённые файлы

1. `jarvis-launch.sh` - удалён "assistant-core" из списка сборки
2. `k8s/overlays/prod/embedding-service.yaml` - imagePullPolicy: Never
3. `k8s/overlays/prod/postgres-pgvector.yaml` - imagePullPolicy: IfNotPresent
4. `apps/security-service/src/main/resources/application.yml` - убран хардкод пароля
5. `apps/life-tracker/src/main/resources/application.yaml` - убран хардкод пароля
6. `apps/user-profile/src/main/resources/application.yaml` - убран хардкод пароля
7. `apps/planner-service/src/main/resources/application.yml` - убран хардкод пароля
8. `apps/memory-service/src/main/resources/application.yml` - убран хардкод пароля
9. `apps/*/src/main/resources/application-docker.yml` (5 файлов) - убраны хардкоды
10. `k8s/dev/services/assistant-core.yaml` → перемещён в `docs/legacy/k8s-dev/`

---

## 🔍 Как проверить

### P1 (assistant-core):
```bash
# Maven build не должен ругаться про assistant-core
mvn clean package -DskipTests 2>&1 | grep -i "assistant-core" || echo "OK: assistant-core не упоминается"
```

### P2 (ImagePullBackOff):
```bash
# Проверка статуса подов
kubectl get pods -n jarvis | grep -E "embedding-service|postgres-pgvector"

# Ожидаемый результат: Running (не ImagePullBackOff)

# Если всё ещё ImagePullBackOff, проверить сборку образов:
eval $(minikube docker-env)
docker images | grep -E "jarvis/embedding-service|ankane/pgvector"
```

### P4 (INTERNAL_ERROR auth):
```bash
# Проверка login endpoint
curl -i -X POST "http://localhost:8080/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'

# Ожидаемый результат: 200 (если пользователь существует) или 401 (неверные credentials)
# НЕ должно быть 500 INTERNAL_ERROR

# Проверка регистрации
curl -i -X POST "http://localhost:8080/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass","role":"USER"}'

# Ожидаемый результат: 200/201 (успех) или 409 (пользователь уже существует)
# НЕ должно быть 500 INTERNAL_ERROR

# Проверка логов security-service
kubectl logs -n jarvis -l app=security-service --tail=50 | grep -i error

# Проверка логов api-gateway
kubectl logs -n jarvis -l app=api-gateway --tail=50 | grep -i auth
```

---

## ⚠️ Риски

1. **P2 (ImagePullBackOff):**
   - Если образы не собраны в minikube docker-env, проблема останется
   - Решение: убедиться, что `jarvis-launch.sh` правильно настраивает minikube docker-env перед сборкой

2. **P4 (INTERNAL_ERROR auth):**
   - Если secrets в K8s не настроены правильно, подключение к БД может не работать
   - Решение: проверить `kubectl get secret jarvis-secrets -n jarvis -o yaml`
   - Если БД не доступна, security-service не сможет подключиться

3. **Хардкод паролей:**
   - Если переменные окружения не установлены, приложения могут не запуститься
   - Решение: убедиться, что secrets в K8s применяются правильно

---

## 📋 Следующие шаги

После проверки:
1. Если P2 всё ещё проблема → проверить сборку образов в minikube
2. Если P4 всё ещё проблема → проверить логи и подключение к БД
3. Перейти к Iteration 2 (секреты без хардкода)


