# Jarvis 2.0 — Kubernetes Dev Deployment

## Quick Start

```bash
# Option 1: Use the startup script (recommended)
./scripts/jarvis-k8s-up.sh

# Option 2: Manual deployment (see below)
```

## Manual Deployment Order

**Important**: Apply resources in this exact order to avoid CrashLoopBackOff/Init errors.

### Step 1: Namespace + ConfigMap + Secrets

```bash
kubectl apply -f k8s/dev/00-namespace.yaml
kubectl apply -f k8s/dev/01-configmap.yaml
kubectl apply -f k8s/dev/02-secrets-dev.yaml
```

### Step 2: Infrastructure (PostgreSQL, RabbitMQ)

```bash
kubectl apply -f k8s/dev/postgres/
kubectl wait --for=condition=ready pod -l app=postgres -n jarvis --timeout=180s

# Optional: RabbitMQ (not required for basic flow)
kubectl apply -f k8s/dev/rabbitmq/
```

### Step 3: Core Services

```bash
# Security service first (api-gateway depends on it)
kubectl apply -f k8s/dev/services/security-service.yaml
kubectl wait --for=condition=ready pod -l app=security-service -n jarvis --timeout=120s

# Then other services
kubectl apply -f k8s/dev/services/api-gateway.yaml
kubectl apply -f k8s/dev/services/voice-gateway.yaml
kubectl apply -f k8s/dev/services/nlp-service.yaml
kubectl apply -f k8s/dev/services/orchestrator.yaml
kubectl apply -f k8s/dev/services/pc-control.yaml
kubectl apply -f k8s/dev/services/life-tracker.yaml
```

### Step 4: Verify

```bash
kubectl get pods -n jarvis
# All pods should be Running/Ready

# Port-forward API Gateway
kubectl -n jarvis port-forward svc/api-gateway 8080:8080

# Test health
curl http://localhost:8080/actuator/health
```

## Enabling LLM (Optional)

LLM is **disabled by default** for stability. To enable:

```bash
# 1. Apply LLM stack
kubectl apply -f k8s/dev/services/llm-stack.yaml

# 2. Wait for LLM to be ready (may take several minutes for model loading)
kubectl wait --for=condition=ready pod -l app=llm-server -n jarvis --timeout=900s
kubectl wait --for=condition=ready pod -l app=llm-service -n jarvis --timeout=300s

# 3. Enable LLM in orchestrator via configmap patch
kubectl patch configmap jarvis-config -n jarvis --type merge \
  -p '{"data":{"FEATURE_LLM_ENABLED":"true"}}'

# 4. Restart orchestrator to pick up new config
kubectl rollout restart deployment/orchestrator -n jarvis
```

For GPU mode (if available):
- Set `DEVICE=cuda` in llm-server deployment
- Add `nvidia.com/gpu: 1` resource limit
- Ensure NVIDIA device plugin is installed in cluster

## Troubleshooting

### Pod stuck in Init / CrashLoopBackOff

1. Check if ConfigMap/Secret exist:
   ```bash
   kubectl get configmap jarvis-config -n jarvis
   kubectl get secret jarvis-secrets -n jarvis
   ```

2. Check pod events:
   ```bash
   kubectl describe pod -l app=<service-name> -n jarvis
   ```

3. Check logs:
   ```bash
   kubectl logs -n jarvis deploy/<service-name> --tail=200
   ```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `CreateContainerConfigError` | Missing ConfigMap/Secret | Apply 00-namespace.yaml, 01-configmap.yaml, 02-secrets-dev.yaml |
| `life-tracker CrashLoop` | Postgres not ready | Wait for postgres pod, check DB credentials |
| `analytics-service Init` | life-tracker not ready | Wait for life-tracker to be Ready |
| `api-gateway 502/503` | Downstream service down | Check target service logs |

## Files

| File | Description |
|------|-------------|
| `00-namespace.yaml` | Namespace `jarvis` |
| `01-configmap.yaml` | Common configuration (URLs, feature flags) |
| `02-secrets-dev.yaml` | Dev credentials (**DO NOT use in production!**) |
| `postgres/` | PostgreSQL StatefulSet |
| `rabbitmq/` | RabbitMQ StatefulSet (optional) |
| `services/` | All microservices deployments |
| `services/llm-stack.yaml` | LLM server + service (optional) |

