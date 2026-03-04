# Jarvis 2.0 - Modules Overview

**Дата обновления:** 2026-03-04  
**Источник правды:** `pom.xml` (root modules), `jarvis-launch.sh` (golden runtime path)

## Сводка модулей (актуально)

| Module | Type | Port | Notes |
|---|---|---|---|
| `jarvis-common` | library | - | Общая библиотека (security/common) |
| `api-gateway` | application | 8080 | Единая точка входа, JWT, WS proxy |
| `voice-gateway` | application | 8081 | STT/TTS + voice websocket |
| `nlp-service` | application | 8082 | NLU/intent parsing |
| `orchestrator` | application | 8083 | Оркестрация команд и fallback |
| `pc-control` | application | 8084 | Действия на desktop/PC |
| `life-tracker` | application | 8085 | Финансы/время/календарь |
| `smart-home-service` | application | 8086 | MQTT bridge для smart-home |
| `analytics-service` | application | 8087 | Аналитика на базе life-tracker |
| `security-service` | application | 8088 | Auth/JWT + users DB |
| `user-profile` | application | 8089 | Профили/предпочтения |
| `llm-service` | application | 8091 | LLM orchestration layer |
| `planner-service` | application | 8092 | Задачи/напоминания/planning |
| `memory-service` | application | 8093 | Долгосрочная память (RAG) |
| `desktop-client-javafx` | client | - | Desktop UI client |
| `launcher-javafx` | client | - | Product launcher UI |

## Не-Maven модули/директории

| Path | Type | Notes |
|---|---|---|
| `apps/mobile-client` | client (Gradle) | Android проект, не входит в root Maven modules |
| `apps/shared-config` | config | Общие YAML-конфиги (например Hikari import) |

## Снятые legacy элементы

- `assistant-core` удалён из активного набора модулей.
- Для истории старые snapshot-документы перемещены в `docs/_archive/legacy/`.

## Golden Path Launch

Единый поддерживаемый backend path:

```bash
./jarvis-launch.sh
```

Остановка:

```bash
./jarvis-stop.sh
```

Логи:

```bash
./jarvis-logs.sh
```

Опционально (LLM + Memory):

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

## Минимальный runtime контур (Jarvis Alive)

Базовый voice loop:
- `api-gateway`, `voice-gateway`, `nlp-service`, `orchestrator`, `pc-control`, `security-service`, `postgres`

Опционально:
- LLM: `llm-service` + `llm-server`
- Memory: `memory-service` + `embedding-service` + `postgres-pgvector`
