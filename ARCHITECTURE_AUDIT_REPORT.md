# 🏗️ Jarvis 2.0 - Полный Архитектурный Аудит

**Дата:** 2025-01-27  
**Аудитор:** Senior Software Architect + Tech Lead + QA  
**Версия проекта:** 0.1.0-SNAPSHOT  
**Статус:** ✅ Проект функционален, требует улучшений для production-ready

---

## A) Executive Summary

**Jarvis 2.0** — это микросервисная система персонального AI-ассистента на базе Spring Boot 3.3.4 и Java 21. Проект представляет собой Maven монорепо с 13 Java-модулями, 2 Python-сервисами (LLM inference и embeddings), JavaFX desktop-клиентом и Android-приложением.

**Текущее состояние:**
- ✅ **Код компилируется** без ошибок (после недавних исправлений)
- ✅ **Архитектура понятна** — четкое разделение на микросервисы
- ⚠️ **Инфраструктура частично готова** — есть K8s манифесты, но нет docker-compose в корне
- ⚠️ **Безопасность требует внимания** — хардкод паролей в конфигах
- ⚠️ **Тестовое покрытие низкое** — найдено только 10 тестовых файлов
- ⚠️ **CI/CD отсутствует** — нет GitHub Actions/Jenkins/GitLab CI

**Главные риски:**
1. Отсутствие docker-compose в корне (Makefile ссылается на несуществующий файл)
2. Хардкод секретов в конфигурационных файлах
3. Низкое покрытие тестами критических компонентов
4. Отсутствие CI/CD pipeline
5. Неполная документация по запуску локально

**Рекомендация:** Проект готов для разработки, но требует 2-3 недели работы для приведения к production-ready состоянию.

---

## B) Map проекта

### Структура репозитория

```
Jarvis2.0/
├── apps/                          # Maven модули (13 сервисов)
│   ├── jarvis-common/             # Shared library
│   ├── api-gateway/               # Gateway (8080)
│   ├── voice-gateway/             # STT/TTS (8081)
│   ├── nlp-service/               # NLP (8082)
│   ├── orchestrator/               # Orchestration (8083)
│   ├── pc-control/                # System control (8084)
│   ├── life-tracker/              # Finance/Time (8085)
│   ├── smart-home-service/        # IoT (8086)
│   ├── analytics-service/         # Analytics (8087)
│   ├── security-service/          # JWT Auth (8088)
│   ├── user-profile/              # User data (8089)
│   ├── llm-service/               # LLM wrapper (8091)
│   ├── memory-service/            # RAG memory (8093)
│   ├── planner-service/           # Tasks (8092)
│   ├── desktop-client-javafx/     # JavaFX client
│   └── mobile-client/             # Android (Gradle)
├── docker/                        # Python сервисы
│   ├── llm-server/                # h2oGPT (5000)
│   └── embedding-service/         # Embeddings (5001)
├── k8s/                           # Kubernetes манифесты
├── scripts/                       # Deployment scripts
├── docs/                          # Документация
└── models/                        # ML модели (не в git)
```

### Таблица сервисов

| Service | Responsibility | Entrypoints | Depends on | Data stores | Messaging | Status |
|---------|---------------|-------------|-----------|-------------|-----------|--------|
| **api-gateway** | JWT auth, routing, rate limiting | `:8080` REST, WebSocket | All services | - | - | ✅ OK |
| **voice-gateway** | STT (Vosk), TTS (Google) | `:8081` REST, WebSocket | orchestrator | - | - | ✅ OK |
| **nlp-service** | Intent classification (rule-based) | `:8082` REST | - | - | - | ✅ OK |
| **orchestrator** | Command routing | `:8083` REST | nlp, pc-control, life-tracker, smart-home, llm | - | - | ⚠️ Needs circuit breaker |
| **pc-control** | System control (Linux) | `:8084` REST, WebSocket | - | - | RabbitMQ, Kafka | ✅ OK |
| **life-tracker** | Finance, time, calendar | `:8085` REST | - | PostgreSQL (jarvis_db) | - | ✅ OK |
| **smart-home-service** | IoT device control | `:8086` REST | - | - | MQTT (Mosquitto) | ✅ OK |
| **analytics-service** | Data aggregation | `:8087` REST | life-tracker (Feign) | - | - | ✅ OK |
| **security-service** | JWT generation/validation | `:8088` REST | - | PostgreSQL (jarvis_security) | - | ⚠️ Hardcoded secrets |
| **user-profile** | User preferences | `:8089` REST | - | PostgreSQL (jarvis_user_profile) | - | ✅ OK |
| **llm-service** | LLM API wrapper | `:8091` REST, WebSocket | llm-server, memory-service | - | - | ✅ OK |
| **planner-service** | Tasks, reminders | `:8092` REST | - | PostgreSQL (jarvis_db) | - | ✅ OK |
| **memory-service** | RAG memory (pgvector) | `:8093` REST | embedding-service | PostgreSQL (jarvis_memory) | - | ✅ OK |
| **llm-server** | h2oGPT inference (Python) | `:5000` REST | - | - | - | ✅ OK |
| **embedding-service** | Text embeddings (Python) | `:5001` REST | - | - | - | ✅ OK |

### Схема взаимодействий

```
Client (Desktop/Mobile)
    ↓
API Gateway (8080) [JWT validation]
    ↓
    ├─→ Voice Gateway (8081) [STT/TTS]
    │       ↓
    │   Orchestrator (8083)
    │       ↓
    │       ├─→ NLP Service (8082) [Intent]
    │       ├─→ PC Control (8084) [Actions]
    │       ├─→ Life Tracker (8085) [Data]
    │       ├─→ Smart Home (8086) [IoT]
    │       └─→ LLM Service (8091) [Fallback]
    │               ↓
    │           LLM Server (5000) [Inference]
    │               ↓
    │           Memory Service (8093) [RAG]
    │               ↓
    │           Embedding Service (5001)
    │
    ├─→ Security Service (8088) [Auth]
    ├─→ Analytics Service (8087) → Life Tracker
    ├─→ User Profile (8089)
    └─→ Planner Service (8092)
```

**Протоколы:**
- **REST/HTTP:** Все сервисы (Spring Boot)
- **WebSocket:** api-gateway, voice-gateway, llm-service, pc-control
- **MQTT:** smart-home-service → Mosquitto
- **RabbitMQ:** pc-control (commands)
- **Kafka:** pc-control (events)

---

## C) Как запустить (реально)

### Local (без Docker)

**Проблема:** ❌ Нет полной инструкции в README для локального запуска всех сервисов.

**Текущая инструкция (частичная):**
```bash
# 1. Запустить PostgreSQL
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_USER=jarvis -e POSTGRES_PASSWORD=jarvis123 \
  pgvector/pgvector:pg16

# 2. Запустить сервис
cd apps/api-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Что отсутствует:**
- Порядок запуска сервисов (зависимости)
- Настройка переменных окружения
- Запуск Python сервисов (llm-server, embedding-service)
- Настройка MQTT broker (Mosquitto)

**Рекомендация:** Создать `docs/LOCAL_SETUP.md` с пошаговой инструкцией.

### Docker Compose

**Проблема:** ❌ **КРИТИЧНО** — `docker-compose.yml` отсутствует в корне проекта!

**Где находится:** `scripts/legacy/docker-compose/docker-compose.yml`

**Проблемы:**
1. Makefile ссылается на `docker compose up`, но файла нет в корне
2. Legacy версия может быть устаревшей
3. Нет единой точки входа для Docker-запуска

**Что есть:**
- `scripts/legacy/docker-compose/docker-compose.yml` — полная версия
- `scripts/legacy/docker-compose/docker-compose-dev.yml` — dev версия
- `scripts/legacy/docker-compose/docker-compose-full.yml` — все сервисы

**Исправление:**
```bash
# Создать симлинк или скопировать в корень
cp scripts/legacy/docker-compose/docker-compose.yml .
# Или обновить Makefile
```

### Kubernetes (Minikube)

**Статус:** ✅ Работает через `./scripts/deploy.sh`

**Процесс:**
1. Запускает Minikube (если не запущен)
2. Собирает Docker images
3. Деплоит через `kubectl apply -k k8s/overlays/dev/`
4. Ждет готовности сервисов

**Проблемы:**
- NodePort меняется после редеплоя (нужно патчить вручную)
- Нет автоматического обновления desktop-client конфигурации

### Частые ошибки и как чинить

| Ошибка | Причина | Решение |
|--------|---------|---------|
| `docker compose up` не работает | Нет docker-compose.yml в корне | Скопировать из `scripts/legacy/docker-compose/` |
| `Connection refused` к БД | PostgreSQL не запущен | `docker run ... postgres` или через compose |
| `JWT_SECRET not found` | Переменная окружения не задана | Установить `export JWT_SECRET=...` |
| `UPSTREAM_HOST_NOT_FOUND` | Неправильный URL сервиса | Проверить `application-docker.yml` hostnames |
| NodePort изменился | K8s пересоздал сервис | `kubectl patch svc api-gateway -n jarvis -p '{"spec":{"type":"NodePort"}}'` |
| LLM не загружается | Модель не найдена | Проверить `/home/kwaqa/models/` |
| Flyway migration failed | БД не инициализирована | Запустить `init-databases.sh` |

---

## D) Топ-20 проблем (по критичности)

### [Severity: Critical]

#### 1. Отсутствует docker-compose.yml в корне проекта
- **Что:** Makefile и README ссылаются на `docker compose`, но файла нет
- **Где:** Корень проекта, `Makefile:75`, `README.md:68`
- **Почему проблема:** Невозможно запустить проект через Docker Compose
- **Как исправить:**
  ```bash
  cp scripts/legacy/docker-compose/docker-compose.yml .
  # Или создать новый с актуальными сервисами
  ```

#### 2. Хардкод паролей и секретов в конфигах
- **Что:** Пароли БД и JWT секреты захардкожены в YAML файлах
- **Где:** 
  - `apps/security-service/src/main/resources/application.yml:16` — `password: jarvis123`
  - `apps/api-gateway/src/main/resources/application.yaml:108` — JWT secret с дефолтом
  - `k8s/secrets/*.yaml` — все секреты в открытом виде
- **Почему проблема:** Безопасность, нельзя коммитить в git
- **Как исправить:**
  - Использовать только `${ENV_VAR}` без дефолтов в prod
  - Создать `.env.example` с примерами
  - Использовать External Secrets Operator в K8s

#### 3. Нет CI/CD pipeline
- **Что:** Отсутствуют GitHub Actions, Jenkins, GitLab CI
- **Где:** Нет `.github/workflows/`, `Jenkinsfile`, `.gitlab-ci.yml`
- **Почему проблема:** Нет автоматической сборки, тестов, деплоя
- **Как исправить:**
  - Создать `.github/workflows/ci.yml` с:
    - `mvn clean test`
    - `mvn clean package`
    - Docker build
    - Security scan (Snyk/Trivy)

#### 4. Низкое покрытие тестами
- **Что:** Найдено только 10 тестовых файлов на 13 сервисов
- **Где:** `apps/*/src/test/` — большинство пустые
- **Почему проблема:** Нет гарантии работоспособности после изменений
- **Как исправить:**
  - Добавить unit тесты для критических компонентов
  - Integration тесты с Testcontainers
  - E2E тесты для основных сценариев

### [Severity: High]

#### 5. Отсутствует документация по локальному запуску
- **Что:** README описывает только K8s деплой
- **Где:** `README.md:146-157` — неполная инструкция
- **Почему проблема:** Новые разработчики не могут запустить проект
- **Как исправить:** Создать `docs/LOCAL_SETUP.md` с пошаговой инструкцией

#### 6. NodePort меняется после редеплоя в K8s
- **Что:** API Gateway NodePort сбрасывается на ClusterIP
- **Где:** `DEPLOYMENT_INSTRUCTIONS.md:93` — ручной патч
- **Почему проблема:** Desktop client не может подключиться
- **Как исправить:**
  - Использовать LoadBalancer или Ingress
  - Или зафиксировать NodePort в манифесте

#### 7. Широкие catch Exception (68 мест)
- **Что:** `catch (Exception e)` скрывает реальные ошибки
- **Где:** 
  - `apps/llm-service/client/LlmClient.java:167, 254`
  - `apps/voice-gateway/websocket/VoiceWebSocketHandler.java:110, 168, ...`
- **Почему проблема:** Сложно отлаживать, теряется контекст ошибки
- **Как исправить:** Заменить на конкретные типы исключений

#### 8. Null safety warnings (50+)
- **Что:** Потенциальные NPE не обработаны
- **Где:**
  - `apps/memory-service/service/MemoryService.java:64, 113`
  - `apps/llm-service/client/LlmClient.java:235, 127`
- **Почему проблема:** Runtime ошибки в production
- **Как исправить:** Добавить `@NonNull`/`@Nullable` аннотации и проверки

#### 9. Отсутствует мониторинг и логирование
- **Что:** Нет централизованного логирования (ELK, Loki)
- **Где:** Каждый сервис логирует локально
- **Почему проблема:** Сложно отслеживать проблемы в production
- **Как исправить:**
  - Добавить Prometheus metrics
  - Настроить централизованное логирование
  - Добавить distributed tracing (Jaeger/Zipkin)

#### 10. Нет health checks для всех сервисов
- **Что:** Не все сервисы имеют `/actuator/health`
- **Где:** Проверить каждый сервис
- **Почему проблема:** K8s не может определить готовность
- **Как исправить:** Добавить health endpoints везде

### [Severity: Medium]

#### 11. Неиспользуемые зависимости в pom.xml
- **Что:** Возможны лишние зависимости
- **Где:** Все `pom.xml` файлы
- **Почему проблема:** Увеличение размера образов, риски безопасности
- **Как исправить:** `mvn dependency:analyze` и удалить неиспользуемые

#### 12. Отсутствует версионирование API
- **Что:** Все эндпоинты используют `/api/v1/`, но нет плана миграции
- **Где:** Все контроллеры
- **Почему проблема:** Сложно обновлять API без breaking changes
- **Как исправить:** Добавить версионирование и deprecation policy

#### 13. Нет rate limiting на критических эндпоинтах
- **Что:** Rate limiting отключен в api-gateway
- **Где:** `apps/api-gateway/src/main/resources/application.yaml:102` — `enabled: false`
- **Почему проблема:** Возможны DDoS атаки
- **Как исправить:** Включить и настроить лимиты

#### 14. Отсутствует валидация входных данных
- **Что:** Не все DTO имеют `@Valid` аннотации
- **Где:** Контроллеры без валидации
- **Почему проблема:** Некорректные данные могут попасть в БД
- **Как исправить:** Добавить `@Valid` и Bean Validation

#### 15. Нет документации по API (OpenAPI/Swagger)
- **Что:** Есть упоминание OpenAPI в `PROJECT_STRUCTURE.md:129`, но не подключен
- **Где:** Должно быть в каждом сервисе
- **Почему проблема:** Сложно интегрироваться с API
- **Как исправить:** Добавить SpringDoc OpenAPI

#### 16. Неиспользуемые поля классов
- **Что:** Поля объявлены, но не используются
- **Где:**
  - `apps/api-gateway/security/JwtUtil.java:22` — `issuer`
  - `apps/jarvis-common/ratelimit/RateLimitInterceptor.java:38` — `requestsPerPeriod`
- **Почему проблема:** Мусор в коде
- **Как исправить:** Удалить или пометить `@Deprecated`

#### 17. Type safety warnings
- **Что:** Raw types и unchecked conversions
- **Где:**
  - `apps/llm-service/client/LlmClient.java:230` — `Map` вместо `Map<String, Object>`
- **Почему проблема:** Возможны ClassCastException
- **Как исправить:** Параметризовать generic типы

#### 18. Отсутствует graceful shutdown для всех сервисов
- **Что:** Не все сервисы настроили `timeout-per-shutdown-phase`
- **Где:** Проверить `application.yml` каждого сервиса
- **Почему проблема:** Потеря данных при остановке
- **Как исправить:** Добавить везде graceful shutdown

#### 19. Нет backup стратегии для БД
- **Что:** Отсутствует автоматический backup PostgreSQL
- **Где:** K8s манифесты для postgres
- **Почему проблема:** Потеря данных при сбое
- **Как исправить:** Настроить pg_dump cron job или использовать оператор

#### 20. Отсутствует документация по развертыванию в production
- **Что:** Нет инструкций для staging/prod окружений
- **Где:** `DEPLOYMENT_INSTRUCTIONS.md` только для dev
- **Почему проблема:** Невозможно безопасно деплоить в prod
- **Как исправить:** Создать `docs/PRODUCTION_DEPLOYMENT.md`

---

## E) Топ-10 улучшений "быстро и много пользы" (Quick Wins)

### 1. Создать docker-compose.yml в корне
**Время:** 30 минут  
**Польза:** Можно запустить весь стек одной командой  
**Действие:** Скопировать из `scripts/legacy/docker-compose/` и обновить

### 2. Добавить .env.example
**Время:** 15 минут  
**Польза:** Понятно какие переменные нужны  
**Действие:** Создать файл с примерами (без реальных значений)

### 3. Включить rate limiting
**Время:** 10 минут  
**Польза:** Защита от DDoS  
**Действие:** `rate-limit.enabled: true` в api-gateway

### 4. Добавить health checks везде
**Время:** 1 час  
**Польза:** K8s корректно определяет готовность  
**Действие:** Проверить все сервисы, добавить `/actuator/health`

### 5. Удалить неиспользуемые импорты
**Время:** 30 минут  
**Польза:** Чище код  
**Действие:** `mvn clean compile` и исправить warnings

### 6. Добавить SpringDoc OpenAPI
**Время:** 2 часа  
**Польза:** Автоматическая документация API  
**Действие:** Добавить зависимость и аннотации

### 7. Создать LOCAL_SETUP.md
**Время:** 1 час  
**Польза:** Новые разработчики могут запустить проект  
**Действие:** Написать пошаговую инструкцию

### 8. Исправить NodePort проблему
**Время:** 30 минут  
**Польза:** Стабильный доступ к API Gateway  
**Действие:** Использовать LoadBalancer или зафиксировать NodePort

### 9. Добавить базовые unit тесты
**Время:** 4 часа  
**Польза:** Уверенность в критических компонентах  
**Действие:** Тесты для AuthService, JwtUtil, основных контроллеров

### 10. Настроить централизованное логирование
**Время:** 2 часа  
**Польза:** Легче отлаживать проблемы  
**Действие:** Добавить logback с JSON форматированием, настроить Loki

---

## F) Рекомендованный roadmap на 2 недели

### Day 1-3: Критические исправления

**Day 1:**
- ✅ Создать `docker-compose.yml` в корне
- ✅ Добавить `.env.example`
- ✅ Исправить хардкод секретов (использовать env vars)
- ✅ Создать `docs/LOCAL_SETUP.md`

**Day 2:**
- ✅ Настроить базовый CI/CD (GitHub Actions)
  - Build на каждый PR
  - Tests
  - Docker build
- ✅ Добавить health checks везде
- ✅ Включить rate limiting

**Day 3:**
- ✅ Исправить NodePort проблему (LoadBalancer/Ingress)
- ✅ Добавить SpringDoc OpenAPI
- ✅ Удалить неиспользуемые импорты/поля

### Day 4-7: Улучшение качества кода

**Day 4:**
- ✅ Рефакторинг catch Exception (заменить на конкретные типы)
- ✅ Добавить null safety аннотации
- ✅ Исправить type safety warnings

**Day 5:**
- ✅ Добавить валидацию входных данных (`@Valid`)
- ✅ Улучшить обработку ошибок (кастомные исключения)
- ✅ Добавить логирование с correlation ID

**Day 6:**
- ✅ Написать unit тесты для критических компонентов:
  - AuthService
  - JwtUtil
  - Основные контроллеры
- ✅ Добавить integration тесты с Testcontainers

**Day 7:**
- ✅ Настроить мониторинг (Prometheus metrics)
- ✅ Настроить централизованное логирование
- ✅ Добавить distributed tracing (опционально)

### Week 2: Production-ready

**Day 8-9:**
- ✅ Создать staging окружение
- ✅ Настроить backup для БД
- ✅ Добавить security scanning (Snyk/Trivy) в CI
- ✅ Создать `docs/PRODUCTION_DEPLOYMENT.md`

**Day 10-11:**
- ✅ Настроить версионирование API
- ✅ Добавить circuit breakers везде (Resilience4j)
- ✅ Настроить graceful shutdown везде
- ✅ Оптимизировать Docker images (multi-stage builds)

**Day 12-14:**
- ✅ E2E тесты для основных сценариев
- ✅ Нагрузочное тестирование
- ✅ Документация по troubleshooting
- ✅ Финальный ревью и проверка всех исправлений

---

## G) Приложение

### Список всех сервисов/модулей

| Модуль | Путь | Язык | Порт | БД |
|--------|------|------|------|-----|
| jarvis-common | `apps/jarvis-common/` | Java | - | - |
| api-gateway | `apps/api-gateway/` | Java | 8080 | - |
| voice-gateway | `apps/voice-gateway/` | Java | 8081 | - |
| nlp-service | `apps/nlp-service/` | Java | 8082 | - |
| orchestrator | `apps/orchestrator/` | Java | 8083 | - |
| pc-control | `apps/pc-control/` | Java | 8084 | - |
| life-tracker | `apps/life-tracker/` | Java | 8085 | PostgreSQL |
| smart-home-service | `apps/smart-home-service/` | Java | 8086 | - |
| analytics-service | `apps/analytics-service/` | Java | 8087 | - |
| security-service | `apps/security-service/` | Java | 8088 | PostgreSQL |
| user-profile | `apps/user-profile/` | Java | 8089 | PostgreSQL |
| llm-service | `apps/llm-service/` | Java | 8091 | - |
| planner-service | `apps/planner-service/` | Java | 8092 | PostgreSQL |
| memory-service | `apps/memory-service/` | Java | 8093 | PostgreSQL |
| llm-server | `docker/llm-server/` | Python | 5000 | - |
| embedding-service | `docker/embedding-service/` | Python | 5001 | - |
| desktop-client | `apps/desktop-client-javafx/` | Kotlin | - | - |
| mobile-client | `apps/mobile-client/` | Kotlin | - | - |

### Список конфигов/секретов

**Конфигурационные файлы:**
- `apps/*/src/main/resources/application.yml` — основной конфиг
- `apps/*/src/main/resources/application-dev.yml` — dev профиль
- `apps/*/src/main/resources/application-docker.yml` — Docker профиль
- `apps/*/src/main/resources/application-k8s.yml` — K8s профиль

**Секреты (требуют замены в production):**
- `k8s/secrets/jwt-secret.yaml` — JWT signing key
- `k8s/secrets/db-credentials.yaml` — PostgreSQL credentials
- `k8s/dev/02-secrets-dev.yaml` — Dev secrets
- `k8s/base/secrets.yaml` — Base secrets
- `k8s/overlays/local/secrets-local.yaml` — Local secrets

**Переменные окружения:**
- `JWT_SECRET` — JWT signing key (обязательно)
- `DB_PASSWORD` — PostgreSQL password
- `SPRING_DATASOURCE_URL` — JDBC URL
- `SPRING_DATASOURCE_USERNAME` — DB username
- `SPRING_DATASOURCE_PASSWORD` — DB password
- `LLM_SERVER_URL` — LLM server URL
- `EMBEDDING_SERVICE_URL` — Embedding service URL
- `MQTT_BROKER_URL` — MQTT broker URL

### Список эндпоинтов/топиков/очередей

**REST Endpoints (через API Gateway:8080):**

**Authentication:**
- `POST /auth/register` — Регистрация
- `POST /auth/login` — Вход
- `POST /auth/refresh` — Обновление токена
- `GET /auth/me` — Текущий пользователь

**Voice:**
- `POST /api/v1/voice/transcribe` — Аудио → текст
- `POST /api/v1/voice/transcribe/stream` — Потоковое распознавание
- `POST /api/v1/voice/synthesize` — Текст → аудио

**NLP:**
- `POST /api/v1/nlp/analyze` — Анализ намерений

**Orchestrator:**
- `POST /api/v1/orchestrator/execute` — Выполнение команды

**PC Control:**
- `POST /api/v1/pc/action` — Системные действия

**Life Tracker:**
- `POST /api/v1/life/finance/expense` — Добавить расход
- `GET /api/v1/life/finance/expenses` — Список расходов
- `POST /api/v1/life/time/start` — Начать трекинг времени
- `POST /api/v1/life/time/stop` — Остановить трекинг
- `GET /api/v1/life/time/records` — Записи времени
- `POST /api/v1/life/calendar/event` — Добавить событие
- `GET /api/v1/life/calendar/events` — Список событий

**Smart Home:**
- `POST /api/v1/smarthome/devices/{id}/action` — Управление устройством

**Analytics:**
- `GET /api/v1/analytics/overview` — Общая статистика
- `GET /api/v1/analytics/expenses/by-month` — Расходы по месяцам
- `GET /api/v1/analytics/expenses/by-category` — Расходы по категориям

**LLM:**
- `POST /api/v1/llm/chat` — Чат с LLM
- `POST /api/v1/llm/dialog` — Диалоговый режим

**Memory:**
- `POST /memory/ingest` — Сохранить разговор
- `POST /memory/search` — Поиск в памяти (RAG)

**Planner:**
- `GET /api/v1/planner/daily` — Дневной план
- `POST /api/v1/planner/tasks` — Создать задачу
- `GET /api/v1/planner/reminders` — Напоминания

**User Profile:**
- `GET /api/v1/user/profile` — Профиль пользователя
- `PUT /api/v1/user/profile` — Обновить профиль
- `GET /api/v1/user/preferences` — Настройки

**WebSocket:**
- `/ws/jarvis-llm` — LLM WebSocket
- `/ws/voice` — Voice WebSocket
- `/ws/pc-control` — PC Control WebSocket

**MQTT Topics:**
- `smarthome/devices/{id}/command` — Команды устройствам
- `smarthome/devices/{id}/status` — Статус устройств

**RabbitMQ Queues:**
- `pc-control.commands` — Команды PC control
- `voice.commands` — Команды voice gateway

**Kafka Topics:**
- `voice.events` — События voice gateway
- `pc-control.events` — События PC control

---

## Заключение

Проект **Jarvis 2.0** находится в хорошем состоянии с точки зрения архитектуры и кода, но требует доработки инфраструктуры и процессов для production-ready состояния. Основные усилия должны быть направлены на:

1. **Инфраструктуру:** docker-compose, CI/CD, мониторинг
2. **Безопасность:** убрать хардкод секретов, добавить security scanning
3. **Качество:** увеличить покрытие тестами, улучшить обработку ошибок
4. **Документацию:** инструкции по запуску и деплою

При следовании рекомендованному roadmap проект может быть готов к production за 2 недели.

---

**Отчет подготовлен:** 2025-01-27  
**Следующий ревью:** После выполнения roadmap

