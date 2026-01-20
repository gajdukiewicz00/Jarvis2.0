# Jarvis Desktop Launcher

Prod-grade one-click запуск Jarvis через иконку. Никаких ручных команд: Launcher сам проверяет зависимости, настраивает TLS/hosts и запускает backend + UI.

## Быстрый старт (UI-first)

1. Открой меню приложений и запусти **Jarvis**.
2. Нажми **Start All**.
3. Если нужно — Launcher сам попросит пароль администратора через GUI (pkexec).
4. Когда статус станет **READY** или **DEGRADED**, нажми **Start Desktop**.

## Desktop integration (launcher icon)

- Ровно один desktop-файл: `~/.local/share/applications/jarvis.desktop` (Desktop ID: `jarvis`, Name: Jarvis).
- Создается **только** на install-этапе (idempotent), runtime его не трогает.
- Инсталлятор удаляет legacy/duplicate entries в стандартных каталогах (user + system).
- Exec указывает на wrapper: `~/.jarvis/app/bin/jarvis-launcher.sh`.
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
 ├─> проверяет зависимости, секреты, TLS, /etc/hosts
 ├─> запускает jarvis-launch.sh
 ├─> ждёт READY/DEGRADED
 └─> запускает Desktop UI
```

## Troubleshooting (только если нужно)

- Открыть логи: `./jarvis-logs.sh`
- Проверить pod'ы: `kubectl get pods -n jarvis`
