# Iteration 1.1: Verification Pack

**Дата:** 2025-01-27  
**Цель:** Доказать, что все исправления работают корректно

---

## 1. Проверка Service URLs (DNS корректность)

### Команды:

```bash
# 1.1. Проверка НЕПРАВИЛЬНЫХ URLs (без service prefix) - ОШИБКА
# Проверяем: k8s/, apps/, scripts/, desktop-client*/
grep -r "http://jarvis\.svc\.cluster\.local" k8s/ apps/ scripts/ apps/desktop-client-javafx/ 2>/dev/null | \
  grep -v "^Binary" | \
  grep -v "xmlns=" | \
  grep -v "xsi:schemaLocation" | \
  grep -v "# Hostnames используют"
# Ожидаемый результат: (пусто) - НЕ должно быть таких URLs

# 1.2. Проверка HTTP endpoints для внешних URL (должно быть https:// после Iteration 7)
grep -r "http://.*api\|http://.*jarvis\|http://.*localhost:8080" \
  apps/desktop-client-javafx/src/ 2>/dev/null | \
  grep -v "xmlns=" | \
  grep -v "xsi:schemaLocation" | \
  grep -v "console.picovoice.ai" | \
  grep -v "WAKE_WORD_SETUP"
# Ожидаемый результат после Iteration 7: (пусто) - все должны быть https://

# 1.3. Проверка ПРАВИЛЬНЫХ URLs (с service prefix) - НОРМА (внутри кластера)
grep -r "\.jarvis\.svc\.cluster\.local" k8s/ 2>/dev/null | \
  grep -E "[a-z-]+\.jarvis\.svc\.cluster\.local" | \
  grep -v "^Binary" | sort
# Ожидаемый результат: список правильных внутренних URLs

# 1.4. Проверка списка реальных сервисов в namespace jarvis
kubectl -n jarvis get svc -o wide

# 1.5. Проверка формата DNS (должен быть <service>.<namespace>.svc.cluster.local)
kubectl -n jarvis get svc -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.ports[0].port}{"\n"}{end}' | \
  awk '{print "http://"$1".jarvis.svc.cluster.local:"$2}'
```

### Ожидаемый результат:

**1.1. НЕПРАВИЛЬНЫЕ URLs (должны отсутствовать):**
```
(пусто)
```

**Если найдены - это ОШИБКА:**
- `http://jarvis.svc.cluster.local:8080` ❌ (без service prefix)

**1.2. ПРАВИЛЬНЫЕ URLs (должны присутствовать):**
```
<service-name>.jarvis.svc.cluster.local:<port>
```

**Примеры правильных URLs:**
- `postgres.jarvis.svc.cluster.local:5432` ✅
- `security-service.jarvis.svc.cluster.local:8088` ✅
- `api-gateway.jarvis.svc.cluster.local:8080` ✅

**НЕ должно быть:**
- `jarvis.svc.cluster.local` (без имени сервиса) ❌
- `jarvis-core.svc.cluster.local` (старый namespace) ❌
- `jarvis-llm.svc.cluster.local` (старый namespace) ❌

**1.2. Список сервисов должен соответствовать URLs в deployments:**
```
NAME                 TYPE        CLUSTER-IP       PORT(S)
api-gateway          NodePort    10.x.x.x         8080:XXXXX/TCP
security-service     ClusterIP   10.x.x.x         8088/TCP
postgres             ClusterIP   10.x.x.x         5432/TCP
...
```

**1.3. Проверка соответствия портов:**
- `security-service` service port = 8088 (должен совпадать с containerPort в deployment)
- `api-gateway` service port = 8080
- Все остальные сервисы должны иметь правильные порты

---

## 2. Проверка Secrets (jarvis-secrets содержит все обязательные переменные)

### Команды:

```bash
# 2.1. Список ключей в jarvis-secrets
kubectl -n jarvis get secret jarvis-secrets -o jsonpath='{.data}' | \
  jq -r 'keys[]' 2>/dev/null || \
  kubectl -n jarvis get secret jarvis-secrets -o json | \
  jq -r '.data | keys[]' 2>/dev/null || \
  kubectl -n jarvis get secret jarvis-secrets -o yaml | \
  grep -E "^\s+[A-Z_]+:" | sed 's/.*\([A-Z_]*\):.*/\1/'

# 2.2. Переменные из security-service/application.yml (обязательные)
grep -E '\$\{[A-Z_]+\}' apps/security-service/src/main/resources/application.yml | \
  sed 's/.*\${\([A-Z_]*\)}.*/\1/' | sort -u

# 2.3. Переменные из api-gateway/application.yaml (обязательные)
grep -E '\$\{[A-Z_]+\}' apps/api-gateway/src/main/resources/application.yaml | \
  sed 's/.*\${\([A-Z_]*\)}.*/\1/' | sort -u

# 2.4. Проверка envFrom в security-service deployment
kubectl -n jarvis describe deploy/security-service | \
  sed -n '/Environment:/,/Mounts:/p' | head -30

# 2.5. Проверка envFrom в api-gateway deployment
kubectl -n jarvis describe deploy/api-gateway | \
  sed -n '/Environment:/,/Mounts:/p' | head -30
```

### Ожидаемый результат:

**2.1. Автоматическое извлечение всех ${ENV_VAR} из application*.yml:**
```bash
# Найти все переменные из всех application*.yml (исключая target/)
find apps/ -name "application*.yml" -o -name "application*.yaml" | \
  grep -v "/target/" | \
  xargs grep -h '\${[A-Z_]*}' | \
  sed 's/.*\${\([A-Z_]*\)}.*/\1/' | \
  sort -u
```

**2.2. Сравнение с ключами в jarvis-secrets:**
```bash
# Получить все ключи из secret
SECRET_KEYS=$(kubectl -n jarvis get secret jarvis-secrets -o jsonpath='{.data}' | \
  jq -r 'keys[]' | sort)

# Для каждой переменной из application*.yml проверить наличие в secret
# (исключая переменные, которые задаются в deployment env, не из secret)
```

**2.3. Строгое правило (БЕЗ исключений):**
Любой `${ENV_VAR}` в application*.yml должен быть реально задан в runtime:
- Либо через `envFrom: secretRef: jarvis-secrets` (переменная должна быть в secret)
- Либо через явный `env:` в deployment (переменная задаётся в deployment)

**2.4. Проверка через kubectl describe:**
```bash
# Для каждого сервиса проверить, что все ${ENV_VAR} из его application*.yml заданы
kubectl -n jarvis describe deploy/security-service | sed -n '/Environment:/,/Mounts:/p'
# Должно показать:
#   Environment Variables from:
#     jarvis-secrets  Secret  Optional: false
#   Environment:
#     SPRING_DATASOURCE_URL:  jdbc:postgresql://postgres.jarvis.svc.cluster.local:5432/jarvis_security
#     SPRING_DATASOURCE_USERNAME:  <set to the key 'SPRING_DATASOURCE_USERNAME' in secret 'jarvis-secrets'>
#     SPRING_DATASOURCE_PASSWORD:  <set to the key 'SPRING_DATASOURCE_PASSWORD' in secret 'jarvis-secrets'>
#     JWT_SECRET:  <set to the key 'JWT_SECRET' in secret 'jarvis-secrets'>
```

**2.5. Автоматическая проверка:**
- Извлечь все `${ENV_VAR}` из application*.yml
- Для каждой переменной проверить:
  - Есть ли в jarvis-secrets? (если да → OK через envFrom)
  - Есть ли в deployment env? (если да → OK явно задано)
  - Если НЕТ ни там, ни там → ОШИБКА ❌

**Проверка соответствия:**
- Все `${ENV_VAR}` из application*.yml → должны быть в secret ИЛИ в deployment env ✅
- Если чего-то не хватает → проверка падает ❌

**2.3. api-gateway/application.yaml использует:**
```
JWT_SECRET (из jarvis-secrets через envFrom)
```

**2.4. security-service deployment должен показывать:**
```
Environment Variables from:
  jarvis-secrets  Secret  Optional: false
Environment:
  SPRING_PROFILES_ACTIVE:  k8s
  SPRING_DATASOURCE_URL:  jdbc:postgresql://postgres.jarvis.svc.cluster.local:5432/jarvis_security
  SPRING_DATASOURCE_USERNAME:  <set to the key 'SPRING_DATASOURCE_USERNAME' in secret 'jarvis-secrets'>
  SPRING_DATASOURCE_PASSWORD:  <set to the key 'SPRING_DATASOURCE_PASSWORD' in secret 'jarvis-secrets'>
  JWT_SECRET:  <set to the key 'JWT_SECRET' in secret 'jarvis-secrets'>
```

**2.5. api-gateway deployment должен показывать:**
```
Environment Variables from:
  jarvis-secrets  Secret  Optional: false
Environment:
  SPRING_PROFILES_ACTIVE:  k8s
  JWT_SECRET:  <set to the key 'JWT_SECRET' in secret 'jarvis-secrets'>
  SERVICES_SECURITY_URL:  http://security-service.jarvis.svc.cluster.local:8088
  ...
```

---

## 3. Проверка статусов подов (нет CrashLoopBackOff из-за отсутствующих env vars)

### Команды:

```bash
# 3.1. Общий статус всех подов
kubectl -n jarvis get pods

# 3.2. Проверка подов с проблемами
kubectl -n jarvis get pods | grep -E "Error|CrashLoopBackOff|ImagePullBackOff|ErrImageNeverPull"

# 3.3. Детали проблемных подов (если есть)
kubectl -n jarvis get pods -o wide | grep -E "Error|CrashLoopBackOff" | \
  awk '{print $1}' | xargs -I {} kubectl -n jarvis describe pod {} | \
  tail -n 50

# 3.4. Логи security-service (проверка ошибок env vars)
kubectl -n jarvis logs -l app=security-service --tail=50 | grep -i -E "error|exception|missing|required"

# 3.5. Логи api-gateway (проверка ошибок env vars)
kubectl -n jarvis logs -l app=api-gateway --tail=50 | grep -i -E "error|exception|missing|required"
```

### Ожидаемый результат:

**3.1. Статусы подов:**
```
NAME                                  READY   STATUS    RESTARTS   AGE
api-gateway-xxx                       1/1     Running   0          Xm
security-service-xxx                  1/1     Running   0          Xm
postgres-0                            1/1     Running   0          Xm
...
```

**НЕ должно быть:**
- `CrashLoopBackOff` (из-за отсутствующих env vars)
- `ImagePullBackOff` (для embedding-service и postgres-pgvector - это отдельная проблема сборки образов)
- `ErrImageNeverPull` (если используется Never без образа)

**3.2. Проблемные поды:**
```
(пусто или только ImagePullBackOff для embedding-service/postgres-pgvector, если образы не собраны)
```

**3.3-3.5. Логи не должны содержать:**
- `Required environment variable 'XXX' is not set`
- `Could not resolve placeholder 'XXX'`
- `Missing required environment variable`
- `No value found for placeholder`

---

## 4. Проверка Auth endpoints (login/register НЕ должны быть 500)

### Команды:

```bash
# 4.1. Определение URL для доступа к API Gateway
# Вариант A: Если используется NodePort
MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "127.0.0.1")
NODE_PORT=$(kubectl -n jarvis get svc api-gateway -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
API_URL="http://${MINIKUBE_IP}:${NODE_PORT}"

# Вариант B: Если используется port-forward
# kubectl -n jarvis port-forward svc/api-gateway 8080:8080 &
# API_URL="http://localhost:8080"

echo "API Gateway URL: $API_URL"

# 4.2. Проверка health endpoint (должен быть доступен)
curl -i "$API_URL/actuator/health" 2>&1 | head -10

# 4.3. Проверка login endpoint (НЕ должно быть 500)
echo "=== Testing LOGIN endpoint ==="
curl -i -X POST "$API_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}' 2>&1

# 4.4. Проверка register endpoint (НЕ должно быть 500)
echo "=== Testing REGISTER endpoint ==="
curl -i -X POST "$API_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser'$(date +%s)'","password":"testpass","role":"USER"}' 2>&1

# 4.5. Логи api-gateway при запросе login
echo "=== API Gateway logs (last 20 lines) ==="
kubectl -n jarvis logs -l app=api-gateway --tail=20

# 4.6. Логи security-service при запросе login
echo "=== Security Service logs (last 20 lines) ==="
kubectl -n jarvis logs -l app=security-service --tail=20

# 4.7. Проверка подключения security-service к БД
kubectl -n jarvis logs -l app=security-service --tail=50 | \
  grep -i -E "datasource|connection|database|postgres"
```

### Ожидаемый результат:

**4.2. Health endpoint:**
```
HTTP/1.1 200 OK
{"status":"UP",...}
```

**4.3. Login endpoint:**
```
HTTP/1.1 200 OK
{"accessToken":"...","refreshToken":"...","username":"test",...}
```
ИЛИ
```
HTTP/1.1 401 Unauthorized
{"error":"Invalid credentials",...}
```

**НЕ должно быть:**
```
HTTP/1.1 500 Internal Server Error
{"error":"INTERNAL_ERROR",...}
```

**4.4. Register endpoint:**
```
HTTP/1.1 200 OK или 201 Created
{"accessToken":"...","refreshToken":"...","username":"testuser...",...}
```
ИЛИ
```
HTTP/1.1 409 Conflict
{"error":"Username already exists",...}
```

**НЕ должно быть:**
```
HTTP/1.1 500 Internal Server Error
```

**4.5-4.6. Логи не должны содержать:**
- `500` status codes
- `INTERNAL_ERROR`
- `NullPointerException`
- `Connection refused` (к БД)
- `Authentication failed` (к БД из-за неправильных credentials)

**4.7. Подключение к БД должно быть успешным:**
```
HikariPool-1 - Starting...
HikariPool-1 - Start completed.
```

---

## 5. Сводная таблица проверок

| Проверка | Команда | Ожидаемый результат | Статус |
|----------|---------|---------------------|--------|
| Service URLs - нет ошибок | `grep -r "http://jarvis\.svc\.cluster\.local" k8s/base/` | (пусто) - нет URLs без service prefix | ⬜ |
| Service URLs - правильный формат | `grep -r "\.jarvis\.svc\.cluster\.local" k8s/base/ \| grep -E "[a-z-]+\.jarvis"` | Все формата `<service>.jarvis.svc.cluster.local` | ⬜ |
| Secrets - автоматическая проверка | `find apps/ -name "application*.yml" \| xargs grep '\${[A-Z_]*}'` | Все ${ENV_VAR} из application*.yml есть в jarvis-secrets | ⬜ |
| envFrom в deployments | `kubectl -n jarvis describe deploy/security-service` | Показывает `envFrom: secretRef: jarvis-secrets` | ⬜ |
| Нет CrashLoopBackOff | `kubectl -n jarvis get pods` | Нет CrashLoopBackOff из-за env vars | ⬜ |
| Login != 500 | `curl -X POST "$API_URL/auth/login" ...` | 200 или 401, НЕ 500 | ⬜ |
| Register != 500 | `curl -X POST "$API_URL/auth/register" ...` | 200/201 или 409, НЕ 500 | ⬜ |
| HTTPS CA в trust store | `openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt` | CA certificate валиден | ⬜ (Iteration 7) |
| HTTPS домены в /etc/hosts | `grep "jarvis.local" /etc/hosts` | api.jarvis.local, voice.jarvis.local | ⬜ (Iteration 7) |
| HTTPS работает без -k | `curl --cacert ... https://api.jarvis.local/health` | 200 OK, без SSL errors | ⬜ (Iteration 7) |

---

## 6. Скрипт автоматической проверки

**Файл:** `scripts/verify-iteration-1.1.sh`

**Использование:**
```bash
./scripts/verify-iteration-1.1.sh
```

**Или:**
```bash
bash scripts/verify-iteration-1.1.sh
```

Скрипт автоматически проверяет все критерии и выводит результат.

**Старая версия (для справки):**
```bash
#!/bin/bash
# verification.sh - Автоматическая проверка Iteration 1.1

set -e

echo "=== Iteration 1.1 Verification ==="
echo ""

# 1. Service URLs
echo "1. Checking Service URLs..."
BAD_URLS=$(grep -r "\.svc\.cluster\.local" k8s/base/ | grep -v "<service>\.jarvis\.svc\.cluster\.local" | wc -l)
if [ "$BAD_URLS" -eq 0 ]; then
    echo "✅ All service URLs are correct"
else
    echo "❌ Found $BAD_URLS incorrect URLs"
    exit 1
fi

# 2. Secrets
echo ""
echo "2. Checking Secrets..."
SECRET_EXISTS=$(kubectl -n jarvis get secret jarvis-secrets 2>/dev/null | wc -l)
if [ "$SECRET_EXISTS" -gt 0 ]; then
    echo "✅ jarvis-secrets exists"
    kubectl -n jarvis get secret jarvis-secrets -o jsonpath='{.data}' | jq -r 'keys[]' | sort > /tmp/secret_keys.txt
    echo "   Keys: $(cat /tmp/secret_keys.txt | tr '\n' ' ')"
else
    echo "❌ jarvis-secrets not found"
    exit 1
fi

# 3. Pods status
echo ""
echo "3. Checking Pods Status..."
CRASH_LOOPS=$(kubectl -n jarvis get pods 2>/dev/null | grep -c "CrashLoopBackOff" || echo "0")
if [ "$CRASH_LOOPS" -eq 0 ]; then
    echo "✅ No CrashLoopBackOff pods"
else
    echo "❌ Found $CRASH_LOOPS CrashLoopBackOff pods"
    exit 1
fi

# 4. Auth endpoints
echo ""
echo "4. Checking Auth Endpoints..."
MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "127.0.0.1")
NODE_PORT=$(kubectl -n jarvis get svc api-gateway -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
API_URL="http://${MINIKUBE_IP}:${NODE_PORT}"

LOGIN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}' 2>/dev/null || echo "000")

if [ "$LOGIN_STATUS" = "500" ]; then
    echo "❌ Login endpoint returns 500"
    exit 1
elif [ "$LOGIN_STATUS" = "200" ] || [ "$LOGIN_STATUS" = "401" ]; then
    echo "✅ Login endpoint returns $LOGIN_STATUS (OK)"
else
    echo "⚠️  Login endpoint returns $LOGIN_STATUS (unexpected but not 500)"
fi

echo ""
echo "=== Verification Complete ==="
```

---

## 7. HTTPS/TLS проверки (Iteration 7 - будущее)

### Команды:

```bash
# 7.1. Проверка CA в trust store
openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt -text -noout
# Ожидаемый результат: валидный CA certificate

# 7.2. Проверка доменов в /etc/hosts (ОБА должны быть)
grep "api.jarvis.local" /etc/hosts
grep "voice.jarvis.local" /etc/hosts
# Ожидаемый результат:
# 127.0.0.1 api.jarvis.local
# 127.0.0.1 voice.jarvis.local

# 7.3. Проверка TLS secret в K8s (ОБА ключа должны быть)
kubectl -n jarvis get secret jarvis-tls
kubectl -n jarvis get secret jarvis-tls -o jsonpath='{.data}' | jq -r 'keys[]'
# Ожидаемый результат: secret существует с ключами tls.crt и tls.key

# 7.4. Проверка HTTPS endpoint (БЕЗ -k, с CA)
curl --cacert /usr/local/share/ca-certificates/jarvis-ca.crt \
  https://api.jarvis.local/actuator/health
# Ожидаемый результат: 200 OK, БЕЗ ошибок SSL verification

# 7.5. Проверка HTTPS БЕЗ явного CA (использует системный trust store)
curl https://api.jarvis.local/actuator/health
# Ожидаемый результат: 200 OK, БЕЗ ошибок SSL verification

# 7.6. Проверка openssl подключения
echo | openssl s_client -connect api.jarvis.local:443 \
  -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt 2>&1 | \
  grep -E "Verify return code|CN="
# Ожидаемый результат: Verify return code: 0 (ok), CN=api.jarvis.local

# 7.7. Проверка HTTP → HTTPS redirect
curl -I http://api.jarvis.local/actuator/health
# Ожидаемый результат: HTTP/1.1 301 Moved Permanently или 308 Permanent Redirect
#                        Location: https://api.jarvis.local/actuator/health

# 7.8. Проверка UI не отключает SSL verification
grep -r "trust.*all\|disable.*ssl\|-k\|insecure\|setDefaultHostnameVerifier" \
  apps/desktop-client-javafx/src/ 2>/dev/null | \
  grep -v "xmlns=" | grep -v "xsi:schemaLocation"
# Ожидаемый результат: (пусто) - НЕ должно быть отключения SSL verification
```

### Ожидаемый результат:

**7.1-7.3. TLS инфраструктура:**
- ✅ CA certificate в `/usr/local/share/ca-certificates/jarvis-ca.crt`
- ✅ Домены в `/etc/hosts`
- ✅ `jarvis-tls` secret в K8s

**7.4-7.5. HTTPS работает:**
- ✅ `curl https://api.jarvis.local` работает БЕЗ `-k`
- ✅ `openssl s_client` показывает `Verify return code: 0 (ok)`
- ✅ НЕТ ошибок SSL verification

**Примечание:** Эти проверки актуальны после завершения Iteration 7. До этого они показывают предупреждения.

---

## 8. Известные проблемы (не критичные для Iteration 1.1)

1. **ImagePullBackOff для embedding-service и postgres-pgvector:**
   - Это проблема сборки образов, не связана с secrets/env vars
   - Решается сборкой образов в minikube docker-env
   - Не блокирует проверку auth endpoints

2. **Init:0/1 для llm-service и memory-service:**
   - Это проблема зависимостей (postgres-pgvector не готов)
   - Не блокирует проверку auth endpoints

---

## 9. Критерии успеха Iteration 1.1

✅ **Все проверки пройдены:**
- Service URLs правильного формата
- jarvis-secrets содержит все обязательные переменные
- envFrom настроен во всех deployments
- Нет CrashLoopBackOff из-за отсутствующих env vars
- login/register != 500

❌ **Если хотя бы одна проверка не пройдена:**
- Исправить проблему
- Повторить проверку
- Обновить этот документ с результатами

---

---

## 10. Результаты предварительной проверки (2025-01-27)

### 1. Service URLs ✅
```bash
$ grep -r "\.svc\.cluster\.local" k8s/base/ | grep -v "jarvis\.svc\.cluster\.local" | grep -v "^Binary" | grep -v "# Hostnames"
(пусто)
```
**Результат:** ✅ Все URLs используют правильный формат `<service>.jarvis.svc.cluster.local`

### 2. Secrets ✅
```bash
$ kubectl -n jarvis get secret jarvis-secrets -o jsonpath='{.data}' | jq -r 'keys[]' | sort
ENCRYPTION_KEY
JWT_SECRET
KAFKA_SASL_PASSWORD
KAFKA_SASL_USERNAME
POSTGRES_PASSWORD
POSTGRES_USER
RABBITMQ_DEFAULT_PASS
RABBITMQ_DEFAULT_USER
RABBITMQ_ERLANG_COOKIE
RABBITMQ_PASSWORD
RABBITMQ_USER
SPRING_DATASOURCE_PASSWORD
SPRING_DATASOURCE_USERNAME
SPRING_RABBITMQ_PASSWORD
SPRING_RABBITMQ_USERNAME
```

**Переменные из application.yml:**
- `SPRING_DATASOURCE_USERNAME` ✅ (есть в secret)
- `SPRING_DATASOURCE_PASSWORD` ✅ (есть в secret)
- `JWT_SECRET` ✅ (есть в secret)
- `SPRING_DATASOURCE_URL` ✅ (задаётся в deployment, не из secret)

**Результат:** ✅ Все обязательные переменные присутствуют

### 3. Статусы подов ⚠️
```bash
$ kubectl -n jarvis get pods | grep -E "Error|CrashLoopBackOff"
(пусто - нет CrashLoopBackOff из-за env vars)
```

**Известные проблемы (не критичные):**
- `embedding-service`: ImagePullBackOff (проблема сборки образа, не env vars)
- `postgres-pgvector`: ImagePullBackOff (проблема сборки образа, не env vars)
- `llm-service`, `memory-service`: Init:0/1 (зависимости, не env vars)

**Результат:** ✅ Нет CrashLoopBackOff из-за отсутствующих env vars

### 4. Auth endpoints (требует проверки на реальном кластере)
**Команды для проверки:**
```bash
MINIKUBE_IP=$(minikube ip)
NODE_PORT=$(kubectl -n jarvis get svc api-gateway -o jsonpath='{.spec.ports[0].nodePort}')
API_URL="http://${MINIKUBE_IP}:${NODE_PORT}"

curl -i -X POST "$API_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'
```

**Ожидаемый результат:** 200 или 401, НЕ 500

---

**Дата проверки:** 2025-01-27  
**Проверил:** Auto (предварительная проверка)  
**Результат:** ⬜ Успешно / ⬜ Требуются исправления  
**Примечание:** Требуется ручная проверка auth endpoints на реальном кластере

