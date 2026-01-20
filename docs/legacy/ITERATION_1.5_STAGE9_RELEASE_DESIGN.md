# Iteration 1.5 - Stage 9: Release & Update Flow Design

**Дата:** 2026-01-05  
**Цель:** Zero-surprises product release and update flow

---

## Структура Release Архива

```
jarvis-release-0.1.0-SNAPSHOT.tar.gz
├── jarvis-release-0.1.0-SNAPSHOT/
│   ├── launcher.jar                    # Launcher JAR (fat JAR)
│   ├── desktop-client-javafx-0.1.0-SNAPSHOT.jar  # Desktop client JAR
│   ├── install.sh                      # Install script (wrapper)
│   ├── bin/
│   │   ├── jarvis-launcher.sh
│   │   ├── jarvis-stop.sh
│   │   └── jarvis-diagnostics.sh
│   ├── config/
│   │   └── logback.xml
│   ├── desktop/
│   │   └── jarvis-launcher.desktop.template
│   ├── docs/
│   │   ├── ACCEPTANCE.md
│   │   └── README.md
│   └── VERSION                         # Version file (0.1.0-SNAPSHOT)
```

---

## Версионирование

### Единый источник версии
- **Root pom.xml:** `<version>0.1.0-SNAPSHOT</version>`
- **Launcher manifest:** `Implementation-Version` из `${project.version}` (уже есть)
- **VERSION file:** Создаётся при install из pom.xml

### Чтение версии в Launcher
```kotlin
// Из manifest JAR
val manifestVersion = getManifestVersion()

// Из ~/.jarvis/app/VERSION
val installedVersion = readInstalledVersion()

// Показываем предупреждение если mismatch
if (manifestVersion != installedVersion) {
    showVersionMismatchWarning(manifestVersion, installedVersion)
}
```

---

## Update Flow

### Fresh Install
1. Проверка, что `~/.jarvis/app/` не существует или пуст
2. Копирование всех файлов
3. Создание VERSION файла
4. Создание desktop file
5. Логирование в `~/.jarvis/logs/install.log`

### Upgrade
1. Проверка существующей установки (`~/.jarvis/app/VERSION`)
2. Чтение текущей версии
3. Создание backup: `~/.jarvis/app/backup/<old-version>/`
4. Копирование всех файлов в backup
5. Установка новой версии поверх
6. Обновление VERSION файла
7. Логирование upgrade в `install.log`

### Backup Structure
```
~/.jarvis/app/backup/
├── 0.1.0-SNAPSHOT/
│   ├── launcher.jar
│   ├── bin/
│   └── config/
└── 0.2.0/
    ├── launcher.jar
    └── ...
```

---

## Release Build Script

### `scripts/product/jarvis-build-release.sh`

**Функции:**
1. Читает версию из root pom.xml
2. Собирает launcher JAR: `mvn -pl apps/launcher-javafx -DskipTests clean package`
3. Собирает desktop-client JAR: `mvn -pl apps/desktop-client-javafx -DskipTests clean package`
4. Создаёт временную директорию для release
5. Копирует все необходимые файлы
6. Создаёт VERSION файл
7. Упаковывает в `jarvis-release-<version>.tar.gz`
8. Выводит путь к release архиву

**Независимость от расположения репозитория:**
- Использует `$(dirname "${BASH_SOURCE[0]}")` для определения пути к скрипту
- Все пути относительные от корня репозитория

---

## Launcher Version Mismatch Warning

### Сценарии

**1. Upgrade выполнен, но launcher не перезапущен:**
- Manifest version: `0.2.0`
- Installed version: `0.1.0-SNAPSHOT`
- **Действие:** Показать предупреждение "Version mismatch detected. Please restart launcher."

**2. Launcher JAR обновлён, но install не выполнен:**
- Manifest version: `0.2.0`
- Installed version: `0.2.0` (но JAR старый)
- **Действие:** Показать предупреждение "Launcher JAR version differs from installed version."

**3. Всё синхронизировано:**
- Manifest version: `0.1.0-SNAPSHOT`
- Installed version: `0.1.0-SNAPSHOT`
- **Действие:** Ничего не показывать

---

## Verification Pack

### Новые проверки

1. **VERSION/manifest match:**
   - Проверка, что `~/.jarvis/app/VERSION` существует
   - Проверка, что версия в VERSION совпадает с manifest версией launcher.jar

2. **Upgrade flow:**
   - Выполнить install дважды
   - Проверка, что backup создан при втором install
   - Проверка, что backup содержит старую версию

3. **Release artifact:**
   - Проверка, что `jarvis-build-release.sh` создаёт корректный архив
   - Проверка структуры архива

---

## Implementation Plan

1. ✅ Улучшить `jarvis-install.sh` для upgrade и backup
2. ✅ Создать `jarvis-build-release.sh`
3. ✅ Обновить `LauncherApplication` для version mismatch warning
4. ✅ Обновить verify script для Stage 9 проверок

---

**Stage 9 готов к реализации!**


