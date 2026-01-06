# Iteration 1.4 - Stage 2 Product Paths Fix

**Дата:** 2026-01-05  
**Проблема:** Абсолютные пути в .desktop не переживут перенос/обновление

---

## Решение: Product Install в ~/.jarvis/app/

### Структура продукта:
```
~/.jarvis/
├── app/                    # Product install location
│   ├── launcher.jar       # Launcher JAR (copied from repo)
│   ├── bin/               # Scripts
│   │   ├── jarvis-launcher.sh
│   │   ├── jarvis-stop.sh
│   │   └── jarvis-diagnostics.sh
│   └── config/
│       └── logback.xml
├── logs/                   # Logs (already exists)
├── run/                    # PID files (already exists)
└── data/                   # Data (already exists)
```

### Desktop file (после install):
```
~/.local/share/applications/jarvis-launcher.desktop
Exec=~/.jarvis/app/bin/jarvis-launcher.sh
```

---

## Установка

### Development mode (текущий):
- Desktop file указывает на `scripts/product/jarvis-launcher.sh`
- Launcher JAR из `apps/launcher-javafx/target/`
- Зависит от расположения репо

### Product mode (после install):
```bash
# Build launcher
mvn -pl apps/launcher-javafx -DskipTests clean package

# Install to ~/.jarvis/app/
./scripts/product/jarvis-install.sh
```

После install:
- Desktop file в `~/.local/share/applications/jarvis-launcher.desktop`
- Все пути указывают на `~/.jarvis/app/`
- Не зависит от расположения репо

---

## Wrapper Script Improvements

### ✅ Strict mode restored:
```bash
set -euo pipefail
```

### ✅ Safe error handling:
- Необязательные команды: `|| true`
- Ошибки в лог: `>> "$LOG" 2>&1`
- GUI уведомления: `zenity` / `notify-send`

### ✅ Product/Dev detection:
- Проверяет `~/.jarvis/app/launcher.jar` (product)
- Fallback на repo location (dev)

---

## Stop Script Improvements

### ✅ Idempotent:
- Проверяет PID перед остановкой
- Показывает "already stopped" если ничего не запущено
- Не падает если процесс уже остановлен

---

## Проверка

### 1. Development mode:
```bash
# Desktop file в репо
cp jarvis-launcher.desktop ~/.local/share/applications/
update-desktop-database ~/.local/share/applications/

# Запуск через меню → должно работать
```

### 2. Product install:
```bash
# Install
./scripts/product/jarvis-install.sh

# Проверка
ls -la ~/.jarvis/app/
# Ожидаемый результат: launcher.jar, bin/, config/

# Desktop file обновлён автоматически
cat ~/.local/share/applications/jarvis-launcher.desktop | grep Exec
# Ожидаемый результат: Exec=~/.jarvis/app/bin/jarvis-launcher.sh
```

### 3. Перенос репо:
```bash
# Переместить репо в другое место
mv /home/kwaqa/IdeaProjects/Jarvis2.0 /tmp/jarvis-test

# Product install должен работать (не зависит от репо)
# Development mode сломается (ожидаемо)
```

### 4. Verification script:
```bash
./scripts/verify-iteration-1.4.sh
# Ожидаемый результат: All checks passed
```

---

## Изменённые файлы

1. `scripts/product/jarvis-launcher.sh` - strict mode + product/dev detection
2. `scripts/product/jarvis-stop.sh` - idempotent
3. `scripts/product/jarvis-install.sh` (новый) - product install
4. `scripts/verify-iteration-1.4.sh` (новый) - verification

---

**Готово к проверке!**

