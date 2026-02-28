# Iteration: Stage 16 - Local Secrets & Prod-only Deploy

**Date:** $(date -Is)  
**Status:** ✅ READY FOR ACCEPTANCE RUN  
**Goal:** Ensure secrets never enter git or release archives, only stored locally

---

## Overview

Stage 16 implements production-grade secret management:
- Secrets stored only locally (`~/.jarvis/secrets/` or `./secrets/`)
- Never committed to git or included in release archives
- Kubernetes secrets created via `jarvis-secrets-apply.sh`
- Fail-fast if secrets missing before deploy
- Verify script detects secret leaks

---

## Deliverables

### 1. Security Policy

**File:** `docs/security/SECRETS_POLICY.md`

**Contents:**
- Core principles (never commit secrets, local storage only)
- Required secret keys by service
- How to create secrets locally
- Security best practices
- Troubleshooting guide

---

### 2. Secrets Template

**File:** `secrets/secrets.example.env`

**Format:**
```bash
POSTGRES_USER=jarvis
POSTGRES_PASSWORD=CHANGE-ME-STRONG-PASSWORD-MIN-16-CHARS
JWT_SECRET=CHANGE-ME-GENERATE-WITH-openssl-rand-base64-32
# ... other keys
```

**Usage:**
```bash
cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
nano ~/.jarvis/secrets/secrets.env  # Edit with real values
chmod 600 ~/.jarvis/secrets/secrets.env
```

---

### 3. Secrets Apply Script

**File:** `scripts/product/jarvis-secrets-apply.sh`

**Features:**
- Reads from `~/.jarvis/secrets/secrets.env` (or `./secrets/secrets.env` with `--local`)
- Creates/updates Kubernetes secret `jarvis-secrets` in namespace `jarvis`
- Idempotent (safe to run multiple times)
- Never prints secret values in logs (only key names)
- Validates required keys
- Clear error messages if secrets file missing

**Usage:**
```bash
# Default: ~/.jarvis/secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh

# Use local ./secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh --local

# Custom directory
./scripts/product/jarvis-secrets-apply.sh --dir=/path/to/secrets
```

---

### 4. Launch Script Updates

**File:** `jarvis-launch.sh`

**Changes:**
- **Pre-deploy check:** Verifies `jarvis-secrets` exists in cluster before deploying services
- **Fail-fast:** Exits with clear instructions if secrets missing
- **Removed:** `kubectl apply -f k8s/base/secrets.yaml` (no longer applies secrets from git)
- **Removed:** `kubectl apply -f k8s/overlays/prod/secrets-local.yaml` (no longer applies secrets from git)

**Behavior:**
```bash
./jarvis-launch.sh
# If secrets missing:
# ❌ Required secrets not found: jarvis-secrets
# 💡 To create secrets:
#   1. cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
#   2. nano ~/.jarvis/secrets/secrets.env
#   3. ./scripts/product/jarvis-secrets-apply.sh
```

---

### 5. Verify Script Updates

**File:** `scripts/verify-iteration-1.4.sh`

**Stage 16 Checks:**
- **FAIL** if private keys found in repository (`BEGIN PRIVATE KEY`)
- **FAIL** if `password=`, `token=`, `aws_access_key` found in non-example files
- **FAIL** if `.env` files exist without `.example` suffix
- **FAIL** if `secrets*.yaml` files exist in `k8s/` (except legacy)
- **WARN** if `k8s/base/secrets.yaml` contains placeholder values
- **With `--require-backend`:** Checks `jarvis-secrets` exists in cluster and has required keys

**Run:**
```bash
./scripts/verify-iteration-1.4.sh  # Default checks
./scripts/verify-iteration-1.4.sh --require-backend  # Includes cluster checks
```

---

### 6. .gitignore Updates

**File:** `.gitignore`

**Added:**
```
secrets/
!secrets/.gitkeep
!secrets/*.example.env
secrets*.yaml
!secrets*.example.yaml
~/.jarvis/secrets/
```

---

## Files Changed

1. `docs/security/SECRETS_POLICY.md` - Security policy and guidelines
2. `secrets/secrets.example.env` - Secrets template
3. `secrets/.gitkeep` - Keep secrets/ directory in git
4. `scripts/product/jarvis-secrets-apply.sh` - Secrets apply script
5. `jarvis-launch.sh` - Pre-deploy secrets check, removed git-based secrets
6. `scripts/verify-iteration-1.4.sh` - Stage 16 security checks
7. `.gitignore` - Ignore secrets files
8. `docs/ITERATION_STAGE16_LOCAL_SECRETS.md` - This file

---

## How to Use

### First-Time Setup

1. **Copy template:**
   ```bash
   mkdir -p ~/.jarvis/secrets
   cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
   ```

2. **Edit with your values:**
   ```bash
   nano ~/.jarvis/secrets/secrets.env
   ```
   
   **Generate strong secrets:**
   ```bash
   # JWT Secret (32 bytes)
   openssl rand -base64 32
   
   # Password (20 chars)
   openssl rand -base64 24 | tr -d "=+/" | cut -c1-20
   ```

3. **Set permissions:**
   ```bash
   chmod 600 ~/.jarvis/secrets/secrets.env
   ```

4. **Apply secrets:**
   ```bash
   ./scripts/product/jarvis-secrets-apply.sh
   ```

5. **Launch:**
   ```bash
   ./jarvis-launch.sh
   ```

### Updating Secrets

```bash
# Edit secrets file
nano ~/.jarvis/secrets/secrets.env

# Re-apply
./scripts/product/jarvis-secrets-apply.sh
```

---

## Acceptance Criteria

### ✅ Secrets Never in Git

- [ ] No `secrets*.yaml` files in `k8s/` (except legacy)
- [ ] No `.env` files without `.example` suffix
- [ ] No private keys (`BEGIN PRIVATE KEY`) in repository
- [ ] No `password=`, `token=`, `aws_access_key` in non-example files
- [ ] `secrets/` directory in `.gitignore`

### ✅ Local Secrets Work

- [ ] `jarvis-secrets-apply.sh` creates/updates Kubernetes secret
- [ ] Script never prints secret values in logs
- [ ] Script validates required keys
- [ ] Script provides clear error messages

### ✅ Launch Script Fail-Fast

- [ ] `jarvis-launch.sh` checks for `jarvis-secrets` before deploy
- [ ] Exits with clear instructions if secrets missing
- [ ] Does not apply secrets from git

### ✅ Verify Script Detects Leaks

- [ ] Stage 16 checks detect private keys
- [ ] Stage 16 checks detect password/token patterns
- [ ] Stage 16 checks detect .env files
- [ ] With `--require-backend`: checks secrets in cluster

---

## Verification

### 1. Check Secrets Not in Git

```bash
# Should return empty (or only legacy files)
find k8s/ -name "secrets*.yaml" | grep -v legacy

# Should return empty
find . -name ".env" | grep -v ".example"
```

### 2. Create and Apply Secrets

```bash
# Create secrets file
cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
nano ~/.jarvis/secrets/secrets.env  # Edit with real values
chmod 600 ~/.jarvis/secrets/secrets.env

# Apply
./scripts/product/jarvis-secrets-apply.sh
```

**Expected:**
- Script reads secrets file
- Validates required keys
- Creates/updates Kubernetes secret
- Never prints secret values

### 3. Verify Script

```bash
./scripts/verify-iteration-1.4.sh
```

**Expected:**
- No FAIL from Stage 16 checks
- No secrets found in repository

### 4. Launch with Secrets

```bash
./jarvis-launch.sh
```

**Expected:**
- Pre-deploy check passes (secrets found)
- Services deploy successfully
- No errors about missing secrets

### 5. Launch Without Secrets

```bash
# Remove secret from cluster
kubectl delete secret jarvis-secrets -n jarvis

# Try launch
./jarvis-launch.sh
```

**Expected:**
- Fail-fast with clear error message
- Instructions to create secrets
- Exit code 1

---

## Troubleshooting

### "Secrets file not found"

**Solution:**
```bash
cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
nano ~/.jarvis/secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh
```

### "Missing required keys"

**Solution:**
- Check `secrets/secrets.example.env` for all required keys
- Add missing keys to your `secrets.env` file

### "Permission denied"

**Solution:**
```bash
chmod 600 ~/.jarvis/secrets/secrets.env
```

### "Secret verification failed"

**Solution:**
- Check `kubectl get secret jarvis-secrets -n jarvis`
- Re-run `jarvis-secrets-apply.sh`
- Check namespace exists: `kubectl get namespace jarvis`

---

## Security Notes

1. **File Permissions:**
   - Secrets file should be `600` (read/write for owner only)
   - Never share secrets file

2. **Secret Generation:**
   - Use `openssl rand -base64 32` for JWT secrets
   - Use strong passwords (16+ chars, mixed case, numbers, symbols)

3. **Backup:**
   - Encrypt secrets file before backup: `gpg --encrypt secrets.env`
   - Store backup in secure location (not in git)

4. **Rotation:**
   - Rotate secrets regularly (every 90 days or as per policy)
   - Update secrets file and re-apply

---

## Migration from Git-based Secrets

If you have `k8s/base/secrets.yaml` with real values:

1. **Extract values (if needed):**
   ```bash
   kubectl get secret jarvis-secrets -n jarvis -o yaml > /tmp/secrets-backup.yaml
   ```

2. **Create local secrets file:**
   ```bash
   cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
   # Manually copy values from backup (be careful!)
   ```

3. **Remove from git:**
   ```bash
   git rm k8s/base/secrets.yaml
   git commit -m "Remove secrets from git (Stage 16: local secrets only)"
   ```

4. **Verify:**
   ```bash
   ./scripts/verify-iteration-1.4.sh
   ```

---

## References

- `docs/security/SECRETS_POLICY.md` - Detailed security policy
- `secrets/secrets.example.env` - Secrets template
- `scripts/product/jarvis-secrets-apply.sh` - Secrets apply script

