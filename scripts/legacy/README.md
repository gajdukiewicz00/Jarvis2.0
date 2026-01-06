# Legacy Scripts & Configurations

This directory contains archived files that have been superseded by the current Kubernetes deployment system.

## ⚠️ Warning

**These files are NOT actively maintained.** Use the scripts in the repository root instead:

```bash
./jarvis-launch.sh   # Start everything
./jarvis-stop.sh     # Stop everything
./jarvis-logs.sh     # View logs
```

---

## Archived Files

### Kubernetes Scripts (v1)

| File | Replaced By | Date |
|------|-------------|------|
| `jarvis-k8s-launch.sh` | `jarvis-launch.sh` (root) | 2025-12-04 |
| `jarvis-k8s-stop.sh` | `jarvis-stop.sh` (root) | 2025-12-04 |
| `jarvis-k8s-logs.sh` | `jarvis-logs.sh` (root) | 2025-12-04 |
| `jarvis-k8s.desktop` | `jarvis.desktop` (root) | 2025-12-04 |

The main differences between old and new:
- Unified naming without `k8s-` prefix (since all scripts use Kubernetes by default)
- Improved error handling and terminal management
- Better NodePort support for minikube

---

### Docker Compose (Pre-Kubernetes)

The `docker-compose/` directory contains the original Docker Compose setup that was used before migrating to Kubernetes:

| File | Description |
|------|-------------|
| `docker-compose.yml` | Basic stack (minimal services) |
| `docker-compose-dev.yml` | Development overrides (debug mode) |
| `docker-compose-full.yml` | Full stack with Kafka, RabbitMQ, nginx |

#### Why Archived?

Docker Compose was replaced by Kubernetes (minikube) for:
- Better service orchestration
- Built-in health checks and restarts
- Easier scaling and resource management
- More production-like local environment

#### Can I Still Use Docker Compose?

Technically yes, but it's **not recommended**:

```bash
# NOT RECOMMENDED - legacy only
cd scripts/legacy/docker-compose
docker-compose up -d
```

These files may be outdated and missing newer services. Use Kubernetes instead.

---

## Migration Guide

If you have local Docker Compose data you want to preserve:

1. **Database**: Export PostgreSQL data before switching
2. **Volumes**: Back up any persistent volumes
3. **Environment**: `.env` file is still used by both systems

Then switch to Kubernetes:
```bash
cd /home/kwaqa/IdeaProjects/Jarvis2.0
./jarvis-launch.sh
```
