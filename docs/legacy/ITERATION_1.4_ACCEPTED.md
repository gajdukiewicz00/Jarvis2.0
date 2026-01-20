# Iteration 1.4 - Stage 1 & 2: ACCEPTED ✅

**Дата:** 2026-01-05  
**Статус:** ✅ **ACCEPTED (product-ready)**

---

## Acceptance Checklist — Все проверки пройдены

### Stage 1: Launcher Module ✅

- ✅ **A1. Логи и директории** — `launcher-start.log` и `launcher.log` создаются при запуске
- ✅ **A2. Idempotency** — Start/Stop идемпотентны, нет дубликатов процессов
- ✅ **A3. Портативность** — Product install независим от расположения репо

### Stage 2: Desktop Icon Integration ✅

- ✅ **B1. Product path** — `~/.jarvis/app/` структура установлена
- ✅ **B2. Desktop Actions** — Stop, Logs, Diagnostics работают

### Hardening Fixes ✅

- ✅ **Desktop file** — Использует `bash -lc "$HOME/.jarvis/app/bin/jarvis-launcher.sh"`
- ✅ **Launcher lock** — `flock` для `launcher.lock` (предотвращает двойной запуск)
- ✅ **Backend lock** — `flock` для `backend.lock` (предотвращает параллельные старты из UI)
- ✅ **VERSION & install.log** — Создаются при install, отображаются в UI

---

## Verification Results

```bash
./scripts/verify-iteration-1.4.sh
# Exit code: 0
# Status: ✅ Stage 1 & 2 ACCEPTED
```

**Режимы verify:**
- **Dev mode** (по умолчанию): WARN если product не установлен
- **Product mode** (`--require-install`): FAIL если product не установлен

---

## Lock Protection Tests

### Launcher Lock ✅
- Двойной запуск `jarvis-launcher.sh` → второй экземпляр мгновенно завершается
- Lock файл: `~/.jarvis/run/launcher.lock`

### Backend Lock ✅
- Множественные клики "Start Backend" → только один процесс запускается
- Lock файл: `~/.jarvis/run/backend.lock`
- Защита от race conditions в UI

---

## Product Install

```bash
# Build launcher
mvn -pl apps/launcher-javafx -DskipTests clean package

# Install to ~/.jarvis/app/
./scripts/product/jarvis-install.sh
```

**Структура после install:**
```
~/.jarvis/app/
├── launcher.jar
├── bin/
│   ├── jarvis-launcher.sh
│   ├── jarvis-stop.sh
│   └── jarvis-diagnostics.sh
├── config/
│   └── logback.xml
└── VERSION
```

**Desktop file:**
- `~/.local/share/applications/jarvis-launcher.desktop`
- `Exec=/usr/bin/env bash -lc "$HOME/.jarvis/app/bin/jarvis-launcher.sh"`
- `Terminal=false`

---

## Изменённые файлы

### Stage 1:
1. `pom.xml` — добавлен модуль launcher-javafx
2. `apps/launcher-javafx/pom.xml` (новый)
3. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` (новый)
4. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/ProcessRunner.kt` (новый, с backend lock)
5. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (новый, с version display)
6. `apps/launcher-javafx/src/main/resources/logback.xml` (новый)

### Stage 2:
7. `scripts/product/jarvis-launcher.sh` (новый, с launcher lock)
8. `scripts/product/jarvis-stop.sh` (новый, идемпотентный)
9. `scripts/product/jarvis-diagnostics.sh` (новый)
10. `scripts/product/jarvis-install.sh` (новый)
11. `jarvis-launcher.desktop` (новый, с bash -lc)
12. `scripts/verify-iteration-1.4.sh` (новый, с dev/product modes)

---

## Definition of Done

- ✅ Launcher module compiles and runs
- ✅ GUI window appears with status and buttons
- ✅ Can start/stop backend via UI
- ✅ Logs written to `~/.jarvis/logs/`
- ✅ Desktop icon launches without terminal
- ✅ Product install independent of repo location
- ✅ Lock files prevent concurrent execution
- ✅ Version displayed in UI
- ✅ All acceptance checklist items passed
- ✅ Verification script returns exit code 0

---

## Next Steps

Iteration 1.4 Stage 1 & 2 **ACCEPTED** — готово к использованию.

Следующие итерации (из Master Plan):
- Iteration 2: HTTPS/TLS (Iteration 7)
- Iteration 3: Self-check и recovery
- Iteration 4: Systemd integration (опционально)

---

**✅ Product-ready: Desktop launch работает, backend управляется через GUI, все locks защищают от race conditions.**


