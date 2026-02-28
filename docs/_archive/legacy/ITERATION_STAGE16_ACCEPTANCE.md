# Stage 16: Local Secrets & Prod-only Deploy — Acceptance Run

**Date:** [TO BE FILLED]  
**Status:** ⬜ PENDING / ✅ ACCEPTED / ❌ FAIL

---

## Acceptance Steps

### 1. Verify Secrets Not in Git

```bash
# Should return empty (or only legacy files)
find k8s/ -name "secrets*.yaml" | grep -v legacy

# Should return empty
find . -name ".env" | grep -v ".example"
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Output:**
```
[TO BE FILLED]
```

---

### 2. Create and Apply Secrets

```bash
# Create secrets file
mkdir -p ~/.jarvis/secrets
cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
nano ~/.jarvis/secrets/secrets.env  # Edit with real values
chmod 600 ~/.jarvis/secrets/secrets.env

# Apply
./scripts/product/jarvis-secrets-apply.sh
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Expected:**
- Script reads secrets file
- Validates required keys
- Creates/updates Kubernetes secret `jarvis-secrets`
- Never prints secret values (only key names)

**Output:**
```
[TO BE FILLED]
```

---

### 3. Verify Script (Stage 16 Checks)

```bash
./scripts/verify-iteration-1.4.sh
echo $?
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Exit Code:** `[TO BE FILLED]`

**Stage 16 Checks:**
- [ ] No private keys found in repository
- [ ] No password=, token= patterns found
- [ ] No .env files without .example
- [ ] secrets/ in .gitignore
- [ ] No secrets*.yaml in k8s/ (except legacy)

**Verify Output (Stage 16 section):**
```
[TO BE FILLED]
```

---

### 4. Verify Script (with --require-backend)

```bash
./scripts/verify-iteration-1.4.sh --require-backend
echo $?
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Exit Code:** `[TO BE FILLED]`

**Checks:**
- [ ] jarvis-secrets exists in cluster
- [ ] All required keys present

**Output:**
```
[TO BE FILLED]
```

---

### 5. Launch with Secrets (Happy Path)

```bash
./jarvis-launch.sh
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Expected:**
- Pre-deploy check passes (secrets found)
- Services deploy successfully
- No errors about missing secrets

**Output (pre-deploy check):**
```
[TO BE FILLED]
```

---

### 6. Launch Without Secrets (Fail-Fast)

```bash
# Remove secret from cluster
kubectl delete secret jarvis-secrets -n jarvis

# Try launch
./jarvis-launch.sh
echo $?
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Exit Code:** `[TO BE FILLED]` (should be 1)

**Expected:**
- Fail-fast with clear error message
- Instructions to create secrets
- Exit code 1

**Output:**
```
[TO BE FILLED]
```

---

## Summary

| Check | Status | Notes |
|-------|--------|-------|
| Secrets not in git | ⬜ | |
| Secrets apply script works | ⬜ | |
| Verify script (default) | ⬜ | |
| Verify script (--require-backend) | ⬜ | |
| Launch with secrets | ⬜ | |
| Launch without secrets (fail-fast) | ⬜ | |

---

## Verdict

**⬜ PENDING** — Acceptance run required

**Criteria:**
- ✅ Secrets never in git
- ✅ Secrets apply script works (idempotent, never prints values)
- ✅ Verify script detects secret leaks
- ✅ Launch script fails-fast if secrets missing
- ✅ Launch script succeeds if secrets present

**If all criteria met → ✅ ACCEPTED**

---

## Notes

- Secrets stored only locally (`~/.jarvis/secrets/`)
- Never committed to git or included in release archives
- Kubernetes secrets created via `jarvis-secrets-apply.sh`
- Production-first approach (no docker-compose, no port-forward by default)


