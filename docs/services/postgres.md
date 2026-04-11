# postgres

## 1. Name

`postgres`

## 2. Type

Runtime datastore / infrastructure component.

## 3. Purpose

Provides relational storage for the core Spring services and an optional pgvector-backed path for semantic memory workloads.

## 4. Current Reality

There are two related datastore paths in the repo:

- core `postgres` in `k8s/base/postgres/statefulset.yaml`
- optional `postgres-pgvector` in `k8s/overlays/prod/postgres-pgvector.yaml`

The local process runtime does not use those manifests directly; it can auto-start a managed local Postgres container from `scripts/runtime/common.sh` using `pgvector/pgvector:pg16`.

## 5. Entry Points

- Kubernetes service: `postgres:5432`
- optional Kubernetes service: `postgres-pgvector:5432`
- local managed container: `${JARVIS_LOCAL_POSTGRES_CONTAINER:-jarvis-local-postgres}`

## 6. Configuration

Main source artifacts:

- `k8s/base/postgres/statefulset.yaml`
- `k8s/overlays/prod/postgres-pgvector.yaml`
- `docker/postgres-init/init-databases.sh`
- `scripts/runtime/common.sh`

Important settings include:

- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## 7. API / WebSocket Surface

No REST or WebSocket surface. This component exposes PostgreSQL on port `5432`.

## 8. Main Internal Components

- core StatefulSet `postgres`
- optional StatefulSet `postgres-pgvector`
- init scripts for database creation / extension enablement
- PVC-backed storage in Kubernetes

## 9. Dependencies On Other Services

Consumers confirmed from code/manifests include:

- `security-service`
- `user-profile`
- `life-tracker`
- `planner-service`
- `memory-service`

`memory-service` additionally requires vector support and in the prod overlay is wired to `postgres-pgvector`.

## 10. Data / Storage

- Kubernetes core DB PVC request: `10Gi`
- optional pgvector DB PVC request: `10Gi`
- local runtime uses a managed container volume

Observed database names in repo scripts/manifests include:

- `jarvis`
- `jarvis_security`
- `jarvis_user_profile`
- `jarvis_memory`
- `jarvis_db`

## 11. Security Model

- credentials come from local env or Kubernetes secret `jarvis-secrets`
- cluster exposure is internal `ClusterIP`, not public ingress
- services authenticate at the application layer; PostgreSQL itself is internal infrastructure

## 12. How To Run / Test

Local runtime path:

```bash
./scripts/runtime-up.sh
```

Kubernetes path:

```bash
./jarvis-launch.sh
```

## 13. Implementation Status

Implemented infrastructure component.

## 14. Known Gaps / Caveats

- core K8s postgres and optional pgvector postgres are separate runtime paths in manifests
- local runtime and K8s init scripts are similar, but not identical, so the manifests/scripts remain the source of truth
- `postgres-pgvector` is optional and defaults to `replicas: 0`
