# Iteration 1.4 - Stage 4: Logs, Health & Ready UX

**Дата:** 2026-01-05  
**Статус:** ✅ Реализовано

---

## Цель

Сделать production-уровень UX для логов и диагностики, чтобы пользователь понимал, что происходит, без терминала и без дебага.

---

## Реализованные компоненты

### 1️⃣ Log Viewer Tab

**Функциональность:**
- ✅ Показывает `launcher.log` и `backend-launch.log`
- ✅ Переключатель файлов (ComboBox)
- ✅ Auto-scroll (on/off checkbox)
- ✅ Ограничение: последние 1000 строк
- ✅ Обновление каждые 2 секунды
- ✅ UI не блокируется (background threads)

**Реализация:**
- `LogViewer.kt` - отдельный компонент
- Использует `ScheduledExecutorService` для периодического обновления
- Проверяет размер файла для определения изменений
- Читает только последние MAX_LINES строк

---

### 2️⃣ Diagnostics Snapshot (One-click)

**Функциональность:**
- ✅ Кнопка "Collect Diagnostics" в Launcher
- ✅ Собирает snapshot в `~/.jarvis/logs/diagnostics-YYYYMMDD-HHMMSS.txt`
- ✅ Автоматически открывает файл (`xdg-open`)
- ✅ Показывает уведомление "Diagnostics saved"

**Содержимое snapshot:**
- ✅ Launcher version
- ✅ OS + Java version
- ✅ Backend status (PID, alive/dead)
- ✅ Health reasons (из Stage 3)
- ✅ Lock files status (launcher.lock, backend.lock)
- ✅ Последние 100 строк:
  - `launcher.log`
  - `backend-launch.log`
- ✅ Kubernetes summary (если backend запущен):
  - `kubectl get pods -n jarvis`
  - `kubectl get deploy -n jarvis`

**Реализация:**
- `DiagnosticsCollector.kt` - отдельный класс
- Все операции в background thread
- Таймауты на kubectl команды (5 секунд)
- Graceful fallback если kubectl недоступен

---

### 3️⃣ Fail-Fast UX (Human readable)

**Улучшенные сообщения:**
- ✅ ERROR статус объясняет, что делать:
  - "API Gateway not reachable — backend may still be starting"
  - "Action: Wait 10-15 seconds, then check again"
  - "Security Service DOWN — database connection issue"
  - "Action: Check database pod status: kubectl get pods -n jarvis | grep postgres"
  - "Backend process stopped unexpectedly"
  - "Action: Click 'Start Backend' to restart"
- ✅ Никаких "UNKNOWN ERROR"
- ✅ Все ошибки → action-oriented

**Реализация:**
- Метод `buildErrorMessage()` анализирует health status
- Определяет конкретную причину (API Gateway, Security Service, Process)
- Предлагает конкретные действия

---

### 4️⃣ Safety & Stability

**Реализовано:**
- ✅ Все I/O операции — background threads
- ✅ Таймауты на всё:
  - HTTP health checks: 3s
  - kubectl commands: 5s
  - File reads: в background, не блокируют UI
- ✅ Любая ошибка:
  - Логируется
  - Не ломает UI
- ✅ Если kubectl недоступен → graceful fallback (пропускает Kubernetes секцию)

---

## UI Changes

### TabPane Structure
- **Control Tab:**
  - Header, Status, Buttons
  - Simple status log area (последние 200 строк для быстрых сообщений)
- **Logs Tab:**
  - Full LogViewer с переключателем файлов
  - Auto-scroll, обновление каждые 2 секунды

### Buttons
- "Open Logs Folder" → открывает `~/.jarvis/logs/` в файловом менеджере
- "Collect Diagnostics" → собирает snapshot и открывает файл

---

## Файлы

1. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LogViewer.kt` (новый)
2. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/DiagnosticsCollector.kt` (новый)
3. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (обновлён)
4. `scripts/verify-iteration-1.4.sh` (обновлён)

---

## Definition of Done

- ✅ Логи читаются и обновляются в UI
- ✅ Launcher не зависает при больших логах
- ✅ Diagnostics snapshot создаётся и открывается
- ✅ ERROR-статусы объяснимы человеку
- ✅ Verify script может проверить наличие diagnostics файла
- ✅ Ничего не сломалось из Stage 1–3

---

## Verify

```bash
# 1. Run verify script
./scripts/verify-iteration-1.4.sh --require-install
echo $?
# Ожидаемо: 0

# 2. Launch launcher
java -jar ~/.jarvis/app/launcher.jar

# 3. Check Logs tab:
#    - Switch between launcher.log and backend-launch.log
#    - Toggle auto-scroll
#    - Verify updates every 2 seconds

# 4. Check Diagnostics:
#    - Click "Collect Diagnostics"
#    - Verify file created in ~/.jarvis/logs/
#    - Verify file opens automatically
#    - Check content (version, health, logs, k8s status)

# 5. Check ERROR messages:
#    - Stop backend while running
#    - Verify human-readable error with actions
```

---

**Stage 4 готов к тестированию!**


