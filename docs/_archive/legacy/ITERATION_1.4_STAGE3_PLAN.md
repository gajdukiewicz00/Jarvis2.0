# Iteration 1.4 - Stage 3: Health/Ready Criteria in UI

**Дата:** 2026-01-05  
**Статус:** 📋 План (готов к реализации)

---

## Цель

Launcher UI показывает точный статус готовности системы:
- **READY** только когда все критичные сервисы здоровы
- **STARTING** пока сервисы поднимаются
- **DEGRADED** если часть сервисов недоступна (с понятным reason)

---

## Критерии READY

### Обязательные (блокеры):
- ✅ `api-gateway` health = UP
- ✅ `security-service` health = UP

### Опциональные (не блокируют READY):
- `voice-gateway` health = UP (если включен)
- Voice WebSocket connected (если включен)
- LLM/Memory services (если включены)

---

## Реализация

### 1. Health Check Service
Создать `HealthCheckService` в launcher:
- Проверяет `/actuator/health` для каждого сервиса
- Кэширует результаты (TTL 5-10 секунд)
- Периодически обновляет статус (каждые 5 секунд)

### 2. UI Status Updates
- `LauncherStatus.READY` → только если все обязательные сервисы UP
- `LauncherStatus.STARTING` → если хотя бы один обязательный сервис не готов
- `LauncherStatus.DEGRADED` → если опциональные сервисы недоступны, но обязательные UP

### 3. Status Details
Показывать в UI:
- Список сервисов и их статусы
- Reason для DEGRADED (например: "LLM service unavailable")
- Время последней проверки

---

## Файлы для изменения

1. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt` (новый)
2. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (обновить)
3. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` (добавить health check URLs)

---

## API Endpoints

### Health Checks:
- `http://localhost:8080/actuator/health` (api-gateway, через NodePort)
- `http://security-service.jarvis.svc.cluster.local:8080/actuator/health` (внутри кластера)

### Voice WebSocket:
- `ws://localhost:31482/ws/voice` (через NodePort)
- Проверка подключения через WebSocket ping

---

## Definition of Done

- ✅ HealthCheckService проверяет обязательные сервисы
- ✅ UI показывает READY только когда все обязательные сервисы UP
- ✅ UI показывает STARTING пока сервисы поднимаются
- ✅ UI показывает DEGRADED с reason если опциональные сервисы недоступны
- ✅ Статус обновляется периодически (каждые 5 секунд)
- ✅ "Start Desktop" доступен только в статусе READY

---

## Verify

```bash
# 1. Запустить launcher
# 2. Нажать "Start Backend"
# 3. Наблюдать статус: IDLE → STARTING → READY
# 4. Проверить что "Start Desktop" доступен только в READY
# 5. Остановить один из обязательных сервисов
# 6. Проверить что статус меняется на DEGRADED/ERROR
```

---

**Готово к реализации после фиксации 3 быстрых улучшений.**


