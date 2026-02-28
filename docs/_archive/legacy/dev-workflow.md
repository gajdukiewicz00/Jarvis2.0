# Jarvis 2.0 - Developer Workflow

## Содержание
- [Типичный рабочий день](#типичный-рабочий-день)
- [Работа с Docker](#работа-с-docker)
- [Отладка проблем](#отладка-проблем)
- [Полезные команды](#полезные-команды)

---

## Типичный рабочий день

### 1. Утро: Запуск стенда

```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0

# Вариант A: Через Makefile
make up

# Вариант B: Напрямую
docker compose up -d

# Проверить статус
make ps
# или
docker compose ps
```

### 2. Проверка здоровья

```bash
# Быстрая проверка
make health

# Подробная проверка
curl http://localhost:8080/actuator/health | jq
```

### 3. Разработка

#### Работа с конкретным сервисом

```bash
# Пересобрать и перезапустить сервис
mvn clean package -pl apps/life-tracker -am -DskipTests
docker compose up -d --build life-tracker

# Смотреть логи
make logs-life-tracker
# или
docker compose logs -f life-tracker
```

#### Локальный запуск без Docker

```bash
# 1. Оставить только PostgreSQL в Docker
docker compose up -d postgres

# 2. Запустить сервис локально
cd apps/life-tracker
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4. Тестирование изменений

```bash
# Smoke-тест через Makefile
make smoke-test

# Ручной тест API
curl http://localhost:8080/api/v1/life/finance/expenses | jq

# Запуск unit-тестов
mvn test -pl apps/life-tracker
```

### 5. Вечер: Остановка стенда

```bash
# Остановить сервисы
make down

# Или остановить с сохранением данных
docker compose stop
```

---

## Работа с Docker

### Управление сервисами

| Команда | Описание |
|---------|----------|
| `make up` | Запустить все сервисы |
| `make down` | Остановить все сервисы |
| `make restart` | Перезапустить все сервисы |
| `make ps` | Статус контейнеров |

### Логи

| Команда | Описание |
|---------|----------|
| `make logs` | Последние 100 строк логов |
| `make logs-f` | Follow-режим (все сервисы) |
| `make logs-api-gateway` | Логи api-gateway |
| `make logs-life-tracker` | Логи life-tracker |
| `make logs-security-service` | Логи security-service |
| `make logs-postgres` | Логи PostgreSQL |

### Пересборка сервиса

```bash
# Один сервис
docker compose up -d --build life-tracker

# Несколько сервисов
docker compose up -d --build life-tracker analytics-service

# Все сервисы
docker compose up -d --build
```

### Очистка

```bash
# Остановить и удалить volumes (⚠️ удалит данные БД)
make down-v

# Удалить неиспользуемые образы
docker image prune -a

# Полная очистка Docker
docker system prune -a --volumes
```

---

## Отладка проблем

### Сервис не стартует

```bash
# 1. Проверить логи
docker compose logs life-tracker | tail -50

# 2. Типичные причины:
#    - БД недоступна → проверить postgres
docker compose logs postgres | tail -20

#    - Порт занят → проверить локальные процессы
lsof -i :8085
```

### Ошибки БД

```bash
# Проверить доступность PostgreSQL
docker exec jarvis20_postgres pg_isready

# Подключиться к БД
docker exec -it jarvis20_postgres psql -U jarvis -d jarvis_db

# Проверить соединения
docker exec jarvis20_postgres psql -U jarvis -d jarvis -c \
  "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"
```

### Feign ошибки (Service Unavailable)

```bash
# 1. Проверить, что целевой сервис запущен
docker compose ps

# 2. Проверить логи целевого сервиса
docker compose logs life-tracker | tail -20

# 3. Проверить сеть
docker exec jarvis20_api-gateway ping -c 2 life-tracker
```

### JWT ошибки

```bash
# Получить новый токен
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass"}'

# Проверить токен
TOKEN="<вставить токен>"
curl http://localhost:8080/api/v1/life/finance/expenses \
  -H "Authorization: Bearer $TOKEN"
```

### HikariCP ошибки (Connection timeout)

```bash
# Проверить количество соединений
docker exec jarvis20_postgres psql -U jarvis -d jarvis -c \
  "SELECT datname, count(*) FROM pg_stat_activity GROUP BY datname;"

# Если >90 из 100 — нужно уменьшить pool size или увеличить max_connections
```

---

## Полезные команды

### Makefile

```bash
make help        # Показать все доступные команды
make build       # Собрать проект
make test        # Запустить тесты
make up          # Запустить Docker
make down        # Остановить Docker
make health      # Проверить здоровье сервисов
make smoke-test  # Smoke-тесты
```

### Docker

```bash
# Статус
docker compose ps
docker stats

# Логи
docker compose logs -f <service>
docker compose logs --tail=50 <service>

# Вход в контейнер
docker exec -it jarvis20_<service> /bin/sh

# Перезапуск
docker compose restart <service>
```

### Maven

```bash
# Полная сборка
mvn clean package -DskipTests

# Сборка модуля
mvn clean package -pl apps/<module> -am -DskipTests

# Тесты модуля
mvn test -pl apps/<module>

# Запуск локально
mvn spring-boot:run -pl apps/<module> -Dspring-boot.run.profiles=dev
```

### PostgreSQL

```bash
# Подключение
docker exec -it jarvis20_postgres psql -U jarvis -d jarvis_db

# SQL-команды внутри psql:
\dt                          # Список таблиц
\d expenses                  # Структура таблицы
SELECT * FROM expenses LIMIT 5;
\q                           # Выход
```

### curl

```bash
# Health check
curl http://localhost:8080/actuator/health | jq

# GET запрос
curl http://localhost:8080/api/v1/life/finance/expenses | jq

# POST запрос
curl -X POST http://localhost:8080/api/v1/life/finance/expense \
  -H "Content-Type: application/json" \
  -d '{"amount":25.50,"category":"FOOD","description":"Test"}'

# С авторизацией
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/life/finance/expenses
```

---

## Чек-лист перед коммитом

- [ ] `make build` — проект собирается без ошибок
- [ ] `make test` — тесты проходят
- [ ] `make up` — сервисы стартуют
- [ ] `make health` — все сервисы healthy
- [ ] `make smoke-test` — базовые запросы работают
- [ ] Проверены логи на отсутствие ошибок
- [ ] Обновлена документация (если нужно)

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

