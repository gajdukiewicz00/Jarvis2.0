# Iteration 1.5 - Stage 9: Acceptance Run

**Дата:** 2026-01-05  
**Цель:** Product Release & Update Flow (zero-surprises)

---

## Команды проверки

### 1) Fresh Install

```bash
# Удалить существующую установку (если есть)
rm -rf ~/.jarvis/app

# Выполнить fresh install
./scripts/product/jarvis-install.sh

# Проверить VERSION файл
cat ~/.jarvis/app/VERSION

# Проверить, что backup не создан (fresh install)
ls ~/.jarvis/app/backup 2>/dev/null || echo "✅ No backup (fresh install)"
```

**Ожидаемый результат:**
- VERSION файл создан с версией из pom.xml
- Backup директория не создана
- Launcher показывает версию без предупреждения

---

### 2) Upgrade Flow

```bash
# Изменить версию в pom.xml (для теста)
# Или использовать существующую установку

# Выполнить install снова (upgrade)
./scripts/product/jarvis-install.sh

# Проверить backup
ls -la ~/.jarvis/app/backup/

# Проверить, что backup содержит старую версию
OLD_VERSION=$(ls ~/.jarvis/app/backup/ | head -1)
ls ~/.jarvis/app/backup/$OLD_VERSION/

# Проверить новый VERSION
cat ~/.jarvis/app/VERSION
```

**Ожидаемый результат:**
- Backup создан в `~/.jarvis/app/backup/<old-version>/`
- Backup содержит старые файлы (launcher.jar, bin/, config/, VERSION)
- Новый VERSION файл обновлён
- Launcher показывает предупреждение о version mismatch (если launcher.jar не пересобран)

---

### 3) Release Build

```bash
# Создать release архив
./scripts/product/jarvis-build-release.sh

# Проверить архив
ls -lh target/release/jarvis-release-*.tar.gz

# Проверить структуру архива
tar -tzf target/release/jarvis-release-*.tar.gz | head -20

# Проверить содержимое
cd /tmp
tar -xzf /path/to/jarvis-release-*.tar.gz
cd jarvis-release-*
ls -la
```

**Ожидаемый результат:**
- Release архив создан: `target/release/jarvis-release-<version>.tar.gz`
- Архив содержит:
  - `launcher.jar`
  - `desktop-client-javafx-<version>.jar`
  - `bin/` (scripts)
  - `config/` (logback.xml)
  - `docs/` (ACCEPTANCE.md, README.md)
  - `VERSION`
  - `install.sh`
  - `README.md`

---

### 4) Verify Pack

```bash
# Полная проверка
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
```

**Ожидаемый результат:**
- Exit code: 0
- Stage 9 проверки:
  - ✅ VERSION file exists
  - ✅ VERSION matches root pom.xml
  - ✅ Launcher JAR manifest version matches VERSION file
  - ✅ Upgrade backup directory exists (если был upgrade)
  - ✅ jarvis-build-release.sh exists and is executable

---

### 5) Version Mismatch Warning (Launcher)

```bash
# Запустить launcher
~/.jarvis/app/bin/jarvis-launcher.sh

# Проверить version label в UI
# Должно показать предупреждение если:
# - VERSION файл != manifest version в launcher.jar
```

**Ожидаемый результат:**
- Если версии совпадают: "Version: 0.1.0-SNAPSHOT"
- Если версии не совпадают: "Version: 0.1.0-SNAPSHOT ⚠️ (launcher: 0.2.0)"
- Предупреждение отображается оранжевым цветом

---

## Acceptance Criteria

### ✅ Версионирование
- [x] Единый источник версии (root pom.xml)
- [x] launcher.jar manifest Implementation-Version
- [x] ~/.jarvis/app/VERSION обновляется при install

### ✅ Update Flow
- [x] jarvis-install.sh поддерживает fresh install
- [x] jarvis-install.sh поддерживает upgrade
- [x] Backup предыдущей версии создаётся при upgrade
- [x] Launcher показывает предупреждение при version mismatch

### ✅ Release Artifact
- [x] jarvis-build-release.sh создаёт release архив
- [x] Архив включает все необходимые файлы
- [x] Release независим от расположения репозитория

### ✅ Verification Pack
- [x] Проверка VERSION/manifest match
- [x] Проверка upgrade flow (backup directory)
- [x] Проверка jarvis-build-release.sh

---

## Известные ограничения

1. **Version mismatch warning:** Показывается только при запуске launcher. Если launcher не перезапущен после upgrade, предупреждение не появится до следующего запуска.

2. **Backup cleanup:** Старые backup'ы не удаляются автоматически. Можно добавить cleanup в будущем.

3. **Release archive size:** Может быть большим из-за fat JARs. Можно добавить сжатие или разделение на части.

---

**Stage 9 готов к acceptance run!**


