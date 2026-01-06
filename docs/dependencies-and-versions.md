# Jarvis 2.0 - Dependencies and Versions

## Содержание
- [Core Versions](#core-versions)
- [Spring Ecosystem](#spring-ecosystem)
- [Database](#database)
- [Security](#security)
- [Testing](#testing)
- [Совместимость](#совместимость)

---

## Core Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 | LTS, required |
| Maven | 3.8+ | Build tool |
| Docker | 24+ | Container runtime |
| Docker Compose | 2.0+ | Orchestration |

### Java 21 Features Used

- Virtual Threads (preview, optional)
- Record patterns
- Pattern matching for switch
- Sealed classes

---

## Spring Ecosystem

### Spring Boot

| Dependency | Version | Description |
|------------|---------|-------------|
| spring-boot-starter-parent | 3.3.4 | Parent POM |
| spring-boot-starter-web | 3.3.4 | Web MVC |
| spring-boot-starter-data-jpa | 3.3.4 | JPA/Hibernate |
| spring-boot-starter-security | 3.3.4 | Security |
| spring-boot-starter-actuator | 3.3.4 | Monitoring |
| spring-boot-starter-test | 3.3.4 | Testing |

### Spring Cloud

| Dependency | Version | Description |
|------------|---------|-------------|
| spring-cloud-dependencies | 2023.0.3 | BOM |
| spring-cloud-starter-openfeign | 4.1.3 | HTTP clients |

### Spring Integration (Smart Home)

| Dependency | Version | Description |
|------------|---------|-------------|
| spring-integration-mqtt | 6.3.x | MQTT support |

---

## Database

### PostgreSQL

| Component | Version | Description |
|-----------|---------|-------------|
| PostgreSQL Server | 16 | Database |
| postgresql (JDBC) | 42.7.x | JDBC driver (managed) |
| flyway-core | 10.x | Migrations (managed) |
| flyway-database-postgresql | 10.x | PostgreSQL support |

### HikariCP

| Setting | Value | Description |
|---------|-------|-------------|
| Version | 5.1.x | Connection pool (managed) |
| max-pool-size | 10 | Per service |
| max-lifetime | 20 min | Refresh before PG timeout |

---

## Security

### JWT

| Dependency | Version | Description |
|------------|---------|-------------|
| jjwt-api | 0.12.6 | JWT API |
| jjwt-impl | 0.12.6 | JWT implementation |
| jjwt-jackson | 0.12.6 | JSON serialization |

### Spring Security

- Version: 6.3.x (managed by Spring Boot)
- Stateless session management
- Custom JWT filter

---

## Testing

| Dependency | Version | Description |
|------------|---------|-------------|
| JUnit 5 | 5.10.x | Test framework (managed) |
| Mockito | 5.x | Mocking (managed) |
| Testcontainers | 1.19.3 | Docker-based tests |
| Spring Security Test | 6.3.x | Security testing |

---

## Other Dependencies

### Resilience

| Dependency | Version | Description |
|------------|---------|-------------|
| resilience4j-spring-boot3 | 2.1.0 | Circuit breaker |

### Utilities

| Dependency | Version | Description |
|------------|---------|-------------|
| lombok | 1.18.x | Boilerplate reduction |
| guava | 32.1.3-jre | Rate limiting |

### MQTT (Smart Home)

| Dependency | Version | Description |
|------------|---------|-------------|
| eclipse-paho-client-mqttv3 | 1.2.x | MQTT client |
| Eclipse Mosquitto | 2.x | MQTT broker (Docker) |

### Voice (Voice Gateway)

| Component | Version | Description |
|-----------|---------|-------------|
| Vosk | 0.3.x | Speech recognition |
| vosk-model-small-ru | 0.22 | Russian model |

### LLM

| Component | Version | Description |
|-----------|---------|-------------|
| h2oGPT | - | LLM backend |
| llama2-7b | - | Base model |
| FastAPI | 0.100+ | Python server |
| transformers | 4.35+ | Hugging Face |

---

## Совместимость

### Spring Boot 3.3.x Requirements

- Java 17+ (мы используем 21)
- Jakarta EE 10 (jakarta.* packages)
- Spring Framework 6.1.x

### Spring Cloud 2023.0.x Requirements

- Spring Boot 3.2.x - 3.3.x ✅
- Java 17+ ✅

### PostgreSQL 16 Compatibility

- JDBC driver 42.6+ ✅
- Flyway 10+ ✅
- HikariCP 5+ ✅

### Upgrade Path

| Current | Target | Notes |
|---------|--------|-------|
| Spring Boot 3.3.4 | 3.4.x | Minor upgrade, test thoroughly |
| Java 21 | 22/23 | Wait for LTS (Java 25) |
| PostgreSQL 16 | 17 | Test migrations first |

---

## Version Matrix

### Supported Combinations

| Java | Spring Boot | Spring Cloud | PostgreSQL |
|------|-------------|--------------|------------|
| 21 | 3.3.4 | 2023.0.3 | 16 |
| 21 | 3.2.x | 2023.0.x | 15-16 |
| 17 | 3.2.x | 2023.0.x | 14-16 |

### Not Supported

- Java < 17 (Spring Boot 3.x requirement)
- Spring Boot 2.x (different package names)
- PostgreSQL < 12 (missing features)

---

## Updating Dependencies

### Check for Updates

```bash
# Maven dependency updates
mvn versions:display-dependency-updates

# Plugin updates
mvn versions:display-plugin-updates
```

### Controlled Update Process

1. Update version in pom.xml
2. Run `mvn clean package`
3. Run `mvn test`
4. Test in Docker: `docker compose up -d --build`
5. Run smoke tests: `make smoke-test`
6. Review logs for warnings/deprecations

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

