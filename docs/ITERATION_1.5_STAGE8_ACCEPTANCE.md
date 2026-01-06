# Iteration 1.5 - Stage 8: Acceptance Run Results

**Дата:** 2026-01-05  
**Статус:** ✅ Реализовано, требует ручной настройки (sudo)

---

## Acceptance Run Results

### 0) Preconditions

**Требует ручного выполнения (sudo):**
```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
sudo ./scripts/product/jarvis-install-tls.sh
```

**Проверка:**
- `/etc/hosts` должен содержать `api.jarvis.local` и `voice.jarvis.local`
- `/usr/local/share/ca-certificates/jarvis-ca.crt` должен существовать

---

### 1) Перезапуск стека

**Команды:**
```bash
./scripts/product/jarvis-stop.sh || true
./jarvis-launch.sh
```

**Ожидаемый вывод:**
- `kubectl rollout status` для core сервисов (api-gateway, security-service, life-tracker, analytics-service, voice-gateway)
- Primary endpoint: `https://api.jarvis.local` (если TLS активен)
- NodePort как `[DEBUG]` fallback
- Port-forward НЕ запускается (если `ENABLE_PORT_FORWARD` не установлен)

---

### 2) Быстрые проверки "по факту"

#### 2.1) Primary HTTPS health
```bash
curl -sS https://api.jarvis.local/actuator/health | head
```

**PASS если:**
- Возвращает JSON с `"status":"UP"` (или хотя бы 200 + UP внутри)

#### 2.2) HTTP → HTTPS redirect
```bash
curl -I http://api.jarvis.local/actuator/health | head -n 5
```

**PASS если:**
- Возвращает 301/308
- `Location: https://...`

#### 2.3) SSL verification
```bash
echo | openssl s_client -connect api.jarvis.local:443 -servername api.jarvis.local 2>/dev/null | grep "Verify return code"
```

**PASS если:**
- `Verify return code: 0 (ok)`

---

### 3) Verify Pack (жёсткий)

```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
```

**PASS если:**
- Exit code: 0

**Проверки Stage 8:**
- ✅ TLS detection logic присутствует
- ✅ Port-forward conditional (ENABLE_PORT_FORWARD flag)
- ✅ kubectl rollout status для core сервисов
- ✅ NodePort как DEBUG fallback (опционально)
- ✅ Desktop/Launcher WSS support

---

### 4) Port-forward не должен включаться сам

**Проверка:**
```bash
# После обычного ./jarvis-launch.sh (без флага)
ps aux | grep -E "kubectl port-forward" | grep -v grep
```

**PASS если:**
- Пусто (нет процессов port-forward)

---

### 5) Port-forward включается только флагом

**Проверка:**
```bash
ENABLE_PORT_FORWARD=true ./jarvis-launch.sh
ps aux | grep -E "kubectl port-forward" | grep -v grep
```

**PASS если:**
- Есть процессы port-forward

---

## Известные проблемы

### 1. Verify script syntax error (исправлено)

**Проблема:** Ошибка в проверке assistant-core pods (строка 604)
```bash
[[: 0
0: syntax error in expression (error token is "0")
```

**Исправление:** Добавлена проверка, что `ASSISTANT_CORE_PODS` является числом перед сравнением.

---

### 2. /etc/hosts и CA trust store требуют sudo

**Проблема:** Не может быть выполнено автоматически (требует sudo)

**Решение:** Выполнить вручную:
```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
sudo ./scripts/product/jarvis-install-tls.sh
```

---

## Финальный статус

**✅ Реализовано:**
- Ingress/HTTPS как primary endpoint
- NodePort как DEBUG fallback
- Port-forward только при `ENABLE_PORT_FORWARD=true`
- `kubectl rollout status` для core сервисов
- Launcher авто-детект TLS/Ingress
- Verify script обновлён (упрощена проверка HTTPS output)

**✅ Проверено:**
- Port-forward не запускается без флага: PASS
- Verify script синтаксис: OK

**⚠️ Требует ручного выполнения:**
- `/etc/hosts` setup (sudo)
- CA trust store installation (sudo)
- Запуск backend для проверки rollout status и endpoint output

---

## Следующие шаги

1. Выполнить sudo команды для настройки хостов и CA
2. Запустить `./jarvis-launch.sh` для проверки rollout status и endpoint output
3. Выполнить все проверки из раздела "2) Быстрые проверки"
4. Запустить `verify --require-https` для финальной проверки

**После выполнения всех шагов:**
- Все проверки должны пройти
- `verify --require-https` должен вернуть 0
- Stage 8 будет полностью ACCEPTED

---

**Stage 8 готов к финальной проверке после ручной настройки!**

