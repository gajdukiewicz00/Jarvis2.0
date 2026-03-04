# Jarvis 2.0 - Modules Overview

**Дата обновления:** 2025-12-02

## Сводка модулей

| Module | Type | Port | Description | Dependencies |
|--------|------|------|-------------|--------------|
| `api-gateway` | application | 8080 | API Gateway, точка входа для всех клиентов | security-service, life-tracker, analytics-service, smart-home-service, pc-control, voice-gateway, llm-service, orchestrator, nlp-service, planner-service |
| `security-service` | application | 8088 | Аутентификация, JWT-токены | postgres |
| `life-tracker` | application | 8085 | Финансы, время, календарь | postgres |
| `analytics-service` | application | 8087 | Аналитика данных | life-tracker |
| `assistant-core` | application | 8090 | Ядро ассистента, обработка команд | postgres, life-tracker, analytics-service, llm-service |
| `voice-gateway` | application | 8081 | Голосовой шлюз (WebSocket, STT) | nlp-service, orchestrator |
| `nlp-service` | application | 8082 | Обработка естественного языка | - |
| `orchestrator` | application | 8083 | Оркестрация действий | pc-control, smart-home-service, life-tracker |
| `llm-service` | application | 8091 | Интеграция с LLM-сервером | llm-server (Python) |
| `planner-service` | application | 8092 | Планировщик задач | postgres, life-tracker, analytics-service, user-profile, llm-service |
| `user-profile` | application | 8089 | Профили пользователей | postgres |
| `smart-home-service` | application | 8086 | Управление умным домом (MQTT) | mosquitto |
| `pc-control` | application | 8084 | Управление ПК (громкость, приложения) | - |
| `desktop-client-javafx` | client | - | JavaFX/Kotlin десктоп-клиент | api-gateway (WebSocket) |
| `mobile-client` | client | - | Android клиент (Kotlin) | api-gateway |
| `shared-config` | config | - | Общие конфигурации (Hikari) | - |

## Типы модулей

- **application**: Самостоятельный Spring Boot микросервис
- **client**: Клиентское приложение (не сервер)
- **config**: Конфигурационные файлы

## Микросервисы с БД

| Service | Database | Schema |
|---------|----------|--------|
| life-tracker | jarvis_db | public |
| security-service | jarvis_security | public |
| assistant-core | jarvis_assistant_core | public |
| user-profile | jarvis_user_profile | public |
| planner-service | jarvis_db | public |

## Внешние зависимости

| Service | Dependency | Type |
|---------|------------|------|
| All DB services | postgres | PostgreSQL 16 |
| smart-home-service | mosquitto | MQTT Broker |
| llm-service | llm-server | Python FastAPI (h2oGPT) |
| voice-gateway | Vosk | STT Model |

## Неиспользуемые/кандидаты на ревью

| Module | Status | Notes |
|--------|--------|-------|
| mobile-client | 🟡 В разработке | Минимальный Android-клиент |
| src/ | ❓ Проверить | Директория в корне - неясно назначение |

---

## Версии стека

См. `docs/stack-versions.md` для детальной информации о версиях.

