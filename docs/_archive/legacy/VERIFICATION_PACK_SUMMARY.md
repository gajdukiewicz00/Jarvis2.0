# Verification Pack Summary - Iteration 1.1

**Дата:** 2025-01-27  
**Версия:** 3.0 (production-grade, с раздельными проверками и runtime валидацией)

**Usage:**
```bash
# Default mode (HTTPS checks conditional)
./scripts/verify-iteration-1.1.sh

# Strict mode (requires HTTPS/TLS fully configured)
./scripts/verify-iteration-1.1.sh --require-https
```

---

## ✅ Усиления Verification Pack

### 1. Service URLs Check (раздельная проверка)

**1.1. Desktop-client (external URLs):**
- ✅ После Iteration 7: только `https://api.jarvis.local` и `wss://voice.jarvis.local`
- ❌ Запрещены любые `http://` endpoints (кроме исключений: xmlns, console.picovoice.ai)
- ⚠️ До Iteration 7: предупреждение, но не ошибка

**1.2. K8s/Apps (internal URLs):**
- ✅ Разрешены: `http://<service>.jarvis.svc.cluster.local` (внутри кластера)
- ❌ Запрещены: `http://jarvis.svc.cluster.local` (без service prefix - "голый")
- ❌ Запрещены: внешние `http://` домены (не `.svc.cluster.local`)

---

### 2. Secrets Check (runtime валидация)

**Правило:**
Любой `${ENV_VAR}` в application*.yml должен быть реально задан в runtime:
- Либо через `envFrom: secretRef: jarvis-secrets` (переменная в secret)
- Либо через явный `env:` в deployment (переменная задаётся в deployment)

**Проверка (RUNTIME через kubectl):**
1. Извлечь все `${ENV_VAR}` из всех `application*.yml` (исключая `/target/`)
2. Для каждой переменной:
   - Проверить наличие в `jarvis-secrets` (через `kubectl get secret`)
   - **RUNTIME проверка:** `kubectl -n jarvis describe deploy/<name>` → проверить, что переменная реально присутствует в Environment
   - Fallback: проверка в YAML файлах (если kubectl недоступен)
   - Если НЕТ ни там, ни там → ОШИБКА ❌

**Дополнительно:**
- Проверка, что все deployments используют `envFrom: secretRef: jarvis-secrets`
- Runtime проверка через `kubectl describe deploy/<svc>` для подтверждения реального присутствия переменной

---

### 3. HTTPS/TLS Checks (Iteration 7) - CONDITIONAL

**Режимы:**
- **Default mode:** HTTPS checks = SKIP (если Iteration 7 не реализована)
- **Strict mode (`--require-https`):** требует полную конфигурацию HTTPS/TLS

**Определение Iteration 7:**
- CA certificate существует: `/usr/local/share/ca-certificates/jarvis-ca.crt`
- Домены в `/etc/hosts`: `api.jarvis.local`, `voice.jarvis.local`
- TLS secret существует: `kubectl -n jarvis get secret jarvis-tls`

**DoD Checklist (если Iteration 7 реализована):**
- [ ] `api.jarvis.local` в `/etc/hosts`
- [ ] `voice.jarvis.local` в `/etc/hosts`
- [ ] `jarvis-tls` secret существует в K8s (с `tls.crt` и `tls.key`)
- [ ] CA в `/usr/local/share/ca-certificates/jarvis-ca.crt`
- [ ] **Default mode:** `curl --cacert ... https://api.jarvis.local` работает
- [ ] **Strict mode:** `curl https://api.jarvis.local` работает БЕЗ `--cacert` (системный trust store)
- [ ] `openssl s_client` показывает `Verify return code: 0 (ok)`
- [ ] HTTP → HTTPS redirect работает
- [ ] Desktop client НЕ отключает SSL verification
- [ ] HTTP только для readiness/liveness внутри кластера

**Поведение:**
- Если Iteration 7 не реализована и `--require-https` не указан → SKIP
- Если Iteration 7 не реализована и `--require-https` указан → FAIL
- Если Iteration 7 реализована → выполняются все проверки

---

## 🔍 Команды проверки

### Автоматическая проверка:
```bash
./scripts/verify-iteration-1.1.sh
```

### Ручная проверка (по шагам):

#### 1. Service URLs (раздельная проверка):

**1.1. Desktop-client (external):**
```bash
# HTTP endpoints (запрещены после Iteration 7)
grep -r "http://" apps/desktop-client-javafx/src/ 2>/dev/null | \
  grep -v "xmlns=" | grep -v "xsi:schemaLocation" | grep -v "console.picovoice.ai" | grep -v "https://"
# Ожидаемый результат после Iteration 7: (пусто)

# HTTPS/WSS endpoints (разрешены)
grep -r "https://api\.jarvis\.local\|wss://voice\.jarvis\.local" \
  apps/desktop-client-javafx/src/ 2>/dev/null
# Ожидаемый результат после Iteration 7: найдены
```

**1.2. K8s/Apps (internal):**
```bash
# Ошибки: "голый" http://jarvis.svc.cluster.local (без service prefix)
grep -r "http://jarvis\.svc\.cluster\.local" k8s/ apps/ scripts/ 2>/dev/null | \
  grep -v "^Binary" | grep -v "xmlns=" | grep -v "xsi:schemaLocation"
# Ожидаемый результат: (пусто)

# Ошибки: внешние http:// домены (не .svc.cluster.local)
grep -r "http://.*\.jarvis\.local\|http://.*localhost" k8s/ apps/ scripts/ 2>/dev/null | \
  grep -v "^Binary" | grep -v "\.svc\.cluster\.local"
# Ожидаемый результат: (пусто)

# Правильные: http://<service>.jarvis.svc.cluster.local (внутри кластера)
grep -r "http://[a-z-]*\.jarvis\.svc\.cluster\.local" k8s/ apps/ 2>/dev/null | wc -l
# Ожидаемый результат: > 0
```

#### 2. Secrets (runtime валидация):
```bash
# Извлечь все ${ENV_VAR} из application*.yml
REQUIRED_VARS=$(find apps/ -name "application*.yml" -o -name "application*.yaml" | \
  grep -v "/target/" | \
  xargs grep -h '\${[A-Z_]*}' 2>/dev/null | \
  sed 's/.*\${\([A-Z_]*\)}.*/\1/' | sort -u)

# Получить ключи из secret
SECRET_KEYS=$(kubectl -n jarvis get secret jarvis-secrets -o jsonpath='{.data}' | \
  jq -r 'keys[]' 2>/dev/null | sort)

# Для каждой переменной проверить (RUNTIME через kubectl):
for var in $REQUIRED_VARS; do
  # Есть ли в secret?
  if echo "$SECRET_KEYS" | grep -q "^${var}$"; then
    echo "✅ $var in secret"
  # RUNTIME проверка через kubectl describe
  elif kubectl -n jarvis describe deploy 2>/dev/null | \
       grep -A 100 "Environment:" | \
       grep -q "^\s*${var}"; then
    echo "✅ $var in deployment env (runtime verified)"
  # Fallback: проверка в YAML
  elif grep -r "name: ${var}" k8s/base/*/deployment.yaml 2>/dev/null | grep -q "name: ${var}"; then
    echo "✅ $var in deployment env (YAML fallback)"
  else
    echo "❌ $var MISSING (not in secret and not in deployment)"
  fi
done
```

#### 3. envFrom в deployments:
```bash
# Проверить все deployments
for deploy in k8s/base/*/deployment.yaml; do
  if [ -f "$deploy" ]; then
    if grep -q "secretRef:" "$deploy" && grep -q "jarvis-secrets" "$deploy"; then
      echo "✅ $(basename $(dirname $deploy)) has envFrom"
    else
      echo "❌ $(basename $(dirname $deploy)) missing envFrom"
    fi
  fi
done
```

#### 4. HTTPS/TLS (Iteration 7):
```bash
# 4.1. Домены в /etc/hosts
grep "api.jarvis.local\|voice.jarvis.local" /etc/hosts
# Ожидаемый результат: оба домена присутствуют

# 4.2. TLS secret
kubectl -n jarvis get secret jarvis-tls -o jsonpath='{.data}' | jq -r 'keys[]'
# Ожидаемый результат: tls.crt и tls.key

# 4.3. CA в trust store
openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt -text -noout
# Ожидаемый результат: валидный certificate

# 4.4. HTTPS без -k
curl https://api.jarvis.local/actuator/health
# Ожидаемый результат: 200 OK, без SSL errors

# 4.5. openssl verification
echo | openssl s_client -connect api.jarvis.local:443 \
  -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt 2>&1 | \
  grep "Verify return code"
# Ожидаемый результат: Verify return code: 0 (ok)

# 4.6. HTTP → HTTPS redirect
curl -I http://api.jarvis.local/actuator/health
# Ожидаемый результат: 301/308 redirect на https://

# 4.7. UI не отключает SSL
grep -r "trust.*all\|disable.*ssl\|-k\|insecure\|setDefaultHostnameVerifier" \
  apps/desktop-client-javafx/src/ 2>/dev/null | \
  grep -v "xmlns=" | grep -v "xsi:schemaLocation"
# Ожидаемый результат: (пусто)
```

---

## 📊 Сводная таблица проверок

| Проверка | Область поиска | Ожидаемый результат | Критичность | Режим |
|----------|----------------|---------------------|-------------|-------|
| Service URLs - desktop-client HTTP | desktop-client*/src/ | (пусто после Iteration 7) - только https:///wss:// | Важно | Conditional |
| Service URLs - "голый" jarvis.svc | k8s/, apps/, scripts/ | (пусто) - нет `http://jarvis.svc.cluster.local` | Критично | Always |
| Service URLs - внешние http:// | k8s/, apps/, scripts/ | (пусто) - нет внешних http:// доменов | Критично | Always |
| Service URLs - правильные internal | k8s/, apps/ | > 0 - все формата `http://<service>.jarvis.svc.cluster.local` | Критично | Always |
| Secrets - runtime проверка | kubectl describe deploy | Все ${ENV_VAR} реально присутствуют в Environment | Критично | Always |
| envFrom в deployments | k8s/base/*/deployment.yaml | Все используют envFrom: secretRef | Критично | Always |
| HTTPS - определение Iteration 7 | CA, /etc/hosts, secret | Все три условия выполнены | - | Conditional |
| HTTPS - домены | /etc/hosts | api.jarvis.local, voice.jarvis.local | - | Iteration 7 |
| HTTPS - TLS secret | kubectl -n jarvis get secret jarvis-tls | tls.crt и tls.key присутствуют | - | Iteration 7 |
| HTTPS - CA | /usr/local/share/ca-certificates/ | jarvis-ca.crt валиден | - | Iteration 7 |
| HTTPS - curl (default) | curl --cacert ... https://api.jarvis.local | 200 OK | - | Iteration 7 (default) |
| HTTPS - curl (strict) | curl https://api.jarvis.local | 200 OK БЕЗ --cacert | - | Iteration 7 (--require-https) |
| HTTPS - openssl | openssl s_client | Verify return code: 0 (ok) | - | Iteration 7 |
| HTTPS - redirect | curl -I http://api.jarvis.local | 301/308 redirect | - | Iteration 7 |
| UI - SSL verification | desktop-client*/src/ | (пусто) - нет отключения | - | Iteration 7 |

---

## ✅ Критерии успеха

**Iteration 1.1:**
- ✅ 0 ошибок Service URLs (нет `http://jarvis.svc.cluster.local`)
- ✅ Все `${ENV_VAR}` из application*.yml заданы (в secret ИЛИ в deployment)
- ✅ Все deployments используют `envFrom: secretRef: jarvis-secrets`
- ✅ 0 CrashLoopBackOff из-за отсутствующих env vars
- ✅ login/register != 500

**Iteration 7 (HTTPS):**
- ✅ api.jarvis.local и voice.jarvis.local в /etc/hosts
- ✅ jarvis-tls secret с tls.crt и tls.key
- ✅ CA в trust store
- ✅ curl https://api.jarvis.local работает БЕЗ -k
- ✅ openssl verification проходит
- ✅ HTTP → HTTPS redirect
- ✅ UI не отключает SSL verification

---

**Готово к проверке!**

