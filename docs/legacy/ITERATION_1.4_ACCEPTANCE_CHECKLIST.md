# Iteration 1.4 - Acceptance Checklist

**Дата:** 2026-01-05  
**Статус:** ✅ Все проверки реализованы

---

## Acceptance Checklist: Stage 1 (Launcher)

### A1. Логи и директории (без терминала)
- ✅ Запуск из меню создаёт `~/.jarvis/logs/launcher-start.log`
- ✅ Запуск из меню создаёт `~/.jarvis/logs/launcher.log`
- ✅ Ошибка в префлайте → понятное GUI-сообщение + запись в launcher-start.log

**Проверка:**
```bash
ls -la ~/.jarvis/logs/launcher-start.log
ls -la ~/.jarvis/logs/launcher.log
```

### A2. Idempotency
- ✅ "Start Backend" второй раз не запускает второй процесс
- ✅ "Stop Backend" при отсутствии процесса не падает, пишет "already stopped"

**Проверка:**
1. Запустить launcher
2. Нажать "Start Backend" дважды → должен показать "already running"
3. Нажать "Stop Backend" дважды → должен показать "already stopped"

### A3. Портативность (не зависит от cwd и расположения репо)
- ✅ Репо можно перенести/удалить, а product install продолжает работать (иконка запускает)

**Проверка:**
```bash
# Install product
./scripts/product/jarvis-install.sh

# Переместить репо
mv /home/kwaqa/IdeaProjects/Jarvis2.0 /tmp/jarvis-test

# Иконка должна работать (product install независим)
```

---

## Acceptance Checklist: Stage 2 (Desktop Icon Integration)

### B1. Product path
- ✅ `~/.jarvis/app/launcher.jar` существует
- ✅ `~/.jarvis/app/bin/jarvis-launcher.sh` существует и executable
- ✅ `~/.local/share/applications/jarvis-launcher.desktop` указывает на `~/.jarvis/app/bin/jarvis-launcher.sh`

**Проверка:**
```bash
ls -la ~/.jarvis/app/launcher.jar
ls -la ~/.jarvis/app/bin/jarvis-launcher.sh
cat ~/.local/share/applications/jarvis-launcher.desktop | grep Exec
```

### B2. Desktop Actions
- ✅ "Stop Jarvis" работает из Actions и идемпотентен
- ✅ "Diagnostics" открывает/сохраняет вывод в `~/.jarvis/logs/` (и не требует терминала)

**Проверка:**
1. Правый клик на иконке → "Stop Jarvis" → должно работать
2. Правый клик на иконке → "Diagnostics" → должно открыть диалог

---

## Hardening Fixes (Must-Have)

### 1. Desktop file использует bash -lc (не ~)
**Проблема:** `.desktop` не гарантирует разворачивание `~`

**Решение:**
```desktop
Exec=/usr/bin/env bash -lc "$HOME/.jarvis/app/bin/jarvis-launcher.sh"
```

**Проверка:**
```bash
grep "bash -lc.*\$HOME" ~/.local/share/applications/jarvis-launcher.desktop
```

### 2. Lock-file (предотвращение двойного запуска)
**Проблема:** Двойной клик может запустить два launcher'а

**Решение:**
```bash
exec 9>"$HOME/.jarvis/run/launcher.lock"
flock -n 9 || exit 0
```

**Проверка:**
```bash
grep "flock.*launcher.lock" scripts/product/jarvis-launcher.sh
```

### 3. Версия/обновление
**Проблема:** Нужно знать версию для отладки

**Решение:**
- `~/.jarvis/app/VERSION` - версия из pom.xml
- `~/.jarvis/logs/install.log` - лог установки
- Launcher UI показывает версию

**Проверка:**
```bash
cat ~/.jarvis/app/VERSION
cat ~/.jarvis/logs/install.log
# В launcher UI должна быть видна версия
```

---

## Автоматическая проверка

Запустить verification script:
```bash
./scripts/verify-iteration-1.4.sh
```

**Ожидаемый результат:**
- ✅ All critical checks passed
- ✅ Stage 1 & 2 ACCEPTED

---

## Definition of Done

### Stage 1:
- ✅ Все 8 проверок проходят
- ✅ Логи создаются при запуске
- ✅ Idempotency работает
- ✅ Портативность (product install независим от репо)

### Stage 2:
- ✅ Product install в `~/.jarvis/app/`
- ✅ Desktop file использует `bash -lc` с `$HOME`
- ✅ Lock-file предотвращает двойной запуск
- ✅ VERSION и install.log создаются
- ✅ Launcher UI показывает версию

---

**Готово к формальному принятию!**


