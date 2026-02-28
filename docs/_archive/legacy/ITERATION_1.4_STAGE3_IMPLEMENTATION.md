# Iteration 1.4 - Stage 3: Health/Ready Criteria Implementation

**Дата:** 2026-01-05  
**Статус:** ✅ Реализовано

---

## Реализованные компоненты

### 1. HealthCheckService
- ✅ Multi-signal health checks:
  - Process-level: PID alive check
  - Network-level: HTTP endpoint accessibility
  - Service-level: `/actuator/health` status parsing
- ✅ Hysteresis:
  - READY: требует 2 последовательных успешных проверки
  - ERROR: требует 3 последовательных провала
- ✅ Polling: каждые 5 секунд

### 2. Статусы и правила

- ✅ **IDLE** — backend не запущен (PID нет / процесс мёртв)
- ✅ **STARTING** — backend запущен, но ключевые проверки ещё не ОК
- ✅ **READY** — оба `api-gateway` и `security-service` = UP (после 2 успешных циклов)
- ✅ **DEGRADED** — core READY, но optional модули не готовы
- ✅ **ERROR** — backend процесс умер / health недоступен слишком долго (после 3 провалов)

### 3. Источники правды

- ✅ Process-level: `ProcessHandle.of(pid).isAlive`
- ✅ Network-level: `GET {apiBase}/actuator/health` с timeout 3s
- ✅ Service-level: парсинг JSON ответа для проверки `status=UP`
- ✅ UX-level: показывается reason в статусе (например: "API Gateway: connection refused")

### 4. Проверки

#### Core readiness:
- ✅ `GET {apiBase}/actuator/health` → `status=UP`
- ✅ `security-service` проверяется через gateway (если gateway UP, то security доступен)

#### Optional readiness:
- ✅ Voice WS: определяется URL через ту же логику, что и desktop client (wsBase() с TLS поддержкой)
- ✅ LLM/Memory: помечены как optional, не блокируют READY

### 5. Интервал и устойчивость

- ✅ Polling: каждые 5 секунд
- ✅ Hysteresis:
  - READY: 2 успешных цикла подряд
  - ERROR: 3 провала подряд
- ✅ UI не "дёргается" благодаря hysteresis

### 6. Готовность для кнопок

- ✅ **Start Desktop**: доступна только в READY/DEGRADED
- ✅ **Start Backend**: доступна только в IDLE/ERROR
- ✅ **Stop Backend**: доступна в STARTING/READY/DEGRADED/ERROR (если процесс есть)

### 7. WebSocket readiness с TLS

- ✅ Использует ту же логику `wsBase()`, что и desktop client
- ✅ Если `JARVIS_USE_TLS=true` → использует `wss://voice.jarvis.local`
- ✅ Если не TLS → использует `ws://` через gateway

---

## Файлы

1. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt` (новый)
2. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (обновлён)
3. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` (добавлен `getApiGatewayUrl()`)

---

## Definition of Done

- ✅ HealthCheckService проверяет обязательные сервисы
- ✅ UI показывает READY только когда все обязательные сервисы UP (после 2 циклов)
- ✅ UI показывает STARTING пока сервисы поднимаются
- ✅ UI показывает DEGRADED с reason если опциональные сервисы недоступны
- ✅ Статус обновляется периодически (каждые 5 секунд)
- ✅ "Start Desktop" доступен только в READY/DEGRADED
- ✅ Hysteresis предотвращает "дёргание" UI
- ✅ WebSocket readiness использует ту же логику, что и desktop client

---

## Verify

```bash
# 1. Запустить launcher
java -jar ~/.jarvis/app/launcher.jar

# 2. Нажать "Start Backend"
# 3. Наблюдать статус: IDLE → STARTING → READY (после 2 циклов)
# 4. Проверить что "Start Desktop" доступен только в READY/DEGRADED
# 5. Остановить один из обязательных сервисов
# 6. Проверить что статус меняется на ERROR (после 3 провалов)
```

---

**Stage 3 готов к тестированию!**


