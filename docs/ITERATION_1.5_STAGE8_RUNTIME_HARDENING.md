# Iteration 1.5 - Stage 8: K8s Runtime Hardening

**Дата:** 2026-01-05  
**Цель:** Убрать смешанные схемы доступа и стабилизировать rollout

---

## Проблема

До Stage 8:
- Смешанные схемы доступа: NodePort, port-forward, Ingress одновременно
- Нет явного приоритета для Ingress/HTTPS
- Port-forward всегда включен (даже когда не нужен)
- Нет проверки rollout status после `kubectl apply`
- Launcher может использовать HTTP даже когда TLS активен

---

## Решение

### A) Единый стандарт внешнего доступа (product default)

**Приоритет:**
1. **HTTPS через Ingress** (`https://api.jarvis.local`) — primary
2. **NodePort** (`http://<minikube-ip>:<nodeport>`) — DEBUG fallback
3. **Port-forward** (`http://localhost:8080`) — только при `ENABLE_PORT_FORWARD=true`

**Логика в `jarvis-launch.sh`:**
```bash
# Проверка TLS/Ingress активности
TLS_ACTIVE=false
if [ "${JARVIS_USE_TLS:-false}" = "true" ]; then
    TLS_ACTIVE=true
elif kubectl get ingress jarvis-ingress -n jarvis &>/dev/null; then
    INGRESS_HOSTS=$(kubectl get ingress jarvis-ingress -n jarvis -o jsonpath='{.spec.rules[*].host}' 2>/dev/null || echo "")
    if echo "$INGRESS_HOSTS" | grep -q "api.jarvis.local"; then
        TLS_ACTIVE=true
    fi
fi

# Primary endpoint
if [ "$TLS_ACTIVE" = "true" ]; then
    API_URL="https://api.jarvis.local"
    VOICE_WS_URL="wss://voice.jarvis.local"
    # NodePort только как DEBUG fallback
    echo -e "${BLUE}  [DEBUG] NodePort fallback: http://$MINIKUBE_IP:$NODE_PORT${NC}"
fi
```

---

### B) Port-forward только при флаге

**Изменения:**
- `ENABLE_PORT_FORWARD=${ENABLE_PORT_FORWARD:-false}` — по умолчанию выключен
- Port-forward для Voice Gateway, RabbitMQ, LLM services только если `ENABLE_PORT_FORWARD=true`
- Скрипты product/desktop actions не зависят от port-forward

**Пример:**
```bash
if [ "$ENABLE_PORT_FORWARD" = "true" ]; then
    kubectl port-forward svc/voice-gateway $VOICE_GATEWAY_PORT:8081 -n $NAMESPACE &>/dev/null &
    echo -e "${GREEN}✓${NC} Voice Gateway: http://localhost:$VOICE_GATEWAY_PORT (port-forward)"
fi
```

---

### C) Надёжный rollout и readiness

**Добавлено:**
- `kubectl rollout status` для core сервисов после `kubectl apply`
- Таймаут 120 секунд на сервис
- Читаемые ошибки при timeout

**Core сервисы:**
- `api-gateway`
- `security-service`
- `life-tracker`
- `analytics-service`
- `voice-gateway`

**Код:**
```bash
CORE_SERVICES=("api-gateway" "security-service" "life-tracker" "analytics-service" "voice-gateway")
for svc in "${CORE_SERVICES[@]}"; do
    if kubectl get deploy "$svc" -n $NAMESPACE &>/dev/null; then
        if kubectl rollout status deploy/"$svc" -n $NAMESPACE --timeout=120s &>/dev/null; then
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${YELLOW}⚠️${NC} (timeout or error)"
        fi
    fi
done
```

**Дополнительная проверка TLS/Ingress:**
```bash
if [ "$TLS_ACTIVE" = "true" ]; then
    # Проверка HTTPS endpoint (без -k)
    curl -s --max-time 5 --cacert "$HOME/.jarvis/ca/jarvis-ca.crt" "$API_URL/actuator/health" &>/dev/null
    
    # Проверка HTTP → HTTPS redirect
    HTTP_REDIRECT=$(curl -sI --max-time 5 "http://api.jarvis.local/actuator/health" 2>/dev/null | grep -i "location\|301\|308" || echo "")
fi
```

---

### D) Launcher readiness (Stage 3+)

**Изменения в `JarvisPaths.getApiGatewayUrl()`:**
- Проверка `JARVIS_USE_TLS` env var
- Авто-детект ingress через `kubectl get ingress`
- Если ingress с `api.jarvis.local` существует → использует `https://api.jarvis.local`
- Fallback на `http://localhost:8080` только если TLS не активен

**Код:**
```kotlin
// Stage 8: Check if TLS/Ingress is active
val useTls = System.getenv("JARVIS_USE_TLS")?.toBoolean() ?: false
if (useTls) {
    return "https://api.jarvis.local"
}

// Try to detect ingress (best-effort)
try {
    val process = ProcessBuilder("kubectl", "get", "ingress", "jarvis-ingress", "-n", "jarvis", "-o", "jsonpath={.spec.rules[*].host}")
        .redirectErrorStream(true)
        .start()
    val exitCode = process.waitFor()
    if (exitCode == 0) {
        val output = process.inputStream.bufferedReader().readText().trim()
        if (output.contains("api.jarvis.local")) {
            return "https://api.jarvis.local"
        }
    }
} catch (e: Exception) {
    // Fall through to fallback
}

// Fallback: legacy NodePort/port-forward
return "http://localhost:8080"
```

---

### E) Verification Pack

**Новые проверки в `verify-iteration-1.4.sh --require-https`:**

1. **HTTPS как primary в jarvis-launch.sh:**
   - Проверка наличия `https://api.jarvis.local` в скрипте
   - Проверка логики `TLS_ACTIVE`

2. **Port-forward conditional:**
   - Проверка наличия `ENABLE_PORT_FORWARD` флага
   - Проверка, что по умолчанию `false`

3. **Rollout status:**
   - Проверка использования `kubectl rollout status` для core сервисов

4. **NodePort как DEBUG fallback:**
   - Проверка, что NodePort помечен как `[DEBUG]`

5. **Desktop/Launcher WSS support:**
   - Проверка поддержки `wss://` в Desktop/Launcher коде

---

## Изменённые файлы

1. **`jarvis-launch.sh`**
   - Добавлен флаг `ENABLE_PORT_FORWARD`
   - Логика определения `TLS_ACTIVE`
   - Приоритет HTTPS/Ingress над NodePort/port-forward
   - `kubectl rollout status` для core сервисов
   - Проверка HTTPS endpoint и HTTP→HTTPS redirect
   - Port-forward только при `ENABLE_PORT_FORWARD=true`
   - NodePort показывается как `[DEBUG]` fallback

2. **`apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt`**
   - Улучшена логика `getApiGatewayUrl()` для авто-детекта ingress
   - Проверка `kubectl get ingress` для определения TLS активности

3. **`scripts/verify-iteration-1.4.sh`**
   - Добавлены проверки Stage 8 (только при `--require-https`)
   - Проверка HTTPS как primary
   - Проверка conditional port-forward
   - Проверка rollout status
   - Проверка NodePort как DEBUG fallback
   - Проверка WSS support

---

## Команды проверки

### 1. Проверка endpoint selection в jarvis-launch.sh

```bash
# Проверить, что TLS_ACTIVE логика присутствует
grep -A 10 "TLS_ACTIVE" jarvis-launch.sh

# Проверить, что HTTPS используется как primary
grep "https://api.jarvis.local" jarvis-launch.sh

# Проверить, что NodePort помечен как DEBUG
grep "\[DEBUG\].*NodePort" jarvis-launch.sh
```

### 2. Проверка port-forward conditional

```bash
# Проверить флаг
grep "ENABLE_PORT_FORWARD" jarvis-launch.sh

# Проверить, что по умолчанию false
grep "ENABLE_PORT_FORWARD.*false" jarvis-launch.sh
```

### 3. Проверка rollout status

```bash
# Проверить использование rollout status
grep "kubectl rollout status" jarvis-launch.sh

# Проверить core сервисы
grep "api-gateway\|security-service" jarvis-launch.sh | grep rollout
```

### 4. Полный verify

```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
# Expected: 0
```

---

## Ожидаемые результаты

### При TLS активен (default):

```
✓ API Gateway (HTTPS): https://api.jarvis.local
✓ Voice Gateway (WSS): wss://voice.jarvis.local
  [DEBUG] NodePort fallback: http://192.168.58.2:31482
```

### При TLS не активен (legacy):

```
✓ API Gateway: http://192.168.58.2:31482
```

### При ENABLE_PORT_FORWARD=true:

```
✓ Voice Gateway: http://localhost:8081 (port-forward)
✓ RabbitMQ UI: http://localhost:15672 (port-forward)
```

---

## Риски и митигация

1. **Риск:** Launcher не может определить TLS активность (kubectl недоступен)
   - **Митигация:** Fallback на `http://localhost:8080` (legacy поведение)

2. **Риск:** Rollout status timeout может замедлить запуск
   - **Митигация:** Таймаут 120 секунд, продолжение при ошибке

3. **Риск:** Port-forward отключен по умолчанию, но некоторые скрипты могут зависеть от него
   - **Митигация:** Скрипты product/desktop actions не используют port-forward (используют Ingress/NodePort)

---

## Definition of Done

- ✅ Ingress/HTTPS используется как primary endpoint
- ✅ NodePort показывается только как DEBUG fallback
- ✅ Port-forward только при `ENABLE_PORT_FORWARD=true`
- ✅ `kubectl rollout status` для core сервисов
- ✅ Launcher использует HTTPS когда TLS активен
- ✅ Verify script проверяет все Stage 8 требования
- ✅ `verify --require-https` возвращает 0

---

**Stage 8 готов к acceptance run!**

