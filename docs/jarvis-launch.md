# Jarvis Launch Guide

Complete guide for launching and managing Jarvis 2.0 with Kubernetes.

## Quick Start

### Option 1: Desktop Icon (Recommended)

1. Open GNOME Activities / Application Menu
2. Search for **"Jarvis 2.0"**
3. Click the icon → everything starts automatically!

### Option 2: Terminal

```bash
cd ~/IdeaProjects/Jarvis2.0
./jarvis-launch.sh
```

---

## What Happens on Launch

The launch script performs these steps:

1. **Preflight Check** - Verifies kubectl, docker, java are installed
2. **Cluster Check** - Starts minikube if no K8s cluster running
3. **TLS Certificates** - Generates self-signed certs if needed
4. **Docker Build** - Builds all microservice images (12 services)
5. **K8s Deploy** - Applies manifests (namespace, infra, services)
6. **Wait for Ready** - Waits for core services to become healthy
7. **Port Forward** - Sets up network access from localhost
8. **Desktop Client** - Builds (if needed) and launches the JavaFX GUI

---

## Service Endpoints

| Service | URL | Description |
|---------|-----|-------------|
| API Gateway | `http://localhost:8080` | Main API entry point |
| Voice Gateway | `http://localhost:8081` | TTS/STT WebSocket |
| RabbitMQ UI | `http://localhost:15672` | Message broker management |

### Health Checks

```bash
# API Gateway
curl http://localhost:8080/actuator/health

# Voice Gateway
curl http://localhost:8081/actuator/health
```

---

## Management Scripts

| Script | Purpose |
|--------|---------|
| `./jarvis-launch.sh` | Start everything |
| `./jarvis-stop.sh` | Stop and cleanup |
| `./jarvis-logs.sh` | View service logs |

### Right-Click Actions

The desktop icon provides quick actions:
- **Stop Jarvis** - Stops all services
- **View Logs** - Opens log viewer
- **K8s Status** - Shows pod status in real-time

---

## Dependencies

| Dependency | Version | Install |
|------------|---------|---------|
| Java | 21+ | `sudo apt install openjdk-21-jdk` |
| Docker | Latest | [docs.docker.com](https://docs.docker.com/engine/install/) |
| kubectl | Latest | [kubernetes.io](https://kubernetes.io/docs/tasks/tools/) |
| minikube | Latest | `curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo install minikube-linux-amd64 /usr/local/bin/minikube` |
| Maven | 3.8+ | `sudo apt install maven` |

---

## Troubleshooting

### Port Already in Use

```
❌ Порт 8080 уже занят!
```

**Solution:**
```bash
# Find what's using the port
sudo lsof -i :8080

# Kill the process
sudo kill -9 <PID>
```

### Service Not Starting

```bash
# Check pod status
kubectl get pods -n jarvis

# View logs for specific service
kubectl logs -f deployment/api-gateway -n jarvis
```

### Desktop Client Won't Start

```bash
# Rebuild
mvn clean package -pl apps/desktop-client-javafx -am -DskipTests

# Check JAR exists
ls -lh apps/desktop-client-javafx/target/*.jar
```

### Minikube Issues

```bash
# Full restart
minikube stop
minikube delete
minikube start --memory=8192 --cpus=4 --driver=docker
```

---

## Advanced Options

### Environment Variables

```bash
# Enable Kafka (disabled by default)
ENABLE_KAFKA=true ./jarvis-launch.sh
```

### Manual Control

```bash
# Just deploy without desktop client
./jarvis-launch.sh &
# Press Ctrl+C after K8s is up

# View all pods
kubectl get pods -n jarvis -w

# Access specific service
kubectl port-forward svc/life-tracker 8088:8088 -n jarvis
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Desktop Client (JavaFX)                  │
│                         localhost:*                          │
└─────────────────┬───────────────────────────────────────────┘
                  │ HTTP/WebSocket
                  ▼
┌─────────────────────────────────────────────────────────────┐
│  API Gateway :8080          │    Voice Gateway :8081        │
│  (routing, auth)            │    (TTS/STT)                  │
└─────────────────────────────┴───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│                    Kubernetes (minikube)                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │ security │ │ life-    │ │ analytics│ │ planner  │ ...   │
│  │ -service │ │ tracker  │ │ -service │ │ -service │       │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘       │
│  ┌──────────┐ ┌──────────┐                                  │
│  │ postgres │ │ rabbitmq │                                  │
│  └──────────┘ └──────────┘                                  │
└─────────────────────────────────────────────────────────────┘
```
