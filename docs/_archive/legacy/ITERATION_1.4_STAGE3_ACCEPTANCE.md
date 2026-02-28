# Iteration 1.4 - Stage 3: Acceptance Run Guide

**Дата:** 2026-01-05  
**Статус:** ✅ Готово к тестированию

---

## Acceptance Scenarios

### A) Базовый сценарий (happy path)

**Шаги:**
1. Запустить launcher: `java -jar ~/.jarvis/app/launcher.jar` (или через иконку)
2. Нажать "Stop Backend" (если запущен)
3. Наблюдать: статус должен стать **IDLE** (не "зависнуть")
4. Нажать "Start Backend"
5. Наблюдать: статус должен стать **STARTING** в течение 0–5 сек
6. Дождаться поднятия api-gateway/security
7. Наблюдать: **READY** появляется только после 2 успешных циклов (≈+10 сек после реальной готовности, если poll=5s)

**Проверка кнопок:**
- В **STARTING**: Start Desktop **disabled**
- В **READY**: Start Desktop **enabled**

**Ожидаемо:** Ни одного "дёргания" READY↔STARTING на коротких спайках.

---

### B) Hysteresis на ERROR (проверить 3 провала)

**Шаги:**
1. Запустить backend → дождаться **READY**
2. Сломать gateway:
   ```bash
   kubectl -n jarvis scale deploy/api-gateway --replicas=0
   ```
3. Наблюдать UI:
   - UI **НЕ должен** моментально стать ERROR
   - Сначала уходит в **STARTING/DEGRADED**
   - Только после **3 подряд провалов** (≈15 сек) становится **ERROR**
4. Вернуть gateway обратно:
   ```bash
   kubectl -n jarvis scale deploy/api-gateway --replicas=1
   ```
5. Наблюдать: UI должен вернуться к **STARTING → READY** (после 2 успехов)

---

### C) DEGRADED (optional недоступны)

**Шаги:**
1. Сделать так, чтобы optional сервис не поднялся:
   - Например, voice ws / llm выключено флагом (`ENABLE_LLM=false`)
   - Или остановить optional сервис:
     ```bash
     kubectl -n jarvis scale deploy/voice-gateway --replicas=0
     ```
2. Наблюдать:
   - Core должен стать **READY** (api-gateway + security-service UP)
   - Затем статус должен быть **DEGRADED** с reason:
     - "Voice WS not ready"
     - "LLM disabled"
     - и т.п.
3. Проверить: **Start Desktop** должен быть **enabled** (core работает)

---

### D) TLS / WS readiness (самое важное)

**Без TLS:**
1. Установить: `export JARVIS_USE_TLS=false`
2. Перезапустить launcher
3. Проверить: WS check должен идти на `ws://.../ws/voice`

**С TLS:**
1. Установить: `export JARVIS_USE_TLS=true`
2. Перезапустить launcher
3. Проверить: WS check должен идти на `wss://voice.jarvis.local/...` (или как настроено)
4. Если TLS не настроен полностью → **DEGRADED/ERROR**, но не ложный READY

---

## Hardening Improvements

### 1. "Причина статуса" как first-class поле

**Проверка:**
- UI показывает детальные причины:
  - `Process: pid=alive/dead`
  - `Core api-gateway: UP/DOWN (message)`
  - `Core security-service: UP/DOWN (message)`
  - `Optional voice-gateway: OK/FAIL (message)`
  - и т.д.

**Где смотреть:** В статус-лейбле launcher UI (многострочный текст)

---

### 2. Таймауты и отмена

**Проверка:**
- Каждый check имеет таймаут (HTTP: 3s, WS: 2s)
- Следующий polling не стартует, если предыдущий ещё выполняется
- Guard: `AtomicBoolean runningCheck`

**Как проверить:**
- В логах не должно быть накопления потоков
- При медленном ответе health endpoint, следующий check ждёт завершения предыдущего

---

### 3. Развести "Core READY" и "UI READY"

**Проверка:**
- `coreReady=true` (gateway+security OK)
- `optionalReady={voiceWs, llm, memory}`
- UI решает:
  - **READY** если `coreReady && allOptionalReady`
  - **DEGRADED** если `coreReady && !allOptionalReady`

**Где смотреть:** В логике `HealthCheckService.checkHealthInternal()`

---

## Verify Script

Запустить автоматическую проверку:

```bash
./scripts/verify-iteration-1.4.sh
```

**Проверки Stage 3:**
- ✅ HealthCheckService class found in launcher JAR
- ✅ API Gateway health endpoint accessible and UP (smoke test)
- ✅ LauncherApplication integrates HealthCheckService
- ✅ Hysteresis logic implemented (2 success for READY, 3 failures for ERROR)
- ✅ Concurrent check protection (runningCheck) implemented

---

## Ожидаемые результаты

### Happy Path (A):
- IDLE → STARTING → READY (после 2 циклов)
- Кнопки работают корректно
- Нет "дёргания" статуса

### Hysteresis (B):
- ERROR только после 3 провалов
- Восстановление: STARTING → READY (после 2 успехов)

### DEGRADED (C):
- Core READY, но optional down → DEGRADED с reason
- Start Desktop enabled

### TLS/WS (D):
- Правильный URL (ws:// или wss://) в зависимости от флага
- Нет ложного READY при неполной TLS настройке

---

**Готово к acceptance run!**


