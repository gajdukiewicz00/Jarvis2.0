# Iteration 1.5 (Stage 7): Acceptance Run Results

**Дата:** 2026-01-05  
**Статус:** ⚠️ Требует ручной настройки (sudo)

---

## Выполненные шаги

### ✅ 0) Minikube Ingress Addon
- Ingress addon включён
- Ingress controller pods запущены

### ✅ 1) Ingress Applied
- Старый ingress (jarvis.local) удалён
- Новый ingress (api.jarvis.local, voice.jarvis.local) применён
- Ingress controller готов
- **Исправление:** Убрана annotation `configuration-snippet` (запрещена ingress administrator)

### ⚠️ 2) /etc/hosts + CA Trust Store
**Проблема:** Требует sudo, не может быть выполнено автоматически

**Решение (выполнить вручную):**
```bash
# 1. Получить minikube IP
MINIKUBE_IP=$(minikube ip)

# 2. Добавить в /etc/hosts
echo "$MINIKUBE_IP api.jarvis.local" | sudo tee -a /etc/hosts
echo "$MINIKUBE_IP voice.jarvis.local" | sudo tee -a /etc/hosts

# 3. Установить CA в trust store
sudo ./scripts/product/jarvis-install-tls.sh
```

---

## Результаты проверок (после ручной настройки)

### Ожидаемые результаты после выполнения sudo команд:

**3) Strict TLS Check:**
```bash
curl -i https://api.jarvis.local/actuator/health
# Expected: HTTP 200, JSON с "status":"UP"
```

**4) SSL Verification:**
```bash
echo | openssl s_client -connect api.jarvis.local:443 -servername api.jarvis.local 2>/dev/null | grep "Verify return code"
# Expected: Verify return code: 0 (ok)
```

**5) HTTP → HTTPS Redirect:**
```bash
curl -I http://api.jarvis.local/actuator/health
# Expected: HTTP 301/308, Location: https://...
```

**6) Full Verify:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
# Expected: 0
```

---

## Типовые проблемы и решения

### 1) Ingress "есть", но 404/timeout

**Проверка:**
```bash
# Ingress controller pods
kubectl -n ingress-nginx get pods

# API Gateway service
kubectl -n jarvis get svc api-gateway -o wide

# Ingress details
kubectl -n jarvis describe ingress jarvis-ingress
```

**Решение:**
- Убедиться, что ingress controller pods в статусе Running
- Проверить, что serviceName и port в ingress совпадают с реальным service
- Проверить, что api-gateway pods запущены

---

### 2) TLS есть, но curl ругается на сертификат

**Проверка:**
```bash
# CA в trust store
ls -la /usr/local/share/ca-certificates/jarvis-ca.crt

# Обновить trust store
sudo update-ca-certificates

# Проверить сертификат
openssl x509 -in docker/certs/jarvis.crt -text -noout | grep -A 5 "Subject Alternative Name"
```

**Решение:**
- Убедиться, что CA установлен: `ls -la /usr/local/share/ca-certificates/jarvis-ca.crt`
- Обновить trust store: `sudo update-ca-certificates`
- Перезапустить приложения (JavaFX использует системный trust store)

---

### 3) WebSocket не работает через voice.jarvis.local

**Проверка:**
```bash
# Проверить ingress routing
kubectl -n jarvis describe ingress jarvis-ingress | grep -A 10 "voice.jarvis.local"

# Проверить WebSocket endpoint
curl -i https://voice.jarvis.local/ws/voice
```

**Решение:**
- Убедиться, что ingress имеет правильный path routing для `/ws`
- Проверить, что nginx annotations для WebSocket присутствуют
- Проверить, что api-gateway поддерживает WebSocket

---

## Финальный статус

**✅ Реализовано:**
- Ingress для api.jarvis.local и voice.jarvis.local
- TLS secret создаётся автоматически
- Скрипты для /etc/hosts и CA trust store
- Launcher/Desktop HTTPS support
- Verify script с --require-https

**⚠️ Требует ручного выполнения:**
- `/etc/hosts` setup (sudo)
- CA trust store installation (sudo)

**После выполнения sudo команд:**
- Все проверки должны пройти
- `verify --require-https` должен вернуть 0

---

**Iteration 1.5 (Stage 7) готов к финальной проверке после ручной настройки!**

