# Jarvis 2.0 - Архитектурный обзор

## Содержание
- [Обзор системы](#обзор-системы)
- [Список микросервисов](#список-микросервисов)
- [Взаимодействия между сервисами](#взаимодействия-между-сервисами)
- [Ключевые DTO и Endpoints](#ключевые-dto-и-endpoints)
- [Сетевая топология](#сетевая-топология)

---

## Обзор системы

Jarvis 2.0 — это микросервисная система персонального AI-ассистента, построенная на Spring Boot 3.3.4 и Java 21. Система включает 14 backend-сервисов + desktop-клиент + LLM-сервер на Python.

### Технологический стек
- **Backend**: Spring Boot 3.3.4, Spring Cloud OpenFeign 2023.0.3
- **База данных**: PostgreSQL 16
- **Messaging**: MQTT (Eclipse Mosquitto)
- **Контейнеризация**: Docker Compose
- **LLM**: h2oGPT-7B (Python FastAPI)
- **Desktop**: JavaFX (Kotlin)
- **Mobile**: Android (Kotlin, в разработке)

---

## Список микросервисов

### Core Services (основные сервисы)

| Сервис | Порт | Ответственность | БД |
|--------|------|-----------------|-----|
| **api-gateway** | 8080 | Единая точка входа, JWT-аутентификация, маршрутизация, rate-limiting | Нет |
| **security-service** | 8088 | Регистрация/логин, генерация JWT-токенов | jarvis_security |
| **orchestrator** | 8083 | Координация между сервисами, распределение запросов | Нет |

### AI/NLP Services (интеллектуальные сервисы)

| Сервис | Порт | Ответственность | БД |
|--------|------|-----------------|-----|
| **voice-gateway** | 8081 | STT (Vosk), TTS, обработка аудио | Нет |
| **nlp-service** | 8082 | NLU, распознавание интентов, извлечение сущностей | Нет |
| **llm-service** | 8091 | Интеграция с LLM (h2oGPT), WebSocket API | Нет |
| **llm-server** | 5000 | Python FastAPI сервер для h2oGPT-7B модели | Нет |
| **assistant-core** | 8090 | Ядро логики ассистента, память, поведенческий анализ | jarvis_assistant_core |

### Domain Services (бизнес-сервисы)

| Сервис | Порт | Ответственность | БД |
|--------|------|-----------------|-----|
| **life-tracker** | 8085 | Учёт расходов, тайм-трекинг, календарь | jarvis_db |
| **analytics-service** | 8087 | Аналитика по расходам, времени, календарю | Нет (Feign → life-tracker) |
| **planner-service** | 8092 | Планирование задач, напоминания, daily/weekly планы | jarvis_db |
| **user-profile** | 8089 | Профили пользователей, цели, привычки, настройки | jarvis_user_profile |

### Integration Services (интеграционные сервисы)

| Сервис | Порт | Ответственность | БД |
|--------|------|-----------------|-----|
| **pc-control** | 8084 | Управление ПК: громкость, приложения, hotkeys | Нет |
| **smart-home-service** | 8086 | IoT устройства через MQTT | Нет (MQTT → Mosquitto) |

### Infrastructure (инфраструктура)

| Сервис | Порт | Ответственность |
|--------|------|-----------------|
| **postgres** | 5432 | PostgreSQL 16 с 4 базами данных |
| **mosquitto** | 1883, 9001 | MQTT брокер для smart-home |

### Clients (клиенты)

| Клиент | Технология | Ответственность |
|--------|------------|-----------------|
| **desktop-client-javafx** | JavaFX + Kotlin | GUI-клиент с голосовым управлением |
| **mobile-client** | Android + Kotlin | Мобильное приложение (в разработке) |

---

## Взаимодействия между сервисами

### HTTP/REST (Feign Clients)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           api-gateway (8080)                            │
│   JWT Validation | Routing | Rate Limiting | CORS                       │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────────┐
        │                           │                               │
        ▼                           ▼                               ▼
┌───────────────┐           ┌───────────────┐               ┌───────────────┐
│security-service│          │  life-tracker │               │ analytics-    │
│    (8088)     │           │    (8085)     │◄──Feign──────│ service (8087)│
│               │           │               │               │               │
│ /auth/login   │           │ /api/v1/life/ │               │/api/v1/       │
│ /auth/register│           │   finance/    │               │analytics/     │
│ /auth/refresh │           │   time/       │               │               │
└───────────────┘           │   calendar/   │               └───────────────┘
                            └───────────────┘
        │                           │                               │
        ▼                           ▼                               ▼
┌───────────────┐           ┌───────────────┐               ┌───────────────┐
│  orchestrator │           │  pc-control   │               │ smart-home-   │
│    (8083)     │◄──Feign──│    (8084)     │               │ service (8086)│
│               │           │               │               │               │
│/api/v1/       │           │ /api/v1/pc/   │               │/api/v1/       │
│orchestrator/  │           │   action      │               │smarthome/     │
└───────┬───────┘           └───────────────┘               └───────┬───────┘
        │                                                           │
        ▼                                                           ▼
┌───────────────┐           ┌───────────────┐               ┌───────────────┐
│  nlp-service  │           │ voice-gateway │               │  mosquitto    │
│    (8082)     │           │    (8081)     │               │ (1883/9001)   │
│               │           │               │               │               │
│/api/v1/nlp/   │           │ /api/v1/voice/│               │ MQTT broker   │
│  analyze      │           │   stt, tts    │               │               │
└───────────────┘           └───────────────┘               └───────────────┘
```

### Feign-связи (детали)

| Клиент | Сервер | Endpoints |
|--------|--------|-----------|
| api-gateway → security-service | AuthClient | POST /auth/login, /auth/register, /auth/refresh |
| api-gateway → life-tracker | LifeTrackerClient | GET /api/v1/life/finance/expenses, /time/records, /calendar/events |
| api-gateway → analytics-service | AnalyticsClient | GET /api/v1/analytics/* |
| api-gateway → pc-control | PcControlClient | POST /api/v1/pc/action |
| api-gateway → smart-home-service | SmartHomeClient | POST /api/v1/smarthome/devices/{id}/action |
| api-gateway → orchestrator | OrchestratorClient | POST /api/v1/orchestrator/execute |
| api-gateway → nlp-service | NlpServiceClient | POST /api/v1/nlp/analyze |
| api-gateway → voice-gateway | VoiceGatewayClient | POST /api/v1/voice/stt, /tts |
| analytics-service → life-tracker | LifeTrackerClient | GET /api/v1/life/finance/expenses, /time/records, /calendar/events |
| orchestrator → nlp-service | NlpClient | POST /api/v1/nlp/analyze |
| orchestrator → pc-control | PcControlClient | POST /api/v1/pc/action |
| planner-service → life-tracker | LifeTrackerClient | GET /api/v1/life/* |
| planner-service → analytics-service | AnalyticsClient | GET /api/v1/analytics/* |
| planner-service → user-profile | UserProfileClient | GET /api/v1/user-profile/* |
| planner-service → llm-service | LlmClient | POST /api/v1/llm/chat |
| assistant-core → life-tracker | LifeTrackerClient | GET /api/v1/life/* |
| voice-gateway → orchestrator | OrchestratorClient (REST) | POST /api/v1/orchestrator/execute |

### Зависимости при старте (docker-compose depends_on)

```
postgres (healthcheck: ready)
    │
    ├── life-tracker
    ├── security-service  
    ├── user-profile
    ├── assistant-core
    └── planner-service
            │
            ├── analytics-service (depends: life-tracker)
            └── llm-service (depends: llm-server healthy)

mosquitto
    └── smart-home-service

api-gateway (depends: voice-gateway, nlp-service, orchestrator, pc-control, 
             life-tracker, assistant-core, security-service)
```

---

## Ключевые DTO и Endpoints

### Auth Flow (security-service)

**POST /auth/register**
```json
// Request
{
  "username": "string",
  "password": "string",
  "role": "USER|ADMIN"
}

// Response (201 Created)
{
  "accessToken": "jwt...",
  "refreshToken": "jwt...",
  "expiresIn": 3600
}
```

**POST /auth/login**
```json
// Request
{
  "username": "string",
  "password": "string"
}

// Response (200 OK)
{
  "accessToken": "jwt...",
  "refreshToken": "jwt...",
  "expiresIn": 3600
}
```

### Finance (life-tracker)

**POST /api/v1/life/finance/expense**
```json
// Request
{
  "amount": 100.50,
  "currency": "EUR",
  "category": "FOOD",
  "description": "Lunch"
}

// Response
{
  "id": 1,
  "amount": 100.50,
  "currency": "EUR",
  "category": "FOOD",
  "description": "Lunch",
  "date": "2025-12-02T12:00:00"
}
```

**GET /api/v1/life/finance/expenses**
```json
// Response
[
  {
    "id": 1,
    "amount": 100.50,
    "currency": "EUR",
    "category": "FOOD",
    "description": "Lunch",
    "date": "2025-12-02T12:00:00"
  }
]
```

### Time Tracking (life-tracker)

**POST /api/v1/life/time/record**
```json
// Request
{
  "task": "Coding",
  "startTime": "2025-12-02T09:00:00",
  "endTime": "2025-12-02T12:00:00"
}
```

**GET /api/v1/life/time/records**

### Calendar (life-tracker)

**POST /api/v1/life/calendar/event**
```json
// Request
{
  "title": "Meeting",
  "startTime": "2025-12-02T14:00:00",
  "endTime": "2025-12-02T15:00:00",
  "description": "Team sync"
}
```

**GET /api/v1/life/calendar/events**

### Analytics (analytics-service)

**GET /api/v1/analytics/overview** - Сводка по расходам и времени
**GET /api/v1/analytics/expenses/by-month** - Расходы по месяцам
**GET /api/v1/analytics/expenses/by-category** - Расходы по категориям
**GET /api/v1/analytics/time/summary** - Статистика времени
**GET /api/v1/analytics/calendar/summary** - Статистика календаря

### PC Control (pc-control)

**POST /api/v1/pc/action**
```json
// Request
{
  "actionType": "MEDIA_CONTROL|OPEN_APP|HOTKEY|SYSTEM_COMMAND",
  "parameters": {
    "deltaPercent": "10",
    "direction": "+",
    "appName": "firefox",
    "keyCombination": "CTRL+ALT+T",
    "command": "timer",
    "args": "300"
  }
}
```

### Smart Home (smart-home-service)

**POST /api/v1/smarthome/devices/{deviceId}/action**
```json
// Request
{
  "action": "TURN_ON|TURN_OFF|DIM|SET_COLOR|SET_TEMPERATURE",
  "payload": "{\"brightness\": 80}"
}
```

### Planner (planner-service)

**GET /api/v1/planner/daily?userId=denis** - Дневной план
**GET /api/v1/planner/weekly?userId=denis** - Недельный план
**POST /api/v1/planner/tasks** - Создать задачу
**POST /api/v1/planner/reminders** - Создать напоминание
**GET /api/v1/planner/analytics/habits** - Аналитика привычек

---

## Сетевая топология

### Docker Network: jarvis-net

Все сервисы находятся в единой bridge-сети `jarvis-net`. Внутри сети сервисы доступны по имени контейнера:

- `postgres:5432`
- `life-tracker:8085`
- `security-service:8088`
- `api-gateway:8080`
- и т.д.

### Внешние порты (доступны с хоста)

| Порт | Сервис | Назначение |
|------|--------|------------|
| 8080 | api-gateway | Основной API |
| 8092 | planner-service | Прямой доступ к планировщику |
| 5432 | postgres | Доступ к БД для разработки |
| 1883 | mosquitto | MQTT |
| 9001 | mosquitto | MQTT WebSocket |
| 5000 | llm-server | LLM API |

### Внутренние порты (только внутри jarvis-net)

Остальные сервисы используют `expose` вместо `ports`:
- voice-gateway:8081
- nlp-service:8082
- orchestrator:8083
- pc-control:8084
- life-tracker:8085
- smart-home-service:8086
- analytics-service:8087
- security-service:8088
- user-profile:8089
- assistant-core:8090
- llm-service:8091

---

## Профили Spring

### Доступные профили

| Профиль | Назначение |
|---------|------------|
| `docker` | Для запуска в Docker (hostnames сервисов вместо localhost) |
| `dev` | Режим разработки: permitAll security, DEBUG логи, 24h JWT |
| `dev,docker` | Комбинированный режим для docker-compose-dev.yml |

### Активация профилей

```yaml
# docker-compose.yml
environment:
  - SPRING_PROFILES_ACTIVE=docker

# docker-compose-dev.yml (override)
environment:
  - SPRING_PROFILES_ACTIVE=dev,docker
```

---

## Service Responsibilities (Ответственность сервисов)

### Критичные сервисы (Core)

| Сервис | Критичность | Описание |
|--------|-------------|----------|
| **api-gateway** | 🔴 Критичен | Единая точка входа. Без него система недоступна извне |
| **security-service** | 🔴 Критичен | Auth/JWT. Без него невозможна аутентификация |
| **life-tracker** | 🔴 Критичен | Основные данные пользователя. analytics-service зависит от него |
| **postgres** | 🔴 Критичен | База данных. Без неё не работают 5 сервисов |

### Важные сервисы

| Сервис | Критичность | Описание |
|--------|-------------|----------|
| **analytics-service** | 🟡 Важен | Аналитика. Graceful degradation при проблемах |
| **planner-service** | 🟡 Важен | Планирование. Зависит от многих сервисов |
| **assistant-core** | 🟡 Важен | Ядро ассистента для голосовых сценариев |
| **voice-gateway** | 🟡 Важен | Голосовой ввод. Без него нет STT |

### Второстепенные сервисы

| Сервис | Критичность | Описание |
|--------|-------------|----------|
| **pc-control** | 🟢 Опционален | Управление ПК. Можно отключить |
| **smart-home-service** | 🟢 Опционален | IoT. Работает только с MQTT |
| **nlp-service** | 🟢 Опционален | NLP. Можно упростить логику |
| **llm-service** | 🟢 Опционален | LLM. Тяжёлый, можно отключить |

---

## End-to-End сценарии

### Сценарий 1: Регистрация → Логин → Добавление расхода → Аналитика

```
1. Клиент → POST api-gateway:8080/auth/register
   └── api-gateway → POST security-service:8088/auth/register
       └── security-service → INSERT INTO users (postgres)
   ← 201 Created + JWT tokens

2. Клиент → POST api-gateway:8080/auth/login
   └── api-gateway → POST security-service:8088/auth/login
       └── security-service → SELECT FROM users (postgres)
   ← 200 OK + JWT tokens

3. Клиент → POST api-gateway:8080/api/v1/life/finance/expense (+ Bearer JWT)
   └── api-gateway validates JWT locally
   └── api-gateway → POST life-tracker:8085/api/v1/life/finance/expense
       └── life-tracker → INSERT INTO expenses (postgres)
   ← 200 OK + expense data

4. Клиент → GET api-gateway:8080/api/v1/analytics/overview (+ Bearer JWT)
   └── api-gateway validates JWT
   └── api-gateway → GET analytics-service:8087/api/v1/analytics/overview
       └── analytics-service → GET life-tracker:8085/api/v1/life/finance/expenses
           └── life-tracker → SELECT FROM expenses (postgres)
       └── analytics-service aggregates data
   ← 200 OK + analytics overview
```

### Сценарий 2: Голосовая команда → Действие

```
1. desktop-client → WebSocket api-gateway:8080/ws/voice
   └── Audio stream → voice-gateway:8081/api/v1/voice/stt
       └── Vosk STT → text

2. Text → nlp-service:8082/api/v1/nlp/analyze
   ← intent: "SET_TIMER", entities: {seconds: 300}

3. orchestrator:8083/api/v1/orchestrator/execute
   └── pc-control:8084/api/v1/pc/action
       └── Execute: notify-send + beep after 300s
   ← success
```

---

## Версии зависимостей

| Компонент | Версия |
|-----------|--------|
| Java | 21 |
| Spring Boot | 3.3.4 |
| Spring Cloud | 2023.0.3 |
| PostgreSQL | 16 |
| Flyway | (managed by Spring Boot) |
| jjwt | 0.12.6 |
| Resilience4j | 2.1.0 |
| Vosk | Small Russian model |

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

