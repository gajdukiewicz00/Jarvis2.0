# Iteration 1.4 - Stage 5: Idempotency & Process Management

**Дата:** 2026-01-05  
**Статус:** ✅ Реализовано

---

## Цель

Сделать production-grade управление процессами без дублей и гонок. Обеспечить идемпотентность операций и корректное отслеживание процессов.

---

## Реализованные компоненты

### 1️⃣ Start All Button

**Функциональность:**
- ✅ Запускает backend (если не запущен)
- ✅ Ждёт статус READY/DEGRADED (используя HealthCheckService)
- ✅ Запускает Desktop после READY
- ✅ Если backend уже READY → сразу Start Desktop
- ✅ Таймаут ожидания READY: 90 секунд
- ✅ Понятная ошибка с советом открыть diagnostics при таймауте

**Идемпотентность:**
- ✅ Если backend уже READY/DEGRADED → не запускает backend повторно
- ✅ Если desktop уже запущен → показывает сообщение
- ✅ Если backend STARTING → показывает "already starting"

**Реализация:**
- Метод `startAll()` проверяет текущий статус
- Использует `status.get()` для проверки READY/DEGRADED
- Polling каждые 2 секунды с таймаутом 90 секунд
- Все операции в background thread

---

### 2️⃣ Stop All Button

**Функциональность:**
- ✅ Останавливает Desktop (если запущен)
- ✅ Останавливает Backend graceful (используя текущий stop script)
- ✅ Cleanup stale PID/locks после остановки

**Идемпотентность:**
- ✅ Если ничего не запущено → показывает "already stopped"
- ✅ Безопасен для повторных вызовов

**Реализация:**
- Метод `stopAll()` проверяет состояние перед остановкой
- Последовательность: Desktop → Backend → Cleanup
- Все операции в background thread

---

### 3️⃣ Desktop Process Tracking

**Функциональность:**
- ✅ Сохраняет desktop PID в `~/.jarvis/run/desktop.pid`
- ✅ Stale PID cleanup при старте и остановке
- ✅ Проверка `isDesktopRunning()` для идемпотентности

**Реализация:**
- `desktopPid` добавлен в `JarvisPaths`
- `desktopProcess` хранится в `LauncherApplication`
- `ReentrantLock` для thread-safe доступа
- Cleanup при старте launcher и при остановке

---

### 4️⃣ Idempotency

**Реализовано:**
- ✅ Повторный Start All не запускает ничего лишнего:
  - Проверяет статус перед запуском
  - Если READY → только desktop
  - Если STARTING → показывает "already starting"
- ✅ Повторный Stop All безопасен:
  - Проверяет состояние перед остановкой
  - Если ничего не запущено → показывает "already stopped"
- ✅ Desktop tracking:
  - Проверка `isDesktopRunning()` перед запуском
  - Stale PID cleanup

---

## UI Changes

### Buttons
- **Start All**: Запускает backend → ждёт READY → запускает desktop
- **Stop All**: Останавливает desktop → останавливает backend → cleanup

### Button States
- **Start All**: disabled в STARTING, enabled в IDLE/READY/DEGRADED/ERROR
- **Stop All**: disabled в IDLE, enabled в STARTING/READY/DEGRADED/ERROR

---

## Файлы

1. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (обновлён)
   - Добавлены `startAll()`, `stopAll()`, `stopDesktop()`, `isDesktopRunning()`, `cleanupStalePids()`
2. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` (обновлён)
   - Добавлен `desktopPid: Path`
3. `scripts/verify-iteration-1.4.sh` (обновлён)
   - Добавлены проверки Stage 5
4. `docs/ITERATION_1.4_STAGE5.md` (новый)

---

## Definition of Done

- ✅ Start All: IDLE → STARTING → READY/DEGRADED → Desktop started
- ✅ Stop All: Desktop stopped → backend stopped → no stale PID
- ✅ Повторные клики не ломают состояние
- ✅ Desktop PID tracking работает
- ✅ Stale PID cleanup работает
- ✅ Таймаут 90 секунд с понятной ошибкой
- ✅ Verify script проверяет все компоненты

---

## Acceptance Scenarios

### A) Start All (Happy Path)
1. Статус: IDLE
2. Нажать "Start All"
3. Наблюдать: IDLE → STARTING → READY → Desktop started
4. Проверить: Desktop window открылся

### B) Start All (Backend Already READY)
1. Статус: READY (backend уже запущен)
2. Нажать "Start All"
3. Наблюдать: Desktop started сразу (без ожидания backend)
4. Проверить: Desktop window открылся

### C) Start All (Timeout)
1. Статус: IDLE
2. Сломать backend (например, удалить deployment)
3. Нажать "Start All"
4. Наблюдать: Через 90 секунд → ошибка с советом открыть diagnostics
5. Проверить: Сообщение понятное, action-oriented

### D) Stop All (Happy Path)
1. Статус: READY, Desktop running
2. Нажать "Stop All"
3. Наблюдать: Desktop stopped → Backend stopped → IDLE
4. Проверить: Нет stale PID файлов

### E) Stop All (Idempotency)
1. Статус: IDLE (ничего не запущено)
2. Нажать "Stop All"
3. Наблюдать: "Nothing to stop (desktop and backend already stopped)"
4. Проверить: Не падает, не создаёт ошибок

### F) Repeated Clicks
1. Быстро нажать "Start All" 3 раза
2. Проверить: Backend запускается только один раз
3. Проверить: Desktop запускается только один раз
4. Проверить: Нет дублей процессов

---

## Verify

```bash
# 1. Run verify script
./scripts/verify-iteration-1.4.sh --require-install
echo $?
# Ожидаемо: 0

# 2. Launch launcher
java -jar ~/.jarvis/app/launcher.jar

# 3. Test Start All:
#    - Click "Start All" from IDLE
#    - Verify: backend starts → READY → desktop starts
#    - Check: ~/.jarvis/run/desktop.pid exists

# 4. Test Stop All:
#    - Click "Stop All"
#    - Verify: desktop stops → backend stops → IDLE
#    - Check: ~/.jarvis/run/desktop.pid removed

# 5. Test Idempotency:
#    - Click "Start All" multiple times quickly
#    - Verify: no duplicate processes
```

---

**Stage 5 готов к тестированию!**


