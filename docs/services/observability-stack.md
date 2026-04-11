# observability-stack

## 1. Name

`observability-stack`

## 2. Type

Kubernetes-only observability/runtime-infra stack.

## 3. Purpose

Collects metrics, logs, and traces for Jarvis workloads and exposes dashboards through Grafana.

## 4. Current Reality

The observability stack is defined under `k8s/base/observability/` and is part of the normal Kubernetes path. It includes:

- Prometheus
- Loki
- Tempo
- Grafana
- Alloy

## 5. Entry Points

Observed service ports in manifests:

- Grafana: `3000`
- Prometheus: `9090`
- Loki: `3100`
- Tempo: `3200`, `4317`, `4318`
- Alloy: `12345`

The repo verification scripts treat Grafana as the human-facing entrypoint and default to `https://grafana.jarvis.local`.

## 6. Configuration

Main source artifacts:

- `k8s/base/observability/prometheus.yaml`
- `k8s/base/observability/loki.yaml`
- `k8s/base/observability/tempo.yaml`
- `k8s/base/observability/grafana.yaml`
- `k8s/base/observability/alloy.yaml`
- `k8s/base/observability/networkpolicy.yaml`
- `scripts/verify-observability.sh`
- `scripts/provision-grafana-dashboards.sh`
- `scripts/sync-grafana-admin.sh`

## 7. API / WebSocket Surface

No Jarvis product API. This stack exposes its own component endpoints for:

- metrics scraping/querying
- log ingestion/querying
- trace ingestion/querying
- dashboard UI

## 8. Main Internal Components

- Prometheus pod discovery and actuator scraping
- Loki log storage/query
- Tempo trace storage/query
- Grafana datasource/dashboard provisioning
- Alloy collection pipeline
- dedicated observability network policies

## 9. Dependencies On Other Services

Depends on Jarvis workloads exposing health/metrics endpoints and on Kubernetes metadata for discovery. Grafana admin credentials come from `jarvis-secrets`.

## 10. Data / Storage

Observed persistent storage in manifests:

- Prometheus PVC: `10Gi`
- Grafana PVC: `2Gi`
- Loki PVC: `10Gi`
- Tempo PVC: `10Gi`

Alloy currently uses `emptyDir`.

## 11. Security Model

- internal cluster services by default
- Grafana admin credentials from Kubernetes secret
- explicit observability NetworkPolicies narrow ingress/egress flows
- Prometheus currently scrapes some internal-TLS targets with `insecure_skip_verify: true` as a compatibility compromise documented in the manifest

## 12. How To Run / Test

Kubernetes path:

```bash
./jarvis-launch.sh
./scripts/verify-observability.sh
```

## 13. Implementation Status

Implemented infrastructure stack, k8s-only.

## 14. Known Gaps / Caveats

- not part of the local process runtime
- current Prometheus scrape config still carries an internal-TLS compatibility exception
- dashboards/admin sync are script-managed, so manifests are not the only relevant source artifacts
