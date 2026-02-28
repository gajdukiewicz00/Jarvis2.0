# JARVIS 2.0 — Полный аудит монорепозитория

> **⚠️ PARTIALLY OUTDATED (2026-02-28):** This report was created before Phase 2-3 cleanup.
> Key changes since this report:
> - `JwtFilter` + `JwtAuthenticationFilter` → merged into `JwtAuthFilter` (PR3.2)
> - `JwtPropertyDeprecationLogger` → removed, `jwt.*` → `jarvis.jwt.*` (PR3.4)
> - `BaseGlobalExceptionHandler` → confirmed unused, removed in cleanup PR-C6
> - Security filter registration fix applied (PR2.2)
> - Current state: see `docs/security-jwt.md` and `memory/2026-02-28.md`

**Дата:** 2026-02-27  
**Автор:** Staff SWE / SRE-аудитор  
**Версия:** `org.jarvis:jarvis-root:0.1.0-SNAPSHOT`  
**Java:** 21.0.10, Maven 3.8.7, Spring Boot 3.3.4

---

## 1) Project Map

### Reactor Build Order (источник истины)

```
mvn -DskipTests validate → Reactor Build Order:

 1/17  jarvis-root               [pom]    ← parent POM (не модуль-приложение)
 2/17  Jarvis Common             [jar]
 3/17  api-gateway               [jar]
 4/17  voice-gateway             [jar]
 5/17  nlp-service               [jar]
 6/17  orchestrator              [jar]
 7/17  pc-control                [jar]
 8/17  life-tracker              [jar]
 9/17  analytics-service         [jar]
10/17  Planner Service           [jar]
11/17  User Profile Service      [jar]
12/17  security-service          [jar]
13/17  llm-service               [jar]
14/17  Jarvis Memory Service     [jar]
15/17  smart-home-service        [jar]
16/17  desktop-client-javafx     [jar]
17/17  launcher-javafx           [jar]
```

**Итого:** 17 элементов в reactor = 1 parent POM (`jarvis-root`) + **16 child модулей**.  
Из них: 13 Spring Boot сервисов, 1 shared lib, 2 JavaFX клиента.

**Объявление модулей в `pom.xml:31-63`:**

```
<modules>
    <module>apps/jarvis-common</module>     <!-- :33 -->
    <module>apps/api-gateway</module>       <!-- :36 -->
    <module>apps/voice-gateway</module>     <!-- :37 -->
    <module>apps/nlp-service</module>       <!-- :38 -->
    <module>apps/orchestrator</module>      <!-- :39 -->
    <module>apps/pc-control</module>        <!-- :40 -->
    <module>apps/life-tracker</module>      <!-- :43 -->
    <module>apps/analytics-service</module> <!-- :44 -->
    <module>apps/planner-service</module>   <!-- :45 -->
    <module>apps/user-profile</module>      <!-- :46 -->
    <module>apps/security-service</module>  <!-- :49 -->
    <module>apps/llm-service</module>       <!-- :52 -->
    <module>apps/memory-service</module>    <!-- :53 -->
    <module>apps/smart-home-service</module><!-- :56 -->
    <module>apps/desktop-client-javafx</module> <!-- :59 -->
    <module>apps/launcher-javafx</module>       <!-- :60 -->
</modules>
```

### Module Graph (16 child модулей)

| # | Модуль | Packaging | Parent | Зависит от jarvis-common | Entrypoint | Port | Dockerfile |
|---|--------|-----------|--------|--------------------------|------------|------|------------|
| 1 | `apps/jarvis-common` | jar (lib) | `jarvis-root` | — (это ОНА) | нет | — | нет |
| 2 | `apps/api-gateway` | jar (boot) | `jarvis-root` | ✅ | `ApiGatewayApplication` + `@EnableFeignClients` | 8080 | ✅ |
| 3 | `apps/voice-gateway` | jar (boot) | `jarvis-root` | ✅ | `VoiceGatewayApplication` | 8081 | ✅ |
| 4 | `apps/nlp-service` | jar (boot) | `jarvis-root` | ✅ | `NlpServiceApplication` | 8082 | ✅ |
| 5 | `apps/orchestrator` | jar (boot) | `jarvis-root` | ✅ | `OrchestratorApplication` + `@EnableFeignClients` | 8083 | ✅ |
| 6 | `apps/pc-control` | jar (boot) | `jarvis-root` | ✅ | `PcControlApplication` | 8084 | ✅ |
| 7 | `apps/life-tracker` | jar (boot) | `jarvis-root` | ✅ | `LifeTrackerApplication` + `@EnableScheduling` | 8085 | ✅ |
| 8 | `apps/analytics-service` | jar (boot) | `jarvis-root` | ✅ | `AnalyticsApplication` + `@EnableFeignClients` | 8087 | ✅ |
| 9 | **`apps/planner-service`** | jar (boot) | **`spring-boot-starter-parent` ⚠️** | **❌ НЕТ** | `PlannerServiceApplication` + `@EnableScheduling` | 8092 | ✅ |
| 10 | `apps/user-profile` | jar (boot) | `jarvis-root` | ✅ | `UserProfileApplication` | 8089 | ✅ |
| 11 | `apps/security-service` | jar (boot) | `jarvis-root` | ❌ (собственный JWT) | `SecurityServiceApplication` | 8088 | ✅ |
| 12 | `apps/llm-service` | jar (boot) | `jarvis-root` | ✅ | `LlmServiceApplication` | 8091 | ✅ |
| 13 | `apps/memory-service` | jar (boot) | `jarvis-root` | ✅ | `MemoryServiceApplication` | 8093 | ✅ |
| 14 | `apps/smart-home-service` | jar (boot) | `jarvis-root` | ✅ | `SmartHomeApplication` | 8086 | ✅ |
| 15 | `apps/desktop-client-javafx` | jar (shade) | `jarvis-root` | ❌ (Kotlin/JavaFX) | `DesktopApplicationKt` | — | нет |
| 16 | `apps/launcher-javafx` | jar (shade) | `jarvis-root` | ❌ (Kotlin/JavaFX) | `LauncherApplicationKt` | — | нет |

**Внешние (не в Maven reactor):** `apps/mobile-client` (Gradle/Kotlin/Android)  
**Вспомогательные Docker:** `docker/llm-server` (Python), `docker/embedding-service` (Python)

### Граф зависимостей (кто от кого)

```
jarvis-common ← api-gateway, voice-gateway, nlp-service, orchestrator, 
                 pc-control, life-tracker, analytics-service, user-profile,
                 llm-service, memory-service, smart-home-service

planner-service ← (в reactor, но parent ≠ jarvis-root; нет dep на jarvis-common) ⚠️
security-service ← (parent=jarvis-root, но нет dep на jarvis-common; собственный JWT)
desktop-client-javafx, launcher-javafx ← (JavaFX клиенты, нет jarvis-common)
```

### Launch Scripts / Infra

- `Makefile` — build/test/clean + k8s launch/stop/logs
- `jarvis-launch.sh`, `jarvis-stop.sh`, `jarvis-logs.sh` — корневые скрипты
- `scripts/product/` — install, TLS, secrets, diagnostics
- `scripts/ci/` — k8s-preflight, cosign, quality checks
- `k8s/base/` — Kustomize base (12 service deployments + postgres + mosquitto)
- `k8s/overlays/prod/` — prod overlay (llm-server, llm-service, memory-service, embedding-service, pgvector, Kyverno policies, PDB, RBAC, NetworkPolicy)
- `k8s/overlays/dev-hostpath/` — dev overlay (hostpath PV)
- **Нет docker-compose** — только Kubernetes

---

## 2) Build & Test Smoke

### Build Results

**Команда:** `mvn -DskipTests package`

**Evidence — Reactor Summary (вывод команды):**

```
Reactor Summary for jarvis-root 0.1.0-SNAPSHOT:

jarvis-root ........................................ SUCCESS [  0.001 s]
Jarvis Common ...................................... SUCCESS [  0.350 s]
api-gateway ........................................ SUCCESS [  0.228 s]
voice-gateway ...................................... SUCCESS [  0.073 s]
nlp-service ........................................ SUCCESS [  0.030 s]
orchestrator ....................................... SUCCESS [  0.037 s]
pc-control ......................................... SUCCESS [  0.029 s]
life-tracker ....................................... SUCCESS [  0.068 s]
analytics-service .................................. SUCCESS [  0.032 s]
Planner Service .................................... SUCCESS [  0.028 s]
User Profile Service ............................... SUCCESS [  0.022 s]
security-service ................................... SUCCESS [  0.024 s]
llm-service ........................................ SUCCESS [  0.021 s]
Jarvis Memory Service .............................. SUCCESS [  0.040 s]
smart-home-service ................................. SUCCESS [  0.020 s]
desktop-client-javafx .............................. SUCCESS [  8.968 s]
launcher-javafx .................................... SUCCESS [  6.824 s]
------------------------------------------------------------------------
BUILD SUCCESS  (Total time: 16.968 s)
```

**Все 16 child модулей + parent POM = 17/17 SUCCESS.**

**Warnings (non-blocking):**
- 8× Lombok `@EqualsAndHashCode(callSuper=false)` warnings в `life-tracker/src/main/java/org/jarvis/lifetracker/tooling/dto/*.java`
- 1× аналогичный в `memory-service/src/main/java/org/jarvis/memory/tooling/dto/SearchMemoryToolRequest.java`
- Kotlin deprecated `URL(String!)` в `launcher-javafx`

### Test Results

**Команда:** `mvn test` (первый запуск остановился на api-gateway), затем `mvn test --fail-at-end -pl '!apps/api-gateway'` для остальных.

Из 16 child модулей тесты запускаются в **11** (остальные 5 — nlp-service, analytics-service, user-profile, smart-home-service, launcher-javafx — не имеют тестов или тесты не конфигурированы).

| Модуль | Тесты | Результат |
|--------|-------|-----------|
| jarvis-common | 2 | ✅ PASS |
| **api-gateway** | **7 (4 в JwtAuth + 3 в FeignConfig)** | **❌ 1 FAILURE** |
| voice-gateway | 1 | ✅ PASS |
| orchestrator | 3 | ✅ PASS |
| pc-control | 3 | ✅ PASS |
| life-tracker | 2 | ✅ PASS |
| **planner-service** | **8 (2+1+1+4)** | **❌ 1 ERROR** |
| security-service | 1 | ✅ PASS |
| llm-service | 3 | ✅ PASS |
| memory-service | 1 | ✅ PASS |
| desktop-client-javafx | 1 | ✅ PASS |

**Итого: 2 из 11 тестовых модулей падают.**

### Test Failure #1: api-gateway

- **Symptom:** `JwtAuthenticationFilterIntegrationTest.requestWithValidTokenReturns200AndProxiesRequest` → `Status expected:<200> but was:<403>`
- **Evidence (surefire report):** `apps/api-gateway/target/surefire-reports/org.jarvis.apigateway.security.JwtAuthenticationFilterIntegrationTest.txt`:
  ```
  Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.701 s <<< FAILURE!
  requestWithValidTokenReturns200AndProxiesRequest -- Time elapsed: 0.105 s <<< FAILURE!
  java.lang.AssertionError: Status expected:<200> but was:<403>
    at o.s.test.web.servlet.result.StatusResultMatchers.lambda$matcher$9(StatusResultMatchers.java:637)
    at o.s.test.web.servlet.MockMvc$1.andExpect(MockMvc.java:214)
    at o.j.apigateway.security.JwtAuthenticationFilterIntegrationTest
         .requestWithValidTokenReturns200AndProxiesRequest(JwtAuthenticationFilterIntegrationTest.java:115)
  ```
- **Root cause (с доказательствами по классам):**  
  Тест (`apps/api-gateway/src/test/java/.../JwtAuthenticationFilterIntegrationTest.java:51`) делает:
  ```java
  @Import({ SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class })
  ```
  Но `SecurityConfig` (`apps/api-gateway/src/main/java/.../security/SecurityConfig.java:29-30`) инжектит **два** фильтра:
  ```java
  private final JwtAuthenticationFilter jwtAuthenticationFilter;  // :29
  private final JwtFilter jwtFilter;                              // :30
  ```
  `JwtFilter` **не импортирован** в тесте → Spring не может полностью собрать `SecurityConfig` → fallback security блокирует запрос (403).  
  Дополнительно, `FeignAuthConfig` (`apps/api-gateway/src/main/java/.../config/FeignAuthConfig.java:22`) требует `ServiceJwtProvider`:
  ```java
  public RequestInterceptor serviceAuthInterceptor(ServiceJwtProvider serviceJwtProvider, ...) // :22
  ```
  Этот bean не предоставлен в test context → `UnsatisfiedDependencyException` (видно в stacktrace surefire).
- **Fix:** В `apps/api-gateway/src/test/.../JwtAuthenticationFilterIntegrationTest.java:51`:
  - Добавить `@MockBean private ServiceJwtProvider serviceJwtProvider;`
  - Добавить `JwtFilter.class` в `@Import`
- **Verification:** `mvn -pl apps/api-gateway test`

### Test Failure #2: planner-service

- **Symptom:** `ToolTodoControllerSecurityTest` → `NoClassDefFoundError: ServiceJwtProvider`
- **Evidence (surefire report):** `apps/planner-service/target/surefire-reports/org.jarvis.planner.controller.ToolTodoControllerSecurityTest.txt`:
  ```
  Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.004 s <<< FAILURE!
  ToolTodoControllerSecurityTest -- Time elapsed: 0.004 s <<< ERROR!
  java.lang.NoClassDefFoundError: ServiceJwtProvider
    at java.base/java.lang.Class.getDeclaredFields0(Native Method)
    ...
  Caused by: java.lang.ClassNotFoundException: ServiceJwtProvider
    at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
  ```
- **Root cause:** Тест (`apps/planner-service/src/test/.../ToolTodoControllerSecurityTest.java:3`) импортирует:
  ```java
  import org.jarvis.common.security.ServiceJwtProvider;
  ```
  Но `planner-service` **не имеет зависимости на jarvis-common**. Подтверждено: `mvn -pl apps/planner-service dependency:tree` выводит 0 зависимостей от `org.jarvis`. POM (`apps/planner-service/pom.xml:8-13`) указывает parent = `spring-boot-starter-parent:3.3.4`, не `jarvis-root`.
- **Fix:** Либо (1) добавить `jarvis-common` как зависимость + переключить parent на `jarvis-root`; либо (2) убрать импорт `ServiceJwtProvider` и переписать тест с локальным фильтром.
- **Verification:** `mvn -pl apps/planner-service -am test`

---

## 3) Duplicate Clusters

### Cluster-1: SecurityConfig (высокая степень совпадения)

**Тема:** Конфигурация Spring Security filter chain. `jarvis-common` предоставляет `BaseSecurityConfig` — 10 сервисов её наследуют, 3 пишут свою.

**Evidence snippet — `BaseSecurityConfig` vs planner-service `SecurityConfig`:**

В `jarvis-common` (`apps/jarvis-common/src/main/java/.../security/BaseSecurityConfig.java:28-44`):
```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http, ServiceJwtFilter serviceJwtFilter) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(getPublicEndpoints()).permitAll()
            .anyRequest().authenticated()
        )
        .addFilterBefore(serviceJwtFilter, UsernamePasswordAuthenticationFilter.class)     // ← service JWT
        .addFilterAfter(createGatewayAuthFilter(), ServiceJwtFilter.class);               // ← gateway delegation
    configureAdditionalSecurity(http);
    return http.build();
}
```

В planner-service (`apps/planner-service/src/main/java/.../config/SecurityConfig.java:23-35`):
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**", "/api/v1/tools/**").permitAll()
            .anyRequest().authenticated()
        )
        .addFilterBefore(gatewayUserAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

**Отличие:**  
- `BaseSecurityConfig` добавляет `ServiceJwtFilter` (проверка `X-Service-Token` JWT) **+** `GatewayAuthFilter` (делегация `X-User-Id`/`X-User-Roles` после JWT верификации).
- planner-service'овский вариант добавляет только локальный `GatewayUserAuthenticationFilter`, который проверяет `Authorization: Bearer` + `X-User-Id`, **но не валидирует JWT** — просто проверяет наличие "Bearer " prefix.

| Файл | Строки | Наследует `BaseSecurityConfig`? |
|------|--------|---------------------------------|
| `apps/jarvis-common/.../BaseSecurityConfig.java:22-74` | Базовый | — |
| `apps/voice-gateway/.../SecurityConfig.java:14` | 1 строка | ✅ |
| `apps/nlp-service/.../SecurityConfig.java:14` | 1 строка | ✅ |
| `apps/orchestrator/.../SecurityConfig.java:14` | 1 строка | ✅ |
| `apps/pc-control/.../SecurityConfig.java:15` | 1 строка | ✅ |
| `apps/life-tracker/.../SecurityConfig.java:14` | 1 строка | ✅ |
| `apps/analytics-service/.../SecurityConfig.java:14` | 1 строка | ✅ |
| `apps/user-profile/.../SecurityConfig.java:14` | 1 строка | ✅ |
| `apps/llm-service/.../SecurityConfig.java:14-25` | override `getPublicEndpoints()` | ✅ |
| `apps/memory-service/.../SecurityConfig.java:14-24` | override `getPublicEndpoints()` | ✅ |
| `apps/smart-home-service/.../SecurityConfig.java:14` | 1 строка | ✅ |
| **`apps/planner-service/.../SecurityConfig.java:15-35`** | **20 строк, своя** | **❌** |
| `apps/api-gateway/.../SecurityConfig.java:27-47` | Своя (gateway-specific) | ❌ (ok — специфика) |
| `apps/security-service/.../SecurityConfig.java:16-35` | Своя (auth server) | ❌ (ok — специфика) |

### Cluster-2: GatewayAuthFilter vs GatewayUserAuthenticationFilter (высокая степень совпадения)

**Тема:** Фильтр аутентификации через gateway headers.

**Evidence snippet:**

`jarvis-common` (`apps/jarvis-common/src/main/java/.../GatewayAuthFilter.java:34-65`):
```java
protected void doFilterInternal(...) {
    Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
    if (existingAuth == null || !existingAuth.isAuthenticated()) {   // ← требует предварительную JWT auth
        chain.doFilter(request, response); return;
    }
    String userId = request.getHeader(USER_ID_HEADER);               // X-User-Id
    String roles = request.getHeader(USER_ROLES_HEADER);             // X-User-Roles
    if (userId != null && !userId.isBlank() && isServiceAuthentication(existingAuth)) {
        // ... creates delegated auth with merged roles
    }
    chain.doFilter(request, response);
}
```

planner-service (`apps/planner-service/src/main/java/.../GatewayUserAuthenticationFilter.java:24-52`):
```java
protected void doFilterInternal(...) {
    if (!requestPath.startsWith(PLANNER_PATH_PREFIX)) { ... return; }
    if (SecurityContextHolder.getContext().getAuthentication() != null) { ... return; }
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    String userId = request.getHeader(USER_ID_HEADER);
    if (!hasBearerToken(authorization) || userId == null || userId.isBlank()) {
        unauthorized(response); return;
    }
    // ← просто проверяет наличие "Bearer ", не валидирует JWT!
    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            userId, null, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    // ...
}
```

**Отличие:** jarvis-common `GatewayAuthFilter` работает **после** `ServiceJwtFilter` и требует authenticated service token. planner-service вариант **не проверяет JWT подпись** — принимает любой `Authorization: Bearer xxx` если есть `X-User-Id`.

### Cluster-3: TracePropagationFilter (100% совпадение)

**Тема:** Идентичный фильтр для propagation `X-Trace-Id` → MDC.

**Evidence — побайтовое совпадение (diff):**

`apps/analytics-service/src/main/java/org/jarvis/analytics/filter/TracePropagationFilter.java:22-43`  
`apps/life-tracker/src/main/java/org/jarvis/lifetracker/filter/TracePropagationFilter.java:22-43`

Оба содержат идентичный код:
```java
public class TracePropagationFilter extends OncePerRequestFilter {
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
        try { filterChain.doFilter(request, response); }
        finally { MDC.remove(TRACE_ID_MDC_KEY); }
    }
}
```

Единственное отличие — package name (строка 1). Остальные 10 сервисов **не имеют** этого фильтра.

### Cluster-4: GlobalExceptionHandler (средняя степень совпадения)

**Тема:** `@RestControllerAdvice` — разные реализации в 5 сервисах + базовый в common.

**Evidence snippet — совпадающий паттерн error response в api-gateway и analytics-service:**

`apps/api-gateway/src/main/java/.../GlobalExceptionHandler.java` содержит `handleFeignException` (`:100-130`).  
`apps/analytics-service/src/main/java/.../GlobalExceptionHandler.java` содержит практически идентичный `handleFeignException` с тем же паттерном regex `SERVICE_NAME_PATTERN`:

```java
// Оба файла (api-gateway:42, analytics-service:26):
private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("http://([^/:]+)");
```

| Файл | `@ExceptionHandler` count | Заметки |
|------|--------------------------|---------|
| `apps/jarvis-common/.../BaseGlobalExceptionHandler.java` | Базовый (abstract) | Не используется ни одним сервисом |
| `apps/api-gateway/.../GlobalExceptionHandler.java` | 7 handlers | Feign + validation + 404 |
| `apps/analytics-service/.../GlobalExceptionHandler.java` | ~5 handlers | Копия api-gateway Feign handlers |
| `apps/voice-gateway/.../GlobalExceptionHandler.java` | ~2 handlers | Минимальный |
| `apps/security-service/.../GlobalExceptionHandler.java` | Auth-specific | Свой |
| `apps/life-tracker/.../GlobalExceptionHandler.java` | General | Свой |

**Стратегия:** Сервисы без Feign → `extends BaseGlobalExceptionHandler`. Feign-specific handlers → вынести в `jarvis-common` как mixin/trait.

### Cluster-5: IdempotencyConflictException (100% совпадение)

**Evidence:**

`apps/life-tracker/src/main/java/org/jarvis/lifetracker/tooling/IdempotencyConflictException.java:3-6`:
```java
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) { super(message); }
}
```

`apps/planner-service/src/main/java/org/jarvis/planner/tooling/IdempotencyConflictException.java:3-6`:
```java
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) { super(message); }
}
```

Полностью идентичны.

### Cluster-6: ToolExceptionHandler (средняя степень совпадения)

| Файл |
|------|
| `apps/life-tracker/src/main/java/org/jarvis/lifetracker/controller/ToolExceptionHandler.java` |
| `apps/planner-service/src/main/java/org/jarvis/planner/controller/ToolExceptionHandler.java` |
| `apps/memory-service/src/main/java/org/jarvis/memory/controller/ToolExceptionHandler.java` |

Все обрабатывают `IdempotencyConflictException` и `MethodArgumentNotValidException` с одинаковым response format.

### Cluster-7: JWT-зависимости в POM (дублирование версий)

Jjwt 0.12.6 объявлен с explicit версиями в 3 модулях:
- `apps/jarvis-common/pom.xml:33-46`
- `apps/api-gateway/pom.xml:68-83`
- `apps/security-service/pom.xml:49-64`

### Cluster-8: HikariCP Config (средняя степень совпадения)

Конфигурация HikariCP дублируется (с **разными** параметрами!) в:
- `apps/life-tracker/src/main/resources/application.yaml:19-45` — max-lifetime=1200000, leak-detection=60000
- `apps/security-service/src/main/resources/application.yml:18-41` — max-lifetime=1200000, **no** leak-detection
- `apps/user-profile/src/main/resources/application.yaml:12-19` — max-lifetime=1800000, leak-detection=60000
- `apps/shared-config/application-hikari.yml` (только planner-service импортирует)

**Стратегия:** Все DB-сервисы должны импортировать `shared-config/application-hikari.yml`.

---

## 4) Errors / Bugs / Risks

### P0 (ломает / опасно)

#### P0-1: planner-service — не интегрирован в общий security stack jarvis-common

- **Symptom:** Тест `NoClassDefFoundError: ServiceJwtProvider`; security конфигурация отличается от всех остальных сервисов.
- **Evidence:**
  - `apps/planner-service/pom.xml:8-13` — parent = `spring-boot-starter-parent:3.3.4`, НЕ `jarvis-root`:
    ```xml
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/>
    </parent>
    ```
  - При этом `pom.xml:45` root POM **включает** planner-service в `<modules>` — он в reactor, но с чужим parent.
  - `mvn -pl apps/planner-service dependency:tree` → 0 зависимостей от `org.jarvis` (подтверждено).
  - Surefire: `NoClassDefFoundError: ServiceJwtProvider` at `ToolTodoControllerSecurityTest.java:3`.
- **Что planner-service теряет из-за отсутствия jarvis-common:**
  - `ServiceJwtFilter` (`jarvis-common/.../ServiceJwtFilter.java:22-93`) — проверка `X-Service-Token` JWT подписи
  - `ServiceJwtProvider` (`jarvis-common/.../ServiceJwtProvider.java:20-119`) — создание/валидация service JWT
  - `GatewayAuthFilter` (`jarvis-common/.../GatewayAuthFilter.java:29-99`) — безопасная делегация user identity через `X-User-Id` + `X-User-Roles` **только после** JWT верификации
  - `BaseSecurityConfig` (`jarvis-common/.../BaseSecurityConfig.java:22-74`) — стандартный filter chain
  - `BaseGlobalExceptionHandler`, `PiiLoggingGuard`, `RateLimitInterceptor`, `LogSanitizer`
- **Что planner-service делает вместо этого:**
  - Локальный `GatewayUserAuthenticationFilter` (`apps/planner-service/.../GatewayUserAuthenticationFilter.java:19-66`) — принимает любой `Authorization: Bearer xxx` без валидации JWT подписи (строки 39-44, 54-57).
  - Локальный `SecurityConfig` (`apps/planner-service/.../SecurityConfig.java:15-35`) — без `ServiceJwtFilter`.
- **Root cause:** planner-service был создан как standalone и не интегрирован в общий security stack.
- **Fix:**
  1. `apps/planner-service/pom.xml:8-13`: заменить parent на `jarvis-root`, добавить `<dependency>` на `jarvis-common`
  2. Удалить `apps/planner-service/src/main/java/.../config/GatewayUserAuthenticationFilter.java`
  3. `SecurityConfig` → `extends BaseSecurityConfig`
  4. Fix `ToolTodoControllerSecurityTest`
- **Verification:** `mvn -pl apps/planner-service -am test`
- **Риск:** planner-service не использует `ServiceJwtFilter` → service-to-service вызовы с JWT не проверяются. Кроме того, не наследует `pluginManagement` из root POM (checkstyle/pmd/spotbugs).

#### P0-2: api-gateway test — ServiceJwtProvider bean missing

- **Symptom:** `requestWithValidTokenReturns200AndProxiesRequest` → 403 (expected 200)
- **Evidence (surefire stacktrace):**
  ```
  apps/api-gateway/target/surefire-reports/
    org.jarvis.apigateway.security.JwtAuthenticationFilterIntegrationTest.txt:
  
  Status expected:<200> but was:<403>
    at ...JwtAuthenticationFilterIntegrationTest
         .requestWithValidTokenReturns200AndProxiesRequest(:115)
  ```
  Root cause из контекста Spring (тот же surefire report, полный stacktrace):
  ```
  UnsatisfiedDependencyException: Error creating bean with name 'serviceAuthInterceptor'
    defined in [FeignAuthConfig.class]:
    No qualifying bean of type 'ServiceJwtProvider' available
  ```
- **Код виновника:** `apps/api-gateway/src/main/java/.../config/FeignAuthConfig.java:22`:
  ```java
  public RequestInterceptor serviceAuthInterceptor(ServiceJwtProvider serviceJwtProvider, ...)
  ```
  Тест (`apps/api-gateway/src/test/.../JwtAuthenticationFilterIntegrationTest.java:51`):
  ```java
  @Import({ SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class })
  ```
  — не предоставляет ни `ServiceJwtProvider`, ни `JwtFilter` (а `SecurityConfig:30` его требует).
- **Fix:** В `JwtAuthenticationFilterIntegrationTest.java:51`:
  - Добавить `@MockBean private ServiceJwtProvider serviceJwtProvider;`
  - Добавить `JwtFilter.class` в `@Import`
- **Verification:** `mvn -pl apps/api-gateway test`

#### P0-3: K8s images без version tags

- **Symptom:** Все base k8s deployments используют `image: jarvis/<service>` без тега.
- **Evidence (полный список `rg -n "image:" k8s/base/`):**
  ```
  k8s/base/api-gateway/deployment.yaml:30          image: jarvis/api-gateway
  k8s/base/analytics-service/deployment.yaml:30     image: jarvis/analytics-service
  k8s/base/life-tracker/deployment.yaml:30          image: jarvis/life-tracker
  k8s/base/mosquitto/deployment.yaml:30             image: eclipse-mosquitto:2
  k8s/base/mosquitto/deployment.yaml:58             image: eclipse-mosquitto:2
  k8s/base/nlp-service/deployment.yaml:30           image: jarvis/nlp-service
  k8s/base/orchestrator/deployment.yaml:30          image: jarvis/orchestrator
  k8s/base/pc-control/deployment.yaml:30            image: jarvis/pc-control
  k8s/base/planner-service/deployment.yaml:30       image: jarvis/planner-service
  k8s/base/security-service/deployment.yaml:30      image: jarvis/security-service
  k8s/base/smart-home-service/deployment.yaml:30    image: jarvis/smart-home-service
  k8s/base/user-profile/deployment.yaml:30          image: jarvis/user-profile
  k8s/base/voice-gateway/deployment.yaml:30         image: jarvis/voice-gateway
  ```
  **11 из 13 images** (все jarvis/* сервисы) — без тега. `eclipse-mosquitto:2` — только minor tag.
- **Root cause:** Docker/containerd при отсутствии тега резолвит как `:latest`. С `imagePullPolicy: IfNotPresent` это работает локально (собранный образ), но с registry — breakable.
- **Гипотеза про Kyverno:** policy `k8s/overlays/prod/kyverno/06-disallow-latest-tag-policy.yaml:33` проверяет `contains(element.image, ':latest')`. Image `jarvis/api-gateway` (без тега) **НЕ содержит строку `:latest`** → policy **НЕ поймает** этот случай. Policy ловит только явный `:latest`.  
  **Как проверить:** `kustomize build k8s/overlays/prod | kyverno apply k8s/overlays/prod/kyverno/06-disallow-latest-tag-policy.yaml -` (если kyverno CLI доступен).
- **Fix:** Добавить kustomize `images` transformer в `k8s/base/kustomization.yaml` или explicit tags в deployment YAML.
- **Verification:** `kustomize build k8s/overlays/prod | grep "image:"` — все должны иметь теги.

### P1 (важно)

#### P1-1: Flyway version conflict в security-service

- **Symptom:** security-service explicitly declares `flyway-core:11.0.0` и `flyway-database-postgresql:11.0.0`
- **Evidence:** `apps/security-service/pom.xml:41-47`
- **Root cause:** Spring Boot 3.3.4 managed version = `10.10.0` (подтверждено: `mvn help:evaluate -Dexpression=flyway.version -pl apps/life-tracker -q -DforceStdout` → `10.10.0`). Override на 11.0.0 — major version jump.
- **Fix:** Удалить explicit `<version>11.0.0</version>` из security-service POM.
- **Verification:** `mvn -pl apps/security-service dependency:tree | grep flyway`

#### P1-2: security-service — дублирование JWT, несогласованные property names

- **Symptom:** Собственная реализация `JwtService` (`apps/security-service/src/main/java/.../JwtService.java:25`)
- **Evidence:** `apps/security-service/pom.xml` — нет dep на `jarvis-common`; property `jarvis.jwt.secret` vs `jwt.secret` (api-gateway) vs `service.jwt.secret` (common)
- **Root cause:** security-service — auth server, собственный JWT signing **архитектурно оправдан**. Но naming JWT properties не согласован.
- **Fix:** Унифицировать property naming.
- **Verification:** `grep -rn "jwt.secret" apps/*/src/main/resources/`

#### P1-3: Kafka/RabbitMQ зависимости без listeners

- **Symptom:** pc-control, smart-home-service, voice-gateway имеют `spring-kafka` и `spring-boot-starter-amqp` в POM, но 0 `@KafkaListener`/`@RabbitListener`/`KafkaTemplate`/`RabbitTemplate`.
- **Evidence:** `grep "@KafkaListener\|@RabbitListener\|RabbitTemplate\|KafkaTemplate" apps/ --include="*.java"` → 0 matches.
- **Fix:** Убрать зависимости или довести до реализации. Feature flags (`FEATURE_RABBITMQ_PC_CONTROL: false`) уже есть — хороший start.

#### P1-4: @Transactional на read-only операциях

- Хорошо: `apps/security-service/.../AuthService.java:92,122,156` — `@Transactional(readOnly = true)`
- Хорошо: `apps/memory-service/.../MemoryService.java:122` — `@Transactional(readOnly = true)`
- Плохо: `apps/planner-service/.../TaskService.java` — read-методы без `@Transactional`
- **Fix:** Добавить `@Transactional(readOnly = true)` на все read-only public методы в DB-сервисах.

#### P1-5: Отсутствие graceful shutdown у 9 из 13 сервисов

- С `server.shutdown: graceful`: api-gateway (:3), life-tracker (:3), security-service (:3), analytics-service (:3)
- **Без:** voice-gateway, nlp-service, orchestrator, pc-control, planner-service, user-profile, llm-service, memory-service, smart-home-service
- **Fix:** Добавить `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s` во все application.yml.

#### P1-6: Lombok @EqualsAndHashCode warnings (9 файлов)

- **Evidence:** Build output — 8 файлов в `apps/life-tracker/.../tooling/dto/` + 1 в `apps/memory-service/.../tooling/dto/`
- **Fix:** Добавить `@EqualsAndHashCode(callSuper = false)`.

#### P1-7: CORS — hardcoded origins

- **Evidence:** `apps/api-gateway/src/main/resources/application.yaml:116-117`
- **Fix:** Externalize через env var.

### P2 (improvement)

#### P2-1: memory-service — SQL TRACE logging в production config

- `apps/memory-service/src/main/resources/application.yml:63-65`: `org.hibernate.SQL: TRACE`, `org.hibernate.type.descriptor.sql: TRACE`, `org.springframework.transaction: TRACE`

#### P2-2: planner-service — DEBUG logging по умолчанию

- `apps/planner-service/src/main/resources/application.yml:64`: `org.jarvis.planner: DEBUG`

#### P2-3: user-profile — DEBUG logging по умолчанию

- `apps/user-profile/src/main/resources/application.yaml:38`: `org.jarvis.userprofile: DEBUG`

#### P2-4: TracePropagation — отсутствует у 10 из 12 сервисов

#### P2-5: Actuator health `show-details` inconsistency

| Сервис | show-details |
|--------|-------------|
| api-gateway, security-service, life-tracker, memory-service | `when_authorized` ✅ |
| nlp-service, pc-control, smart-home-service | `always` ⚠️ |

#### P2-6: API versioning / error response format inconsistency

---

## 5) Kubernetes / Helm / Kustomize Audit

### Хорошо сделано ✅

- Все 12 base deployments: `runAsNonRoot: true`, `runAsUser: 1000`, `fsGroup: 1000`, `seccompProfile: RuntimeDefault`
- Container-level: `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: ALL`
- Все имеют `startupProbe`, `livenessProbe`, `readinessProbe` (все на `/actuator/health/*`)
- Все envFrom: `secretRef: jarvis-secrets`
- Resources: limits (512Mi/500m) + requests (256Mi/100m) на всех
- Kyverno policies в prod (7 шт): runtime hardening, container hardening, host isolation, disallow capabilities, disallow latest tag, verify images
- NetworkPolicy baseline + allowlist
- PDB для core services
- RBAC service accounts

### Issues

#### K8S-1: Images без тегов (→ P0-3)

**Evidence:** Полный список — см. P0-3. 11 jarvis/* images без тегов + mosquitto с minor tag `:2`.

#### K8S-2: Нет k8s deployment для llm-service/memory-service в base

- `k8s/base/` не содержит `llm-service/` и `memory-service/` директорий
- Они только в `k8s/overlays/prod/` → dev overlay не может их запустить

#### K8S-3: DB URL inconsistency

- `k8s/base/life-tracker/deployment.yaml:49`: `jdbc:postgresql://...5432/jarvis_db`
- `k8s/base/security-service/deployment.yaml:49`: `jdbc:postgresql://...5432/jarvis_security`
- `secrets.example.env`: `SPRING_DATASOURCE_URL=...5432/jarvis`

#### K8S-4: Mosquitto image — minor tag `eclipse-mosquitto:2`

- `k8s/base/mosquitto/deployment.yaml:30,58`
- **Fix:** Pin to `eclipse-mosquitto:2.0.18`

---

## 6) Dependencies / Vulnerabilities / Version Conflicts

### Flyway Version Conflict

| Модуль | Flyway version |
|--------|---------------|
| life-tracker, user-profile, planner-service, memory-service | 10.10.0 (managed by Spring Boot 3.3.4) |
| **security-service** | **11.0.0 (explicit override)** |

### OpenFeign Version Inconsistency

| Модуль | spring-cloud-starter-openfeign | spring-cloud BOM |
|--------|-------------------------------|-----------------|
| api-gateway | через BOM `spring-cloud-dependencies:2023.0.3` | ✅ |
| orchestrator | `4.1.3` (explicit) | ❌ нет BOM |
| analytics-service | `4.1.3` (explicit) | ❌ нет BOM |

### Logback — desktop/launcher

| Модуль | logback-classic |
|--------|----------------|
| desktop-client-javafx | 1.4.11 (explicit) |
| launcher-javafx | 1.4.11 (explicit) |
| Spring Boot сервисы | 1.5.8 (managed) |

**Гипотеза:** logback 1.4.11 → CVE-2023-6378. **Проверить:** `mvn org.owasp:dependency-check-maven:check` или Snyk.

### Потенциально устаревшие (Гипотеза)

| Зависимость | Текущая | Замечание |
|------------|---------|-----------|
| Guava | 32.1.3-jre | ~33.x доступна |
| Resilience4j | 2.1.0 | 2.2.x доступна |

**Как проверить:** OWASP dependency-check или Snyk CLI.

---

## 7) Refactor Plan (безопасный)

### Фаза 0: Hotfixes — 1-2 дня

1. **P0-1: planner-service → jarvis-root** — parent + dep + удалить дубли
2. **P0-2: api-gateway test fix** — MockBean + Import
3. **P0-3: K8s image tags** — kustomize transformer или explicit
4. **P1-1: Flyway alignment** — удалить explicit 11.0.0

### Фаза 1: Infra Consolidation — 3-5 дней

1. `TracePropagationFilter`, `IdempotencyConflictException`, `ToolExceptionHandler` → `jarvis-common`
2. Root POM `<dependencyManagement>`: jjwt, guava, openfeign (spring-cloud BOM)
3. Config: JWT naming, graceful shutdown, HikariCP, Actuator

### Фаза 2: Domain Refactor — 1-2 недели

1. Error response standardization → `BaseGlobalExceptionHandler`
2. Trace propagation → common filter во всех сервисах
3. OpenFeign consolidation
4. Kafka/RabbitMQ cleanup

### Фаза 3: Cleanup — ongoing

1. TRACE/DEBUG → INFO/WARN в production
2. Lombok warnings fix
3. CORS externalization
4. Logback upgrade
5. Consumer-driven tests (Pact / Spring Cloud Contract)

### Зависимости

```
Фаза 0 ─→ Фаза 1 ─→ Фаза 2 ─→ Фаза 3
   │          │          │
   └─ block   └─ block   └─ parallel OK
```

---

## 8) Output Artifacts

### CI Commands That Must Pass:

```bash
mvn clean test                           # Все тесты
mvn -DskipTests package                  # Сборка
mvn checkstyle:check                     # Static analysis
mvn pmd:check                            # PMD
mvn spotbugs:check                       # SpotBugs
kustomize build k8s/base                 # K8s validate
kustomize build k8s/overlays/prod        # Prod validate
./scripts/ci/k8s-preflight.sh            # K8s preflight
```

### Красные флаги перед деплоем:

1. ❌ `mvn test` — 2 из 11 тестовых модулей падают (api-gateway, planner-service) 
2. ❌ planner-service не интегрирован в общий security stack (нет ServiceJwtFilter)
3. ⚠️ Flyway 11 в security-service vs 10.10 в остальных
4. ⚠️ K8s images без тегов (Kyverno policy НЕ ловит отсутствие тега — только explicit `:latest`)
5. ⚠️ SQL TRACE logging в memory-service production config
6. ⚠️ CORS hardcoded origins
