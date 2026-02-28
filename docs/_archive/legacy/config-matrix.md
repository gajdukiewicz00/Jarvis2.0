# Jarvis 2.0 - Configuration Matrix

**Дата обновления:** 2025-12-02

## Сводка по конфигурационным файлам

| Service | Profiles | Config Files |
|---------|----------|--------------|
| api-gateway | default, docker, dev | `application.yaml`, `application-docker.yml`, `application-dev.yaml` |
| life-tracker | default, docker, dev, test | `application.yaml`, `application-docker.yml`, `application-dev.yaml`, `application-test.yml` |
| security-service | default, docker, dev | `application.yml`, `application-docker.yml`, `application-dev.yml` |
| analytics-service | default, docker, dev, test | `application.yml`, `application-docker.yml`, `application-dev.yml`, `application-test.yml` |
| assistant-core | default, docker, dev | `application.yml`, `application-docker.yml`, `application-dev.yml` |
| voice-gateway | default, docker, dev | `application.yaml`, `application-docker.yml`, `application-dev.yaml` |
| planner-service | default, docker | `application.yml`, `application-docker.yml` |
| user-profile | default, docker | `application.yaml`, `application-docker.yml` |
| llm-service | default, docker | `application.yml`, `application-docker.yml` |
| pc-control | default, docker, security | `application-docker.yml`, `application-security.yml` |
| smart-home-service | default, docker, security | `application.yml`, `application-docker.yml`, `application-security.yml` |
| nlp-service | default, docker | `application-docker.yml` |
| orchestrator | default, docker | `application-docker.yml` |
| shared-config | - | `application-hikari.yml` |

---

## Matrix: Service → Profile → Port → DB URL

| Service | Profile | Port | DB URL | Notes |
|---------|---------|------|--------|-------|
| **api-gateway** | default | 8080 | - | Нет БД |
| | docker | 8080 | - | Service URLs → Docker hostnames |
| | dev | 8080 | - | JWT 24h |
| **life-tracker** | default | 8085 | `localhost:5432/jarvis_db` | |
| | docker | 8085 | `postgres:5432/jarvis_db` | ✅ Правильный hostname |
| | dev | 8085 | `localhost:5432/jarvis_db` | |
| **security-service** | default | 8088 | `localhost:5432/jarvis_security` | |
| | docker | 8088 | `postgres:5432/jarvis_security` | ✅ Правильный hostname |
| | dev | 8088 | - | Отключен auth |
| **analytics-service** | default | 8087 | - | Нет БД, Feign → life-tracker |
| | docker | 8087 | - | life-tracker URL → Docker hostname |
| | dev | 8087 | - | |
| **assistant-core** | default | 8090 | `localhost:5432/jarvis_assistant_core` | |
| | docker | 8090 | `postgres:5432/jarvis_assistant_core` | ✅ Правильный hostname |
| | dev | 8090 | `localhost:5432/jarvis_assistant_core` | |
| **voice-gateway** | default | 8081 | - | Нет БД |
| | docker | 8081 | - | |
| | dev | 8081 | - | |
| **planner-service** | default | 8092 | `localhost:5432/jarvis_db` | |
| | docker | 8092 | `postgres:5432/jarvis_db` | ✅ Правильный hostname |
| **user-profile** | default | 8089 | `localhost:5432/jarvis_user_profile` | |
| | docker | 8089 | `postgres:5432/jarvis_user_profile` | ✅ Правильный hostname |
| **llm-service** | default | 8091 | - | Нет БД |
| | docker | 8091 | - | llm-server URL → Docker hostname |
| **pc-control** | default/docker | 8084 | - | Нет БД |
| **smart-home-service** | default/docker | 8086 | - | MQTT → Mosquitto |
| **nlp-service** | docker | 8082 | - | Нет БД |
| **orchestrator** | docker | 8083 | - | Нет БД |

---

## Environment Variables

### Критичные переменные (secrets)

| Variable | Service | Description | Default |
|----------|---------|-------------|---------|
| `JWT_SECRET` | api-gateway, security-service | JWT signing key | ⚠️ hardcoded default |
| `DB_PASSWORD` | all DB services | Postgres password | `jarvis` / `jarvis123` |
| `SPRING_DATASOURCE_PASSWORD` | all DB services | Explicit datasource password | - |

### Переменные конфигурации

| Variable | Service | Description | Default |
|----------|---------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | all | Active profiles | - |
| `SPRING_DATASOURCE_URL` | DB services | JDBC URL | per-service |
| `SPRING_DATASOURCE_USERNAME` | DB services | DB username | `jarvis` |
| `LIFE_TRACKER_URL` | analytics-service | life-tracker URL | `http://localhost:8085` |
| `VOICE_GATEWAY_URL` | api-gateway | voice-gateway URL | Docker: `http://voice-gateway:8081` |
| `SECURITY_URL` | api-gateway | security-service URL | Docker: `http://security-service:8088` |
| `LLM_SERVER_URL` | llm-service | Python LLM server | `http://llm-server:5000` |
| `MQTT_BROKER_URL` | smart-home-service | MQTT broker | `tcp://mosquitto:1883` |

---

## Security Concerns (⚠️ Требуют внимания)

### Hardcoded Secrets

| File | Issue | Action Required |
|------|-------|-----------------|
| `security-service/application.yml` | `jwt.secret` hardcoded | Использовать `${JWT_SECRET}` |
| `api-gateway/application.yaml` | `jwt.secret` with default | OK, но для prod требуется env |
| `**/application*.yml` | `password: jarvis` | Использовать env vars |

### Recommended Changes

1. **Все пароли БД** → через `${SPRING_DATASOURCE_PASSWORD:jarvis}` (уже частично)
2. **JWT Secret** → обязательно через `${JWT_SECRET}` без fallback в prod
3. **Создать `.env.example`** с примером переменных (без реальных значений)

---

## Profile Activation

### docker-compose.yml (Production-like)
```yaml
environment:
  - SPRING_PROFILES_ACTIVE=docker
```

### docker-compose-dev.yml (Development)
```yaml
environment:
  - SPRING_PROFILES_ACTIVE=dev,docker
```

### Local Development (IDE)
```
-Dspring.profiles.active=dev
```

---

## Unused/Legacy Configs (⚠️ Review needed)

| File | Status | Notes |
|------|--------|-------|
| `nlp-service/src/.../application.yml` | ❓ Missing | Нет default config, только docker |
| `orchestrator/src/.../application.yml` | ❓ Missing | Нет default config, только docker |
| `pc-control/src/.../application.yml` | ❓ Missing | Нет default config |

---

*Документ создан: 2025-12-02*

