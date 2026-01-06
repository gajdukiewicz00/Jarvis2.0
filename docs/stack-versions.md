# Jarvis 2.0 - Stack Versions

**Дата обновления:** 2025-12-02

## Core Versions

| Component | Version | Source |
|-----------|---------|--------|
| Java | 21 | `pom.xml` property |
| Maven | 3.8+ | Build tool |
| Spring Boot | 3.3.4 | Parent POM |
| Spring Cloud | 2023.0.x | OpenFeign |
| Kotlin | 1.9.20 | desktop-client-javafx |

## Database

| Component | Version | Notes |
|-----------|---------|-------|
| PostgreSQL | 16 | docker-compose.yml |
| Flyway | 10.x-11.x | Migration tool |
| HikariCP | 5.1.x | Connection pool (managed) |

## Spring Dependencies

| Dependency | Version | Notes |
|------------|---------|-------|
| spring-boot-starter-web | 3.3.4 | Managed |
| spring-boot-starter-data-jpa | 3.3.4 | Managed |
| spring-boot-starter-security | 3.3.4 | Managed |
| spring-boot-starter-actuator | 3.3.4 | Managed |
| spring-cloud-starter-openfeign | 4.1.3 | Explicit |

## Security

| Component | Version | Notes |
|-----------|---------|-------|
| jjwt-api | 0.12.6 | JWT library |
| jjwt-impl | 0.12.6 | JWT implementation |
| jjwt-jackson | 0.12.6 | JWT JSON |

## Infrastructure

| Component | Version | Notes |
|-----------|---------|-------|
| Docker | 24+ | Container runtime |
| Docker Compose | 2.0+ | Orchestration |
| Eclipse Mosquitto | 2.x | MQTT broker |

## AI/Voice

| Component | Version | Notes |
|-----------|---------|-------|
| Vosk | 0.3.x | Speech recognition |
| vosk-model-small-ru | 0.22 | Russian model |
| h2oGPT | - | LLM backend |

## Version Inconsistencies (⚠️ Needs Review)

| Module | Issue |
|--------|-------|
| security-service | Flyway 11.0.0 (others use managed) |
| security-service | PostgreSQL driver 42.7.4 (explicit) |
| desktop-client-javafx | spring-boot 3.2.0 plugin (differs from parent 3.3.4) |

## Recommended Actions

1. Унифицировать версии Flyway через parent POM
2. Убрать explicit версии PostgreSQL driver
3. Синхронизировать spring-boot-maven-plugin версии

