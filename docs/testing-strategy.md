# Jarvis 2.0 - Testing Strategy

## Содержание
- [Обзор](#обзор)
- [Типы тестов](#типы-тестов)
- [Запуск тестов](#запуск-тестов)
- [Testcontainers](#testcontainers)
- [Мокирование](#мокирование)
- [Покрытие кода](#покрытие-кода)

---

## Обзор

Jarvis 2.0 использует многоуровневую стратегию тестирования:

| Уровень | Инструменты | Назначение |
|---------|-------------|------------|
| Unit | JUnit 5, Mockito | Изолированные тесты компонентов |
| Integration | Testcontainers, MockMvc | Тесты с реальной БД |
| Component | Spring Boot Test | Тесты отдельных сервисов |
| E2E | Docker Compose | Полный smoke-тест системы |

---

## Типы тестов

### Unit Tests

Изолированные тесты отдельных классов:

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private PasswordEncoder passwordEncoder;
    
    @InjectMocks
    private AuthService authService;
    
    @Test
    void login_withValidCredentials_returnsToken() {
        // Given
        when(userRepository.findByUsername("user"))
            .thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(any(), any()))
            .thenReturn(true);
        
        // When
        AuthResponse response = authService.login(loginRequest);
        
        // Then
        assertThat(response.accessToken()).isNotNull();
    }
}
```

### Integration Tests (Testcontainers)

Тесты с реальной PostgreSQL:

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class LifeTrackerIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void getExpenses_returnsData() throws Exception {
        mockMvc.perform(get("/api/v1/life/finance/expenses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(0))));
    }
}
```

### Component Tests (Mocked Dependencies)

Тесты сервиса с замоканными внешними зависимостями:

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsControllerTest {
    
    @MockBean
    private LifeTrackerClient lifeTrackerClient;
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void getOverview_withTimeout_returnsPartialData() throws Exception {
        // Given
        when(lifeTrackerClient.getExpenses())
            .thenThrow(new RetryableException(...));
        when(lifeTrackerClient.getTimeRecords())
            .thenReturn(List.of(timeRecord));
        
        // When/Then
        mockMvc.perform(get("/api/v1/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expensesError").exists())
                .andExpect(jsonPath("$.timeRecordCount").value(1));
    }
}
```

---

## Запуск тестов

### Все тесты

```bash
# Все модули
mvn test

# С отчётом о покрытии
mvn test jacoco:report
```

### Конкретный модуль

```bash
# life-tracker
mvn test -pl apps/life-tracker

# analytics-service
mvn test -pl apps/analytics-service

# security-service
mvn test -pl apps/security-service
```

### Конкретный тест

```bash
# Один класс
mvn test -pl apps/life-tracker -Dtest=LifeTrackerIntegrationTest

# Один метод
mvn test -pl apps/life-tracker -Dtest=LifeTrackerIntegrationTest#getExpenses_returnsList
```

### IDE (IntelliJ IDEA)

1. Right-click на тест-класс → Run
2. Или используй Ctrl+Shift+F10

---

## Testcontainers

### Зависимости

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>
```

### Конфигурация

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("jarvis_test")
        .withUsername("jarvis")
        .withPassword("jarvis123");

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    registry.add("spring.flyway.enabled", () -> "false");
}
```

### Профиль test

```yaml
# application-test.yml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
  jpa:
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: false
```

---

## Мокирование

### Feign Clients

```java
@MockBean
private LifeTrackerClient lifeTrackerClient;

@Test
void testFeignSuccess() {
    when(lifeTrackerClient.getExpenses())
        .thenReturn(List.of(expense1, expense2));
    
    // ... test
}

@Test
void testFeignError() {
    when(lifeTrackerClient.getExpenses())
        .thenThrow(FeignException.errorStatus("getExpenses", 
            Response.builder()
                .status(500)
                .request(request)
                .build()));
    
    // ... test
}

@Test
void testFeignTimeout() {
    when(lifeTrackerClient.getExpenses())
        .thenThrow(new RetryableException(
            -1, "Read timed out", 
            Request.HttpMethod.GET, 
            new SocketTimeoutException(), 
            null, request));
    
    // ... test
}
```

### Repositories

```java
@MockBean
private UserRepository userRepository;

@Test
void testUserFound() {
    when(userRepository.findByUsername("testuser"))
        .thenReturn(Optional.of(user));
    
    // ... test
}

@Test
void testUserNotFound() {
    when(userRepository.findByUsername("unknown"))
        .thenReturn(Optional.empty());
    
    // ... test
}
```

---

## Покрытие кода

### Jacoco Configuration

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Генерация отчёта

```bash
mvn test jacoco:report
```

Отчёт: `target/site/jacoco/index.html`

### Целевое покрытие

| Модуль | Текущее | Целевое |
|--------|---------|---------|
| life-tracker | ~20% | 60% |
| analytics-service | ~15% | 50% |
| security-service | ~10% | 60% |
| api-gateway | ~5% | 40% |

### Что покрывать тестами

**Обязательно**:
- Бизнес-логика (сервисы)
- Валидация
- Обработка ошибок
- Edge cases

**Опционально**:
- Контроллеры (интеграционно)
- Конфигурация
- DTO/Entity

**Не нужно**:
- Getters/Setters
- Spring auto-configuration
- Сгенерированный код

---

## Smoke Tests

### Ручной E2E тест

```bash
# 1. Запустить стек
docker compose up -d

# 2. Проверить health
curl http://localhost:8080/actuator/health

# 3. Регистрация
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test123","role":"USER"}'

# 4. Логин
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test123"}' | jq -r '.accessToken')

# 5. Получить данные
curl http://localhost:8080/api/v1/life/finance/expenses

# 6. Добавить расход
curl -X POST http://localhost:8080/api/v1/life/finance/expenses \
  -H "Content-Type: application/json" \
  -d '{"amount":25.50,"category":"FOOD","description":"Test"}'

# 7. Проверить аналитику
curl http://localhost:8080/api/v1/analytics/overview
```

---

*Документ создан: 2025-12-02*
*Последнее обновление: 2025-12-02*

