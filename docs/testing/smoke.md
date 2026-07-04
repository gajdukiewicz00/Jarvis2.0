# Smoke tests

Smoke tests answer one question fast: **is Jarvis alive end to end right now?**
They are graceful — optional / feature-flagged subsystems report `SKIP`, and a run
only fails on a *required* check.

## Single umbrella: `scripts/jarvis-smoke.sh`

The canonical "is it all up?" probe. Two modes:

```bash
# Against the deployed cluster (default). On this host, k3s is the live cluster:
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml      # or run scripts via `sudo k3s kubectl`
./scripts/jarvis-smoke.sh --mode=k8s --namespace=jarvis-prod

# Against a host runtime started by scripts/runtime-up.sh:
./scripts/jarvis-smoke.sh --mode=local
```

What it checks (per the Stage-2 spec):

| Check | Required? | Notes |
| --- | --- | --- |
| Namespace exists | yes (k8s) | `jarvis-prod` |
| Core deployments Ready (api-gateway, security, orchestrator, nlp) | yes | `kubectl rollout status` |
| Optional deployments (voice, llm, memory) | no | `SKIP` if not deployed |
| Postgres / pgvector reachable | yes if present | `pg_isready`; pgvector extension check |
| Kafka / RabbitMQ ready | no | `SKIP` on the legacy `k8s/` tree (omits them) |
| API gateway `/actuator/health` | yes | via temporary port-forward |
| `/api/v1/status/report` | no | needs token; `SKIP`/`404` until the new image is rolled |
| Memory write + search round-trip | no | needs token + `ENABLE_MEMORY` |
| Command dry-run | no | needs token |

Auth-gated checks run only when a token is available:

```bash
export JARVIS_SMOKE_TOKEN='<bearer>'              # pre-obtained, OR
export JARVIS_SMOKE_USER='you' JARVIS_SMOKE_PASS='…'   # script logs in
```

**Expected output (healthy cluster, no token):**

```
Jarvis smoke — mode=k8s namespace=jarvis-prod
✓ PASS Namespace jarvis-prod exists
== Core workload readiness (jarvis-prod) ==
✓ PASS deployment api-gateway ready
…
== Data stores (jarvis-prod) ==
✓ PASS postgres accepts connections (pg_isready)
✓ PASS pgvector extension installed
✓ PASS kafka statefulset ready (broker port 9092)
✓ PASS rabbitmq statefulset ready (broker port 5672)
== Gateway HTTP health (port-forward) ==
✓ PASS API gateway /actuator/health (via port-forward)
== Application flows (auth-gated) ==
- SKIP memory write/read — set JARVIS_SMOKE_TOKEN or JARVIS_SMOKE_USER/PASS
== Summary ==
PASS=… FAIL=0 SKIP=…
Smoke OK (required checks passed).
```

Exit code: `0` when all required checks pass (skips allowed), `1` on any required
failure, `2` on bad arguments / missing `curl`.

## Pre-existing focused smoke scripts

These remain the source of truth for their subsystem and are not replaced:

| Script | Scope |
| --- | --- |
| `scripts/k8s-smoke.sh` | strict cluster topology + ingress-ownership assertions |
| `scripts/runtime-smoke.sh` | host-runtime (`runtime-up.sh`) service health |
| `scripts/llm-smoke.sh` | LLM adapter + daemon |
| `scripts/memory-smoke.sh` | memory write/search + pgvector |
| `scripts/voice-local-smoke.sh` | STT/TTS stack |
| `scripts/analytics-smoke.sh` | analytics read-model |

`jarvis-smoke.sh` is the umbrella; reach for a focused script to debug one
subsystem in depth.
