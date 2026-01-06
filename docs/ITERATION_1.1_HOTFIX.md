# Iteration 1.1: Hotfix - Критические исправления

**Дата:** 2025-01-27  
**Статус:** Завершено

---

## ✅ Исправлено

### 1. Secrets / P4 (критично)
- ✅ Убраны дефолты из всех `application*.yml` (только `${ENV_VAR}` без значений)
- ✅ Добавлен `envFrom: secretRef: jarvis-secrets` во все deployments
- ✅ Fail-fast: если переменных нет → сервис не стартует (Spring Boot не запустится без обязательных переменных)

**Изменённые файлы:**
- `apps/security-service/src/main/resources/application.yml` - убран дефолт JWT_SECRET
- `apps/api-gateway/src/main/resources/application.yaml` - убран дефолт JWT_SECRET
- Все deployments в `k8s/base/*/deployment.yaml` - добавлен `envFrom: secretRef: jarvis-secrets`

### 2. P2 ImagePullPolicy (критично)
- ✅ `embedding-service`: вернул `IfNotPresent` (было `Never`)
- ✅ Добавлен `minikube image load` после успешной сборки embedding-service
- ✅ Гарантируется, что сборка происходит в minikube docker-env ПЕРЕД применением манифестов

**Изменённые файлы:**
- `k8s/overlays/local/embedding-service.yaml` - imagePullPolicy: IfNotPresent
- `jarvis-launch.sh` - добавлен `minikube image load` после сборки LLM образов

### 3. "Нет dev" (обязательно)
- ✅ Убраны все ссылки на `k8s/dev/*` из `jarvis-launch.sh`
- ✅ Заменено на `k8s/base/` + `k8s/overlays/local/`
- ✅ Исправлены namespace: все сервисы в `jarvis` (было jarvis-core, jarvis-llm, jarvis-data)

**Изменённые файлы:**
- `jarvis-launch.sh` - убраны `k8s/dev/postgres/`, `k8s/dev/rabbitmq/`, `k8s/dev/services/`, `k8s/dev/ingress.yaml`
- Все deployments - namespace исправлен на `jarvis`
- Все service URLs - исправлены на `jarvis.svc.cluster.local`

### 4. Дополнительные исправления
- ✅ Исправлены порты в security-service (8088 вместо 8085)
- ✅ Исправлены service URLs в api-gateway (jarvis.svc.cluster.local)

---

## 📝 Изменённые файлы (полный список)

1. `apps/security-service/src/main/resources/application.yml` - убран дефолт JWT_SECRET
2. `apps/api-gateway/src/main/resources/application.yaml` - убран дефолт JWT_SECRET
3. `k8s/base/security-service/deployment.yaml` - envFrom, namespace, порты
4. `k8s/base/api-gateway/deployment.yaml` - envFrom, namespace, service URLs
5. `k8s/base/life-tracker/deployment.yaml` - envFrom, namespace, service URLs
6. `k8s/base/user-profile/deployment.yaml` - envFrom, namespace, service URLs
7. `k8s/base/planner-service/deployment.yaml` - envFrom, namespace, service URLs
8. `k8s/base/memory-service/deployment.yaml` - envFrom, namespace, service URLs
9. `k8s/base/postgres/statefulset.yaml` - namespace
10. `k8s/overlays/local/embedding-service.yaml` - imagePullPolicy: IfNotPresent
11. `jarvis-launch.sh` - убраны k8s/dev, добавлен minikube image load

---

## 🔍 Как проверить

### 1. Проверка secrets в deployments:
```bash
kubectl -n jarvis describe deploy/security-service | sed -n '/Environment:/,/Mounts:/p'
# Должно показать:
#   Environment Variables from:
#     jarvis-secrets  Secret  Optional: false
#   SPRING_DATASOURCE_USERNAME:  <set to the key 'SPRING_DATASOURCE_USERNAME' in secret 'jarvis-secrets'>
#   SPRING_DATASOURCE_PASSWORD:  <set to the key 'SPRING_DATASOURCE_PASSWORD' in secret 'jarvis-secrets'>
#   JWT_SECRET:  <set to the key 'JWT_SECRET' in secret 'jarvis-secrets'>

kubectl -n jarvis describe deploy/api-gateway | sed -n '/Environment:/,/Mounts:/p'
# Должно показать envFrom: secretRef: jarvis-secrets
```

### 2. Проверка ImagePullBackOff / ErrImageNeverPull:
```bash
kubectl get pods -n jarvis
# Ожидаемый результат: НЕТ ImagePullBackOff или ErrImageNeverPull
# Все поды должны быть Running или Pending (но не с ImagePullBackOff)

# Проверка конкретных подов:
kubectl get pods -n jarvis | grep -E "embedding-service|postgres-pgvector"
# Ожидаемый результат: Running (не ImagePullBackOff)

# Если есть проблемы, проверить события:
kubectl describe pod -n jarvis -l app=embedding-service | tail -n 20
kubectl describe pod -n jarvis -l app=postgres-pgvector | tail -n 20
```

### 3. Проверка login/register (НЕ должно быть 500):
```bash
# Проверка login
curl -i -X POST "http://localhost:8080/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'

# Ожидаемый результат:
# - 200 OK (если пользователь существует и пароль верный)
# - 401 Unauthorized (неверные credentials)
# - НЕ должно быть 500 INTERNAL_ERROR

# Проверка register
curl -i -X POST "http://localhost:8080/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass","role":"USER"}'

# Ожидаемый результат:
# - 200/201 (успех)
# - 409 (пользователь уже существует)
# - НЕ должно быть 500 INTERNAL_ERROR

# Если есть 500, проверить логи:
kubectl logs -n jarvis -l app=security-service --tail=50 | grep -i error
kubectl logs -n jarvis -l app=api-gateway --tail=50 | grep -i auth
```

### 4. Проверка отсутствия dev в запуске:
```bash
# Проверка, что jarvis-launch.sh не использует k8s/dev
grep -n "k8s/dev" jarvis-launch.sh
# Ожидаемый результат: НЕТ строк с k8s/dev (или только в комментариях)

# Проверка, что применяются правильные манифесты:
# При запуске jarvis-launch.sh должны применяться:
# - k8s/base/
# - k8s/overlays/local/
# НЕ должно быть k8s/dev/
```

### 5. Проверка fail-fast (если secrets нет):
```bash
# Удалить secret (для теста)
kubectl delete secret jarvis-secrets -n jarvis

# Попытаться запустить security-service
kubectl apply -f k8s/base/security-service/deployment.yaml

# Проверить логи пода
kubectl logs -n jarvis -l app=security-service --tail=50
# Ожидаемый результат: ошибка о недостающих переменных окружения
# Spring Boot должен упасть с понятной ошибкой, НЕ INTERNAL_ERROR

# Восстановить secret
kubectl apply -f k8s/base/secrets.yaml
```

---

## ✅ Ожидаемый результат

После всех проверок:
- ✅ 0 ImagePullBackOff / ErrImageNeverPull
- ✅ login/register != 500 (200/401/409 - OK)
- ✅ Все deployments используют `envFrom: secretRef: jarvis-secrets`
- ✅ Нет ссылок на `k8s/dev/*` в активном запуске
- ✅ Все namespace = `jarvis`

---

## ⚠️ Важные замечания

1. **Secrets должны существовать:** Перед запуском убедитесь, что `kubectl apply -f k8s/base/secrets.yaml` выполнен
2. **Minikube docker-env:** Образы должны собираться в minikube docker-env (уже есть в jarvis-launch.sh)
3. **Namespace:** Все сервисы теперь в namespace `jarvis` (было несколько namespace)

---

## 📋 Следующие шаги

После подтверждения, что Iteration 1.1 работает:
- Iteration 2: Секреты без хардкода (генерация при первом запуске)
- Iteration 3: GUI launcher без терминала

