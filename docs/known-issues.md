# Jarvis 2.0 - Известные проблемы и решения

## Содержание
- [Критические проблемы](#критические-проблемы)
- [Проблемы подключения к БД](#проблемы-подключения-к-бд)
- [Проблемы Feign/HTTP](#проблемы-feignhttp)
- [Проблемы JWT](#проблемы-jwt)
- [Проблемы Docker](#проблемы-docker)
- [Статус исправлений](#статус-исправлений)

---

## Критические проблемы

### 1. UnknownHostException: postgres

**Симптом**:
```
java.net.UnknownHostException: postgres
Caused by: java.net.UnknownHostException: postgres: Name or service not known
```

**Причина**: Сервис не находится в сети `jarvis-net` или стартует до того, как Docker DNS готов.

**Решение**:
1. Проверить `networks: - jarvis-net` в docker-compose.yml
2. Добавить `depends_on: postgres: condition: service_healthy`
3. Использовать `initialization-fail-timeout: -1` в HikariCP

**Статус**: ✅ Исправлено в docker-compose.yml

---

### 2. HikariPool - Connection is not available, request timed out

**Симптом**:
```
HikariPool-1 - Connection is not available, request timed out after 30000ms.
```

**Причина**: Все соединения в пуле заняты или исчерпаны.

**Решение**:
1. Увеличить `maximum-pool-size` до 10-15
2. Проверить длительные транзакции
3. Добавить `leak-detection-threshold: 60000` для диагностики

**Статус**: ⚠️ Частично исправлено (настроен shared-config)

---

### 3. Failed to validate connection - This connection has been closed

**Симптом**:
```
Failed to validate connection org.postgresql.jdbc.PgConnection@xxx 
(This connection has been closed.)
```

**Причина**: PostgreSQL закрывает idle соединения, HikariCP пытается использовать закрытое соединение.

**Решение**:
1. Уменьшить `max-lifetime` до 1200000 (20 мин) — меньше, чем PostgreSQL idle timeout
2. Добавить `keepalive-time: 300000` для периодической проверки
3. Добавить `connection-test-query: SELECT 1`

**Статус**: ✅ Исправлено в application-docker.yml всех сервисов

---

## Проблемы подключения к БД

### 4. Could not open JPA EntityManager

**Симптом**:
```
org.springframework.transaction.CannotCreateTransactionException: 
Could not open JPA EntityManager for transaction
Caused by: org.hibernate.exception.JDBCConnectionException: 
Unable to acquire JDBC Connection
```

**Причина**: Невозможно получить соединение из пула.

**Решение**:
- См. проблему #2 и #3
- Проверить доступность PostgreSQL: `docker exec jarvis20_postgres pg_isready`

---

### 5. Connection refused to postgres:5432

**Симптом**:
```
Connection refused (Connection refused)
org.postgresql.util.PSQLException: Connection to postgres:5432 refused
```

**Причина**: PostgreSQL ещё не готов принимать соединения.

**Решение**:
1. Использовать healthcheck в docker-compose:
   ```yaml
   postgres:
     healthcheck:
       test: ["CMD-SHELL", "pg_isready -U jarvis"]
       interval: 10s
       retries: 5
   ```
2. В сервисах добавить:
   ```yaml
   depends_on:
     postgres:
       condition: service_healthy
   ```
3. В HikariCP: `initialization-fail-timeout: -1`

**Статус**: ✅ Исправлено в docker-compose.yml

---

## Проблемы Feign/HTTP

### 6. FeignException$InternalServerError [500] to life-tracker

**Симптом**:
```
feign.FeignException$InternalServerError: [500] during [GET] to 
[http://life-tracker:8085/api/v1/life/calendar/events]
```

**Причина**: life-tracker возвращает 500 (часто из-за проблем с БД).

**Решение**:
1. Исправить проблемы БД (см. #2, #3)
2. Добавить обработку ошибок в AnalyticsController
3. Добавить fallback в analytics-service

**Статус**: ⚠️ Требует улучшения обработки ошибок

---

### 7. RetryableException: Read timed out

**Симптом**:
```
feign.RetryableException: Read timed out executing POST 
http://security-service:8088/auth/register
```

**Причина**: security-service не отвечает в течение readTimeout.

**Решение**:
1. Увеличить readTimeout для auth-service:
   ```yaml
   spring.cloud.openfeign.client.config.auth-service.readTimeout: 30000
   ```
2. Проверить здоровье security-service
3. Проверить нагрузку на PostgreSQL

**Статус**: ⚠️ Требуется увеличение таймаутов

---

### 8. Service unavailable - UnknownHostException в Feign

**Симптом**:
```
Service unavailable [life-tracker]: java.net.UnknownHostException: life-tracker
```

**Причина**: DNS Docker не может разрешить имя сервиса.

**Решение**: См. проблему #1

---

## Проблемы JWT

### 9. JWT validation failed: JWT expired

**Симптом** (многократно в логах):
```
JWT validation failed: JWT expired at 2025-12-01T10:00:00Z. 
Current time: 2025-12-02T12:00:00Z
```

**Причина**: Клиент использует истёкший токен.

**Проблемы**:
1. Логируется как ERROR (должен быть WARN/DEBUG)
2. Нет различия между "expired" и другими JWT-ошибками
3. Клиенту не всегда понятно, что нужен refresh

**Решение**:
1. Изменить уровень логирования на WARN/DEBUG
2. Отдельно обрабатывать ExpiredJwtException
3. Возвращать структурированный ответ:
   ```json
   {
     "error": "TOKEN_EXPIRED",
     "message": "JWT token has expired"
   }
   ```

**Статус**: ⚠️ Требует рефакторинга JwtFilter

---

### 10. Invalid JWT signature

**Симптом**:
```
JWT validation failed: JWT signature does not match
```

**Причина**: 
- Разные JWT_SECRET в api-gateway и security-service
- Токен подделан

**Решение**:
- Синхронизировать JWT_SECRET через environment variable
- В docker-compose.yml уже настроено одинаковое значение

**Статус**: ✅ Исправлено

---

## Проблемы Docker

### 11. Container exits immediately after start

**Симптом**:
```
jarvis20-life-tracker-1 exited with code 1
```

**Возможные причины**:
1. БД недоступна при старте
2. Ошибка в конфигурации
3. Flyway migration failed

**Решение**:
1. Проверить логи: `docker logs jarvis20-life-tracker-1`
2. Добавить `restart: on-failure` в docker-compose.yml
3. Использовать healthcheck для postgres

**Статус**: ✅ Настроен restart для сервисов с БД

---

### 12. Port already in use

**Симптом**:
```
Error starting userland proxy: listen tcp4 0.0.0.0:5432: 
bind: address already in use
```

**Причина**: Локальный PostgreSQL занимает порт.

**Решение**:
```bash
# Остановить локальный PostgreSQL
sudo systemctl stop postgresql

# Или изменить порт в docker-compose.yml
ports:
  - "5433:5432"
```

---

### 13. Volume permission denied

**Симптом**:
```
FATAL: could not open directory "pg_notify": Permission denied
```

**Причина**: Неправильные права на volume.

**Решение**:
```bash
sudo chown -R 999:999 ./data/postgres
# или
sudo rm -rf ./data/postgres && docker compose up -d
```

---

## Статус исправлений

| # | Проблема | Статус | Приоритет |
|---|----------|--------|-----------|
| 1 | UnknownHostException: postgres | ✅ Исправлено | P1 |
| 2 | Connection not available (timeout) | ⚠️ Частично | P1 |
| 3 | Connection has been closed | ✅ Исправлено | P1 |
| 4 | Could not open JPA EntityManager | ⚠️ Зависит от #2,#3 | P1 |
| 5 | Connection refused | ✅ Исправлено | P1 |
| 6 | FeignException 500 to life-tracker | ⚠️ Требует работы | P2 |
| 7 | Read timed out (Feign) | ⚠️ Требует работы | P2 |
| 8 | UnknownHostException в Feign | ✅ Исправлено | P1 |
| 9 | JWT expired (логирование) | ⚠️ Требует рефакторинга | P2 |
| 10 | Invalid JWT signature | ✅ Исправлено | P1 |
| 11 | Container exits immediately | ✅ Исправлено | P2 |
| 12 | Port already in use | ℹ️ Документировано | P3 |
| 13 | Volume permission denied | ℹ️ Документировано | P3 |

### Легенда
- ✅ Исправлено — проблема решена
- ⚠️ Частично/Требует работы — требуется дополнительная работа
- ℹ️ Документировано — известная проблема с workaround
- P1 — критический приоритет
- P2 — высокий приоритет
- P3 — низкий приоритет

---

## План исправлений

### Фаза 1 (Критические)
- [x] Настроить healthcheck для postgres
- [x] Добавить depends_on с condition: service_healthy
- [x] Настроить HikariCP во всех сервисах
- [x] Синхронизировать JWT_SECRET

### Фаза 2 (Высокий приоритет)
- [ ] Рефакторинг JwtFilter для корректной обработки expired tokens
- [ ] Улучшить GlobalExceptionHandler для Feign ошибок
- [ ] Добавить fallback в analytics-service
- [ ] Увеличить Feign таймауты для тяжёлых операций

### Фаза 3 (Улучшения)
- [ ] Добавить circuit breaker (Resilience4j)
- [ ] Добавить retry для transient ошибок
- [ ] Улучшить логирование (structured logging)
- [ ] Добавить метрики для мониторинга

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

