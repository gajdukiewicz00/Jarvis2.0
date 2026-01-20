# 🔒 HTTPS / TLS as Product Standard

**Дата:** 2025-01-27  
**Статус:** Зафиксировано как обязательный стандарт  
**Итерация:** Iteration 7

---

## 📋 Требования

### 1. Внешний трафик — только HTTPS/WSS
- ✅ ВСЕ внешние запросы к API Gateway — только HTTPS
- ✅ WebSocket соединения — только WSS
- ✅ HTTP разрешён ТОЛЬКО для readiness/liveness внутри кластера

### 2. TLS терминирование
- ✅ TLS терминируется в API Gateway / Ingress
- ✅ Внутри кластера сервисы общаются по HTTP (ClusterIP)
- ✅ Внешний доступ — только через HTTPS

### 3. Self-signed CA для локального продукта
- ✅ CA + certs генерируются при первом запуске launcher'ом
- ✅ CA устанавливается в Ubuntu trust store (`/usr/local/share/ca-certificates/`)
- ✅ Certs передаются в Kubernetes через Secret (`jarvis-tls`)
- ✅ CA обновляется через `update-ca-certificates`

### 4. Стандартные домены
- ✅ `api.jarvis.local` — API Gateway
- ✅ `voice.jarvis.local` — Voice Gateway
- ✅ Скрипт добавляет их в `/etc/hosts` (IP хоста)

### 5. UI не отключает SSL verification
- ✅ Desktop client использует HTTPS БЕЗ `-k` / `trust-all`
- ✅ Desktop client использует CA из trust store
- ✅ НЕТ отключения SSL verification в коде

### 6. HTTP только для readiness/liveness
- ✅ `/actuator/health` доступен по HTTP внутри кластера
- ✅ Внешний доступ к health — через HTTPS

---

## 🏗️ Архитектура

```
External Client (Desktop UI)
    ↓ HTTPS (api.jarvis.local:443)
    ↓
Ingress / API Gateway (TLS termination)
    ↓ HTTP (внутри кластера)
    ↓
Backend Services (ClusterIP, HTTP)
```

---

## 📝 Реализация (Iteration 7)

### 1. Генерация CA и certs

**Скрипт:** `scripts/product/jarvis-generate-certs.sh`

```bash
#!/bin/bash
# Генерирует:
# - CA private key: ~/.jarvis/tls/jarvis-ca.key
# - CA certificate: ~/.jarvis/tls/jarvis-ca.crt
# - Server private key: ~/.jarvis/tls/jarvis.key
# - Server certificate: ~/.jarvis/tls/jarvis.crt (CN=api.jarvis.local, SAN=voice.jarvis.local)
```

### 2. Установка CA в trust store

```bash
# Копировать CA в trust store
sudo cp ~/.jarvis/tls/jarvis-ca.crt /usr/local/share/ca-certificates/jarvis-ca.crt
sudo update-ca-certificates
```

### 3. Добавление доменов в /etc/hosts

```bash
# Добавить в /etc/hosts (требует sudo)
sudo ./scripts/product/jarvis-setup-hosts.sh
```

### 4. Kubernetes Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: jarvis-tls
  namespace: jarvis
type: kubernetes.io/tls
data:
  tls.crt: <base64 encoded server cert>
  tls.key: <base64 encoded server key>
```

### 5. Ingress с TLS

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jarvis-ingress
  namespace: jarvis
spec:
  tls:
    - hosts:
        - api.jarvis.local
        - voice.jarvis.local
      secretName: jarvis-tls
  rules:
    - host: api.jarvis.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port:
                  number: 8080
```

### 6. Desktop Client обновление

**Файл:** `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt`

```kotlin
val apiGatewayBaseUrl: String by lazy {
    val env = System.getenv("JARVIS_API_BASE_URL")
        ?: System.getenv("API_URL")
    (env?.takeIf { it.isNotBlank() } ?: "https://api.jarvis.local").trimEnd('/')
}
```

**Важно:** НЕТ `-k`, НЕТ `trust-all`, используется системный trust store.

---

## 🔍 Проверка (Verification Pack)

### Команды:

```bash
# 1. CA в trust store
openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt -text -noout

# 2. Домены в /etc/hosts
grep "jarvis.local" /etc/hosts

# 3. TLS secret в K8s
kubectl -n jarvis get secret jarvis-tls

# 4. HTTPS endpoint (БЕЗ -k)
curl --cacert /usr/local/share/ca-certificates/jarvis-ca.crt \
  https://api.jarvis.local/actuator/health

# 5. openssl проверка
echo | openssl s_client -connect api.jarvis.local:443 \
  -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt 2>&1 | \
  grep -E "Verify return code|CN="
```

### Ожидаемый результат:

- ✅ CA certificate валиден
- ✅ Домены в /etc/hosts
- ✅ jarvis-tls secret существует
- ✅ `curl https://api.jarvis.local` работает БЕЗ `-k`
- ✅ `openssl s_client` показывает `Verify return code: 0 (ok)`

---

## ⚠️ Важные замечания

1. **CA должен быть установлен ДО первого запуска UI** — иначе SSL verification упадёт
2. **Домены должны быть в /etc/hosts ДО первого запуска** — иначе DNS не резолвится
3. **Launcher должен проверять наличие CA и доменов** — если нет, установить автоматически
4. **При обновлении CA** — нужно перезапустить `update-ca-certificates` и перезапустить UI

---

## 📋 DoD (Iteration 7) - Чёткие критерии

### 1. Домены настроены
- ✅ `api.jarvis.local` добавлен в `/etc/hosts` (IP хоста)
- ✅ `voice.jarvis.local` добавлен в `/etc/hosts` (IP хоста)
- ✅ Проверка: `grep "jarvis.local" /etc/hosts` показывает оба домена

### 2. TLS Secret в Kubernetes
- ✅ `jarvis-tls` secret существует в namespace `jarvis`
- ✅ Secret содержит ключи: `tls.crt` и `tls.key`
- ✅ Проверка: `kubectl -n jarvis get secret jarvis-tls`
- ✅ Проверка: `kubectl -n jarvis describe secret jarvis-tls` показывает оба ключа

### 3. CA в trust store
- ✅ CA certificate установлен в `/usr/local/share/ca-certificates/jarvis-ca.crt`
- ✅ CA обновлён через `update-ca-certificates`
- ✅ Проверка: `openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt -text -noout` работает
- ✅ Проверка: `openssl verify -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt <server-cert>` проходит

### 4. HTTPS работает без -k
- ✅ `curl --cacert /usr/local/share/ca-certificates/jarvis-ca.crt https://api.jarvis.local/actuator/health` возвращает 200
- ✅ `curl https://api.jarvis.local/actuator/health` работает БЕЗ `-k` (использует системный trust store)
- ✅ `openssl s_client -connect api.jarvis.local:443 -CAfile /usr/local/share/ca-certificates/jarvis-ca.crt` показывает `Verify return code: 0 (ok)`
- ✅ НЕТ ошибок SSL verification

### 5. HTTP → HTTPS redirect
- ✅ Ingress настроен с redirect HTTP → HTTPS
- ✅ `curl -L http://api.jarvis.local/actuator/health` редиректит на HTTPS
- ✅ Проверка: `curl -I http://api.jarvis.local/actuator/health` показывает `301` или `308` redirect

### 6. UI не отключает SSL verification
- ✅ Desktop client НЕ использует `-k` / `trust-all` / `disableSSLVerification`
- ✅ Desktop client использует системный trust store
- ✅ Проверка: `grep -r "trust.*all\|disable.*ssl\|-k\|insecure" apps/desktop-client-javafx/src/` возвращает (пусто)
- ✅ Проверка: `grep -r "https://api.jarvis.local" apps/desktop-client-javafx/src/` находит использование HTTPS

### 7. HTTP только для readiness/liveness
- ✅ `/actuator/health` доступен по HTTP внутри кластера (ClusterIP)
- ✅ Внешний доступ к `/actuator/health` — только через HTTPS
- ✅ Проверка: `curl http://api.jarvis.local/actuator/health` редиректит на HTTPS (не работает напрямую)

---

## ✅ Итоговый DoD Checklist

- [ ] `api.jarvis.local` в `/etc/hosts`
- [ ] `voice.jarvis.local` в `/etc/hosts`
- [ ] `jarvis-tls` secret существует в K8s
- [ ] CA в `/usr/local/share/ca-certificates/jarvis-ca.crt`
- [ ] `curl https://api.jarvis.local` работает БЕЗ `-k`
- [ ] `openssl s_client` показывает `Verify return code: 0 (ok)`
- [ ] HTTP → HTTPS redirect работает
- [ ] Desktop client НЕ отключает SSL verification
- [ ] HTTP только для readiness/liveness внутри кластера
