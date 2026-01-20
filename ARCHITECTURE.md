# Jarvis 2.0 Architecture

## Overview

Jarvis 2.0 is a microservices-based personal assistant system built with Spring Boot. The architecture follows clear separation of concerns with dedicated services for different functionalities.

## Service Architecture

```
┌─────────────┐
│   Client    │ (Audio/Text requests)
└──────┬──────┘
       │
       ▼
┌──────────────────────────────────────────┐
│          API Gateway (Port 8080)         │
│  - JWT Authentication                    │
│  - Request Routing                       │
│  - Rate Limiting                         │
└──────────────┬───────────────────────────┘
               │
       ┌───────┴────────┬──────────────────┬─────────────┐
       ▼                ▼                  ▼             ▼
┌──────────────┐  ┌──────────────┐  ┌─────────────┐  ┌──────────────┐
│voice-gateway │  │ orchestrator │  │ nlp-service │  │security-     │
│  (8081)      │  │   (8083)     │  │   (8082)    │  │ service      │
│              │  │              │  │             │  │  (8088)      │
│ **ONLY STT** │  │ Coordinates  │  │ Intent &    │  │ JWT Token    │
│ **& TTS**    │  │ Services     │  │ Entity Rec. │  │ Management   │
└──────────────┘  └──────┬───────┘  └─────────────┘  └──────────────┘
                         │
          ┌──────────────┼──────────────┬─────────────────┐
          ▼              ▼              ▼                 ▼
   ┌──────────┐   ┌──────────┐  ┌──────────────┐  ┌──────────────┐
   │assistant-│   │life-     │  │pc-control    │  │smart-home-   │
   │core      │   │tracker   │  │  (8084)      │  │service       │
   │ (8090)   │   │ (8085)   │  │              │  │  (8086)      │
   │          │   │          │  │ System       │  │ IoT Control  │
   │Core Logic│   │Expenses &│  │ Control      │  │              │
   │& Memory  │   │Time Track│  │              │  │              │
   └──────────┘   └──────────┘  └──────────────┘  └──────────────┘
```

## Port Allocation

| Service            | Port | Purpose                                    |
|--------------------|------|--------------------------------------------|
| api-gateway        | 8080 | Gateway with JWT auth & routing            |
| voice-gateway      | 8081 | **Audio processing (STT/TTS) ONLY**        |
| nlp-service        | 8082 | Natural language understanding             |
| orchestrator       | 8083 | Service coordination                       |
| pc-control         | 8084 | System control (apps, volume, etc.)        |
| life-tracker       | 8085 | Expenses & time tracking                   |
| smart-home-service | 8086 | IoT device control                         |
| analytics-service  | 8087 | Data analytics                             |
| security-service   | 8088 | JWT generation & validation                |
| user-profile       | 8089 | User goals, habits, priorities             |
| assistant-core     | 8090 | Core logic, memory, behavioral analysis    |
| llm-service        | 8091 | LLM integration (h2oGPT-7B)                |
| llm-server         | 5000 | Python LLM inference server                |

## Key Design Principles

### 1. Single Audio Service
**voice-gateway is the ONLY service that handles audio processing.**

- ✅ All audio input → voice-gateway (STT with Vosk)
- ✅ All TTS output → voice-gateway
- ❌ No other service has STT/TTS capabilities
- ✅ Vosk model is mounted only in voice-gateway container

**Benefits:**
- No model duplication
- Centralized audio processing
- Simplified deployment
- Clear responsibility boundaries

### 2. JWT Authentication Flow

```
Client → API Gateway (validate JWT locally) → Downstream Services (trusted)
```

- **api-gateway**: Validates JWT on every request (local validation, no network call)
- **security-service**: Issues JWT tokens
- **Microservices**: Trust gateway, no additional auth required

### 3. Service Communication

- **Synchronous**: Feign clients for REST calls
- **Authentication**: Services behind gateway are trusted (internal network)
- **User Context**: Gateway adds userId/username as request attributes

## Audio Processing Flow

```
1. Client sends audio
   ↓
2. voice-gateway receives audio
   ↓
3. voice-gateway: Vosk STT → text
   ↓
4. voice-gateway → orchestrator (text)
   ↓
5. orchestrator → nlp-service (intent recognition)
   ↓
6. orchestrator → appropriate service (pc-control, life-tracker, etc.)
   ↓
7. Response → voice-gateway (TTS if needed) → Client
```

## Data Persistence

- **PostgreSQL**: Shared database for all services
- **Schema**: Each service has its own schema for isolation
- **Migrations**: Flyway for version-controlled schema changes

## Production (k3s)

- Services run in namespace `jarvis` and communicate via service DNS
- TLS terminates at ingress-nginx; internal traffic is HTTP
- Secrets are injected via Kubernetes Secret (`jarvis-secrets`)

## Security

- **Authentication**: JWT tokens (stateless)
- **Authorization**: Role-based (ROLE_USER by default)
- **Public Endpoints**: `/api/v1/security/**`, `/actuator/health`
- **Protected**: Everything else requires valid JWT

## LLM Integration

Jarvis 2.0 integrates a local Large Language Model (h2oGPT-7B based on Llama2) for natural conversation and improved intent understanding.

### Architecture

```
┌─────────────────────────────────────┐
│     llm-service (Java, 8091)        │
│  - WebSocket API (/ws/jarvis-llm)  │
│  - REST API (/api/v1/llm/chat)     │
│  - Conversation Memory              │
│  - System Prompt Management         │
└──────────┬──────────────────────────┘
           │ HTTP/REST
           ▼
┌─────────────────────────────────────┐
│   llm-server (Python FastAPI, 5000) │
│  - h2oGPT-7B Model Loading          │
│  - Text Generation (CPU/GPU)        │
│  - Llama2 Chat Format               │
└─────────────────────────────────────┘
```

### Integration Flow

**Option 1: Direct LLM Chat (WebSocket)**
```
Client → api-gateway → llm-service (WebSocket) → llm-server → response
```

**Option 2: Hybrid (Rule-based + LLM fallback)**
```
Client → voice-gateway (STT) → orchestrator 
  → nlp-service (rule-based, high confidence) → action
  → llm-service (low confidence, complex queries) → action
```

### System Requirements

- **RAM**: 8GB minimum for h2oGPT-7B model
- **CPU**: 4+ cores recommended
- **Latency**: 2-5 seconds per response (CPU), <1s (GPU)
- **Disk**: ~15GB for model files

### API Examples

**WebSocket:**
```javascript
// Connect
const socket = new SockJS('http://localhost:8080/ws/jarvis-llm');
const stompClient = Stomp.over(socket);

// Subscribe
stompClient.subscribe('/topic/llm-response/session-123', (response) => {
  console.log(response.reply);
});

// Send message
stompClient.send('/app/llm-chat', {}, JSON.stringify({
  sessionId: 'session-123',
  message: 'Поставь таймер на 5 минут'
}));
```

**REST:**
```bash
curl -X POST http://localhost:8080/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "user-123",
    "messages": [
      {"role": "user", "content": "Привет!"}
    ]
  }'
```

## External Dependencies

- **Vosk**: Russian STT model (voice-gateway only)
- **jjwt**: JWT library (api-gateway, security-service)
- **PostgreSQL**: Database
- **Spring Cloud OpenFeign**: Service-to-service communication
- **h2oGPT-7B**: Local LLM for natural conversation (llm-server)
- **PyTorch + Transformers**: Python ML stack (llm-server)
