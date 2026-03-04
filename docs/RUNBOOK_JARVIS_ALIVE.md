# Jarvis Alive Runbook

Короткий operational runbook: открыть один файл, выполнить шаги, получить рабочий Jarvis.

## 1) Prereqs (обязательно)

1. Подготовить secrets:
```bash
mkdir -p ~/.jarvis/secrets
cp ./secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
chmod 600 ~/.jarvis/secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh
```

2. Подготовить TLS:
```bash
./scripts/product/jarvis-generate-certs.sh
sudo ./scripts/product/jarvis-install-tls.sh
```

3. Подготовить hosts:
```bash
sudo ./scripts/product/jarvis-setup-hosts.sh
```

4. Проверить, что есть доступ к Kubernetes (k3s или другой контекст):
```bash
kubectl cluster-info
kubectl get ns jarvis
```

## 2) Tier A: Core Voice Loop

Старт:
```bash
./jarvis-launch.sh
```

Проверка готовности:
```bash
kubectl get pods -n jarvis
kubectl get deploy -n jarvis api-gateway voice-gateway nlp-service orchestrator pc-control security-service
curl -k https://api.jarvis.local/actuator/health
```

Если нужен smoke через internal path:
```bash
./scripts/product/jarvis-run-acceptance.sh
```

Acceptance напрямую:
```bash
# Auto (по готовности деплоев)
./scripts/acceptance-ai.sh

# Принудительно включить LLM smoke
JARVIS_ACCEPT_LLM=true ./scripts/acceptance-ai.sh

# Принудительно выключить Memory smoke
JARVIS_ACCEPT_MEMORY=false ./scripts/acceptance-ai.sh
```

## 3) Tier B: Enable LLM

Старт с LLM:
```bash
ENABLE_LLM=true ./jarvis-launch.sh
```

Быстрая проверка LLM:
```bash
kubectl -n jarvis port-forward svc/llm-server 15000:5000 >/tmp/jarvis-pf-llm-server.log 2>&1 &
PF1=$!
kubectl -n jarvis port-forward svc/llm-service 18091:8091 >/tmp/jarvis-pf-llm-service.log 2>&1 &
PF2=$!
sleep 2
LLM_SERVER_URL=http://127.0.0.1:15000 LLM_SERVICE_URL=http://127.0.0.1:18091 ./scripts/llm-smoke.sh
kill $PF1 $PF2
```

## 4) Tier C: Enable Memory

Старт с LLM + Memory:
```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

Быстрая проверка Memory:
```bash
kubectl -n jarvis port-forward svc/embedding-service 15001:5001 >/tmp/jarvis-pf-embedding.log 2>&1 &
PF1=$!
kubectl -n jarvis port-forward svc/memory-service 18093:8093 >/tmp/jarvis-pf-memory.log 2>&1 &
PF2=$!
sleep 2
EMBEDDING_URL=http://127.0.0.1:15001 MEMORY_URL=http://127.0.0.1:18093 ./scripts/memory-smoke.sh
kill $PF1 $PF2
```

## 5) 10 быстрых debug-команд

1.
```bash
kubectl get pods -n jarvis -o wide
```

2.
```bash
kubectl get svc -n jarvis
```

3.
```bash
kubectl get ingress -n jarvis
```

4.
```bash
kubectl logs -f deployment/api-gateway -n jarvis --tail=200
```

5.
```bash
kubectl logs -f deployment/orchestrator -n jarvis --tail=200
```

6.
```bash
kubectl logs -f deployment/voice-gateway -n jarvis --tail=200
```

7.
```bash
kubectl describe pod -n jarvis "$(kubectl get pods -n jarvis -o name | head -1)"
```

8.
```bash
cat ~/.jarvis/run/last-run.json
```

9.
```bash
./jarvis-logs.sh
```

10.
```bash
./jarvis-stop.sh --yes
```

## 6) STOP / RESET

Мягкая остановка:
```bash
./jarvis-stop.sh --yes
```

Если namespace/ingress завис:
```bash
kubectl delete ns jarvis
```

Если зависли port-forward процессы:
```bash
ps aux | rg "kubectl.*port-forward" | awk '{print $2}' | xargs -r kill
```

## 7) Guardrails (порядок фиксированный)

```bash
mvn -q -DskipTests package
./scripts/verify-prod.sh
./scripts/ci/k8s-preflight.sh
./scripts/ci/k8s-preflight-staging.sh
```
