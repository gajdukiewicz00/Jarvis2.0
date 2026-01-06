# 🎯 Master Plan: Jarvis 2.0 → Production-Grade Product

**Дата:** 2025-01-27  
**Цель:** Довести проект до production-ready состояния для локальной установки на Ubuntu  
**Стандарт:** MINIKUBE (с абстракцией для будущего перехода на k3s)  
**Принцип:** НЕТ DEV-решений, только продукт

---

## 📋 Решения по Q1-Q9 (зафиксированы)

### Q1: Kubernetes Runtime
- **Стандарт:** MINIKUBE (текущий выбор)
- **Требование:** Абстракция в launcher для будущего перехода на k3s
- **Действие:** Создать `scripts/k8s-runtime.sh` с функциями `detect_runtime()`, `start_cluster()`, `build_images()`

### Q2: Секреты
- **Стратегия:** Генерация при первом запуске, хранение в OS keyring или `~/.jarvis/secrets.json.enc`
- **Требование:** НИКАКИХ секретов в git и yaml открытым текстом
- **Действие:** Создать `scripts/generate-secrets.sh`, убрать хардкод из всех конфигов

### Q3: Иконка/Дистрибуция
- **Стандарт:** `.desktop` → GUI-friendly launcher (JavaFX/GTK)
- **Требование:** Запуск кликом, БЕЗ терминала для пользователя
- **Действие:** Создать `jarvis-launcher` (GUI), обновить `jarvis.desktop`

### Q4: LLM Models Path
- **Стандарт:** `~/.jarvis/models/`
- **Действие:** Убрать хардкод `/home/kwaqa/models` из всех мест

### Q5: GPU Недоступен
- **Поведение:** LLM стек ОТКЛЮЧАЕТСЯ, продукт работает без него
- **UI:** Показывает статус "LLM недоступен"
- **CPU fallback:** НЕ делаем (нестабильно)

### Q6: Postgres
- **Текущее:** 2 инстанса (postgres + postgres-pgvector) - НЕ трогаем сейчас
- **Будущее:** Отдельный этап "объединение в 1 postgres с pgvector" (оптимизация)

### Q7: Endpoints UI
- **Контракт:** UI → API Gateway → security-service
- **URLs:** `$baseUrl/auth/login`, `$baseUrl/auth/register` (где baseUrl = API Gateway)
- **Требование:** 200/401/409 - OK, 500 - НЕ OK

### Q8: Self-check
- **Подход:** Launcher управляет всем (minikube, манифесты, readiness, self-heal)
- **Логи:** `~/.jarvis/logs/launcher.log`

### Q9: MVP DoD
**Обязательно:**
- Desktop запускается кликом по иконке без терминала
- backend + базы поднимаются автоматически
- регистрация/логин работают стабильно через gateway
- user profile сохраняется и читается
- life-tracker + planner доступны
- orchestrator доступен (минимум health + базовая команда)
- логи в файл (launcher + desktop)
- self-check при старте (и попытка восстановить)

**Опционально:**
- voice-gateway
- LLM/memory только если GPU доступен

---

## 🗑️ УДАЛЕНИЕ LEGACY/DEV

**Правило:** Все файлы/папки с названием `dev/legacy` должны быть:
- Удалены (если не нужны)
- Перемещены в `docs/legacy/` (если нужны для истории)
- НИКАК не использоваться в запуске продукта

**Затронутые места:**
- `k8s/dev/*` → переместить в `docs/legacy/k8s-dev/` или удалить
- `scripts/legacy/*` → переместить в `docs/legacy/scripts/`
- `Makefile` → убрать docker-compose ссылки или пометить как legacy
- Любые `application-dev.yml` → удалить или переместить
- Любые `.env.local` → удалить

---

## 📊 ИТЕРАЦИИ (Master Plan)

### Iteration 1: Критические фиксы (P1, P2, P4) + Legacy Cleanup
**Цель:** Устранить блокирующие проблемы, убрать legacy

**Задачи:**
1. **P1:** Удалить `assistant-core` из сборки и k8s
2. **P2:** Исправить ImagePullBackOff (embedding-service, postgres-pgvector)
3. **P4:** Исправить INTERNAL_ERROR при login/registration
4. **Legacy:** Удалить/переместить dev/legacy файлы

**Файлы:**
- `jarvis-launch.sh` (удалить "assistant-core" из списка сборки)
- `k8s/dev/services/assistant-core.yaml` (удалить или переместить)
- `k8s/overlays/local/embedding-service.yaml` (imagePullPolicy, сборка образа)
- `k8s/overlays/local/postgres-pgvector.yaml` (imagePullPolicy)
- `jarvis-launch.sh` (секция сборки образов для minikube)
- `apps/api-gateway/src/main/java/org/jarvis/apigateway/controller/AuthProxyController.java`
- `apps/security-service/src/main/resources/application.yml` (убрать хардкод паролей)
- `Makefile` (убрать docker-compose или пометить legacy)
- `k8s/dev/*` → переместить в `docs/legacy/` или удалить

**DoD:**
- ✅ Maven build без ошибок про assistant-core
- ✅ `kubectl get pods -n jarvis` - нет ImagePullBackOff
- ✅ `curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d '{"username":"test","password":"test"}'` возвращает 200/401 (не 500)
- ✅ Нет упоминаний dev/legacy в активных скриптах запуска

**Verify:**
```bash
# P1
mvn clean package -DskipTests  # не должно быть ошибок про assistant-core

# P2
kubectl get pods -n jarvis | grep -E "embedding-service|postgres-pgvector"  # должны быть Running

# P4
curl -i -X POST "http://localhost:8080/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'  # должен быть 200 или 401, не 500

# Legacy
grep -r "dev\|legacy" jarvis-launch.sh scripts/deploy.sh  # не должно быть активных ссылок
```

---

### Iteration 2: Секреты без хардкода
**Цель:** Генерация секретов при первом запуске, безопасное хранение

**Задачи:**
1. Создать `scripts/generate-secrets.sh` (генерация + сохранение)
2. Убрать хардкод из `application*.yml`
3. Убрать хардкод из `k8s/base/secrets.yaml` (использовать env vars)
4. Интеграция с OS keyring (или fallback на `~/.jarvis/secrets.json.enc`)

**Файлы:**
- `scripts/generate-secrets.sh` (новый)
- `apps/security-service/src/main/resources/application.yml`
- `apps/api-gateway/src/main/resources/application.yaml`
- Все `application*.yml` с хардкодом паролей
- `k8s/base/secrets.yaml`
- `jarvis-launch.sh` (вызов generate-secrets.sh при первом запуске)

**DoD:**
- ✅ Первый запуск генерирует секреты автоматически
- ✅ Секреты сохраняются в `~/.jarvis/secrets.json.enc` или OS keyring
- ✅ Секреты НЕ в git (проверка `.gitignore`)
- ✅ Последующие запуски используют сохранённые секреты

**Verify:**
```bash
# Проверка генерации
rm -rf ~/.jarvis/secrets.json.enc
./jarvis-launch.sh  # должен сгенерировать секреты

# Проверка отсутствия в git
git grep -i "jarvis123\|change-me" -- "*.yml" "*.yaml"  # не должно быть реальных паролей
```

---

### Iteration 3: GUI Launcher без терминала
**Цель:** Запуск кликом по иконке, GUI статус, логи в файл

**Задачи:**
1. Создать `jarvis-launcher` (GUI-friendly, скрытый терминал)
2. Обновить `jarvis.desktop` (запуск launcher'а)
3. Логирование в `~/.jarvis/logs/launcher.log`
4. Self-check и self-heal в launcher

**Файлы:**
- `jarvis-launcher` (новый, GUI)
- `jarvis.desktop` (обновить Exec)
- `jarvis-launch.sh` (можно оставить как fallback или удалить)
- Создать `~/.jarvis/logs/` при первом запуске

**DoD:**
- ✅ Клик по иконке запускает стек БЕЗ терминала
- ✅ GUI показывает статус (без терминала)
- ✅ Логи в `~/.jarvis/logs/launcher.log`
- ✅ Self-check работает (проверка minikube, readiness, self-heal)

**Verify:**
```bash
# Клик по иконке в меню Ubuntu
# Должен запуститься GUI launcher, показать статус, НЕ открывать терминал

# Проверка логов
cat ~/.jarvis/logs/launcher.log  # должны быть логи запуска
```

---

### Iteration 4: Данные в ~/.jarvis/ (персистентность)
**Цель:** Данные переживают перезапуски и обновления

**Задачи:**
1. Обновить PV/PVC в k8s манифестах (hostPath → `~/.jarvis/data/`)
2. Настроить миграции БД (безопасные)

**Файлы:**
- `k8s/base/postgres/statefulset.yaml`
- `k8s/overlays/local/postgres-pgvector.yaml`
- Все StatefulSet с volumeClaimTemplates

**DoD:**
- ✅ Данные в `~/.jarvis/data/`
- ✅ После перезапуска данные сохраняются
- ✅ Миграции БД безопасные (не ломают данные)

**Verify:**
```bash
# Проверка данных
ls -la ~/.jarvis/data/  # должны быть данные БД

# Проверка персистентности
kubectl delete pod -n jarvis -l app=postgres
# После пересоздания данные должны сохраниться
```

---

### Iteration 5: GPU деградация
**Цель:** Продукт работает без GPU, LLM отключается корректно

**Задачи:**
1. Проверка GPU в launcher
2. Отключение LLM стека при недоступности GPU
3. UI показывает статус "LLM недоступен"
4. Core функции работают без LLM

**Файлы:**
- `jarvis-launcher` (GPU check)
- `k8s/overlays/local/llm-server.yaml` (условный деплой)
- Desktop client (показ статуса LLM)

**DoD:**
- ✅ При отсутствии GPU LLM отключается
- ✅ Core функции работают без LLM
- ✅ UI показывает статус "LLM недоступен"

**Verify:**
```bash
# Симуляция отсутствия GPU
# Продукт должен запуститься, LLM отключен, core функции работают
```

---

### Iteration 6: Health checks + OpenAPI
**Цель:** Все сервисы имеют health checks, доступен swagger

**Задачи:**
1. Проверить все deployment'ы на readiness/liveness probes
2. Добавить SpringDoc OpenAPI в api-gateway
3. Настроить swagger-ui

**Файлы:**
- Все `k8s/base/*/deployment.yaml` (проверка probes)
- `apps/api-gateway/pom.xml` (добавить springdoc-openapi)
- `apps/api-gateway/src/main/resources/application.yaml` (настройка OpenAPI)

**DoD:**
- ✅ Все сервисы имеют health checks
- ✅ `http://localhost:8080/swagger-ui.html` доступен

**Verify:**
```bash
# Health checks
kubectl get pods -n jarvis  # все должны быть Ready

# OpenAPI
curl http://localhost:8080/swagger-ui.html  # должен быть доступен
```

---

### Iteration 7: HTTPS / TLS as Product Standard
**Цель:** Весь внешний трафик только через HTTPS/WSS

**Требования:**
1. ВЕСЬ внешний трафик — только HTTPS / WSS
2. TLS терминируется в API Gateway / Ingress
3. Self-signed CA для локального продукта:
   - CA + certs генерируются при первом запуске launcher'ом
   - CA устанавливается в trust store Ubuntu
   - certs передаются в Kubernetes через Secret (jarvis-tls)
4. Стандартные домены:
   - `api.jarvis.local` (API Gateway)
   - `voice.jarvis.local` (Voice Gateway)
   - launcher добавляет их в `/etc/hosts`
5. UI НЕ отключает SSL verification и НЕ использует trust-all
6. HTTP разрешён только для readiness/liveness внутри кластера

**Задачи:**
1. Создать `scripts/generate-tls-ca.sh` (генерация CA + certs)
2. Обновить launcher для генерации CA при первом запуске
3. Установка CA в Ubuntu trust store (update-ca-certificates)
4. Создать Ingress с TLS терминированием
5. Обновить API Gateway для работы с HTTPS
6. Обновить desktop-client для использования HTTPS (без -k)
7. Добавить домены в `/etc/hosts` через launcher
8. Обновить Verification Pack с HTTPS проверками

**Файлы:**
- `scripts/generate-tls-ca.sh` (новый)
- `jarvis-launcher` (генерация CA, установка в trust store, /etc/hosts)
- `k8s/base/ingress.yaml` (TLS терминирование)
- `k8s/base/api-gateway/deployment.yaml` (HTTPS поддержка)
- `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt` (HTTPS URLs)
- `scripts/verify-iteration-1.1.sh` (HTTPS проверки)

**DoD:**
- ✅ CA генерируется при первом запуске
- ✅ CA установлен в Ubuntu trust store
- ✅ Certs передаются в Kubernetes через jarvis-tls secret
- ✅ Ingress терминирует TLS
- ✅ API Gateway доступен через `https://api.jarvis.local`
- ✅ Desktop client использует HTTPS без отключения verification
- ✅ HTTP только для readiness/liveness внутри кластера
- ✅ Verification Pack проверяет HTTPS (openssl + curl без -k)

**Verify:**
```bash
# Проверка CA в trust store
openssl x509 -in /usr/local/share/ca-certificates/jarvis-ca.crt -text -noout

# Проверка доменов в /etc/hosts
grep "jarvis.local" /etc/hosts

# Проверка HTTPS endpoint (без -k)
curl https://api.jarvis.local/actuator/health

# Проверка TLS secret в K8s
kubectl -n jarvis get secret jarvis-tls

# Проверка Ingress с TLS
kubectl -n jarvis get ingress
```

---

### Iteration 8: Документация (TROUBLESHOOTING)
**Цель:** Руководство по диагностике

**Задачи:**
1. Создать `docs/TROUBLESHOOTING.md`

**Файлы:**
- `docs/TROUBLESHOOTING.md` (новый)

**DoD:**
- ✅ Есть troubleshooting guide

---

### Iteration 9: Абстракция K8s runtime (будущее)
**Цель:** Подготовка к переходу на k3s

**Задачи:**
1. Создать `scripts/k8s-runtime.sh` с абстракцией
2. Обновить launcher для использования абстракции

**Файлы:**
- `scripts/k8s-runtime.sh` (новый)
- `jarvis-launcher` (использование абстракции)

**DoD:**
- ✅ Абстракция создана, можно переключить на k3s в будущем

---

### Iteration 10: Объединение Postgres (будущая оптимизация)
**Цель:** 1 инстанс postgres с pgvector вместо 2

**Задачи:**
1. Миграция данных из postgres-pgvector в postgres
2. Обновление манифестов
3. Тестирование

**Файлы:**
- `k8s/base/postgres/statefulset.yaml`
- `k8s/overlays/local/postgres-pgvector.yaml` (удалить)

**DoD:**
- ✅ 1 инстанс postgres с pgvector
- ✅ Данные мигрированы
- ✅ Все сервисы работают

---

## 🔍 ДИАГНОСТИКА ПЕРЕД ИТЕРАЦИЯМИ

### P4: INTERNAL_ERROR при login/registration

**Найдено:**
1. **Desktop-client URLs:**
   - `LoginController.kt:70` → `$baseUrl/auth/login` (где baseUrl = `AppConfig.apiGatewayBaseUrl`)
   - `LoginController.kt:151` → `$baseUrl/auth/register`
   - `AppConfig.kt:14` → `apiGatewayBaseUrl` по умолчанию `http://localhost:8080`

2. **Gateway routes:**
   - `AuthProxyController.java:20` → `@RequestMapping({ "/auth", "/api/v1/security/auth" })`
   - `AuthProxyController.java:61` → `@PostMapping("/login")` → проксирует через `authClient.login()`
   - `AuthClient.java:15` → `@FeignClient(url = "${services.security.url:http://localhost:8088}")`
   - `AuthClient.java:21` → `@PostMapping("/auth/login")` → обращается к `http://security-service:8088/auth/login`

3. **Security config:**
   - `SecurityConfig.java:63` → `/auth/**` разрешён как `permitAll()`

**Возможные причины 500:**
- Security-service недоступен (Connection refused)
- Неправильный URL в `services.security.url`
- Проблемы с БД (миграции, подключение)
- Хардкод паролей в `application.yml` не совпадает с k8s secrets
- Feign timeout

**Действие:** Проверить логи api-gateway и security-service, проверить подключение к БД

---

### P2: ImagePullBackOff

**Найдено:**
1. **embedding-service:**
   - `kubectl describe pod` → `Error response from daemon: pull access denied for jarvis/embedding-service, repository does not exist`
   - Образ `jarvis/embedding-service:latest` не собран или не загружен в minikube

2. **postgres-pgvector:**
   - `kubectl describe pod` → `Error: ImagePullBackOff` для `ankane/pgvector:v0.7.4-pg16`
   - Возможно, нет доступа к Docker Hub или образ не существует

**Действие:**
- Для embedding-service: собрать образ в minikube docker-env, использовать `imagePullPolicy: Never`
- Для postgres-pgvector: проверить существование образа, использовать `imagePullPolicy: IfNotPresent` или загрузить в minikube

---

## ✅ КРИТЕРИИ УСПЕХА (MVP DoD)

После всех итераций продукт должен:

1. ✅ Запускаться кликом по иконке без терминала
2. ✅ Backend + базы поднимаются автоматически
3. ✅ Регистрация/логин работают стабильно (200/401/409, не 500)
4. ✅ User profile сохраняется и читается
5. ✅ Life-tracker + planner доступны
6. ✅ Orchestrator доступен (минимум health + базовая команда)
7. ✅ Логи в файл (`~/.jarvis/logs/launcher.log`, `~/.jarvis/logs/desktop.log`)
8. ✅ Self-check при старте (и попытка восстановить)
9. ✅ LLM/memory работают только если GPU доступен, иначе показывается статус "LLM недоступен"
10. ✅ Данные в `~/.jarvis/` переживают перезапуски

---

## 📝 ПРИМЕЧАНИЯ

- Все изменения должны быть production-ready (без dev-решений)
- Каждая итерация должна быть проверяемой (команды verify)
- После каждой итерации - коммит с описанием изменений
- Документация обновляется по мере необходимости

