# Jarvis 2.0 - JWT Security

## Содержание
- [Обзор](#обзор)
- [Конфигурация JWT](#конфигурация-jwt)
- [Формат токенов](#формат-токенов)
- [Обработка ошибок JWT](#обработка-ошибок-jwt)
- [Whitelisted endpoints](#whitelisted-endpoints)
- [Безопасность](#безопасность)

---

## Обзор

Jarvis 2.0 использует JWT (JSON Web Tokens) для stateless аутентификации. JWT валидируется локально в api-gateway без обращения к security-service.

### Компоненты
- **security-service** — генерация токенов
- **api-gateway (JwtAuthFilter)** — единый JWT-фильтр: валидация токенов, SecurityContext, X-User-* headers, 401 responses
- **Downstream сервисы** — получают user context через X-User-* headers

### Алгоритм
- **Подпись**: HS256 (HMAC-SHA256)
- **Библиотека**: jjwt 0.12.6

---

## Конфигурация JWT

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | (set locally) | Секретный ключ (мин. 256 бит) |
| `jarvis.jwt.enabled` | true | Включить JWT валидацию |
| `jarvis.jwt.enforce-issuer` | false | Проверять iss claim |

### application.yaml (api-gateway)

```yaml
jarvis:
  jwt:
    enabled: true
    secret: ${JWT_SECRET}
    issuer: jarvis
    enforce-issuer: false   # opt-in issuer validation
```

### application.yml (security-service)

```yaml
jarvis:
  jwt:
    secret: ${JWT_SECRET}
    access-expiration: 3600000    # 1 hour (prod)
    refresh-expiration: 604800000 # 7 days
    issuer: jarvis
```

---

## Формат токенов

### Access Token

**Header:**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload:**
```json
{
  "sub": "1",              // User ID
  "username": "testuser",
  "role": "USER",
  "type": "access",
  "iss": "jarvis",
  "iat": 1733140800,       // Issued at
  "exp": 1733144400        // Expiration
}
```

### Refresh Token

**Payload:**
```json
{
  "sub": "1",              // User ID
  "type": "refresh",
  "iss": "jarvis",
  "iat": 1733140800,
  "exp": 1733745600        // 7 days later
}
```

### Пример токена
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.
eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJ0ZXN0dXNlciIsInJvbGUiOiJVU0VSIiwidHlwZSI6ImFjY2VzcyIsImlzcyI6ImphcnZpcyIsImlhdCI6MTczMzE0MDgwMCwiZXhwIjoxNzMzMTQ0NDAwfQ.
<signature>
```

---

## Обработка ошибок JWT

### Типы ошибок

| Error Code | HTTP Status | Причина | Log Level |
|------------|-------------|---------|-----------|
| `TOKEN_EXPIRED` | 401 | Токен истёк | INFO |
| `INVALID_SIGNATURE` | 401 | Неверная подпись | WARN |
| `MALFORMED_TOKEN` | 401 | Некорректный формат | WARN |
| `MISSING_TOKEN` | 401 | Нет Authorization header | DEBUG |
| `EMPTY_TOKEN` | 401 | Пустой токен | DEBUG |
| `INVALID_TOKEN` | 401 | Другие проблемы формата | WARN |
| `JWT_ERROR` | 500 | Внутренняя ошибка | ERROR |

### Формат ответа об ошибке

```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "TOKEN_EXPIRED",
  "message": "JWT token has expired",
  "service": "api-gateway"
}
```

### Примеры ответов

**Истёкший токен:**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "TOKEN_EXPIRED",
  "message": "JWT token has expired",
  "service": "api-gateway"
}
```

**Неверная подпись:**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "INVALID_SIGNATURE",
  "message": "Invalid token signature",
  "service": "api-gateway"
}
```

**Отсутствует токен:**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "MISSING_TOKEN",
  "message": "Missing or invalid Authorization header",
  "service": "api-gateway"
}
```

---

## Whitelisted endpoints

Следующие endpoints не требуют JWT:

```java
private static final List<String> WHITELIST = List.of(
    "/auth/login",
    "/auth/register",
    "/auth/refresh",
    "/auth/health",
    "/api/security/auth/login",
    "/api/security/auth/register",
    "/api/v1",          // Development: все /api/v1/** открыты
    "/actuator",        // Health checks
    "/health",
    "/favicon.ico"
);
```

### Для development-режима

В текущей конфигурации все `/api/v1/**` endpoints открыты для удобства разработки. Для production:

```java
// Убрать "/api/v1" из whitelist
private static final List<String> WHITELIST = List.of(
    "/auth/login",
    "/auth/register", 
    "/auth/refresh",
    "/actuator/health"
);
```

---

## Безопасность

### Рекомендации для Production

1. **Сменить JWT_SECRET**
   ```bash
   # Генерация безопасного ключа (256 бит)
   openssl rand -base64 32
   ```

2. **Уменьшить TTL токенов**
   ```yaml
   jarvis:
     jwt:
       access-expiration: 900000   # 15 minutes
       refresh-expiration: 86400000 # 1 day
   ```

3. **Ограничить whitelist**
   - Убрать `/api/v1` из whitelist
   - Оставить только auth и health endpoints

4. **Использовать HTTPS**
   - JWT передаётся в Authorization header
   - Без HTTPS токен может быть перехвачен

5. **Добавить rate limiting**
   ```yaml
   rate-limit:
     enabled: true
     max-requests-per-minute: 60
   ```

### Передача user context

api-gateway добавляет headers для downstream сервисов:

| Header | Value | Description |
|--------|-------|-------------|
| `X-User-Id` | "1" | User ID из JWT sub |
| `X-Username` | "testuser" | Username из JWT |
| `X-User-Roles` | "USER" | Role из JWT |

Downstream сервисы доверяют этим headers (internal network).

### Валидация на уровне сервиса

Если сервису нужна дополнительная авторизация:

```java
@GetMapping("/admin/users")
public ResponseEntity<?> getUsers(@RequestHeader("X-User-Roles") String roles) {
    if (!roles.contains("ADMIN")) {
        return ResponseEntity.status(403).body(Map.of("error", "FORBIDDEN"));
    }
    // ...
}
```

---

## Troubleshooting

### "JWT signature does not match"

**Причина**: Разный `JWT_SECRET` в api-gateway и security-service

**Решение**:
```bash
# Проверить переменные
docker exec jarvis20_api-gateway env | grep JWT
docker exec jarvis20_security-service env | grep JWT
```

### Много "JWT expired" в логах

**Причина**: Клиент использует старые токены

**Решение на клиенте**:
1. Проверять `expiresIn` из ответа
2. Обновлять токен заранее (за 5 мин до истечения)
3. При 401 TOKEN_EXPIRED → вызвать `/auth/refresh`

**Решение на сервере** (уже реализовано):
- Логировать как INFO, не ERROR
- Возвращать структурированный ответ

### Токен работает в curl, но не в браузере

**Причина**: CORS блокирует Authorization header

**Решение** (application.yaml):
```yaml
cors:
  allowed-headers:
    - Authorization
    - Content-Type
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2026-02-28 — Phase 3: unified JwtAuthFilter, jarvis.jwt.* properties, enforce-issuer flag*
