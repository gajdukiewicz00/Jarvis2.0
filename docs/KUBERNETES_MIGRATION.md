# Jarvis 2.0 - Kubernetes Migration Guide

## Содержание

1. [Обзор новой архитектуры](#обзор-новой-архитектуры)
2. [Решённые проблемы](#решённые-проблемы)
3. [Быстрый старт](#быстрый-старт)
4. [Поэтапная миграция](#поэтапная-миграция)
5. [Messaging Architecture](#messaging-architecture)
6. [HTTPS Configuration](#https-configuration)
7. [Observability](#observability)
8. [Troubleshooting](#troubleshooting)

---

## Обзор новой архитектуры

### Схема целевой архитектуры

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENTS (Browser/Mobile)                        │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTPS
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    INGRESS / NGINX (TLS Termination)                         │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTP
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            api-gateway:8080                                  │
│  - JWT Validation (локальная, без сетевых вызовов)                           │
│  - Routing к микросервисам                                                   │
│  - RabbitMQ Producer (команды) - когда FEATURE_RABBITMQ_* = true             │
│  - Kafka Producer (audit) - когда FEATURE_KAFKA_AUDIT = true                 │
└───────┬───────────────────────────┬─────────────────────────────────────────┘
        │                           │
        │  Синхронные запросы       │  Асинхронные команды
        │  (Feign/HTTP)             │  (RabbitMQ)
        │                           │
        ▼                           ▼
┌───────────────┐    ┌──────────────────────────────────────────────────────┐
│security-svc   │    │                  RabbitMQ Cluster                    │
│  :8088        │    │  Exchanges: jarvis.commands, jarvis.rpc, jarvis.dlx  │
│  ┌─────────┐  │    │  Queues: pc-control.cmds, smart-home.cmds, voice.cmds│
│  │Postgres │  │    └──────────────────────────┬───────────────────────────┘
│  │jarvis_  │  │                               │
│  │security │  │           ┌───────────────────┼───────────────────┐
│  └─────────┘  │           │                   │                   │
└───────────────┘           ▼                   ▼                   ▼
                     ┌────────────┐      ┌────────────┐      ┌────────────┐
                     │ pc-control │      │smart-home  │      │voice-gtw   │
                     │   :8084    │      │  :8086     │      │  :8081     │
                     │ Consumer   │      │ Consumer   │      │ Producer   │
                     └────────────┘      └────────────┘      └────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              Kafka Cluster                                   │
│  Topics: jarvis.events.{expenses,time-tracking,calendar,smarthome,...}       │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
           ┌────────────────────┼────────────────────┐
           │                    │                    │
           ▼                    ▼                    ▼
    ┌────────────┐       ┌────────────┐       ┌────────────┐
    │life-tracker│       │analytics   │       │ audit-log  │
    │   :8085    │       │  :8087     │       │(будущее)   │
    │ Producer   │       │ Consumer   │       │ Consumer   │
    │ ┌────────┐ │       └────────────┘       └────────────┘
    │ │Postgres│ │
    │ │jarvis_ │ │
    │ │db      │ │
    │ └────────┘ │
    └────────────┘
```

---

## Решённые проблемы

### Before → After

| Проблема | Причина | Решение |
|----------|---------|---------|
| `UnknownHostException: postgres` | DNS не готов при старте | K8s Service DNS + initContainers с wait-for |
| `UnknownHostException: security-service` | Docker DNS race condition | K8s Service DNS стабильный |
| `HikariPool-1 - Connection not available` | Pool exhaustion | Единый `application-k8s.yml` с правильным Hikari config |
| `Read timed out executing POST http://security-service` | Feign timeout | Circuit breaker + разумные timeouts |
| `No static resource actuator/health` (voice-gateway) | Отсутствует actuator dependency | Добавлен `spring-boot-starter-actuator` |
| `InvalidDefinitionException: HttpMethod` | Сериализация объекта вместо string | Уже исправлено в коде (`.name()`) |
| `503 от analytics-service` | Синхронная зависимость life-tracker | Kafka events (когда feature flag включен) |

### Детали решений

#### 1. DNS и Service Discovery

**Было (Docker Compose):**
```yaml
# Имя сервиса = hostname, DNS может не успеть
depends_on:
  postgres:
    condition: service_healthy
```

**Стало (Kubernetes):**
```yaml
# Стабильный DNS через Service
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres.jarvis.svc.cluster.local:5432/jarvis_db

# + initContainer для гарантии
initContainers:
  - name: wait-for-postgres
    command: ["sh", "-c", "until nc -z postgres 5432; do sleep 5; done"]
```

#### 2. HikariCP Configuration

**Единый конфиг в `application-k8s.yml`:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1200000      # 20 min - до PostgreSQL timeout
      keepalive-time: 300000     # 5 min
      initialization-fail-timeout: -1  # Не падать при старте
```

#### 3. Actuator в voice-gateway

**Добавлено в `pom.xml`:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

---

## Быстрый старт

### Локальный запуск (Docker Compose)

```bash
# 1. Генерируем self-signed сертификат
./scripts/generate-certs.sh

# 2. Добавляем в /etc/hosts
echo "127.0.0.1 jarvis.local" | sudo tee -a /etc/hosts

# 3. Собираем все образы
mvn clean package -DskipTests

# 4. Запускаем полный стек
docker-compose -f docker-compose-full.yml up -d

# 5. Проверяем статус
docker-compose -f docker-compose-full.yml ps

# 6. Открываем в браузере
# https://jarvis.local
```

### Kubernetes (Dev профиль)

```bash
# 1. Генерируем сертификат
./scripts/generate-certs.sh

# 2. Применяем манифесты
kubectl apply -f k8s/base/
kubectl apply -f k8s/base/tls-secret-generated.yaml
kubectl apply -f k8s/dev/postgres/
kubectl apply -f k8s/dev/rabbitmq/
kubectl apply -f k8s/dev/kafka/

# 3. Ждём инфраструктуру
kubectl wait --for=condition=ready pod -l app=postgres -n jarvis --timeout=120s
kubectl wait --for=condition=ready pod -l app=rabbitmq -n jarvis --timeout=120s
kubectl wait --for=condition=ready pod -l app=kafka -n jarvis --timeout=180s

# 4. Создаём топики Kafka
kubectl apply -f k8s/dev/kafka/topics-init-job.yaml

# 5. Деплоим микросервисы
kubectl apply -f k8s/dev/services/

# 6. Деплоим Ingress
kubectl apply -f k8s/dev/ingress.yaml

# 7. Проверяем
kubectl get pods -n jarvis
kubectl get svc -n jarvis
kubectl get ingress -n jarvis
```

---

## Поэтапная миграция

### Шаг 1: pc-control через RabbitMQ

```bash
# Включаем feature flag
kubectl set env deployment/api-gateway FEATURE_RABBITMQ_PC_CONTROL=true -n jarvis
kubectl set env deployment/pc-control FEATURE_RABBITMQ_PC_CONTROL=true -n jarvis

# Проверяем
curl -X POST https://jarvis.local/api/v1/pc/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action": "VOLUME_UP", "value": 10}'

# Смотрим логи
kubectl logs -f deployment/pc-control -n jarvis
```

### Шаг 2: smart-home через RabbitMQ

```bash
kubectl set env deployment/api-gateway FEATURE_RABBITMQ_SMART_HOME=true -n jarvis
kubectl set env deployment/smart-home-service FEATURE_RABBITMQ_SMART_HOME=true -n jarvis
```

### Шаг 3: analytics через Kafka

```bash
kubectl set env deployment/life-tracker FEATURE_KAFKA_EVENTS=true -n jarvis
kubectl set env deployment/analytics-service FEATURE_KAFKA_ANALYTICS=true -n jarvis
```

---

## Messaging Architecture

### RabbitMQ - Command Bus

| Exchange | Type | Routing Key | Queue | Consumer |
|----------|------|-------------|-------|----------|
| jarvis.commands | topic | pc.control.# | pc-control.commands | pc-control |
| jarvis.commands | topic | smarthome.# | smart-home.commands | smart-home-service |
| jarvis.commands | topic | voice.# | voice.commands | assistant-core |
| jarvis.commands | topic | assistant.# | assistant-core.commands | assistant-core |
| jarvis.dlx | topic | dlq.* | dlq.* | (manual processing) |

### Kafka - Event Stream

| Topic | Producer | Consumer | Retention |
|-------|----------|----------|-----------|
| jarvis.events.expenses | life-tracker | analytics-service | 7 days |
| jarvis.events.time-tracking | life-tracker | analytics-service | 7 days |
| jarvis.events.calendar | life-tracker | analytics-service | 7 days |
| jarvis.events.smarthome | smart-home-service | - | 3 days |
| jarvis.events.pc-control | pc-control | - | 3 days |
| jarvis.events.audit | api-gateway | - | 30 days |
| jarvis.events.logs | all services | - | 1 day |

---

## HTTPS Configuration

### TLS в Kubernetes (Ingress)

```yaml
# k8s/dev/ingress.yaml
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - jarvis.local
      secretName: jarvis-tls
```

### TLS в Docker Compose (Nginx)

```
Client → https://jarvis.local:443 → nginx (TLS termination) → http://api-gateway:8080
```

### Важные заголовки

Nginx добавляет:
- `X-Forwarded-Proto: https`
- `X-Forwarded-For: <client-ip>`
- `X-Real-IP: <client-ip>`

Spring должен доверять этим заголовкам:
```yaml
server:
  forward-headers-strategy: framework
```

---

## Observability

### Текущее состояние

- ✅ Actuator endpoints (`/actuator/health`, `/actuator/info`)
- ✅ Liveness/Readiness probes
- ✅ Structured logging с traceId
- ⏳ Prometheus metrics (готовы, не собираются)
- ⏳ Grafana dashboards (не настроены)
- ⏳ Distributed tracing (не настроено)

### Планы на будущее

1. **Prometheus + Grafana**
   ```bash
   helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring
   ```

2. **OpenTelemetry / Jaeger**
   ```xml
   <dependency>
       <groupId>io.micrometer</groupId>
       <artifactId>micrometer-tracing-bridge-otel</artifactId>
   </dependency>
   ```

3. **Централизованные логи (Loki)**
   ```bash
   helm install loki grafana/loki-stack -n monitoring
   ```

---

## Troubleshooting

### Pod не стартует

```bash
# Смотрим события
kubectl describe pod <pod-name> -n jarvis

# Смотрим логи (включая предыдущий контейнер)
kubectl logs <pod-name> -n jarvis --previous

# Проверяем ресурсы
kubectl top pod -n jarvis
```

### Проблемы с DNS

```bash
# Запускаем debug pod
kubectl run -it --rm debug --image=busybox -n jarvis -- sh

# Проверяем DNS
nslookup postgres
nslookup rabbitmq
nslookup kafka
```

### RabbitMQ проблемы

```bash
# Статус кластера
kubectl exec -it rabbitmq-0 -n jarvis -- rabbitmqctl cluster_status

# Список очередей
kubectl exec -it rabbitmq-0 -n jarvis -- rabbitmqctl list_queues -p jarvis

# UI
kubectl port-forward svc/rabbitmq 15672:15672 -n jarvis
# Открыть http://localhost:15672
```

### Kafka проблемы

```bash
# Список топиков
kubectl exec -it kafka-0 -n jarvis -- kafka-topics.sh --bootstrap-server localhost:9092 --list

# Описание топика
kubectl exec -it kafka-0 -n jarvis -- kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic jarvis.events.expenses

# Консьюмер для отладки
kubectl exec -it kafka-0 -n jarvis -- kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic jarvis.events.expenses --from-beginning
```

### Проверка HTTPS

```bash
# Проверка сертификата
openssl s_client -connect jarvis.local:443 -servername jarvis.local </dev/null 2>/dev/null | openssl x509 -text

# curl с игнорированием self-signed
curl -k https://jarvis.local/actuator/health
```

---

## Сводная таблица сервисов

| Сервис | Port | Docker Image | K8s Service | Actuator | RabbitMQ | Kafka |
|--------|------|--------------|-------------|----------|----------|-------|
| api-gateway | 8080 | jarvis/api-gateway | api-gateway | ✅ | Producer | Producer |
| security-service | 8088 | jarvis/security-service | security-service | ✅ | - | - |
| life-tracker | 8085 | jarvis/life-tracker | life-tracker | ✅ | - | Producer |
| analytics-service | 8087 | jarvis/analytics-service | analytics-service | ✅ | - | Consumer |
| voice-gateway | 8081 | jarvis/voice-gateway | voice-gateway | ✅ | Producer | - |
| pc-control | 8084 | jarvis/pc-control | pc-control | ✅ | Consumer | Producer |
| smart-home-service | 8086 | jarvis/smart-home-service | smart-home-service | ✅ | Consumer | Producer |
| assistant-core | 8090 | jarvis/assistant-core | assistant-core | ✅ | Consumer | - |
| nlp-service | 8082 | jarvis/nlp-service | nlp-service | ✅ | - | - |
| orchestrator | 8083 | jarvis/orchestrator | orchestrator | ✅ | - | - |
| user-profile | 8089 | jarvis/user-profile | user-profile | ✅ | - | - |
| llm-service | 8091 | jarvis/llm-service | llm-service | ✅ | - | - |
| planner-service | 8092 | jarvis/planner-service | planner-service | ✅ | - | - |
| llm-server | 5000 | jarvis/llm-server | llm-server | N/A | - | - |

