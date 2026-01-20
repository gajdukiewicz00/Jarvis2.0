# Iteration 1.5 (Stage 7): HTTPS/TLS as Mandatory Product Standard

**Дата:** 2026-01-05  
**Статус:** ✅ Реализовано

---

## Цель

Реализовать HTTPS/TLS как обязательный стандарт продукта для внешних доменов:
- `api.jarvis.local` (REST API)
- `voice.jarvis.local` (WebSocket)

Обеспечить работу Launcher/Desktop в strict TLS режиме без "полу-TLS".

---

## Архитектура

```
External Client (Desktop/Launcher)
    ↓ HTTPS (api.jarvis.local:443)
    ↓ WSS (voice.jarvis.local:443)
Ingress (nginx, TLS termination)
    ↓ HTTP (внутри кластера)
    ↓
API Gateway (ClusterIP, HTTP)
    ↓
Backend Services (ClusterIP, HTTP)
```

**Важно:**
- TLS терминируется в Ingress
- Внутри кластера используется HTTP (`http://<service>.jarvis.svc.cluster.local`)
- Desktop/Launcher не отключают SSL verification
- Self-signed CA устанавливается в системный trust store

---

## Компоненты

### 1️⃣ Ingress/TLS

**Файл:** `k8s/base/ingress.yaml`

**Функциональность:**
- ✅ Ingress для `api.jarvis.local` → api-gateway:8080
- ✅ Ingress для `voice.jarvis.local` → api-gateway:8080 (WebSocket)
- ✅ TLS секрет: `jarvis-tls` (tls.crt + tls.key)
- ✅ HTTP→HTTPS redirect (nginx annotations)

**Конфигурация:**
- `ingressClassName: nginx`
- `ssl-redirect: true`
- `force-ssl-redirect: true`
- WebSocket support через annotations

---

### 2️⃣ Trust Store Installation

**Скрипт:** `scripts/product/jarvis-install-tls.sh`

**Функциональность:**
- ✅ Копирует CA сертификат в `/usr/local/share/ca-certificates/jarvis-ca.crt`
- ✅ Вызывает `update-ca-certificates`
- ✅ Требует sudo (проверка в скрипте)

**Использование:**
```bash
sudo ./scripts/product/jarvis-install-tls.sh
```

**Проверка:**
```bash
openssl s_client -connect api.jarvis.local:443 -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt < /dev/null 2>/dev/null | grep 'Verify return code'
# Expected: Verify return code: 0 (ok)
```

---

### 3️⃣ /etc/hosts Setup

**Скрипт:** `scripts/product/jarvis-setup-hosts.sh`

**Функциональность:**
- ✅ Добавляет `api.jarvis.local` и `voice.jarvis.local` в `/etc/hosts`
- ✅ Автоматически определяет IP (minikube IP или ingress IP)
- ✅ Проверяет существующие записи и обновляет при необходимости
- ✅ Требует sudo

**Использование:**
```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
```

---

### 4️⃣ Certificate Generation

**Обновление:** `jarvis-launch.sh`

**Изменения:**
- ✅ Генерация сертификата с SAN для `api.jarvis.local` и `voice.jarvis.local`
- ✅ Автоматическая регенерация, если сертификат не содержит нужные домены
- ✅ CN: `api.jarvis.local`
- ✅ SAN: `api.jarvis.local`, `voice.jarvis.local`, `*.jarvis.local`, `localhost`, `127.0.0.1`

---

### 5️⃣ Desktop/Launcher TLS Support

**Desktop Client (`AppConfig.kt`):**
- ✅ Уже поддерживает `JARVIS_USE_TLS` и `wss://`
- ✅ Fail-fast для "полу-TLS" режима
- ✅ Автоматическое определение TLS по домену `jarvis.local`

**Launcher (`JarvisPaths.kt`, `HealthCheckService.kt`):**
- ✅ Авто-детект TLS: если URL содержит `jarvis.local` → HTTPS
- ✅ Health checks поддерживают HTTPS
- ✅ SSL errors обрабатываются с понятными сообщениями

---

## Установка и Настройка

### Шаг 1: Генерация сертификатов

Сертификаты генерируются автоматически при запуске `jarvis-launch.sh`:
```bash
./jarvis-launch.sh
```

Сертификаты сохраняются в `docker/certs/jarvis.crt` и `docker/certs/jarvis.key`.

---

### Шаг 2: Установка CA в trust store

```bash
sudo ./scripts/product/jarvis-install-tls.sh
```

**Проверка:**
```bash
ls -la /usr/local/share/ca-certificates/jarvis-ca.crt
```

---

### Шаг 3: Настройка /etc/hosts

```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
```

**Проверка:**
```bash
grep jarvis.local /etc/hosts
```

---

### Шаг 4: Применение Ingress

Ingress применяется автоматически через `jarvis-launch.sh`:
```bash
kubectl apply -f k8s/base/ingress.yaml
```

**Проверка:**
```bash
kubectl get ingress -n jarvis
kubectl describe ingress jarvis-ingress -n jarvis
```

---

### Шаг 5: Проверка TLS

```bash
# HTTPS connection (без -k)
curl https://api.jarvis.local/actuator/health

# SSL verification
echo | openssl s_client -connect api.jarvis.local:443 -servername api.jarvis.local | grep "Verify return code"

# WebSocket endpoint
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Key: test" -H "Sec-WebSocket-Version: 13" https://voice.jarvis.local/ws/voice
```

---

## Verification Pack

**Флаг:** `--require-https`

**Проверки:**
- ✅ `/etc/hosts` содержит `api.jarvis.local` и `voice.jarvis.local`
- ✅ Ingress `jarvis-ingress` существует и настроен для обоих доменов
- ✅ TLS secret `jarvis-tls` существует
- ✅ CA сертификат установлен в trust store
- ✅ `curl https://api.jarvis.local/actuator/health` работает без `-k`
- ✅ `openssl s_client` показывает `Verify return code: 0 (ok)`
- ✅ WebSocket endpoint `voice.jarvis.local` доступен

**Использование:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
```

---

## Troubleshooting

### SSL Certificate Verification Failed

**Симптом:** `curl https://api.jarvis.local` возвращает SSL error

**Решение:**
1. Проверить, что CA установлен: `ls -la /usr/local/share/ca-certificates/jarvis-ca.crt`
2. Обновить trust store: `sudo update-ca-certificates`
3. Проверить сертификат: `openssl x509 -in docker/certs/jarvis.crt -text -noout | grep -A 5 "Subject Alternative Name"`

---

### /etc/hosts entries missing

**Симптом:** DNS resolution fails для `api.jarvis.local`

**Решение:**
```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
```

---

### Ingress not accessible

**Симптом:** Ingress существует, но не отвечает

**Решение:**
1. Проверить ingress controller: `kubectl get pods -n ingress-nginx`
2. Проверить ingress status: `kubectl describe ingress jarvis-ingress -n jarvis`
3. Проверить TLS secret: `kubectl get secret jarvis-tls -n jarvis`

---

### Desktop client cannot connect

**Симптом:** Desktop показывает ошибку подключения

**Решение:**
1. Проверить `JARVIS_USE_TLS=true` (если используется домен `jarvis.local`)
2. Проверить, что CA установлен в trust store
3. Проверить логи desktop: `~/.jarvis/logs/desktop.log`

---

## Файлы

1. `k8s/base/ingress.yaml` (новый)
2. `jarvis-launch.sh` (обновлён: генерация сертификатов)
3. `scripts/product/jarvis-install-tls.sh` (новый)
4. `scripts/product/jarvis-setup-hosts.sh` (новый)
5. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` (обновлён)
6. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt` (обновлён)
7. `scripts/verify-iteration-1.4.sh` (обновлён: --require-https)
8. `docs/ITERATION_1.5_TLS_HTTPS.md` (новый)
9. `docs/ITERATION_1.5_ACCEPTANCE.md` (новый)

---

**Iteration 1.5 (Stage 7) готов к acceptance run!**


