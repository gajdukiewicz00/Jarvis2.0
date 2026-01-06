# Jarvis 2.0 - API Error Handling

## Содержание
- [Формат ошибок](#формат-ошибок)
- [Коды ошибок](#коды-ошибок)
- [Upstream ошибки](#upstream-ошибки)
- [Примеры ответов](#примеры-ответов)
- [Обработка на клиенте](#обработка-на-клиенте)

---

## Формат ошибок

Все ошибки API возвращаются в едином JSON-формате:

```json
{
  "timestamp": "2025-12-02T10:30:00.123456",
  "status": 503,
  "error": "UPSTREAM_TIMEOUT",
  "message": "Read timed out executing GET life-tracker/api/v1/life/finance/expenses",
  "upstreamService": "life-tracker",
  "upstreamPath": "/api/v1/life/finance/expenses",
  "service": "api-gateway",
  "path": "/api/v1/analytics/overview"
}
```

### Поля

| Поле | Тип | Описание |
|------|-----|----------|
| `timestamp` | string | ISO-8601 время ошибки |
| `status` | number | HTTP статус код |
| `error` | string | Код ошибки (UPPER_SNAKE_CASE) |
| `message` | string | Человекочитаемое описание |
| `service` | string | Сервис, где произошла ошибка |
| `path` | string | Путь запроса |
| `upstreamService` | string? | (опционально) Upstream сервис |
| `upstreamStatus` | number? | (опционально) HTTP статус upstream |
| `upstreamPath` | string? | (опционально) Путь upstream запроса |
| `upstreamMessage` | string? | (опционально) Сообщение от upstream |
| `supportedMethods` | array? | (опционально) Для 405 ошибок |

---

## Коды ошибок

### Client Errors (4xx)

| Код ошибки | HTTP | Описание |
|------------|------|----------|
| `VALIDATION_ERROR` | 400 | Ошибка валидации запроса |
| `MISSING_PARAMETER` | 400 | Отсутствует обязательный параметр |
| `INVALID_ARGUMENT` | 400 | Невалидный аргумент |
| `MISSING_TOKEN` | 401 | Отсутствует JWT токен |
| `TOKEN_EXPIRED` | 401 | JWT токен истёк |
| `INVALID_SIGNATURE` | 401 | Неверная подпись JWT |
| `INVALID_CREDENTIALS` | 401 | Неверные логин/пароль |
| `NOT_FOUND` | 404 | Ресурс не найден |
| `METHOD_NOT_ALLOWED` | 405 | Метод не поддерживается |
| `USER_ALREADY_EXISTS` | 409 | Пользователь уже существует |
| `PAYLOAD_TOO_LARGE` | 413 | Файл слишком большой |

### Server Errors (5xx)

| Код ошибки | HTTP | Описание |
|------------|------|----------|
| `INTERNAL_ERROR` | 500 | Внутренняя ошибка сервера |
| `DATABASE_ERROR` | 500 | Ошибка базы данных |
| `DATABASE_UNAVAILABLE` | 503 | БД недоступна |
| `AUTH_SERVICE_UNAVAILABLE` | 503 | Auth сервис недоступен |

### Upstream Errors (Feign)

| Код ошибки | HTTP | Описание |
|------------|------|----------|
| `UPSTREAM_TIMEOUT` | 503 | Таймаут запроса к upstream |
| `UPSTREAM_CONNECTION_REFUSED` | 503 | Upstream отклонил соединение |
| `UPSTREAM_HOST_NOT_FOUND` | 503 | DNS не может найти upstream |
| `UPSTREAM_UNAVAILABLE` | 503 | Upstream недоступен (общее) |
| `UPSTREAM_ERROR` | 502 | Upstream вернул 5xx |
| `UPSTREAM_CLIENT_ERROR` | 4xx | Upstream вернул 4xx (pass-through) |

---

## Upstream ошибки

### Когда возникают

Upstream ошибки возникают когда api-gateway вызывает downstream сервисы:
- life-tracker
- analytics-service
- security-service
- pc-control
- smart-home-service
- и другие

### Типы upstream ошибок

#### 1. Timeout (503)
```json
{
  "status": 503,
  "error": "UPSTREAM_TIMEOUT",
  "message": "Read timed out executing GET life-tracker/api/v1/life/finance/expenses",
  "upstreamService": "life-tracker",
  "upstreamPath": "/api/v1/life/finance/expenses"
}
```

#### 2. Connection Refused (503)
```json
{
  "status": 503,
  "error": "UPSTREAM_CONNECTION_REFUSED",
  "message": "Connection refused to security-service",
  "upstreamService": "security-service"
}
```

#### 3. Host Not Found (503)
```json
{
  "status": 503,
  "error": "UPSTREAM_HOST_NOT_FOUND",
  "message": "Host not found: life-tracker",
  "upstreamService": "life-tracker"
}
```

#### 4. Server Error (502)
```json
{
  "status": 502,
  "error": "UPSTREAM_ERROR",
  "message": "Error from upstream service",
  "upstreamService": "life-tracker",
  "upstreamStatus": 500,
  "upstreamPath": "/api/v1/life/finance/expenses",
  "upstreamMessage": "{\"error\":\"DATABASE_ERROR\",\"message\":\"Connection timeout\"}"
}
```

#### 5. Client Error (pass-through)
```json
{
  "status": 404,
  "error": "UPSTREAM_CLIENT_ERROR",
  "message": "Error from upstream service",
  "upstreamService": "life-tracker",
  "upstreamStatus": 404,
  "upstreamPath": "/api/v1/life/finance/expenses/999"
}
```

---

## Примеры ответов

### Успешная регистрация
```http
POST /auth/register HTTP/1.1
Content-Type: application/json

{
  "username": "newuser",
  "password": "password123",
  "role": "USER"
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "username": "newuser",
  "role": "USER"
}
```

### Ошибка валидации (400)
```http
POST /auth/register HTTP/1.1
Content-Type: application/json

{
  "username": "",
  "password": "123"
}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "timestamp": "2025-12-02T10:30:00",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "username: must not be blank; password: must be at least 6 characters",
  "service": "security-service",
  "path": "/auth/register"
}
```

### Пользователь уже существует (409)
```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{
  "timestamp": "2025-12-02T10:30:00",
  "status": 409,
  "error": "USER_ALREADY_EXISTS",
  "message": "Username 'existinguser' already exists",
  "service": "security-service",
  "path": "/auth/register"
}
```

### Токен истёк (401)
```http
GET /protected/resource HTTP/1.1
Authorization: Bearer expired.jwt.token
```

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "timestamp": "2025-12-02T10:30:00",
  "status": 401,
  "error": "TOKEN_EXPIRED",
  "message": "JWT token has expired",
  "service": "api-gateway"
}
```

### Сервис недоступен (503)
```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "timestamp": "2025-12-02T10:30:00",
  "status": 503,
  "error": "UPSTREAM_TIMEOUT",
  "message": "Read timed out executing GET life-tracker/api/v1/life/finance/expenses",
  "upstreamService": "life-tracker",
  "upstreamPath": "/api/v1/life/finance/expenses",
  "service": "api-gateway",
  "path": "/api/v1/analytics/overview"
}
```

### Метод не поддерживается (405)
```http
GET /auth/register HTTP/1.1
```

```http
HTTP/1.1 405 Method Not Allowed
Content-Type: application/json

{
  "timestamp": "2025-12-02T10:30:00",
  "status": 405,
  "error": "METHOD_NOT_ALLOWED",
  "message": "Request method 'GET' is not supported",
  "supportedMethods": ["POST"],
  "service": "api-gateway",
  "path": "/auth/register"
}
```

---

## Обработка на клиенте

### JavaScript/TypeScript

```typescript
interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  service: string;
  path: string;
  upstreamService?: string;
  upstreamStatus?: number;
}

async function apiRequest<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${getToken()}`,
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const error: ApiError = await response.json();
    
    switch (error.error) {
      case 'TOKEN_EXPIRED':
        // Refresh token and retry
        await refreshToken();
        return apiRequest(url, options);
        
      case 'UPSTREAM_TIMEOUT':
      case 'UPSTREAM_UNAVAILABLE':
        // Show "service temporarily unavailable" message
        showNotification('Service temporarily unavailable. Please try again.');
        break;
        
      case 'VALIDATION_ERROR':
        // Show validation errors to user
        showValidationErrors(error.message);
        break;
        
      default:
        // Generic error handling
        showNotification(error.message || 'An error occurred');
    }
    
    throw error;
  }

  return response.json();
}
```

### Kotlin (Android)

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val error: ApiError) : ApiResult<Nothing>()
}

data class ApiError(
    val timestamp: String,
    val status: Int,
    val error: String,
    val message: String,
    val service: String,
    val path: String,
    val upstreamService: String? = null
)

suspend fun <T> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = call()
        if (response.isSuccessful) {
            ApiResult.Success(response.body()!!)
        } else {
            val error = parseError(response.errorBody())
            when (error.error) {
                "TOKEN_EXPIRED" -> {
                    refreshToken()
                    safeApiCall(call)
                }
                else -> ApiResult.Error(error)
            }
        }
    } catch (e: Exception) {
        ApiResult.Error(ApiError(
            timestamp = Instant.now().toString(),
            status = 0,
            error = "NETWORK_ERROR",
            message = e.message ?: "Network error",
            service = "client",
            path = ""
        ))
    }
}
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

