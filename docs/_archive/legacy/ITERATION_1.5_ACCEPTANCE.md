# Iteration 1.5 (Stage 7): HTTPS/TLS Acceptance

**Дата:** 2026-01-05  
**Статус:** ✅ Готов к acceptance run

---

## Pre-requisites

1. **Backend запущен:**
   ```bash
   kubectl get pods -n jarvis
   # Должно быть несколько pods
   ```

2. **Ingress controller активен:**
   ```bash
   kubectl get pods -n ingress-nginx
   # Или другой namespace для ingress controller
   ```

3. **Product install выполнен:**
   ```bash
   ./scripts/product/jarvis-install.sh
   ```

---

## Acceptance Scenarios

### A) Certificate Generation

**Цель:** Убедиться, что сертификаты генерируются с правильными доменами.

**Шаги:**
1. Удалить старые сертификаты (если есть):
   ```bash
   rm -f docker/certs/jarvis.crt docker/certs/jarvis.key
   ```
2. Запустить `jarvis-launch.sh` (или только генерацию сертификатов)
3. Проверить сертификат:
   ```bash
   openssl x509 -in docker/certs/jarvis.crt -text -noout | grep -A 5 "Subject Alternative Name"
   ```

**Expected Output:**
- ✅ CN: `api.jarvis.local`
- ✅ SAN содержит: `api.jarvis.local`, `voice.jarvis.local`

**Что делать при failure:**
- Проверить, что `jarvis-launch.sh` обновлён для генерации с новыми доменами
- Проверить права на `docker/certs/`

---

### B) TLS Secret Creation

**Цель:** Убедиться, что TLS secret создаётся в Kubernetes.

**Команда:**
```bash
kubectl get secret jarvis-tls -n jarvis
kubectl describe secret jarvis-tls -n jarvis
```

**Expected Output:**
- ✅ Secret существует
- ✅ Type: `kubernetes.io/tls`
- ✅ Data: `tls.crt`, `tls.key`

**Что делать при failure:**
- Проверить, что `jarvis-launch.sh` создаёт secret
- Проверить namespace: `kubectl get ns jarvis`

---

### C) Ingress Configuration

**Цель:** Убедиться, что Ingress настроен правильно.

**Команда:**
```bash
kubectl get ingress jarvis-ingress -n jarvis -o yaml
```

**Expected Output:**
- ✅ Ingress существует
- ✅ `spec.rules[].host` содержит `api.jarvis.local` и `voice.jarvis.local`
- ✅ `spec.tls[].hosts` содержит оба домена
- ✅ `spec.tls[].secretName` = `jarvis-tls`
- ✅ Annotations: `ssl-redirect: true`

**Что делать при failure:**
- Применить ingress вручную: `kubectl apply -f k8s/base/ingress.yaml`
- Проверить ingress controller: `kubectl get ingressclass`

---

### D) /etc/hosts Setup

**Цель:** Убедиться, что домены резолвятся.

**Шаги:**
1. Запустить скрипт:
   ```bash
   sudo ./scripts/product/jarvis-setup-hosts.sh
   ```
2. Проверить:
   ```bash
   grep jarvis.local /etc/hosts
   ```

**Expected Output:**
- ✅ `/etc/hosts` содержит:
   ```
   <MINIKUBE_IP> api.jarvis.local
   <MINIKUBE_IP> voice.jarvis.local
   ```

**Что делать при failure:**
- Проверить, что скрипт имеет права на выполнение: `chmod +x scripts/product/jarvis-setup-hosts.sh`
- Проверить minikube IP: `minikube ip`

---

### E) CA Trust Store Installation

**Цель:** Убедиться, что CA установлен в trust store.

**Шаги:**
1. Запустить скрипт:
   ```bash
   sudo ./scripts/product/jarvis-install-tls.sh
   ```
2. Проверить:
   ```bash
   ls -la /usr/local/share/ca-certificates/jarvis-ca.crt
   ```

**Expected Output:**
- ✅ Файл существует
- ✅ Права: `644`

**Что делать при failure:**
- Проверить, что скрипт имеет права на выполнение
- Проверить, что сертификат существует: `ls -la docker/certs/jarvis.crt`

---

### F) HTTPS Connection Test

**Цель:** Убедиться, что HTTPS работает без `-k`.

**Команда:**
```bash
curl -v https://api.jarvis.local/actuator/health
```

**Expected Output:**
- ✅ Connection successful (HTTP 200)
- ✅ SSL handshake successful
- ✅ Certificate verification passed (без `-k`)

**Что делать при failure:**
- Проверить CA trust store: `ls -la /usr/local/share/ca-certificates/jarvis-ca.crt`
- Обновить trust store: `sudo update-ca-certificates`
- Проверить ingress: `kubectl get ingress -n jarvis`

---

### G) SSL Certificate Verification

**Цель:** Убедиться, что openssl подтверждает сертификат.

**Команда:**
```bash
echo | openssl s_client -connect api.jarvis.local:443 -servername api.jarvis.local 2>&1 | grep "Verify return code"
```

**Expected Output:**
- ✅ `Verify return code: 0 (ok)`

**Что делать при failure:**
- Проверить CA trust store
- Проверить сертификат: `openssl x509 -in docker/certs/jarvis.crt -text -noout`

---

### H) WebSocket Endpoint Test

**Цель:** Убедиться, что WebSocket endpoint доступен.

**Команда:**
```bash
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Key: test" -H "Sec-WebSocket-Version: 13" https://voice.jarvis.local/ws/voice
```

**Expected Output:**
- ✅ Connection successful (HTTP 101 или 400/404, но не SSL error)

**Что делать при failure:**
- Проверить ingress annotations для WebSocket
- Проверить, что api-gateway поддерживает WebSocket

---

### I) Desktop Client TLS

**Цель:** Убедиться, что Desktop подключается по HTTPS/WSS.

**Шаги:**
1. Установить переменные окружения:
   ```bash
   export JARVIS_USE_TLS=true
   export JARVIS_API_BASE_URL=https://api.jarvis.local
   ```
2. Запустить Desktop через Launcher
3. Проверить логи:
   ```bash
   tail -f ~/.jarvis/logs/desktop.log | grep -i "connect\|tls\|ssl\|wss"
   ```

**Expected Output:**
- ✅ Desktop подключается к `https://api.jarvis.local`
- ✅ WebSocket использует `wss://voice.jarvis.local`
- ✅ Нет SSL errors

**Что делать при failure:**
- Проверить, что CA установлен в trust store
- Проверить переменные окружения
- Проверить логи desktop на SSL errors

---

### J) Launcher Health Checks (HTTPS)

**Цель:** Убедиться, что Launcher проверяет health по HTTPS.

**Шаги:**
1. Запустить Launcher
2. Установить `JARVIS_USE_TLS=true` или использовать домен `api.jarvis.local`
3. Наблюдать health checks в UI

**Expected Output:**
- ✅ Health checks успешны (READY статус)
- ✅ Нет SSL errors в логах

**Что делать при failure:**
- Проверить, что `JarvisPaths.getApiGatewayUrl()` возвращает HTTPS URL
- Проверить логи launcher: `~/.jarvis/logs/launcher.log`

---

### K) Verify Script --require-https

**Цель:** Убедиться, что verify script проверяет все компоненты TLS.

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
```

**Expected Output:**
- ✅ Exit code: 0
- ✅ Все проверки PASS:
  - /etc/hosts entries
  - Ingress configuration
  - TLS secret
  - CA trust store
  - HTTPS connection (curl)
  - SSL verification (openssl)
  - WebSocket endpoint

**Что делать при failure:**
- Проверить вывод verify script на конкретные FAIL
- Выполнить проверки вручную (см. сценарии A-J)

---

## Full Acceptance Run

**Полный прогон всех проверок:**

```bash
# 1. Pre-requisites
./scripts/product/jarvis-install.sh
./jarvis-launch.sh  # Запустить backend

# 2. TLS Setup
sudo ./scripts/product/jarvis-setup-hosts.sh
sudo ./scripts/product/jarvis-install-tls.sh

# 3. Apply Ingress
kubectl apply -f k8s/base/ingress.yaml

# 4. Verify
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo "Exit code: $?"

# 5. Test HTTPS
curl https://api.jarvis.local/actuator/health

# 6. Test SSL
echo | openssl s_client -connect api.jarvis.local:443 -servername api.jarvis.local | grep "Verify return code"

# 7. Test WebSocket
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Key: test" -H "Sec-WebSocket-Version: 13" https://voice.jarvis.local/ws/voice
```

**Expected Final Result:**
- ✅ Exit code: 0
- ✅ Все проверки PASS
- ✅ HTTPS работает без `-k`
- ✅ SSL verification: `0 (ok)`
- ✅ Desktop подключается по HTTPS/WSS

---

## Troubleshooting

### Ingress not accessible

**Симптом:** `curl https://api.jarvis.local` не подключается

**Решение:**
1. Проверить ingress controller: `kubectl get pods -n ingress-nginx`
2. Проверить ingress status: `kubectl describe ingress jarvis-ingress -n jarvis`
3. Проверить minikube IP: `minikube ip`
4. Проверить /etc/hosts: `grep jarvis.local /etc/hosts`

---

### SSL Certificate Verification Failed

**Симптом:** `curl https://api.jarvis.local` возвращает SSL error

**Решение:**
1. Проверить CA: `ls -la /usr/local/share/ca-certificates/jarvis-ca.crt`
2. Обновить trust store: `sudo update-ca-certificates`
3. Проверить сертификат: `openssl x509 -in docker/certs/jarvis.crt -text -noout`

---

### Desktop cannot connect

**Симптом:** Desktop показывает SSL error

**Решение:**
1. Проверить CA trust store
2. Проверить переменные окружения: `echo $JARVIS_USE_TLS`
3. Проверить логи: `tail -f ~/.jarvis/logs/desktop.log`

---

**Iteration 1.5 (Stage 7) готов к acceptance run!**


