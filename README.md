# 🤖 Jarvis 2.0 - Personal AI Assistant

**Intelligent personal assistant with voice control, task planning, habit analytics, and LLM integration.**

![Jarvis](icons/jarvis-icon.png)

---

## 🎯 Overview

Jarvis 2.0 is a comprehensive AI-powered personal assistant system featuring:
- 🎤 **Voice Control** - natural language commands (Vosk STT + Google TTS)
- 📅 **Smart Planning** - daily/weekly plans with task management  
- 📊 **Habit Analytics** - sleep, work, and productivity tracking
- 🧠 **LLM Integration** - conversational AI with h2oGPT (GPU accelerated)
- 🔐 **JWT Security** - stateless authentication
- 🖥️ **Desktop GUI** - JavaFX interface with wake word detection
- ☸️ **Kubernetes Native** - fully containerized microservices

---

## 🏗️ Architecture

### Microservices (14 services)

| Service | Port | Namespace | Description |
|---------|------|-----------|-------------|
| **api-gateway** | 8080 | jarvis-core | Main API Gateway (routing, JWT) |
| **voice-gateway** | 8081 | jarvis-core | Voice input/output (STT/TTS) |
| **nlp-service** | 8082 | jarvis-core | NLP processing |
| **orchestrator** | 8083 | jarvis-core | Command routing |
| **pc-control** | 8084 | jarvis-core | System control |
| **security-service** | 8085 | jarvis-core | JWT authentication |
| **smart-home-service** | 8086 | jarvis-iot | IoT integration |
| **analytics-service** | 8087 | jarvis-core | Data analysis |
| **life-tracker** | 8088 | jarvis-core | Time/expense tracking |
| **user-profile** | 8089 | jarvis-core | User preferences |
| **llm-service** | 8091 | jarvis-llm | LLM API wrapper |
| **planner-service** | 8092 | jarvis-core | Tasks & reminders |
| **memory-service** | 8093 | jarvis-llm | Long-term memory (RAG) |
| **llm-server** | 5000 | jarvis-llm | h2oGPT Python server (GPU) |
| **embedding-service** | 5001 | jarvis-llm | Text embeddings (CPU) |

### Infrastructure

| Component | Port | Namespace |
|-----------|------|-----------|
| PostgreSQL + pgvector | 5432 | jarvis-data |
| Mosquitto MQTT | 1883 | jarvis-iot |

---

## 🚀 Quick Start

### Prerequisites

- **Minikube** (local Kubernetes)
- **kubectl**
- **Java 21** (OpenJDK)
- **Maven 3.8+**
- **Docker**
- **NVIDIA GPU** (optional, for LLM acceleration)

### One-Command Deploy

```bash
# Deploy everything to Kubernetes
./scripts/deploy.sh
```

This will:
1. Start Minikube (if not running)
2. Build all Docker images
3. Deploy to Kubernetes
4. Wait for services to be ready
5. Show access URLs

### Access

After deployment:
```
API Gateway: http://<minikube-ip>:30080
```

### Stop

```bash
./scripts/stop.sh
```

---

## 📁 Project Structure

```
Jarvis2.0/
├── apps/                      # Java/Kotlin microservices
│   ├── jarvis-common/         # Shared library (security, utilities)
│   ├── api-gateway/           # Spring Cloud Gateway
│   ├── llm-service/           # LLM API wrapper
│   ├── memory-service/        # RAG memory (pgvector)
│   ├── planner-service/       # Tasks & reminders
│   ├── voice-gateway/         # STT/TTS (Vosk + Google Cloud)
│   └── ... (10 more services)
│
├── docker/                    # Python services Dockerfiles
│   ├── llm-server/            # h2oGPT inference server
│   └── embedding-service/     # Sentence transformers
│
├── k8s/                       # Kubernetes manifests
│   ├── namespaces/            # Namespace definitions
│   ├── base/                  # Base deployments
│   ├── overlays/              # Environment overlays
│   │   └── dev/               # Development (Minikube)
│   ├── secrets/               # Secrets (JWT, DB)
│   └── ingress/               # Ingress with TLS
│
├── models/                    # ML models (not in git)
│   ├── vosk-model-small-ru-0.22/
│   └── README.md
│
├── scripts/
│   ├── deploy.sh              # One-click deploy
│   ├── stop.sh                # Stop all services
│   └── build-images.sh        # Build Docker images
│
├── docs/                      # Documentation
├── pom.xml                    # Maven parent POM
└── README.md
```

---

## 🛠️ Development

### Build

```bash
# Full build
mvn clean package -DskipTests

# Build specific service
mvn clean package -pl apps/llm-service -am -DskipTests
```

### Run Locally (dev profile)

```bash
# Start PostgreSQL
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_USER=jarvis -e POSTGRES_PASSWORD=jarvis123 \
  pgvector/pgvector:pg16

# Run service
cd apps/api-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Kubernetes Development

```bash
# Build images in Minikube context
eval $(minikube docker-env)
./scripts/build-images.sh

# Apply changes
kubectl apply -k k8s/overlays/dev/

# Watch pods
kubectl get pods -A -l app.kubernetes.io/part-of=jarvis -w
```

---

## 🔐 Security

- **JWT Authentication** - stateless tokens (HS256)
- **API Gateway** - single entry point, JWT validation
- **Internal services** - trust gateway headers (X-User-Id, X-User-Roles)
- **Public endpoints**: `/actuator/health`, `/auth/**`

---

## 🔧 Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | (change!) | JWT signing key |
| `DB_PASSWORD` | jarvis123 | PostgreSQL password |
| `LLM_BACKEND` | transformers | LLM backend (transformers/llamacpp) |
| `MAX_NEW_TOKENS` | 512 | Max LLM output tokens |

### Kubernetes Secrets

Located in `k8s/secrets/`:
- `jwt-secret.yaml` - JWT signing key
- `db-credentials.yaml` - Database credentials

**⚠️ Change these for production!**

---

## 📊 API Endpoints

### Authentication
- `POST /auth/register` - Register user
- `POST /auth/login` - Get JWT tokens
- `POST /auth/refresh` - Refresh token

### Voice
- `POST /api/v1/voice/transcribe` - Audio → Text
- `POST /api/v1/voice/synthesize` - Text → Audio

### LLM
- `POST /api/v1/llm/chat` - Chat with LLM
- `POST /api/v1/llm/dialog` - Dialog mode

### Memory
- `POST /memory/ingest` - Store conversation
- `POST /memory/search` - Search memory (RAG)

### Planner
- `GET /api/v1/planner/daily` - Daily plan
- `POST /api/v1/planner/tasks` - Create task
- `GET /api/v1/planner/reminders` - List reminders

---

## 🧠 LLM Setup

### GPU Requirements

- NVIDIA GPU with 12GB+ VRAM
- CUDA 12.8+
- nvidia-container-toolkit

### Models

Place models in `/home/kwaqa/models/`:
- `h2ogpt-4096-llama2-7b-chat/` (HF format)
- `h2ogpt-7b-chat-q4_k_m.gguf` (GGUF for llama.cpp)

---

## 🐛 Troubleshooting

### Check pod status
```bash
kubectl get pods -A -l app.kubernetes.io/part-of=jarvis
kubectl logs -f deployment/api-gateway -n jarvis-core
```

### LLM not loading
```bash
kubectl logs -f deployment/llm-server -n jarvis-llm
kubectl exec -it deployment/llm-server -n jarvis-llm -- nvidia-smi
```

### Database issues
```bash
kubectl logs -f statefulset/postgres -n jarvis-data
kubectl exec -it postgres-0 -n jarvis-data -- psql -U jarvis
```

---

## 📝 License

Private/Personal use.

---

## 👨‍💻 Author

**Denis (kwaqa)**

---

**🚀 Deploy with one command: `./scripts/deploy.sh`**
