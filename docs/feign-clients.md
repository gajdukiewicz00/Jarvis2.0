# Jarvis 2.0 - Feign Clients

## Содержание
- [Обзор](#обзор)
- [Конфигурация](#конфигурация)
- [Список клиентов](#список-клиентов)
- [Таймауты](#таймауты)
- [Обработка ошибок](#обработка-ошибок)

---

## Обзор

Jarvis 2.0 использует Spring Cloud OpenFeign для синхронной коммуникации между микросервисами. Feign предоставляет декларативный HTTP-клиент, упрощающий создание REST-запросов.

### Зависимости

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2023.0.3</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## Конфигурация

### Включение Feign

```java
@SpringBootApplication
@EnableFeignClients
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

### Глобальная конфигурация (application.yaml)

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 5000    # 5 seconds
            readTimeout: 10000      # 10 seconds
            loggerLevel: BASIC
```

---

## Список клиентов

### api-gateway Feign Clients

#### AuthClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/AuthClient.java`

```java
@FeignClient(name = "auth-service", url = "${services.security.url:http://localhost:8088}")
public interface AuthClient {
    
    @PostMapping("/auth/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request);
    
    @PostMapping("/auth/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request);
    
    @PostMapping("/auth/refresh")
    ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, Object> request);
}
```

| Метод | HTTP | Path | Описание |
|-------|------|------|----------|
| register | POST | /auth/register | Регистрация пользователя |
| login | POST | /auth/login | Логин и получение JWT |
| refresh | POST | /auth/refresh | Обновление access token |

#### LifeTrackerClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/LifeTrackerClient.java`

```java
@FeignClient(name = "life-tracker", url = "${services.life-tracker.url:http://localhost:8085}")
public interface LifeTrackerClient {
    
    @GetMapping("/api/v1/life/finance/expenses")
    List<ExpenseDTO> getExpenses();
    
    @PostMapping("/api/v1/life/finance/expense")
    ExpenseDTO addExpense(@RequestBody Map<String, Object> expense);
    
    @GetMapping("/api/v1/life/time/records")
    List<TimeRecordDTO> getTimeRecords();
    
    @PostMapping("/api/v1/life/time/record")
    TimeRecordDTO addTimeRecord(@RequestBody Map<String, Object> record);
    
    @GetMapping("/api/v1/life/calendar/events")
    List<CalendarEventDTO> getCalendarEvents();
    
    @PostMapping("/api/v1/life/calendar/event")
    CalendarEventDTO addCalendarEvent(@RequestBody Map<String, Object> event);
}
```

| Метод | HTTP | Path | Описание |
|-------|------|------|----------|
| getExpenses | GET | /api/v1/life/finance/expenses | Список расходов |
| addExpense | POST | /api/v1/life/finance/expense | Добавить расход |
| getTimeRecords | GET | /api/v1/life/time/records | Записи времени |
| addTimeRecord | POST | /api/v1/life/time/record | Добавить запись |
| getCalendarEvents | GET | /api/v1/life/calendar/events | События календаря |
| addCalendarEvent | POST | /api/v1/life/calendar/event | Добавить событие |

#### AnalyticsClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/AnalyticsClient.java`

```java
@FeignClient(name = "analytics-service", url = "${services.analytics.url:http://localhost:8087}")
public interface AnalyticsClient {
    
    @GetMapping("/api/v1/analytics/overview")
    AnalyticsOverviewDTO getOverview();
    
    @GetMapping("/api/v1/analytics/expenses/by-month")
    List<ExpenseSummaryDTO> getExpensesByMonth(
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to);
    
    @GetMapping("/api/v1/analytics/expenses/by-category")
    List<ExpenseSummaryDTO> getExpensesByCategory(
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to);
}
```

#### PcControlClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/PcControlClient.java`

```java
@FeignClient(name = "pc-control", url = "${services.pc-control.url:http://localhost:8084}")
public interface PcControlClient {
    
    @PostMapping("/api/v1/pc/action")
    ResponseEntity<String> executeAction(@RequestBody Map<String, Object> request);
}
```

| Метод | HTTP | Path | Описание |
|-------|------|------|----------|
| executeAction | POST | /api/v1/pc/action | Выполнить действие (volume, hotkey, etc.) |

#### SmartHomeClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/SmartHomeClient.java`

```java
@FeignClient(name = "smart-home", url = "${services.smart-home.url:http://localhost:8086}")
public interface SmartHomeClient {
    
    @PostMapping("/api/v1/smarthome/devices/{deviceId}/action")
    String executeAction(@PathVariable String deviceId, @RequestBody Map<String, Object> request);
}
```

#### OrchestratorClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/OrchestratorClient.java`

```java
@FeignClient(name = "orchestrator", url = "${services.orchestrator.url:http://localhost:8083}")
public interface OrchestratorClient {
    
    @PostMapping("/api/v1/orchestrator/execute")
    ResponseEntity<String> execute(@RequestBody Map<String, String> request);
}
```

#### NlpServiceClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/NlpServiceClient.java`

```java
@FeignClient(name = "nlp-service", url = "${services.nlp-service.url:http://localhost:8082}")
public interface NlpServiceClient {
    
    @PostMapping("/api/v1/nlp/analyze")
    ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request);
}
```

#### VoiceGatewayClient
**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/VoiceGatewayClient.java`

```java
@FeignClient(name = "voice-gateway", url = "${services.voice-gateway.url:http://localhost:8081}")
public interface VoiceGatewayClient {
    
    @PostMapping("/api/v1/voice/stt")
    ResponseEntity<Map<String, Object>> speechToText(@RequestBody byte[] audio);
    
    @PostMapping("/api/v1/voice/tts")
    ResponseEntity<byte[]> textToSpeech(@RequestBody Map<String, String> request);
}
```

---

### analytics-service Feign Clients

#### LifeTrackerClient
**Файл**: `apps/analytics-service/src/main/java/org/jarvis/analytics/client/LifeTrackerClient.java`

```java
@FeignClient(name = "life-tracker", url = "${jarvis.life-tracker.url:http://life-tracker:8085}")
public interface LifeTrackerClient {
    
    @GetMapping("/api/v1/life/finance/expenses")
    List<ExpenseDTO> getExpenses();
    
    @GetMapping("/api/v1/life/time/records")
    List<TimeRecordDTO> getTimeRecords();
    
    @GetMapping("/api/v1/life/calendar/events")
    List<CalendarEventDTO> getCalendarEvents();
}
```

**Конфигурация** (`application.yml`):
```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 5000
            readTimeout: 30000    # 30 seconds for data fetching
          life-tracker:
            connectTimeout: 5000
            readTimeout: 30000

jarvis:
  life-tracker:
    url: ${LIFE_TRACKER_URL:http://localhost:8085}
```

---

### orchestrator Feign Clients

#### NlpClient
**Файл**: `apps/orchestrator/src/main/java/org/jarvis/orchestrator/client/NlpClient.java`

```java
@FeignClient(name = "nlp-service", url = "${services.nlp-service.url:http://nlp-service:8082}")
public interface NlpClient {
    
    @PostMapping("/api/v1/nlp/analyze")
    ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request);
}
```

#### PcControlClient
**Файл**: `apps/orchestrator/src/main/java/org/jarvis/orchestrator/client/PcControlClient.java`

```java
@FeignClient(name = "pc-control", url = "${services.pc-control.url:http://pc-control:8084}")
public interface PcControlClient {
    
    @PostMapping("/api/v1/pc/action")
    ResponseEntity<String> executeAction(@RequestBody Map<String, Object> request);
}
```

---

### planner-service Feign Clients

```yaml
# application-docker.yml
services:
  life-tracker:
    url: http://life-tracker:8085
  analytics:
    url: http://analytics-service:8087
  user-profile:
    url: http://user-profile:8089
  llm-service:
    url: http://llm-service:8091
```

---

### voice-gateway REST Client

**Файл**: `apps/voice-gateway/src/main/java/org/jarvis/voicegateway/client/impl/RestOrchestratorClient.java`

Использует RestTemplate вместо Feign:

```java
@Service
public class RestOrchestratorClient implements OrchestratorClient {
    
    private final RestTemplate restTemplate;
    private final String orchestratorUrl;
    
    public RestOrchestratorClient(
            RestTemplateBuilder builder,
            @Value("${services.orchestrator.url:http://orchestrator:8083}") String url) {
        this.restTemplate = builder.build();
        this.orchestratorUrl = url;
    }
    
    @Override
    public String processCommand(String text) {
        Map<String, String> request = Map.of("text", text);
        ResponseEntity<String> response = restTemplate.postForEntity(
            orchestratorUrl + "/api/v1/orchestrator/execute",
            request,
            String.class
        );
        return response.getBody();
    }
}
```

---

## Таймауты

### Рекомендуемые значения

| Клиент | connectTimeout | readTimeout | Обоснование |
|--------|----------------|-------------|-------------|
| auth-service | 5000 | 10000 | Быстрые операции |
| life-tracker | 5000 | 30000 | Может возвращать много данных |
| analytics-service | 5000 | 30000 | Аналитика может быть медленной |
| pc-control | 5000 | 10000 | Быстрые системные команды |
| smart-home | 5000 | 15000 | IoT устройства могут отвечать медленно |
| nlp-service | 5000 | 10000 | NLU обычно быстрая |
| llm-service | 5000 | 60000 | LLM может генерировать долго |
| voice-gateway | 5000 | 30000 | Обработка аудио |

### Настройка для конкретного клиента

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 5000
            readTimeout: 10000
          life-tracker:
            connectTimeout: 5000
            readTimeout: 30000
          llm-service:
            connectTimeout: 5000
            readTimeout: 60000
```

---

## Обработка ошибок

### GlobalExceptionHandler (api-gateway)

**Файл**: `apps/api-gateway/src/main/java/org/jarvis/apigateway/config/GlobalExceptionHandler.java`

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(feign.FeignException.class)
    public ResponseEntity<Map<String, Object>> handleFeignException(
            FeignException ex, WebRequest request) {
        
        String serviceName = extractServiceName(ex.getMessage());
        HttpStatus status = HttpStatus.resolve(ex.status());
        
        if (status == null) {
            // Timeout, DNS error, connection refused
            status = HttpStatus.SERVICE_UNAVAILABLE;
            log.warn("Service unavailable [{}]: {}", serviceName, ex.getMessage());
            
            return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 503,
                "error", "SERVICE_UNAVAILABLE",
                "message", "Service temporarily unavailable",
                "serviceUnavailable", serviceName
            ));
        }
        
        // 4xx/5xx from downstream
        log.warn("Downstream error [{}]: {} - {}", serviceName, ex.status(), ex.getMessage());
        return buildErrorResponse(status, ex.contentUTF8(), request);
    }

    @ExceptionHandler(feign.RetryableException.class)
    public ResponseEntity<Map<String, Object>> handleRetryableException(
            RetryableException ex, WebRequest request) {
        
        String serviceName = extractServiceName(ex.getMessage());
        log.warn("Service connection failed [{}]: {}", serviceName, ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "timestamp", LocalDateTime.now().toString(),
            "status", 503,
            "error", "SERVICE_UNAVAILABLE",
            "message", "Service temporarily unavailable",
            "serviceUnavailable", serviceName
        ));
    }
}
```

### Типичные ошибки

| Исключение | HTTP Status | Причина |
|------------|-------------|---------|
| `FeignException$ServiceUnavailable` | 503 | Сервис недоступен |
| `FeignException$InternalServerError` | 500 | Ошибка в downstream сервисе |
| `FeignException$NotFound` | 404 | Ресурс не найден |
| `RetryableException` | 503 | Timeout или connection refused |
| `UnknownHostException` (в FeignException) | 503 | DNS не может разрешить hostname |

### Пример ответа при ошибке

```json
{
  "timestamp": "2025-12-02T10:30:00",
  "status": 503,
  "error": "SERVICE_UNAVAILABLE",
  "message": "Service temporarily unavailable",
  "serviceUnavailable": "life-tracker",
  "path": "/api/v1/analytics/overview"
}
```

---

## URL-конфигурация по профилям

### Local Development (без docker)

```yaml
# application.yaml
services:
  voice-gateway:
    url: http://localhost:8081
  nlp-service:
    url: http://localhost:8082
  orchestrator:
    url: http://localhost:8083
  pc-control:
    url: http://localhost:8084
  life-tracker:
    url: http://localhost:8085
  smart-home:
    url: http://localhost:8086
  analytics:
    url: http://localhost:8087
  security:
    url: http://localhost:8088
```

### Docker Profile

```yaml
# application-docker.yml
services:
  voice-gateway:
    url: http://voice-gateway:8081
  nlp-service:
    url: http://nlp-service:8082
  orchestrator:
    url: http://orchestrator:8083
  pc-control:
    url: http://pc-control:8084
  life-tracker:
    url: http://life-tracker:8085
  smart-home:
    url: http://smart-home-service:8086
  analytics:
    url: http://analytics-service:8087
  security:
    url: http://security-service:8088
  llm:
    url: http://llm-service:8091
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

