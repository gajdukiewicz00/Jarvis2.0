# Jarvis 2.0 - Smart Home и PC Control

## Содержание
- [Smart Home Service](#smart-home-service)
- [PC Control Service](#pc-control-service)
- [Примеры запросов](#примеры-запросов)
- [Типичные ошибки](#типичные-ошибки)

---

## Smart Home Service

### Обзор

smart-home-service управляет IoT-устройствами через MQTT. Сервис отправляет команды на брокер Mosquitto, откуда их получают подключённые устройства.

### Архитектура

```
┌─────────────┐     ┌───────────────────┐     ┌───────────┐     ┌─────────┐
│ api-gateway │────▶│ smart-home-service │────▶│ mosquitto │────▶│ Devices │
│   (8080)    │     │      (8086)        │     │   (1883)  │     │  (IoT)  │
└─────────────┘     └───────────────────┘     └───────────┘     └─────────┘
```

### Endpoints

#### POST /api/v1/smarthome/devices/{deviceId}/action

Отправить команду на устройство.

**Request:**
```json
{
  "action": "TURN_ON",
  "payload": "{\"brightness\": 100}"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "deviceId": "living-room-light",
  "action": "TURN_ON",
  "payload": "{\"brightness\": 100}",
  "timestamp": "2025-12-02T10:30:00",
  "message": "Action sent successfully via MQTT"
}
```

**Error Response (500):**
```json
{
  "success": false,
  "deviceId": "living-room-light",
  "action": "TURN_ON",
  "error": "ACTION_FAILED",
  "message": "Failed to send action: MQTT broker unavailable",
  "timestamp": "2025-12-02T10:30:00"
}
```

#### GET /api/v1/smarthome/actions

Получить список поддерживаемых действий.

**Response:**
```json
{
  "supportedActions": [
    "TURN_ON", "TURN_OFF", "DIM", "BRIGHTEN",
    "SET_COLOR", "SET_TEMPERATURE", "LOCK", "UNLOCK"
  ],
  "description": "Smart home device control via MQTT"
}
```

### MQTT Topics

Команды отправляются на топики формата:
```
jarvis/smarthome/{deviceId}/{action}
```

Примеры:
- `jarvis/smarthome/living-room-light/TURN_ON`
- `jarvis/smarthome/thermostat/SET_TEMPERATURE`
- `jarvis/smarthome/front-door/LOCK`

### Конфигурация

```yaml
# application-docker.yml
mqtt:
  broker-url: tcp://mosquitto:1883

jarvis:
  mqtt:
    client-id: jarvis-smart-home
```

---

## PC Control Service

### Обзор

pc-control управляет локальной системой Linux: громкость, запуск приложений, горячие клавиши, таймеры.

### Endpoints

#### POST /api/v1/pc/action

Выполнить действие на ПК.

**Типы действий:**

| actionType | Описание | Параметры |
|------------|----------|-----------|
| `MEDIA_CONTROL` | Управление громкостью | `deltaPercent`, `direction` |
| `OPEN_APP` | Запуск приложения | `appName` |
| `HOTKEY` | Горячая клавиша | `keyCombination` |
| `SYSTEM_COMMAND` | Системная команда | `command`, `args` |

#### GET /api/v1/pc/actions

Получить описание поддерживаемых действий.

### Примеры запросов

#### Изменение громкости

**Request:**
```json
{
  "actionType": "MEDIA_CONTROL",
  "parameters": {
    "deltaPercent": "10",
    "direction": "+"
  }
}
```

**Response:**
```json
{
  "success": true,
  "actionType": "MEDIA_CONTROL",
  "volumeChange": "+10%",
  "timestamp": "2025-12-02T10:30:00",
  "message": "Volume changed successfully"
}
```

#### Запуск приложения

**Request:**
```json
{
  "actionType": "OPEN_APP",
  "parameters": {
    "appName": "firefox"
  }
}
```

**Response:**
```json
{
  "success": true,
  "actionType": "OPEN_APP",
  "appName": "firefox",
  "timestamp": "2025-12-02T10:30:00",
  "message": "Application launch initiated"
}
```

#### Горячая клавиша

**Request:**
```json
{
  "actionType": "HOTKEY",
  "parameters": {
    "keyCombination": "Alt+Tab"
  }
}
```

**Response:**
```json
{
  "success": true,
  "actionType": "HOTKEY",
  "keyCombination": "Alt+Tab",
  "timestamp": "2025-12-02T10:30:00",
  "message": "Hotkey executed successfully"
}
```

#### Таймер

**Request:**
```json
{
  "actionType": "SYSTEM_COMMAND",
  "parameters": {
    "command": "timer",
    "args": "300"
  }
}
```

**Response:**
```json
{
  "success": true,
  "actionType": "SYSTEM_COMMAND",
  "command": "timer",
  "durationSeconds": 300,
  "timestamp": "2025-12-02T10:30:00",
  "message": "Timer started for 300 seconds"
}
```

### Поддерживаемые приложения

| appName | Команда |
|---------|---------|
| `browser`, `chrome`, `firefox` | `xdg-open https://google.com` |
| `youtube` | `xdg-open https://youtube.com` |
| `spotify` | `spotify` |
| `ide`, `vscode`, `code` | `code` |
| `calculator`, `calc` | `gnome-calculator` |
| `terminal` | `gnome-terminal` |
| (другое) | `xdg-open {appName}` |

### Системные требования

Для работы pc-control в Docker требуется:

```yaml
# docker-compose.yml
pc-control:
  volumes:
    - /run/user/1000/pulse:/run/user/1000/pulse  # PulseAudio
    - /tmp/.X11-unix:/tmp/.X11-unix              # X11
  environment:
    - PULSE_SERVER=unix:/run/user/1000/pulse/native
    - DISPLAY=:0
```

---

## Примеры запросов

### cURL

```bash
# Smart Home: включить свет
curl -X POST http://localhost:8080/api/v1/smarthome/devices/living-room-light/action \
  -H "Content-Type: application/json" \
  -d '{"action":"TURN_ON","payload":"{\"brightness\":100}"}'

# PC Control: увеличить громкость
curl -X POST http://localhost:8080/api/v1/pc/action \
  -H "Content-Type: application/json" \
  -d '{"actionType":"MEDIA_CONTROL","parameters":{"deltaPercent":"10","direction":"+"}}'

# PC Control: открыть браузер
curl -X POST http://localhost:8080/api/v1/pc/action \
  -H "Content-Type: application/json" \
  -d '{"actionType":"OPEN_APP","parameters":{"appName":"firefox"}}'

# PC Control: запустить таймер на 5 минут
curl -X POST http://localhost:8080/api/v1/pc/action \
  -H "Content-Type: application/json" \
  -d '{"actionType":"SYSTEM_COMMAND","parameters":{"command":"timer","args":"300"}}'
```

---

## Типичные ошибки

### Smart Home

**MQTT брокер недоступен:**
```json
{
  "success": false,
  "error": "ACTION_FAILED",
  "message": "Failed to send action: Connection refused"
}
```

**Решение:** Проверить статус Mosquitto: `docker compose logs mosquitto`

### PC Control

**Отсутствует PulseAudio:**
```json
{
  "success": false,
  "error": "EXECUTION_FAILED",
  "message": "Failed to execute action: pactl: command not found"
}
```

**Решение:** Проверить volume-монтирование PulseAudio в docker-compose.yml

**Отсутствует X11 (для hotkey/openApp):**
```json
{
  "success": false,
  "error": "EXECUTION_FAILED",
  "message": "Failed to execute action: cannot open display"
}
```

**Решение:** Проверить DISPLAY переменную и X11 socket

**Неизвестный тип действия:**
```json
{
  "success": false,
  "error": "UNKNOWN_ACTION_TYPE",
  "message": "Unknown action type: SHUTDOWN"
}
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

