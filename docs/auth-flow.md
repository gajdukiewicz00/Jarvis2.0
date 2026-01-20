# Jarvis 2.0 - Authentication Flow

## Содержание
- [Обзор](#обзор)
- [Регистрация](#регистрация)
- [Вход (Login)](#вход-login)
- [Обновление токена](#обновление-токена)
- [Получение информации о пользователе](#получение-информации-о-пользователе)
- [Коды ошибок](#коды-ошибок)
- [Проблемы и их решения](#проблемы-и-их-решения)

---

## Обзор

### Компоненты
- **security-service** (порт 8088) — генерация и управление JWT токенами
- **api-gateway** (порт 8080) — валидация JWT и маршрутизация

### JWT-структура
- **Access Token** — короткоживущий (1 час в prod, 24 часа в dev)
- **Refresh Token** — долгоживущий (7 дней)
- **Алгоритм**: HS256
- **Секрет**: настраивается через `JWT_SECRET` env variable

### Поток аутентификации

```
┌─────────────┐         ┌─────────────┐         ┌─────────────────┐
│   Client    │────────▶│ api-gateway │────────▶│ security-service│
│             │         │   (8080)    │         │     (8088)      │
└─────────────┘         └─────────────┘         └─────────────────┘
      │                        │                         │
      │ POST /auth/login       │                         │
      │───────────────────────▶│ Proxy to security       │
      │                        │─────────────────────────▶│
      │                        │                         │ Validate creds
      │                        │                         │ Generate JWT
      │                        │◀─────────────────────────│
      │◀───────────────────────│ {accessToken, refreshToken}
      │                        │                         │
      │ GET /api/v1/life/...   │                         │
      │ Authorization: (omitted)│                        │
      │───────────────────────▶│ Validate JWT locally    │
      │                        │ Add X-User-Id header    │
      │                        │────────────────────────▶│
      │                        │         life-tracker    │
```

---

## Регистрация

### Endpoint
```
POST /auth/register
```

### Request
```json
{
  "username": "newuser",
  "password": "securePassword123",
  "role": "USER"
}
```

### Success Response (201 Created)
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "username": "newuser",
  "role": "USER"
}
```

### Error Responses

**Username already exists (409 Conflict)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 409,
  "error": "USER_ALREADY_EXISTS",
  "message": "Username 'newuser' already exists",
  "service": "security-service",
  "path": "/auth/register"
}
```

**Validation error (400 Bad Request)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "username: must not be blank; password: must not be blank",
  "service": "security-service",
  "path": "/auth/register"
}
```

**Database unavailable (503 Service Unavailable)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 503,
  "error": "AUTH_SERVICE_UNAVAILABLE",
  "message": "Authentication temporarily unavailable due to database issues",
  "service": "security-service",
  "path": "/auth/register"
}
```

---

## Вход (Login)

### Endpoint
```
POST /auth/login
```

### Request
```json
{
  "username": "existinguser",
  "password": "correctPassword"
}
```

### Success Response (200 OK)
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "username": "existinguser",
  "role": "USER"
}
```

### Error Responses

**Invalid credentials (401 Unauthorized)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "INVALID_CREDENTIALS",
  "message": "Invalid username or password",
  "service": "security-service",
  "path": "/auth/login"
}
```

**Account disabled (401 Unauthorized)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "ACCOUNT_DISABLED",
  "message": "User account is disabled",
  "service": "security-service",
  "path": "/auth/login"
}
```

---

## Обновление токена

### Endpoint
```
POST /auth/refresh
```

### Request
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

### Success Response (200 OK)
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "username": "user",
  "role": "USER"
}
```

### Error Responses

**Invalid token (401 Unauthorized)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "INVALID_TOKEN",
  "message": "Invalid refresh token",
  "service": "security-service",
  "path": "/auth/refresh"
}
```

**Token expired (401 Unauthorized)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "TOKEN_EXPIRED",
  "message": "Refresh token has expired",
  "service": "security-service",
  "path": "/auth/refresh"
}
```

---

## Получение информации о пользователе

### Endpoint
```
GET /auth/me
```
Authorization header omitted in docs.

### Success Response (200 OK)
```json
{
  "id": 1,
  "username": "user",
  "role": "USER",
  "enabled": true
}
```

### Error Responses

**Missing token (401 Unauthorized)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "MISSING_TOKEN",
  "message": "Missing or invalid Authorization header",
  "service": "security-service",
  "path": "/auth/me"
}
```

**Expired token (401 Unauthorized)**
```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "TOKEN_EXPIRED",
  "message": "Token has expired",
  "service": "security-service",
  "path": "/auth/me"
}
```

---

## Коды ошибок

### Бизнес-ошибки (4xx)

| Код ошибки | HTTP Status | Описание |
|------------|-------------|----------|
| `INVALID_CREDENTIALS` | 401 | Неверное имя пользователя или пароль |
| `USER_ALREADY_EXISTS` | 409 | Пользователь уже существует |
| `ACCOUNT_DISABLED` | 401 | Аккаунт отключён |
| `TOKEN_EXPIRED` | 401 | Токен истёк |
| `INVALID_TOKEN` | 401 | Невалидный токен |
| `MISSING_TOKEN` | 401 | Отсутствует токен |
| `USER_NOT_FOUND` | 401 | Пользователь не найден |
| `VALIDATION_ERROR` | 400 | Ошибка валидации запроса |

### Технические ошибки (5xx)

| Код ошибки | HTTP Status | Описание |
|------------|-------------|----------|
| `AUTH_SERVICE_UNAVAILABLE` | 503 | БД недоступна |
| `DATABASE_ERROR` | 500 | Ошибка базы данных |
| `INTERNAL_ERROR` | 500 | Внутренняя ошибка |

---

## Проблемы и их решения

### Проблема: JWT expired в логах

**Симптом**: Много записей `JWT validation failed: JWT expired` в логах api-gateway

**Причина**: Клиент использует истёкший токен

**Решение на стороне клиента**:
1. Проверять `expiresIn` в ответе
2. Заранее обновлять токен (за 5 мин до истечения)
3. При получении 401 с `TOKEN_EXPIRED` — вызвать `/auth/refresh`

**Решение на стороне сервера**:
- Логировать как WARN/DEBUG, не ERROR
- Возвращать структурированный ответ с кодом `TOKEN_EXPIRED`

### Проблема: Database unavailable при логине

**Симптом**: 503 `AUTH_SERVICE_UNAVAILABLE`

**Возможные причины**:
1. PostgreSQL не запущен
2. Все соединения HikariCP заняты
3. Сетевая проблема между security-service и postgres

**Диагностика**:
```bash
# Проверить PostgreSQL
docker exec jarvis20_postgres pg_isready

# Проверить логи security-service
docker logs jarvis20_security-service | grep -i "hikari\|connection"

# Проверить соединения
docker exec jarvis20_postgres psql -U jarvis -c \
  "SELECT count(*) FROM pg_stat_activity WHERE datname='jarvis_security';"
```

### Проблема: Connection timeout при регистрации

**Симптом**: `RetryableException: Read timed out executing POST http://security-service:8088/auth/register`

**Причины**:
1. security-service перегружен
2. Долгое хеширование пароля
3. Проблемы с БД

**Решение**:
1. Увеличить `readTimeout` для auth-service в api-gateway:
   ```yaml
   spring.cloud.openfeign.client.config.auth-service.readTimeout: 30000
   ```
2. Проверить нагрузку на PostgreSQL

---

## Примеры использования

### cURL

```bash
# Регистрация
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","password":"password123","role":"USER"}'

# Логин
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","password":"password123"}'

# Сохранить токен
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","password":"password123"}' | jq -r '.accessToken')

# Использовать токен
curl http://localhost:8080/api/v1/life/finance/expenses \
  -H "Content-Type: application/json"
# Authorization header omitted in docs.

# Обновить токен
REFRESH=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","password":"password123"}' | jq -r '.refreshToken')

curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}"
```

### JavaScript/Fetch

```javascript
// Login
const loginResponse = await fetch('http://localhost:8080/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'user', password: 'pass' })
});

const { accessToken, refreshToken, expiresIn } = await loginResponse.json();

// Store tokens
localStorage.setItem('accessToken', accessToken);
localStorage.setItem('refreshToken', refreshToken);
localStorage.setItem('tokenExpiry', Date.now() + expiresIn * 1000);

// Use token
const dataResponse = await fetch('http://localhost:8080/api/v1/life/finance/expenses', {
  // Auth headers are injected by the gateway; keep tokens out of docs.
  headers: { 'Content-Type': 'application/json' }
});

// Refresh token (when expired)
if (Date.now() > localStorage.getItem('tokenExpiry') - 300000) { // 5 min before expiry
  const refreshResponse = await fetch('http://localhost:8080/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: localStorage.getItem('refreshToken') })
  });
  const newTokens = await refreshResponse.json();
  localStorage.setItem('accessToken', newTokens.accessToken);
  // ...
}
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*
