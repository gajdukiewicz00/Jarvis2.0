# Iteration 1.4 - Stage 6: Security & Verification Pack Acceptance

**Дата:** 2026-01-05  
**Статус:** ✅ Реализовано

---

## Цель Acceptance Run

Проверить, что Stage 6 реализован корректно:
- Security hardening (маскировка секретов)
- Verify script как release gate
- Все проверки проходят на рабочем кластере

---

## Pre-requisites

1. **Backend запущен:**
   ```bash
   # Запустить backend через launcher или вручную
   kubectl get pods -n jarvis
   # Должно быть несколько pods (api-gateway, security-service, etc.)
   ```

2. **Desktop запущен (опционально для некоторых проверок):**
   ```bash
   # Запустить через launcher: Start All или Start Desktop
   # Проверить PID файл
   cat ~/.jarvis/run/desktop.pid
   ```

3. **Product install выполнен:**
   ```bash
   ./scripts/product/jarvis-install.sh
   ```

---

## Acceptance Scenarios

### A) Security Hardening - Diagnostics

**Цель:** Убедиться, что diagnostics не содержит секретов в открытом виде.

**Шаги:**
1. Запустить launcher
2. Нажать "Collect Diagnostics"
3. Дождаться открытия файла
4. Проверить содержимое

**Expected Output:**
- ✅ JWT токены маскированы: `eyJh...xyz` → `eyJh...xyz` (первые 4 + последние 4 символа)
- ✅ Пароли маскированы: `password=secret123` → `password=***MASKED***`
- ✅ JSON секреты маскированы: `"token": "abc123"` → `"token": "***MASKED***"`
- ✅ Environment variables из jarvis-secrets: только ключи, значения `***MASKED***`

**Что делать при failure:**
- Проверить, что `SecurityUtils.kt` существует
- Проверить, что `DiagnosticsCollector.kt` использует `SecurityUtils.maskSensitiveData()`
- Проверить логи launcher на ошибки

---

### B) Security Hardening - Log Viewer

**Цель:** Убедиться, что Log Viewer маскирует секреты в UI (файл не изменяется).

**Шаги:**
1. Запустить launcher
2. Перейти на вкладку "Logs"
3. Выбрать `launcher.log` или `backend-launch.log`
4. Прокрутить до строк с токенами/паролями (если есть)

**Expected Output:**
- ✅ Секреты маскированы в UI
- ✅ Исходный файл не изменён (проверить через `cat ~/.jarvis/logs/launcher.log`)

**Что делать при failure:**
- Проверить, что `LogViewer.kt` использует `SecurityUtils.maskSensitiveData()`
- Проверить, что маскировка применяется только к отображению, не к файлу

---

### C) Verify Script - Basic Run

**Цель:** Убедиться, что verify script работает без флагов.

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh
echo $?
```

**Expected Output:**
- ✅ Exit code: 0 (или не 0, но с понятными WARN, не FAIL для опциональных проверок)
- ✅ Все Stage 1-6 проверки выполнены
- ✅ Security checks (SecurityUtils, masking) проходят

**Что делать при failure:**
- Проверить вывод скрипта на конкретные FAIL
- Убедиться, что все файлы на месте
- Проверить права доступа к скрипту

---

### D) Verify Script - With --require-install

**Цель:** Убедиться, что verify требует product install.

**Команда:**
```bash
# БЕЗ install
rm -rf ~/.jarvis/app/
./scripts/verify-iteration-1.4.sh --require-install
echo $?
# Ожидаемо: не 0

# С install
./scripts/product/jarvis-install.sh
./scripts/verify-iteration-1.4.sh --require-install
echo $?
# Ожидаемо: 0
```

**Expected Output:**
- ✅ Без install: FAIL на проверках product paths
- ✅ С install: PASS на проверках product paths

**Что делать при failure:**
- Проверить, что `--require-install` правильно обрабатывается
- Проверить, что product install создал все необходимые файлы

---

### E) Verify Script - With --require-backend

**Цель:** Убедиться, что verify требует запущенный backend.

**Команда:**
```bash
# Backend НЕ запущен
kubectl scale deploy --all --replicas=0 -n jarvis
./scripts/verify-iteration-1.4.sh --require-backend
echo $?
# Ожидаемо: не 0

# Backend запущен
# (запустить через launcher или вручную)
./scripts/verify-iteration-1.4.sh --require-backend
echo $?
# Ожидаемо: 0
```

**Expected Output:**
- ✅ Без backend: FAIL на проверке "Backend is running"
- ✅ С backend: PASS на проверке "Backend is running"

**Что делать при failure:**
- Проверить, что `kubectl get pods -n jarvis` работает
- Проверить, что backend действительно запущен
- Проверить namespace (должен быть `jarvis`)

---

### F) Verify Script - Stage 5 Process Management

**Цель:** Убедиться, что проверки Stage 5 работают.

**Шаги:**
1. Запустить backend и desktop через launcher (Start All)
2. Проверить desktop.pid:
   ```bash
   cat ~/.jarvis/run/desktop.pid
   ps -p $(cat ~/.jarvis/run/desktop.pid)
   ```
3. Запустить verify:
   ```bash
   ./scripts/verify-iteration-1.4.sh --require-backend
   ```

**Expected Output:**
- ✅ Desktop PID file exists and process is alive
- ✅ Нет stale PID warnings

**Что делать при failure:**
- Проверить, что desktop.pid создан
- Проверить, что процесс действительно запущен
- Проверить права доступа к PID файлу

---

### G) Verify Script - Stop All Cleanup

**Цель:** Убедиться, что после Stop All нет stale PID.

**Шаги:**
1. Запустить Start All
2. Дождаться READY
3. Запустить Stop All
4. Проверить PID файлы:
   ```bash
   ls -la ~/.jarvis/run/*.pid
   ```
5. Запустить verify:
   ```bash
   ./scripts/verify-iteration-1.4.sh
   ```

**Expected Output:**
- ✅ Desktop PID файл удалён (или пустой)
- ✅ Backend PID файл удалён (или пустой)
- ✅ Нет stale PID warnings

**Что делать при failure:**
- Проверить, что Stop All действительно остановил процессы
- Проверить, что cleanupStalePids() вызывается
- Проверить логи launcher на ошибки cleanup

---

### H) Verify Script - Assistant-Core Removal

**Цель:** Убедиться, что assistant-core полностью удалён.

**Команда:**
```bash
./scripts/verify-iteration-1.4.sh --require-backend
```

**Expected Output:**
- ✅ No assistant-core pods in cluster
- ✅ No assistant-core references in k8s manifests

**Что делать при failure:**
- Удалить assistant-core вручную:
  ```bash
  kubectl delete deploy assistant-core -n jarvis
  kubectl delete svc assistant-core -n jarvis
  ```
- Проверить k8s manifests:
  ```bash
  grep -r "assistant-core" k8s/
  # Удалить или закомментировать найденные ссылки
  ```

---

## Full Acceptance Run

**Полный прогон всех проверок:**

```bash
# 1. Pre-requisites
./scripts/product/jarvis-install.sh
# Запустить backend через launcher

# 2. Basic verify
./scripts/verify-iteration-1.4.sh
echo "Exit code: $?"

# 3. With install requirement
./scripts/verify-iteration-1.4.sh --require-install
echo "Exit code: $?"

# 4. With backend requirement
./scripts/verify-iteration-1.4.sh --require-backend
echo "Exit code: $?"

# 5. Full check
./scripts/verify-iteration-1.4.sh --require-install --require-backend
echo "Exit code: $?"
```

**Expected Final Result:**
- ✅ Exit code: 0
- ✅ Все проверки PASS
- ✅ Минимум WARN (опциональные проверки)

---

## Troubleshooting

### Diagnostics содержит секреты
- Проверить `SecurityUtils.kt`: методы `maskJwt()`, `maskSecrets()`, `maskEnvVarValue()`
- Проверить `DiagnosticsCollector.kt`: использование `SecurityUtils.maskSensitiveData()`
- Проверить логи launcher на ошибки

### Verify script падает на --require-backend
- Проверить, что backend запущен: `kubectl get pods -n jarvis`
- Проверить, что namespace правильный: `kubectl get ns | grep jarvis`
- Проверить права доступа к kubectl

### Desktop PID не создаётся
- Проверить, что desktop запущен: `ps aux | grep desktop-client`
- Проверить права на `~/.jarvis/run/`
- Проверить логи launcher на ошибки записи PID

### Assistant-core всё ещё в кластере
- Удалить вручную: `kubectl delete deploy assistant-core -n jarvis`
- Проверить manifests: `grep -r "assistant-core" k8s/`
- Убедиться, что `jarvis-launch.sh` не применяет assistant-core

---

**Stage 6 готов к acceptance run!**


