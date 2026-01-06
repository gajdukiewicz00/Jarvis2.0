# Jarvis 2.0 - Конфигурация баз данных

## Содержание
- [Обзор](#обзор)
- [PostgreSQL конфигурация](#postgresql-конфигурация)
- [Базы данных](#базы-данных)
- [Конфигурации сервисов](#конфигурации-сервисов)
- [HikariCP настройки](#hikaricp-настройки)
- [Troubleshooting](#troubleshooting)

---

## Обзор

Jarvis 2.0 использует PostgreSQL 16 в Docker-контейнере. Для каждого доменного сервиса создаётся отдельная база данных для изоляции данных.

### Общие параметры подключения

| Параметр | Значение |
|----------|----------|
| Host (Docker) | `postgres` |
| Host (Local) | `localhost` |
| Port | `5432` |
| Username | `jarvis` |
| Password | `${DB_PASSWORD:-jarvis123}` |
| Driver | `org.postgresql.Driver` |

---

## PostgreSQL конфигурация

### Файл: `docker/postgres/postgres.conf`

```
# Memory Settings
shared_buffers = 256MB           # 25% of container memory (1GB)
work_mem = 8MB                   # Per-operation memory
maintenance_work_mem = 128MB     # VACUUM, CREATE INDEX
effective_cache_size = 512MB     # OS cache estimate

# Connection Settings
max_connections = 100            # 6 services × 10 conn + overhead
superuser_reserved_connections = 3

# Autovacuum (оптимизировано для 4 БД)
autovacuum = on
autovacuum_max_workers = 4
autovacuum_naptime = 30s
autovacuum_vacuum_threshold = 50
autovacuum_vacuum_scale_factor = 0.1

# Safety
statement_timeout = 300000       # 5 minutes max per statement
```

### Инициализация БД: `docker/postgres-init/init-databases.sh`

Скрипт автоматически создаёт 4 базы данных при первом запуске:
- `jarvis_db` — life-tracker, planner-service
- `jarvis_assistant_core` — assistant-core
- `jarvis_user_profile` — user-profile
- `jarvis_security` — security-service

---

## Базы данных

### jarvis_db

| Сервис | Схема | Таблицы |
|--------|-------|---------|
| life-tracker | public | expenses, time_records, calendar_events |
| planner-service | public | tasks, reminders, daily_plans, recommendations |

### jarvis_assistant_core

| Сервис | Схема | Таблицы |
|--------|-------|---------|
| assistant-core | public | conversation_memory, user_context, behavioral_analysis |

### jarvis_user_profile

| Сервис | Схема | Таблицы |
|--------|-------|---------|
| user-profile | public | user_profiles, goals, habits, preferences |

### jarvis_security

| Сервис | Схема | Таблицы |
|--------|-------|---------|
| security-service | public | users |

---

## Конфигурации сервисов

### life-tracker

**application.yaml** (local development):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jarvis_db
    username: jarvis
    password: ${DB_PASSWORD:jarvis}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
      connection-init-sql: SELECT 1
      validation-timeout: 5000
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    baseline-on-migrate: true
```

**application-docker.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/jarvis_db
    username: jarvis
    password: ${DB_PASSWORD:jarvis123}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      max-lifetime: 1800000
      idle-timeout: 600000
      connection-timeout: 30000
      validation-timeout: 5000
      initialization-fail-timeout: -1
      connection-test-query: SELECT 1
```

### security-service

**application.yml**:
```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/jarvis_security}
    username: ${SPRING_DATASOURCE_USERNAME:jarvis}
    password: ${SPRING_DATASOURCE_PASSWORD:jarvis}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    baseline-on-migrate: true
```

**application-docker.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/jarvis_security
    username: jarvis
    password: ${DB_PASSWORD:jarvis123}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      max-lifetime: 1800000
      idle-timeout: 600000
      connection-timeout: 30000
      validation-timeout: 5000
      initialization-fail-timeout: -1
      connection-test-query: SELECT 1
```

### user-profile

**application.yaml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jarvis_user_profile
    username: jarvis
    password: ${DB_PASSWORD:jarvis123}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
      connection-init-sql: SELECT 1
      validation-timeout: 5000
```

**application-docker.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/jarvis_user_profile
    username: jarvis
    password: ${DB_PASSWORD:jarvis123}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      max-lifetime: 1800000
      idle-timeout: 600000
      connection-timeout: 30000
      validation-timeout: 5000
      initialization-fail-timeout: -1
      connection-test-query: SELECT 1
```

### assistant-core

**application-docker.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/jarvis_assistant_core
    username: jarvis
    password: ${DB_PASSWORD:jarvis123}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 3
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
      connection-init-sql: SELECT 1
      validation-timeout: 5000
      initialization-fail-timeout: -1
```

### planner-service

**application-docker.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/jarvis_db
    username: jarvis
    password: ${DB_PASSWORD:jarvis123}
    hikari:
      maximum-pool-size: 15
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1200000      # 20 minutes
      keepalive-time: 300000     # 5 minutes
      leak-detection-threshold: 60000
      connection-test-query: SELECT 1
      validation-timeout: 5000
      initialization-fail-timeout: -1
```

---

## HikariCP настройки

### Shared Config: `apps/shared-config/application-hikari.yml`

Рекомендуемая конфигурация для всех сервисов:

```yaml
spring:
  datasource:
    hikari:
      # Pool Size
      maximum-pool-size: 5       # 6 services × 5 = 30 total (max_connections = 100)
      minimum-idle: 2
      
      # Timeouts
      connection-timeout: 30000  # 30 seconds to get connection
      idle-timeout: 600000       # 10 minutes idle before close
      max-lifetime: 1200000      # 20 minutes max connection age
      keepalive-time: 300000     # 5 minutes keepalive ping
      validation-timeout: 5000   # 5 seconds validation check
      
      # Validation
      connection-test-query: SELECT 1
      
      # Debugging
      leak-detection-threshold: 60000  # Warn if held > 60s
      
      # Startup
      initialization-fail-timeout: -1  # Don't fail if DB unavailable
```

### Почему такие значения?

| Параметр | Значение | Обоснование |
|----------|----------|-------------|
| maximum-pool-size | 5-10 | 6 сервисов × 10 = 60 < max_connections (100) |
| minimum-idle | 2-3 | Держать готовые соединения |
| max-lifetime | 1200000 (20 мин) | Меньше чем PostgreSQL idle timeout (~30 мин) |
| keepalive-time | 300000 (5 мин) | Предотвращает silent disconnect |
| connection-test-query | SELECT 1 | Быстрая проверка валидности |
| initialization-fail-timeout | -1 | Позволяет стартовать без БД (для docker-compose) |

### Импорт shared config

В каждом сервисе `application-docker.yml`:

```yaml
spring:
  config:
    import: optional:file:../shared-config/application-hikari.yml
```

---

## Troubleshooting

### Ошибка: "Connection is not available, request timed out"

**Причина**: Все соединения в пуле заняты

**Решения**:
1. Увеличить `maximum-pool-size` до 10-15
2. Проверить длительные транзакции: `@Transactional` без timeout
3. Проверить connection leaks (смотреть логи leak-detection)

### Ошибка: "This connection has been closed"

**Причина**: PostgreSQL закрыл idle соединение, а HikariCP пытается его использовать

**Решения**:
1. Уменьшить `max-lifetime` до 1200000 (20 мин)
2. Добавить `keepalive-time: 300000`
3. Добавить `connection-test-query: SELECT 1`

### Ошибка: "UnknownHostException: postgres"

**Причина**: Сервис не в сети `jarvis-net` или неправильный hostname

**Решения**:
1. Проверить `networks: - jarvis-net` в docker-compose.yml
2. Проверить URL: `jdbc:postgresql://postgres:5432/...`
3. Проверить `depends_on: postgres` с `condition: service_healthy`

### Ошибка: "Failed to validate connection"

**Причина**: Соединение стало невалидным между запросами

**Решения**:
1. Добавить `connection-test-query: SELECT 1`
2. Уменьшить `max-lifetime`
3. Добавить `keepalive-time`

### Мониторинг соединений

```bash
# Общее количество соединений
docker exec jarvis20_postgres psql -U jarvis -d jarvis -c \
  "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"

# Соединения по базам
docker exec jarvis20_postgres psql -U jarvis -d jarvis -c \
  "SELECT datname, count(*) FROM pg_stat_activity GROUP BY datname;"

# Долгие запросы (> 5 сек)
docker exec jarvis20_postgres psql -U jarvis -d jarvis -c \
  "SELECT pid, now() - query_start AS duration, query 
   FROM pg_stat_activity 
   WHERE state != 'idle' AND now() - query_start > interval '5 seconds'
   ORDER BY duration DESC;"
```

---

## Миграции (Flyway)

### Расположение миграций

- life-tracker: `apps/life-tracker/src/main/resources/db/migration/`
- security-service: `apps/security-service/src/main/resources/db/migration/`
- planner-service: `apps/planner-service/src/main/resources/db/migration/`
- user-profile: `apps/user-profile/src/main/resources/db/migration/`

### Соглашение о наименовании

```
V{версия}__{описание}.sql

Примеры:
V1__create_users_table.sql
V2__add_email_column.sql
V3__create_indexes.sql
```

### Полезные настройки Flyway

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true    # Создать baseline если БД не пустая
    clean-disabled: true         # Запретить clean в production
    repair-on-migrate: true      # Автоматически исправлять checksums
    locations: classpath:db/migration
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

