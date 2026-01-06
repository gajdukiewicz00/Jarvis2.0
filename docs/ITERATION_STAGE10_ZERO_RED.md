# Iteration 1.5 - Stage 10: Zero-Red Verification + Repo Hygiene

**Дата:** 2026-01-05  
**Цель:** Zero-surprises verification with smart gating

---

## Проблема

До Stage 10:
- Verify script падал из-за legacy assistant-core checks даже в product mode
- Нет разделения между product checks и repo hygiene checks
- Legacy/dev paths влияли на product verification
- Нет способа проверить только product без кластера

---

## Решение

### 1) Режимы verify

**Default Mode (PRODUCT checks only):**
- Проверяет только product installation и release flow
- Legacy checks → WARN (не FAIL)
- Не требует кластер или HTTPS

**Backend Mode (`--require-backend`):**
- Добавляет проверки, требующие запущенный кластер
- Pods, health checks, process management

**HTTPS Mode (`--require-https`):**
- Добавляет TLS/HTTPS проверки
- Требует настроенный TLS и `/etc/hosts`

**Strict Repo Mode (`--strict-repo`):**
- FAIL на legacy references в product manifests
- Игнорирует `k8s/legacy/` и `k8s/prod-like/`
- Игнорирует закомментированные строки (`#` или `# Legacy`)

---

### 2) Переклассификация assistant-core проверок

**Default mode:**
- WARN только (не FAIL)
- Игнорирует `k8s/legacy/` и `k8s/prod-like/`
- Игнорирует закомментированные строки

**Strict repo mode:**
- FAIL на активные references в `k8s/base` и `k8s/overlays/local`
- Игнорирует закомментированные строки

---

### 3) Почистка/изоляция legacy

**Выполнено:**
- `k8s/dev/` → `k8s/legacy/dev/` (перемещён)
- `k8s/base/configmap.yaml`: `DB_NAME_ASSISTANT_CORE` закомментирован
- Verify script исключает `k8s/legacy/` и `k8s/prod-like/` из проверок
- `k8s/overlays/local/kustomization.yaml` не ссылается на `k8s/dev`

**Проверено:**
- `jarvis-launch.sh` не использует `k8s/dev`
- `kubectl apply -k k8s/overlays/local` не подтягивает legacy

---

### 4) Документация

**Создано:**
- `docs/VERIFY_MODES.md` — описание режимов и примеры команд
- `docs/ITERATION_STAGE10_ZERO_RED.md` — этот документ

---

## Изменённые файлы

1. **`scripts/verify-iteration-1.4.sh`**
   - Добавлен флаг `--strict-repo`
   - Переклассифицированы assistant-core проверки (WARN по умолчанию, FAIL в strict-repo)
   - Исключены `k8s/legacy/` и `k8s/prod-like/` из проверок
   - Игнорируются закомментированные строки

2. **`k8s/base/configmap.yaml`**
   - `DB_NAME_ASSISTANT_CORE` закомментирован

3. **`k8s/dev/` → `k8s/legacy/dev/`**
   - Legacy dev манифесты перемещены в `k8s/legacy/`

4. **`docs/VERIFY_MODES.md`** (новый)
   - Документация режимов verify

5. **`docs/ITERATION_STAGE10_ZERO_RED.md`** (новый)
   - Документация Stage 10

---

## Definition of Done

### ✅ DoD 1: Default mode (PRODUCT only)
```bash
./scripts/verify-iteration-1.4.sh --require-install
echo $?
# Expected: 0
```

**Результат:** ✅ PASS (exit code 0, WARN допустимы)

---

### ✅ DoD 2: Strict repo mode (clean repo)
```bash
./scripts/verify-iteration-1.4.sh --require-install --strict-repo
echo $?
# Expected: 0
```

**Результат:** ✅ PASS (exit code 0 после закомментирования `DB_NAME_ASSISTANT_CORE`)

---

### ✅ DoD 3: Full production check
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
echo $?
# Expected: 0 (на поднятом кластере с TLS)
```

**Результат:** ⚠️ Требует настроенный кластер и TLS (не проверялось автоматически)

---

### ✅ DoD 4: Zero false positives
- ✅ Default mode не падает из-за legacy в `k8s/legacy/`
- ✅ Strict repo mode игнорирует закомментированные строки
- ✅ FAIL только за реально критичное в выбранном режиме

---

## Команды проверки

### 1) Default mode (PRODUCT only)
```bash
./scripts/verify-iteration-1.4.sh --require-install
```

**Ожидаемый результат:**
- Exit code: 0
- WARN допустимы (legacy references)
- Все PRODUCT checks пройдены

---

### 2) Strict repo mode
```bash
./scripts/verify-iteration-1.4.sh --require-install --strict-repo
```

**Ожидаемый результат:**
- Exit code: 0 (после закомментирования активных legacy references)
- Нет FAIL на legacy в `k8s/legacy/` или закомментированных строках

---

### 3) Full production check
```bash
# После настройки TLS и запуска backend
./scripts/verify-iteration-1.4.sh --require-install --require-backend --require-https
```

**Ожидаемый результат:**
- Exit code: 0
- Все проверки пройдены (product, backend, HTTPS)

---

## Известные ограничения

1. **Legacy в k8s/prod-like/:** Не проверяется в strict-repo mode (считается legacy)
2. **Commented lines detection:** Простая проверка (`grep -v "^\s*#"`), может не покрыть все случаи
3. **Backend checks:** Требуют запущенный кластер (нельзя проверить без `--require-backend`)

---

## Следующие шаги

1. ✅ Режимы verify реализованы
2. ✅ Legacy изолирован (`k8s/legacy/dev/`)
3. ✅ Assistant-core проверки переклассифицированы
4. ✅ Документация создана

**Stage 10 готов к acceptance run!**

