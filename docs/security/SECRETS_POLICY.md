# Jarvis 2.0 - Secrets Policy

**Date:** $(date -Is)  
**Status:** PRODUCTION STANDARD  
**Goal:** Ensure secrets never enter git or release archives, only stored locally

---

## Core Principles

1. **NEVER commit secrets to git**
   - No passwords, tokens, keys, certificates in repository
   - No `.env` files with real values (only `.example` templates)
   - No `secrets*.yaml` files with real values in git

2. **NEVER include secrets in release archives**
   - Release archives are public/distributable
   - Secrets must be created locally by each user

3. **Local storage only**
   - Secrets stored in `~/.jarvis/secrets/` (user's home directory)
   - Local paths outside the repo are allowed if needed

4. **Kubernetes Secrets**
   - Secrets applied to cluster via `kubectl create secret` or `kubectl apply`
   - Never use `kubectl apply -f` with secret files from git

---

## Required Secret Keys

### Core Services

**jarvis-secrets** (namespace: `jarvis`)

#### PostgreSQL
- `POSTGRES_USER` - Database username (default: `jarvis`)
- `POSTGRES_PASSWORD` - Database password (min 16 chars, use strong password)
- `SPRING_DATASOURCE_USERNAME` - Spring datasource username (usually same as POSTGRES_USER)
- `SPRING_DATASOURCE_PASSWORD` - Spring datasource password (usually same as POSTGRES_PASSWORD)
- `SPRING_DATASOURCE_URL` - JDBC URL (e.g., `jdbc:postgresql://postgres.jarvis.svc.cluster.local:5432/jarvis`)

#### RabbitMQ
- `RABBITMQ_DEFAULT_USER` - RabbitMQ username (default: `jarvis`)
- `RABBITMQ_DEFAULT_PASS` - RabbitMQ password (min 16 chars)
- `RABBITMQ_ERLANG_COOKIE` - Erlang cookie for clustering (random string, min 20 chars)
- `SPRING_RABBITMQ_USERNAME` - Spring RabbitMQ username (usually same as RABBITMQ_DEFAULT_USER)
- `SPRING_RABBITMQ_PASSWORD` - Spring RabbitMQ password (usually same as RABBITMQ_DEFAULT_PASS)

#### JWT Authentication
- `JWT_SECRET` - JWT signing secret (min 256 bits / 32 bytes, use `openssl rand -base64 32`)

#### Optional: Encryption
- `ENCRYPTION_KEY` - Encryption key for sensitive data (32 bytes, use `openssl rand -base64 32`)

### TLS/HTTPS

**jarvis-tls** (namespace: `jarvis`)

- Generated automatically by `jarvis-launch.sh` (self-signed certificates)
- Or manually via `kubectl create secret tls jarvis-tls --cert=jarvis.crt --key=jarvis.key -n jarvis`

---

## Creating Secrets Locally

### Method 1: Using secrets.example.env Template

1. Copy template:
   ```bash
   cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
   ```

2. Edit `~/.jarvis/secrets/secrets.env` with your values:
   ```bash
   nano ~/.jarvis/secrets/secrets.env
   ```

3. Apply secrets:
   ```bash
   ./scripts/product/jarvis-secrets-apply.sh
   ```

### Method 2: Using kubectl Directly

```bash
kubectl create secret generic jarvis-secrets \
  --from-literal=POSTGRES_USER=jarvis \
  --from-literal=POSTGRES_PASSWORD=<set-locally> \
  --from-literal=JWT_SECRET=$(openssl rand -base64 32) \
  -n jarvis
```

### Method 3: Using Environment Variables

```bash
export POSTGRES_PASSWORD="<set-locally>"
export JWT_SECRET=$(openssl rand -base64 32)
# ... set other variables ...

./scripts/product/jarvis-secrets-apply.sh
```

---

## Secret File Format

Secrets file (`~/.jarvis/secrets/secrets.env`) format:

```bash
# PostgreSQL
POSTGRES_USER=jarvis
POSTGRES_PASSWORD=<set-locally>
SPRING_DATASOURCE_USERNAME=jarvis
SPRING_DATASOURCE_PASSWORD=<set-locally>
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres.jarvis.svc.cluster.local:5432/jarvis

# RabbitMQ
RABBITMQ_DEFAULT_USER=jarvis
RABBITMQ_DEFAULT_PASS=<set-locally>
RABBITMQ_ERLANG_COOKIE=<set-locally>
SPRING_RABBITMQ_USERNAME=jarvis
SPRING_RABBITMQ_PASSWORD=<set-locally>

# JWT
JWT_SECRET=<set-locally>

# Optional: Encryption
ENCRYPTION_KEY=<set-locally>
```

**Important:**
- No quotes needed (unless value contains spaces)
- Empty lines and lines starting with `#` are ignored
- File must have `600` permissions (read/write for owner only)

---

## Security Best Practices

1. **File Permissions:**
   ```bash
   chmod 600 ~/.jarvis/secrets/secrets.env
   ```

2. **Generate Strong Secrets:**
   ```bash
   # JWT Secret (32 bytes)
   openssl rand -base64 32
   
   # Password (16+ chars, mixed case, numbers, symbols)
   openssl rand -base64 24 | tr -d "=+/" | cut -c1-20
   ```

3. **Never Share Secrets:**
   - Don't email secrets
   - Don't paste in chat/forums
   - Don't commit to git
   - Don't include in release archives

4. **Rotate Secrets Regularly:**
   - Change passwords every 90 days (or as per policy)
   - Rotate JWT secrets if compromised

5. **Backup Secrets Securely:**
   - Use encrypted backup (e.g., `gpg --encrypt secrets.env`)
   - Store backup in secure location (not in git)

---

## Verification

### Check Secrets Exist in Cluster

```bash
kubectl get secret jarvis-secrets -n jarvis
```

### Verify Secret Keys

```bash
kubectl get secret jarvis-secrets -n jarvis -o jsonpath='{.data}' | jq 'keys'
```

### Check Secret Values (for debugging, be careful!)

```bash
kubectl get secret jarvis-secrets -n jarvis -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d
```

---

## Troubleshooting

### "Secret not found" Error

**Symptom:** `jarvis-launch.sh` fails with "Required secrets not found"

**Solution:**
1. Create secrets file: `cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env`
2. Edit with your values
3. Apply: `./scripts/product/jarvis-secrets-apply.sh`

### "Permission denied" Error

**Symptom:** Cannot read `~/.jarvis/secrets/secrets.env`

**Solution:**
```bash
chmod 600 ~/.jarvis/secrets/secrets.env
```

### "Invalid secret format" Error

**Symptom:** `jarvis-secrets-apply.sh` fails to parse secrets file

**Solution:**
- Check file format (KEY=value, one per line)
- Ensure no quotes around values (unless needed)
- Check for special characters (escape if needed)

---

## Migration from Git-based Secrets

If you have secrets in git (e.g., `k8s/base/secrets.yaml`):

1. **Extract values:**
   ```bash
   kubectl get secret jarvis-secrets -n jarvis -o yaml > /tmp/secrets-backup.yaml
   ```

2. **Create local secrets file:**
   ```bash
   # Extract values from backup (be careful!)
   # Create ~/.jarvis/secrets/secrets.env with extracted values
   ```

3. **Remove from git:**
   ```bash
   git rm k8s/base/secrets.yaml
   git commit -m "Remove secrets from git (moved to local storage)"
   ```

4. **Update .gitignore:**
   ```bash
   echo "secrets/" >> .gitignore
   echo "*.env" >> .gitignore
   echo "!*.example.env" >> .gitignore
   ```

---

## References

- Kubernetes Secrets: https://kubernetes.io/docs/concepts/configuration/secret/
- OpenSSL Random: `man openssl rand`
- File Permissions: `man chmod`


