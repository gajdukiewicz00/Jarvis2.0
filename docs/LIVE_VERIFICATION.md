# Jarvis 2.0 — Live Verification

## Acceptance pass — 2026-07-06 (node IP `10.110.0.58`)

Full acceptance pass re-run. Cluster: **28/28 pods Running, 24/24 deployments Ready.**

| Check | Result |
|-------|--------|
| `./jarvis status` | ✅ 24/24 Ready, gateway UP, host LLM :18080 = 200, host TTS :18090 = 200, host-model-daemon endpoint == node IP |
| `./jarvis doctor` | ✅ all checks OK (k3s active, node Ready, brains 200, all pods/deployments, gateway UP, endpoint wired) |
| `./jarvis drift-check` | ✅ **18/18** live images match the pinned overlay tags |
| `./jarvis reboot-verify` | ✅ **7 passed / 0 failed** (k3s svc, k3s API, node IP, host-model endpoint, pods Ready, gateway health, brain-via-cluster) |
| `./jarvis smoke-e2e` | ✅ **4 passed / 0 failed** (login, brain chat `model=qwen3-14b-q4_k_m.gguf`, memory /search 200, planner /llm/recommend 200) |
| **Prometheus scrapes agent/media/sync** | ❌ **NOT scraped** — see gap below |
| memory-service Prometheus target | ⚠️ reports **down** (has `scrape=true port=8093` but target unhealthy — investigate) |

### ❗ Open gap — Prometheus visibility (operator-gated)
Live `agent-service`, `media-service`, `sync-service` deployments carry **no** `prometheus.io/scrape` annotation, and the live NetworkPolicy `jarvis-services-ingress-from-prometheus` (67d old) does **not** include them. The scrape patches + netpol allowlist are **committed in the repo but not applied live** (the agent is guard-blocked from `kubectl apply` of NetworkPolicy). Operator fix:
```
sudo k3s kubectl -n jarvis-prod apply -f ~/Jarvis/Jarvis2.0/infra/k8s/overlays/prod/networkpolicy-allowlist.yaml
sudo k3s kubectl apply -k ~/Jarvis/Jarvis2.0/infra/k8s/overlays/prod
```
After apply, re-check with the Prometheus `/api/v1/targets` query (agent/media/sync should become `up`). This also re-applies the base observability netpol change that added agent/media/sync to the ingress-from-prometheus allowlist.

---

_Run: 2026-07-05 23:49 CEST_ · node IP `10.110.0.58`

## 1. `./jarvis doctor`
```
== Jarvis doctor ==
  GPU: NVIDIA GeForce RTX 5070, 10270 MiB, 1497 MiB
  Disk(/home/kwaqa): 77G free (83% used)
[ OK ] k3s systemd service active
[ OK ] k3s reachable (jarvis-prod)
[ OK ] node reachable and Ready (InternalIP=10.110.0.58)
[ OK ] host :18080 health 200
[ OK ] host :18090 health 200
[ OK ] all pods Running
[ OK ] all deployments Ready
[ OK ] gateway /actuator/health UP (https://10.110.0.58)
[ OK ] host-model-daemon endpoint IP (10.110.0.58) == node InternalIP
[ OK ] host-model-daemon endpoint wired (cluster llm-service pod -> host-model-daemon:18080/health -> HTTP 200)
```
## 2. `./jarvis status`
```
[INFO] Namespace: jarvis-prod  (kubectl: sudo k3s kubectl)
NAME                 READY   UP-TO-DATE   AVAILABLE   AGE
agent-service        1/1     1            1           11d
alloy                1/1     1            1           67d
analytics-service    1/1     1            1           67d
api-gateway          1/1     1            1           67d
embedding-service    1/1     1            1           67d
grafana              1/1     1            1           67d
life-tracker         1/1     1            1           67d
llm-server           1/1     1            1           67d
llm-service          1/1     1            1           67d
loki                 1/1     1            1           67d
media-service        1/1     1            1           11d
memory-service       1/1     1            1           67d
mosquitto            1/1     1            1           67d
nlp-service          1/1     1            1           67d
orchestrator         1/1     1            1           67d
pc-control           1/1     1            1           67d
planner-service      1/1     1            1           67d
prometheus           1/1     1            1           67d
security-service     1/1     1            1           67d
smart-home-service   1/1     1            1           67d
sync-service         1/1     1            1           58d
tempo                1/1     1            1           67d
user-profile         1/1     1            1           67d
voice-gateway        1/1     1            1           67d

deployments Ready: 24/24
pods not Running: 0

```
## 3. image drift-check
```
llm-service             localhost:5000/jarvis/llm-service:movie9        localhost:5000/jarvis/llm-service:movie9        OK
llm-server              localhost:5000/jarvis/llm-server:local          localhost:5000/jarvis/llm-server:local          OK
embedding-service       localhost:5000/jarvis/embedding-service:local   localhost:5000/jarvis/embedding-service:local   OK
memory-service          localhost:5000/jarvis/memory-service:w3         localhost:5000/jarvis/memory-service:w3         OK

✅ no image drift: 18/18 deployment(s) match the pinned overlay tags
```
## 4. post-reboot-verify
```
  [PASS] node InternalIP = 10.110.0.58

== 4. host-model-daemon endpoint ==
  [PASS] host-model-daemon Endpoints IP (10.110.0.58) == node InternalIP

== 5. pods Ready ==
  [PASS] all 28 pods Running/Completed

== 6. gateway /actuator/health ==
  [PASS] gateway health UP (https://10.110.0.58/actuator/health, Host: api.jarvis.local)

== 7. brain via cluster (llm-service -> host-model-daemon:18080) ==
  [PASS] brain reachable from cluster (HTTP 200)

== post-reboot-verify result: 7 passed, 0 failed ==
```
## 5. smoke-e2e
```
  [PASS] login acquired an accessToken
  [PASS] llm chat returned model=qwen3-14b-q4_k_m.gguf
  [PASS] memory /search returned HTTP 200
  [PASS] planner /llm/recommend returned HTTP 200
== smoke-e2e result: 4 passed, 0 failed ==
```
## 6. pods
```
     28 1/1
```
## 7. new gateway routes (netpol-gated)
```
  /api/v1/agents/roles -> HTTP 503
  /api/v1/media/jobs -> HTTP 503
  /api/v1/planner/weekly -> HTTP 200
  /api/v1/smarthome/scenes -> HTTP 200
  /api/v1/analytics/insights/weekly -> HTTP 200
```
## 8. Prometheus scrape annotations on live pods
```
  agent-service: prometheus.io/scrape='<none>'
  media-service: prometheus.io/scrape='<none>'
  sync-service: prometheus.io/scrape='<none>'
  memory-service: prometheus.io/scrape='true'
  analytics-service: prometheus.io/scrape='true'
```

> NOTE: agent/media routes 503 and agent/media/sync scrape=<none> until the operator applies
> `infra/k8s/overlays/prod/networkpolicy-allowlist.yaml` + the overlay scrape-patches (guard-gated for the agent).
