# 🔍 Диагностика P4 (INTERNAL_ERROR auth) и P2 (ImagePullBackOff)

**Дата:** 2025-01-27  
**Цель:** Выявить точные причины проблем перед исправлением

---

## P4: INTERNAL_ERROR при login/registration

### 1. Desktop-client URLs (найдено)

**Файл:** `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/controller/LoginController.kt`

```kotlin
// Строка 22
private val baseUrl = AppConfig.apiGatewayBaseUrl

// Строка 70 - Login
.uri(URI.create("$baseUrl/auth/login"))

// Строка 151 - Register
.uri(URI.create("$baseUrl/auth/register"))
```

**Файл:** `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt`

```kotlin
// Строка 12
private const val DEFAULT_API_GATEWAY = "http://localhost:8080"

// Строка 14-18
val apiGatewayBaseUrl: String by lazy {
    val env = System.getenv("JARVIS_API_BASE_URL")
        ?: System.getenv("API_URL")
    (env?.takeIf { it.isNotBlank() } ?: DEFAULT_API_GATEWAY).trimEnd('/')
}
```

**Вывод:** Desktop-client использует `http://localhost:8080/auth/login` и `http://localhost:8080/auth/register` (через API Gateway).

---

### 2. Gateway Routes (найдено)

**Файл:** `apps/api-gateway/src/main/java/org/jarvis/apigateway/controller/AuthProxyController.java`

```java
// Строка 20
@RequestMapping({ "/auth", "/api/v1/security/auth" })

// Строка 61
@PostMapping("/login")
public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request) {
    return authClient.login(request);
}
```

**Файл:** `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/AuthClient.java`

```java
// Строка 15
@FeignClient(name = "auth-service-client", url = "${services.security.url:http://localhost:8088}")

// Строка 21
@PostMapping("/auth/login")
ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request);
```

**Вывод:** Gateway проксирует `/auth/login` → Feign Client → `http://security-service:8088/auth/login`

**Файл:** `apps/api-gateway/src/main/resources/application.yaml`

```yaml
# Строка 62-63
services:
  security:
    url: ${SECURITY_URL:http://security-service:8088}
```

**Вывод:** По умолчанию используется `http://security-service:8088` (K8s service name).

---

### 3. Security Config (найдено)

**Файл:** `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/SecurityConfig.java`

```java
// Строка 63
.requestMatchers("/auth/**").permitAll() // Login, register, refresh - NO AUTH REQUIRED
```

**Вывод:** `/auth/**` разрешён как `permitAll()`, не должен блокироваться.

---

### 4. Возможные причины 500 INTERNAL_ERROR

1. **Security-service недоступен:**
   - Pod не запущен или не ready
   - Service не создан или неправильный DNS
   - Проверка: `kubectl get pods -n jarvis -l app=security-service`

2. **Неправильный URL в Feign:**
   - `services.security.url` не настроен правильно
   - Проверка: логи api-gateway должны показывать URL

3. **Проблемы с БД:**
   - PostgreSQL не доступен
   - Миграции не выполнены
   - Неправильные credentials
   - Проверка: логи security-service должны показывать ошибки БД

4. **Хардкод паролей:**
   - `application.yml` имеет `password: jarvis123`
   - K8s secrets имеют другой пароль
   - Проверка: `kubectl get secret jarvis-secrets -n jarvis -o yaml`

5. **Feign timeout:**
   - Security-service отвечает медленно
   - Проверка: логи api-gateway должны показывать timeout

**Действие для диагностики:**
```bash
# Проверка security-service
kubectl get pods -n jarvis -l app=security-service
kubectl logs -n jarvis -l app=security-service --tail=100

# Проверка api-gateway
kubectl logs -n jarvis -l app=api-gateway --tail=100 | grep -i auth

# Проверка подключения к БД
kubectl exec -n jarvis -it $(kubectl get pod -n jarvis -l app=security-service -o jsonpath='{.items[0].metadata.name}') -- \
  env | grep SPRING_DATASOURCE

# Тест login endpoint
curl -i -X POST "http://localhost:8080/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'
```

---

## P2: ImagePullBackOff (embedding-service, postgres-pgvector)

### 1. embedding-service

**Статус:** `ImagePullBackOff`

**Событие из `kubectl describe pod`:**
```
Events:
  Type     Reason   Age                       From     Message
  ----     ------   ----                      ----     -------
  Normal   Pulling  52m (x5864 over 20d)      kubelet  Pulling image "jarvis/embedding-service:latest"
  Warning  Failed   52m (x5862 over 20d)      kubelet  Failed to pull image "jarvis/embedding-service:latest": Error response from daemon: pull access denied for jarvis/embedding-service, repository does not exist or may require 'docker login': denied: requested access to the resource is denied
  Normal   BackOff  2m44s (x132564 over 20d)  kubelet  Back-off pulling image "jarvis/embedding-service:latest"
  Warning  Failed   2m44s (x132564 over 20d)  kubelet  Error: ImagePullBackOff
```

**Причина:** Образ `jarvis/embedding-service:latest` не существует в Docker registry (локальном или удалённом).

**Файл:** `k8s/overlays/local/embedding-service.yaml`
```yaml
# Строка 29
image: jarvis/embedding-service:latest
imagePullPolicy: IfNotPresent
```

**Проблема:** 
- Образ не собран в minikube docker-env
- `imagePullPolicy: IfNotPresent` пытается pull из registry, но образа нет

**Решение:**
1. Собрать образ в minikube docker-env: `eval $(minikube docker-env) && docker build -t jarvis/embedding-service:latest docker/embedding-service`
2. Использовать `imagePullPolicy: Never` для локальных образов

---

### 2. postgres-pgvector

**Статус:** `ImagePullBackOff`

**Событие из `kubectl describe pod`:**
```
Events:
  Type     Reason   Age                       From     Message
  ----     ------   ----                      ----     -------
  Normal   BackOff  2m43s (x132586 over 20d)  kubelet  Back-off pulling image "ankane/pgvector:v0.7.4-pg16"
  Warning  Failed   2m43s (x132586 over 20d)  kubelet  Error: ImagePullBackOff
```

**Причина:** Образ `ankane/pgvector:v0.7.4-pg16` не может быть загружен (нет доступа к Docker Hub или образ не существует).

**Файл:** `k8s/overlays/local/postgres-pgvector.yaml`
```yaml
# Строка 30
image: ankane/pgvector:v0.7.4-pg16
```

**Проблема:**
- Minikube не может pull из Docker Hub (нет интернета или rate limit)
- Образ не загружен локально

**Решение:**
1. Проверить существование образа: `docker pull ankane/pgvector:v0.7.4-pg16`
2. Загрузить в minikube: `minikube image load ankane/pgvector:v0.7.4-pg16`
3. Или использовать `imagePullPolicy: IfNotPresent` и убедиться, что образ доступен

---

## 📋 План исправления (Iteration 1)

### P4 (INTERNAL_ERROR auth):
1. Проверить логи api-gateway и security-service
2. Проверить подключение security-service к БД
3. Убрать хардкод паролей из `application.yml`
4. Убедиться, что secrets в K8s правильные
5. Проверить Feign timeout настройки

### P2 (ImagePullBackOff):
1. Собрать `jarvis/embedding-service:latest` в minikube docker-env
2. Загрузить `ankane/pgvector:v0.7.4-pg16` в minikube или использовать другой способ
3. Обновить `imagePullPolicy` на `Never` для локальных образов
4. Убедиться, что сборка образов происходит в `jarvis-launch.sh` правильно

---

## ✅ Критерии успеха

**P4:**
- `curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d '{"username":"test","password":"test"}'` возвращает 200 или 401 (не 500)

**P2:**
- `kubectl get pods -n jarvis | grep -E "embedding-service|postgres-pgvector"` показывает `Running` (не `ImagePullBackOff`)

