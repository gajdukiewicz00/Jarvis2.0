# Jarvis 2.0 - Руководство по настройке среды разработки

## Содержание
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Запуск через Docker](#запуск-через-docker)
- [Запуск без Docker](#запуск-без-docker)
- [Smoke-тесты](#smoke-тесты)
- [Troubleshooting](#troubleshooting)

---

## Требования

### Обязательные
| Компонент | Минимальная версия | Проверка |
|-----------|-------------------|----------|
| Java | 21 | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | 2.0+ | `docker compose version` |

### Рекомендуемые
| Компонент | Назначение |
|-----------|------------|
| IntelliJ IDEA | IDE с поддержкой Spring Boot |
| DBeaver | Работа с PostgreSQL |
| Postman/Bruno | Тестирование API |

### Системные требования
| Ресурс | Минимум | Рекомендуется |
|--------|---------|---------------|
| RAM | 8 GB | 16 GB |
| CPU | 4 cores | 8 cores |
| Disk | 20 GB | 50 GB |

---

## Быстрый старт

### Вариант 1: Одной командой (рекомендуется)

```bash
# Клонируем и запускаем
cd /home/kwaqa/IdeaProjects/Jarvis2.0
./jarvis-launch.sh
```

Скрипт автоматически:
1. Поднимет Docker-стек (все 15 сервисов)
2. Откроет окно с логами
3. Запустит desktop-клиент

### Вариант 2: Через Makefile

```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0

# Сборка проекта
make build

# Запуск всего стека
make run-all

# Остановка
make stop-all
```

### Вариант 3: Ручной запуск

```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0

# 1. Сборка
mvn clean package -DskipTests

# 2. Запуск Docker-стека
docker compose up -d

# 3. Просмотр логов
docker compose logs -f

# 4. Остановка
docker compose down
```

---

## Запуск через Docker

### Полный стек (production-like)

```bash
# Сборка и запуск
docker compose up -d --build

# Проверка статуса
docker compose ps

# Логи всех сервисов
docker compose logs -f

# Логи конкретного сервиса
docker compose logs -f life-tracker
docker compose logs -f security-service

# Остановка
docker compose down

# Остановка с удалением volumes (⚠️ удалит данные БД)
docker compose down -v
```

### Development mode (с dev-профилем)

```bash
# Запуск с dev-профилем (расширенные права, DEBUG логи)
docker compose -f docker-compose.yml -f docker-compose-dev.yml up -d

# Проверка
docker compose ps
```

### Отдельные сервисы

```bash
# Запуск только PostgreSQL и mosquitto (для локальной разработки)
docker compose up -d postgres mosquitto

# Запуск без LLM (если не нужен AI-чат)
docker compose up -d --scale llm-server=0 --scale llm-service=0
```

### Пересборка конкретного сервиса

```bash
# Пересобрать и перезапустить life-tracker
docker compose up -d --build life-tracker

# Пересобрать несколько сервисов
docker compose up -d --build life-tracker analytics-service
```

---

## Запуск без Docker

### 1. Запуск PostgreSQL локально

```bash
# Через Docker (рекомендуется)
docker run -d \
  --name jarvis-postgres \
  -e POSTGRES_USER=jarvis \
  -e POSTGRES_PASSWORD=jarvis123 \
  -e POSTGRES_DB=jarvis \
  -p 5432:5432 \
  -v jarvis-pgdata:/var/lib/postgresql/data \
  postgres:16

# Создание баз данных
docker exec -it jarvis-postgres psql -U jarvis -d jarvis -c "
  CREATE DATABASE jarvis_db;
  CREATE DATABASE jarvis_assistant_core;
  CREATE DATABASE jarvis_user_profile;
  CREATE DATABASE jarvis_security;
"
```

### 2. Сборка проекта

```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0

# Полная сборка
mvn clean package -DskipTests

# Сборка конкретного модуля
mvn clean package -pl apps/life-tracker -am -DskipTests
```

### 3. Запуск сервисов

```bash
# Терминал 1: security-service
cd apps/security-service
java -jar target/security-service-0.1.0-SNAPSHOT.jar

# Терминал 2: life-tracker
cd apps/life-tracker
java -jar target/life-tracker-0.1.0-SNAPSHOT.jar

# Терминал 3: analytics-service
cd apps/analytics-service
java -jar target/analytics-service-0.1.0-SNAPSHOT.jar

# Терминал 4: api-gateway
cd apps/api-gateway
java -jar target/api-gateway-0.1.0-SNAPSHOT.jar
```

### 4. Запуск из IDE (IntelliJ IDEA)

1. Открыть проект `/home/kwaqa/IdeaProjects/Jarvis2.0`
2. Дождаться индексации Maven
3. Найти `*Application.java` в нужном модуле
4. Run → Run 'Application'

---

## Smoke-тесты

### После запуска проверить:

#### 1. Health Endpoints

```bash
# API Gateway
curl -s http://localhost:8080/actuator/health | jq

# Security Service (через gateway)
curl -s http://localhost:8080/actuator/health | jq

# Life Tracker (напрямую, если порт открыт)
# В Docker доступен только внутри сети
```

#### 2. Аутентификация

```bash
# Регистрация
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass123",
    "role": "USER"
  }'

# Логин
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "testpass123"
  }'

# Ответ содержит accessToken и refreshToken
```

#### 3. Life Tracker API

```bash
# Получить расходы (dev-режим без JWT)
curl http://localhost:8080/api/v1/life/finance/expenses

# Добавить расход
curl -X POST http://localhost:8080/api/v1/life/finance/expense \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 25.50,
    "currency": "EUR",
    "category": "FOOD",
    "description": "Lunch"
  }'

# Получить записи времени
curl http://localhost:8080/api/v1/life/time/records

# Получить события календаря
curl http://localhost:8080/api/v1/life/calendar/events
```

#### 4. Analytics API

```bash
# Обзор аналитики
curl http://localhost:8080/api/v1/analytics/overview

# Расходы по месяцам
curl http://localhost:8080/api/v1/analytics/expenses/by-month

# Расходы по категориям
curl http://localhost:8080/api/v1/analytics/expenses/by-category
```

#### 5. Planner API

```bash
# Дневной план
curl "http://localhost:8092/api/v1/planner/daily?userId=testuser"

# Создать задачу
curl -X POST http://localhost:8092/api/v1/planner/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "testuser",
    "title": "Test Task",
    "category": "WORK",
    "priority": "HIGH"
  }'
```

### Проверка Docker-сервисов

```bash
# Статус всех контейнеров
docker compose ps

# Ожидаемый результат: все сервисы в статусе "running" или "healthy"

# Проверка healthcheck
docker inspect --format='{{.State.Health.Status}}' jarvis20_api-gateway
docker inspect --format='{{.State.Health.Status}}' jarvis20_life-tracker
docker inspect --format='{{.State.Health.Status}}' jarvis20_security-service
```

---

## Troubleshooting

### Сервис не стартует

```bash
# Проверить логи
docker compose logs -f <service-name>

# Типичные проблемы:
# 1. БД недоступна → проверить postgres healthcheck
# 2. Port already in use → остановить локальные сервисы
# 3. Out of memory → увеличить Docker resources
```

### PostgreSQL не поднимается

```bash
# Проверить логи
docker compose logs postgres

# Проверить volume permissions
ls -la ./data/postgres

# Сбросить volume (⚠️ удалит данные)
docker compose down -v
docker compose up -d postgres
```

### Feign ошибки (Service Unavailable)

```bash
# Проверить, что целевой сервис запущен
docker compose ps

# Проверить сеть
docker network inspect jarvis20_jarvis-net

# Проверить DNS внутри контейнера
docker exec jarvis20_api-gateway ping life-tracker
```

### JWT ошибки

```bash
# Проверить, что JWT_SECRET одинаковый
docker compose config | grep JWT_SECRET

# Получить новый токен
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser", "password": "testpass123"}'
```

### Память заканчивается

```bash
# Проверить использование ресурсов
docker stats

# Ограничить память для сервисов в docker-compose.yml:
# deploy:
#   resources:
#     limits:
#       memory: 512M
```

---

## Полезные команды

```bash
# Перезапуск всего стека
docker compose restart

# Перезапуск конкретного сервиса
docker compose restart life-tracker

# Очистка неиспользуемых образов
docker image prune -a

# Вход в контейнер
docker exec -it jarvis20_life-tracker /bin/sh

# Просмотр переменных окружения сервиса
docker exec jarvis20_life-tracker env | grep SPRING

# Подключение к PostgreSQL
docker exec -it jarvis20_postgres psql -U jarvis -d jarvis_db

# Просмотр таблиц
docker exec -it jarvis20_postgres psql -U jarvis -d jarvis_db -c "\dt"
```

---

## Переменные окружения

### Файл `.env` (создать в корне проекта)

```bash
# Database
DB_PASSWORD=jarvis123

# JWT (⚠️ сменить в production)
JWT_SECRET=jarvis-secret-key-change-in-production-min-256-bits-long-for-HS256-algorithm

# Optional: Porcupine Wake Word (для desktop-клиента)
PORCUPINE_ACCESS_KEY=your-picovoice-key
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

