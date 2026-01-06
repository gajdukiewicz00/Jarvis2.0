# Verification Script Modes

**Script:** `scripts/verify-iteration-1.4.sh`

---

## Режимы проверки

### Default Mode (PRODUCT checks only)

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh --require-install
```

**Что проверяется:**
- ✅ Launcher module (JAR, scripts, desktop file)
- ✅ Product installation (`~/.jarvis/app/`)
- ✅ Version management (VERSION file, manifest match)
- ✅ Desktop integration (desktop file, locks)
- ✅ Release & Update Flow (backup, release script)

**Что НЕ проверяется:**
- ❌ Backend cluster (pods, health checks)
- ❌ HTTPS/TLS setup
- ❌ Legacy/repo hygiene (assistant-core references)

**Legacy checks:**
- ⚠️ WARN только (не FAIL) для assistant-core references в product manifests
- Legacy/dev paths игнорируются

**Exit code:** 0 если все PRODUCT checks прошли (WARN допустимы)

---

### Backend Mode (`--require-backend`)

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend
```

**Что проверяется:**
- ✅ Все проверки из Default Mode
- ✅ Backend pods running (core services)
- ✅ Health checks (api-gateway, security-service)
- ✅ Process management (desktop PID, backend PID)

**Требования:**
- Kubernetes cluster должен быть запущен
- Backend должен быть развёрнут (`kubectl get pods -n jarvis`)

**Exit code:** 0 если все проверки прошли

---

### HTTPS/TLS Mode (`--require-https`)

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
```

**Что проверяется:**
- ✅ Все проверки из Default + Backend Mode
- ✅ `/etc/hosts` entries (api.jarvis.local, voice.jarvis.local)
- ✅ Ingress configuration
- ✅ TLS secret (`jarvis-tls`)
- ✅ CA trust store installation
- ✅ HTTPS endpoint доступность (curl без -k)
- ✅ SSL certificate verification (openssl)
- ✅ HTTP → HTTPS redirect
- ✅ Stage 8: K8s Runtime Hardening (Ingress default, port-forward conditional)

**Требования:**
- Kubernetes cluster запущен
- Backend развёрнут
- TLS настроен (`sudo ./scripts/product/jarvis-install-tls.sh`)
- `/etc/hosts` настроен (`sudo ./scripts/product/jarvis-setup-hosts.sh`)

**Exit code:** 0 если все проверки прошли

---

### Strict Repo Mode (`--strict-repo`)

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --strict-repo
```

**Что проверяется:**
- ✅ Все проверки из Default Mode
- ✅ **FAIL** на assistant-core references в product manifests (k8s/base, k8s/overlays/local)
- ✅ **FAIL** на assistant-core pods (если `--require-backend` также указан)

**Исключения:**
- Закомментированные строки (начинающиеся с `#` или содержащие `# Legacy`) игнорируются
- `k8s/legacy/` и `k8s/prod-like/` не проверяются

**Exit code:** 0 только если repo полностью чист от legacy references

---

## Комбинации режимов

### Полная проверка продукта (production-ready)
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
```

**Ожидаемый результат:** Exit code 0, все проверки пройдены

---

### Проверка после install (без кластера)
```bash
./scripts/verify-iteration-1.4.sh --require-install
```

**Ожидаемый результат:** Exit code 0, WARN допустимы

---

### Проверка repo hygiene (для разработчиков)
```bash
./scripts/verify-iteration-1.4.sh --require-install --strict-repo
```

**Ожидаемый результат:** Exit code 0 только если нет legacy references

---

## Примеры использования

### После fresh install
```bash
./scripts/product/jarvis-install.sh
./scripts/verify-iteration-1.4.sh --require-install
# Expected: exit 0
```

### После upgrade
```bash
./scripts/product/jarvis-install.sh  # Upgrade
./scripts/verify-iteration-1.4.sh --require-install
# Expected: exit 0, backup directory exists
```

### Перед release
```bash
./scripts/verify-iteration-1.4.sh --require-install --strict-repo
# Expected: exit 0 (clean repo)
```

### После полного развёртывания
```bash
./jarvis-launch.sh
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
# Expected: exit 0 (production-ready)
```

---

## Troubleshooting

### Exit code != 0

**Проблема:** Verify падает с FAIL

**Решение:**
1. Проверь вывод verify script — там указаны конкретные FAIL причины
2. Для PRODUCT checks: убедись, что `./scripts/product/jarvis-install.sh` выполнен
3. Для BACKEND checks: убедись, что кластер запущен и backend развёрнут
4. Для HTTPS checks: выполни `sudo ./scripts/product/jarvis-install-tls.sh` и `sudo ./scripts/product/jarvis-setup-hosts.sh`
5. Для STRICT-REPO checks: удали или закомментируй legacy references в `k8s/base` и `k8s/overlays/local`

---

### WARN вместо FAIL

**Проблема:** Verify показывает WARN, но exit code 0

**Решение:**
- WARN допустимы в default mode (legacy references в dev/legacy paths)
- Если нужен строгий режим: используй `--strict-repo`

---

### Legacy references в strict-repo mode

**Проблема:** `--strict-repo` падает на закомментированных строках

**Решение:**
- Verify игнорирует строки, начинающиеся с `#` или содержащие `# Legacy`
- Убедись, что активные (не закомментированные) строки удалены или закомментированы

---

**Документация обновлена: 2026-01-05**

