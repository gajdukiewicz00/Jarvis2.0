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

## ✅ Status

See `docs/STATUS.md`.

---

## 🏗️ Architecture

### Microservices (14 services)

| Service | Port | Namespace | Description |
|---------|------|-----------|-------------|
| **api-gateway** | 8080 | jarvis | Main API Gateway (routing, JWT) |
| **voice-gateway** | 8081 | jarvis | Voice input/output (STT/TTS) |
| **nlp-service** | 8082 | jarvis | NLP processing |
| **orchestrator** | 8083 | jarvis | Command routing |
| **pc-control** | 8084 | jarvis | System control |
| **security-service** | 8088 | jarvis | JWT authentication |
| **smart-home-service** | 8086 | jarvis | IoT integration |
| **analytics-service** | 8087 | jarvis | Data analysis |
| **life-tracker** | 8085 | jarvis | Time/expense tracking |
| **user-profile** | 8089 | jarvis | User preferences |
| **llm-service** | 8091 | jarvis | LLM API wrapper |
| **planner-service** | 8092 | jarvis | Tasks & reminders |
| **memory-service** | 8093 | jarvis | Long-term memory (RAG) |
| **llm-server** | 5000 | jarvis | h2oGPT Python server (GPU) |
| **embedding-service** | 5001 | jarvis | Text embeddings (CPU) |

### Infrastructure

| Component | Port | Namespace |
|-----------|------|-----------|
| PostgreSQL + pgvector | 5432 | jarvis |
| Mosquitto MQTT | 1883 | jarvis |

---

## 🚀 Quick Start (UI-first)

1. Запусти **Jarvis** из меню приложений (desktop icon).
2. Нажми **Start All** в Launcher.
3. Если потребуется доступ администратора — появится GUI-подтверждение (pkexec).
4. Дождись статуса **READY/DEGRADED**, затем нажми **Start Desktop**.

### Desktop launcher

Installer создает один launcher-файл: `~/.local/share/applications/jarvis.desktop` (Name: Jarvis, Exec: `~/.jarvis/app/bin/jarvis-launcher.sh`). Инсталлятор чистит дубли в стандартных путях, runtime Launcher не создает и не изменяет `.desktop`.

Если вдруг нужна ручная чистка, удали `*jarvis*.desktop` из:
`~/.local/share/applications`, `~/.config/autostart`, `/usr/share/applications`, `/usr/local/share/applications`,
`/var/lib/snapd/desktop/applications`, `~/.local/share/flatpak/exports/share/applications`.

### Access

```
API Gateway: https://api.jarvis.local
Voice Gateway: wss://voice.jarvis.local
```

## 🧯 Troubleshooting (CLI, optional)

Если UI недоступен, можно использовать скрипты напрямую:

```bash
./jarvis-launch.sh
./jarvis-stop.sh
./jarvis-logs.sh
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
│   ├── base/                  # Base deployments
│   └── overlays/              # Environment overlays
│       └── prod/              # Production (k3s + ingress-nginx)
│
├── models/                    # Legacy local models (not in git)
│
├── jarvis-launch.sh           # Canonical backend launch
├── jarvis-stop.sh             # Canonical backend stop
├── jarvis-logs.sh             # Kubernetes logs viewer
├── scripts/
│   └── build-images.sh        # Build Docker images
│
├── docs/                      # Documentation
├── pom.xml                    # Maven parent POM
└── README.md
```

---

**Models:** place Vosk/LLM models in `~/.jarvis/models` (not tracked in git).

## 🛠️ Development

### Build

```bash
# Full build
mvn clean package -DskipTests

# Build specific service
mvn clean package -pl apps/llm-service -am -DskipTests
```

### Runtime

This repo is **prod-only**. Primary path is the Launcher UI.
CLI scripts are for troubleshooting or automation.

---

## 🔐 Security

- **JWT Authentication** - stateless tokens (HS256)
- **API Gateway** - single entry point, JWT validation
- **Internal services** - trust gateway headers (X-User-Id, X-User-Roles)
- **Public endpoints**: `/actuator/health`, `/auth/**`

---

## 🔧 Configuration

### Kubernetes Secrets (local only)

Secrets are stored **locally only** in `~/.jarvis/secrets/secrets.env` and applied during launch.
No secrets are committed to git.

### Optional flags

LLM and Memory are optional; they must not block core readiness.

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
- CUDA 12.4+ (driver 580+ OK)
- nvidia-container-toolkit

### Models

Place models in `~/.jarvis/models/`:
- `h2ogpt-4096-llama2-7b-chat/` (HF format)
- `h2ogpt-7b-chat-q4_k_m.gguf` (GGUF for llama.cpp)

---

## 🐛 Troubleshooting

### Check pod status
```bash
kubectl get pods -A -l app.kubernetes.io/part-of=jarvis
kubectl logs -f deployment/api-gateway -n jarvis
```

### LLM not loading
```bash
kubectl logs -f deployment/llm-server -n jarvis
kubectl exec -it deployment/llm-server -n jarvis -- nvidia-smi
```

### Database issues
```bash
kubectl logs -f statefulset/postgres -n jarvis
kubectl exec -it postgres-0 -n jarvis -- psql -U jarvis
```

---

## 📝 License

Private/Personal use.

---

## 👨‍💻 Author

**Denis (kwaqa)**

---

**🚀 Deploy with one command: `./jarvis-launch.sh`**
