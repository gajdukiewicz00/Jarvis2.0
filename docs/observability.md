# Jarvis 2.0 - Observability

## Содержание
- [Health Endpoints](#health-endpoints)
- [Метрики](#метрики)
- [Логирование](#логирование)
- [Мониторинг](#мониторинг)

---

## Health Endpoints

### Доступные endpoints

Все сервисы предоставляют Actuator endpoints:

| Endpoint | Описание |
|----------|----------|
| `/actuator/health` | Общий статус здоровья |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/info` | Информация о сервисе |
| `/actuator/metrics` | Список доступных метрик |
| `/actuator/prometheus` | Prometheus-формат метрик |

### Проверка здоровья

```bash
# API Gateway
curl http://localhost:8080/actuator/health | jq

# Через docker
docker exec jarvis20_api-gateway curl -s http://localhost:8080/actuator/health | jq
```

### Пример ответа health

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 250685575168,
        "free": 125342787584,
        "threshold": 10485760
      }
    },
    "lifeTracker": {
      "status": "UP",
      "details": {
        "service": "life-tracker",
        "status": "reachable",
        "responseTimeMs": 45
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

### Health статусы

| Status | Описание | HTTP Code |
|--------|----------|-----------|
| `UP` | Сервис работает нормально | 200 |
| `DOWN` | Сервис недоступен | 503 |
| `OUT_OF_SERVICE` | Сервис временно отключён | 503 |
| `UNKNOWN` | Статус неизвестен | 200 |

### Кастомные health indicators

#### LifeTrackerHealthIndicator (analytics-service)

Проверяет доступность life-tracker:

```java
@Component
public class LifeTrackerHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        try {
            lifeTrackerClient.getExpenses();
            return Health.up()
                    .withDetail("service", "life-tracker")
                    .withDetail("status", "reachable")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("service", "life-tracker")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
```

---

## Метрики

### Доступные метрики

```bash
# Список всех метрик
curl http://localhost:8080/actuator/metrics | jq '.names[]' | head -20

# Конкретная метрика
curl http://localhost:8080/actuator/metrics/jvm.memory.used | jq
```

### Ключевые метрики

#### JVM

| Метрика | Описание |
|---------|----------|
| `jvm.memory.used` | Используемая память |
| `jvm.memory.max` | Максимальная память |
| `jvm.gc.pause` | Время GC-пауз |
| `jvm.threads.live` | Количество потоков |

#### HTTP

| Метрика | Описание |
|---------|----------|
| `http.server.requests` | HTTP-запросы (count, sum, max) |

#### HikariCP

| Метрика | Описание |
|---------|----------|
| `hikaricp.connections.active` | Активные соединения |
| `hikaricp.connections.idle` | Idle соединения |
| `hikaricp.connections.pending` | Ожидающие соединения |
| `hikaricp.connections.timeout` | Таймауты соединений |
| `hikaricp.connections.acquire` | Время получения соединения |

### Prometheus формат

```bash
curl http://localhost:8080/actuator/prometheus
```

```prometheus
# HELP jvm_memory_used_bytes Used bytes of a given JVM memory area
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 1.048576E7

# HELP http_server_requests_seconds
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{method="GET",outcome="SUCCESS",status="200",uri="/api/v1/life/finance/expenses",} 42
http_server_requests_seconds_sum{method="GET",outcome="SUCCESS",status="200",uri="/api/v1/life/finance/expenses",} 1.234567
```

---

## Логирование

### Конфигурация логов

```yaml
logging:
  level:
    root: INFO
    org.jarvis: INFO
    org.springframework.web: INFO
    com.zaxxer.hikari: INFO
    feign: WARN
    
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### Уровни логирования

| Level | Использование |
|-------|---------------|
| ERROR | Критические ошибки, требующие внимания |
| WARN | Предупреждения (таймауты, retry, etc.) |
| INFO | Важные события (логин, регистрация) |
| DEBUG | Отладочная информация |
| TRACE | Детальная трассировка |

### Рекомендации по логированию

#### Что логировать на уровне INFO:
- Успешная аутентификация
- Регистрация пользователей
- Важные бизнес-события

#### Что логировать на уровне WARN:
- JWT expired (не ERROR!)
- Таймауты к upstream сервисам
- Retry attempts
- Валидация failed

#### Что логировать на уровне ERROR:
- Внутренние ошибки сервера
- Ошибки БД (кроме таймаутов)
- Неожиданные исключения

### Просмотр логов

```bash
# Все сервисы
docker compose logs -f

# Конкретный сервис
docker compose logs -f life-tracker

# Только ошибки
docker compose logs -f 2>&1 | grep -i error

# Последние 100 строк
docker compose logs --tail=100 api-gateway

# С timestamps
docker compose logs -t api-gateway
```

---

## Мониторинг

### Docker healthchecks

Все сервисы в docker-compose.yml имеют healthchecks:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 60s
```

### Проверка статуса контейнеров

```bash
# Статус всех контейнеров
docker compose ps

# Детальный healthcheck статус
docker inspect --format='{{.Name}}: {{.State.Health.Status}}' $(docker ps -q)

# Логи healthcheck
docker inspect --format='{{json .State.Health}}' jarvis20_api-gateway | jq
```

### Скрипт мониторинга

```bash
#!/bin/bash
# monitor.sh - простой мониторинг сервисов

SERVICES=(
  "api-gateway:8080"
  "life-tracker:8085"
  "analytics-service:8087"
  "security-service:8088"
)

for service in "${SERVICES[@]}"; do
  name=$(echo $service | cut -d: -f1)
  port=$(echo $service | cut -d: -f2)
  
  status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$port/actuator/health)
  
  if [ "$status" == "200" ]; then
    echo "✅ $name: UP"
  else
    echo "❌ $name: DOWN (HTTP $status)"
  fi
done
```

### Prometheus + Grafana (опционально)

Для production рекомендуется добавить мониторинг:

```yaml
# docker-compose.monitoring.yml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    networks:
      - jarvis-net

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    networks:
      - jarvis-net
```

```yaml
# docker/prometheus/prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'jarvis-services'
    static_configs:
      - targets:
        - 'api-gateway:8080'
        - 'life-tracker:8085'
        - 'analytics-service:8087'
        - 'security-service:8088'
    metrics_path: '/actuator/prometheus'
```

---

## Alerts (рекомендации)

### Критические алерты

| Условие | Действие |
|---------|----------|
| `up == 0` | Сервис недоступен |
| `hikaricp_connections_pending > 5` | Нехватка соединений БД |
| `http_server_requests_seconds_count{status="500"} > 10` | Много ошибок 500 |

### Предупреждения

| Условие | Действие |
|---------|----------|
| `jvm_memory_used_bytes / jvm_memory_max_bytes > 0.9` | Мало памяти |
| `http_server_requests_seconds_max > 5` | Медленные запросы |
| `hikaricp_connections_timeout_total > 0` | Таймауты БД |

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

