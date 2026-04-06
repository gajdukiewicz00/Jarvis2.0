# Jarvis Desktop Launcher

Prod-grade one-click запуск Jarvis через иконку. Никаких ручных команд: Launcher сам проверяет зависимости, настраивает TLS/hosts и запускает backend + UI.

## Быстрый старт (UI-first)

1. Открой меню приложений и запусти **Jarvis**.
2. Desktop entry поднимет canonical wrapper `~/.jarvis/app/bin/jarvis-launcher.sh`.
3. Wrapper делегирует в актуальный repo launcher, если workspace доступен, и при необходимости сам пересоберёт `launcher-javafx` и `desktop-client-javafx`.
4. Launcher auto-start'ит полный локальный стек; кнопки **Start All** и **Start Desktop** остаются как ручной fallback/diagnostics surface.

## Desktop integration (launcher icon)

- Ровно один desktop-файл: `~/.local/share/applications/jarvis.desktop` (Desktop ID: `jarvis`, Name: Jarvis).
- Создается **только** на install-этапе (idempotent), runtime его не трогает.
- Инсталлятор удаляет legacy/duplicate entries в стандартных каталогах (user + system).
- Exec указывает на wrapper: `~/.jarvis/app/bin/jarvis-launcher.sh`.
- Wrapper является стабильным shim'ом: при наличии repo он передаёт управление в `scripts/product/jarvis-launcher.sh` из workspace, чтобы GUI не запускал устаревшую копию из `~/.jarvis/app`.
- Icon — абсолютный путь: `~/.jarvis/app/assets/icons/jarvis.png`.

## Если иконки дублируются

- Запусти установку повторно: она удалит `*jarvis*.desktop` в стандартных путях.
- Если нет pkexec, очистка system-level пропускается (останется user-level установка).
- Ручная чистка (если нужно): `~/.local/share/applications`, `~/.config/autostart`, `/usr/share/applications`, `/usr/local/share/applications`, `/var/lib/snapd/desktop/applications`, `~/.local/share/flatpak/exports/share/applications`.

## Кнопки и что они делают

- **Start All** — полный запуск backend + ожидание READY.
- **Start Desktop** — UI клиента (после READY).
- **Fix TLS** — генерация сертификатов + установка CA + /etc/hosts.
- **Reset Jarvis** — чистка namespace `jarvis` (с подтверждением).
- **Disk Cleanup** — безопасная уборка диска для борьбы с DiskPressure.
- **Enable GPU** — настройка NVIDIA runtime и device plugin для k3s.
- **Run Acceptance** — verify + acceptance через internal port-forward.

## LLM и Memory

LLM и Memory — опциональные (по умолчанию выключены).
Их ошибки **не блокируют** READY. При проблемах будет статус **DEGRADED**.

## Как это работает

```
Launcher UI
 ├─> desktop entry вызывает стабильный wrapper в ~/.jarvis/app
 ├─> wrapper резолвит repo source of truth и обновляет launcher/desktop JAR при drift
 ├─> launcher проверяет зависимости, секреты, TLS, /etc/hosts
 ├─> launcher запускает local runtime (`scripts/runtime-up.sh`) when repo is available, otherwise installed product path
 ├─> ждёт READY/DEGRADED
 └─> запускает Desktop UI
```

## Troubleshooting (только если нужно)

- Открыть логи: `./jarvis-logs.sh`
- Проверить pod'ы: `kubectl get pods -n jarvis`
