# LLM Integration Guide

Comprehensive guide for using the h2oGPT-7B LLM integration in Jarvis 2.0.

## Architecture Overview

```
┌──────────────────┐
│    Clients       │
│ (Desktop/Mobile) │
└────────┬─────────┘
         │
         ▼
┌───────────────────────────────────────┐
│      API Gateway (8080)               │
│      Routes: /api/v1/llm/**          │
│      WebSocket: /ws/jarvis-llm       │
└────────┬──────────────────────────────┘
         │
         ▼
┌───────────────────────────────────────┐
│   llm-service (Java, 8091)            │
│   - WebSocket Controller (STOMP)      │
│   - REST Controller                   │
│   - Conversation Memory (in-memory)   │
│   - LLM Client (HTTP to Python)       │
│   - System Prompt Management          │
└────────┬──────────────────────────────┘
         │ HTTP REST
         ▼
┌───────────────────────────────────────┐
│   llm-server (Python FastAPI, 5000)   │
│   - Model: h2oGPT-7B (Llama2-based)   │
│   - Device: CPU or CUDA               │
│   - Format: Llama2 chat template      │
│   - Generation: Sampling with temp    │
└───────────────────────────────────────┘
```

## Installation

### 1. Download h2oGPT Model

```bash
# Create models directory
mkdir -p ~/.jarvis/models
cd ~/.jarvis/models

# Download from HuggingFace
git lfs install
git clone https://huggingface.co/h2oai/h2ogpt-4096-llama2-7b-chat

# Verify model files
ls h2ogpt-4096-llama2-7b-chat/
# Expected: config.json, pytorch_model.bin, tokenizer.model, etc.
```

### 2. Start Services

```bash
# From Jarvis2.0 root directory
cd /path/to/Jarvis2.0

# Start LLM services in Kubernetes
ENABLE_LLM=true ./jarvis-launch.sh

# Monitor logs
kubectl logs -f deployment/llm-server -n jarvis
# Wait for: "Model loaded successfully!" (30-60 seconds)

# Verify health
kubectl -n jarvis port-forward svc/llm-server 5000:5000
curl http://localhost:5000/health
# Expected: {"status":"healthy","model_loaded":true}
```

## API Reference

### REST API

#### POST /api/v1/llm/chat

Process chat request with conversation history.

**Request:**
```json
{
  "sessionId": "user-123",
  "messages": [
    {"role": "user", "content": "Привет, Jarvis!"}
  ],
  "maxTokens": 512,
  "temperature": 0.7
}
```

**Response:**
```json
{
  "reply": "Привет! Чем могу помочь?",
  "tokens": {
    "input": 25,
    "output": 10
  },
  "model": "h2oGPT-7B",
  "processingTimeMs": 2300
}
```

**Example:**
```bash
# Auth headers are injected by the gateway; keep tokens out of docs.
curl -X POST https://api.jarvis.local/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-session",
    "messages": [
      {"role": "user", "content": "Поставь таймер на 5 минут"}
    ]
  }'
```

#### DELETE /api/v1/llm/session/{sessionId}

Clear conversation history for a session.

```bash
# Auth headers are injected by the gateway; keep tokens out of docs.
curl -X DELETE https://api.jarvis.local/api/v1/llm/session/test-session \
  -H "Content-Type: application/json"
```

#### GET /api/v1/llm/health

Check LLM service and server health.

```bash
curl https://api.jarvis.local/api/v1/llm/health
```

**Response:**
```json
{
  "status": "healthy",
  "llm_server_available": true
}
```

### WebSocket API

#### Endpoint: `/ws/jarvis-llm`

STOMP WebSocket endpoint for real-time chat.

**JavaScript Example:**
```javascript
// Connect
const socket = new SockJS('https://api.jarvis.local/ws/jarvis-llm');
const stompClient = Stomp.over(socket);

stompClient.connect({}, function(frame) {
  console.log('Connected:', frame);
  
  // Subscribe to session topic
  const sessionId = 'user-123';
  stompClient.subscribe(`/topic/llm-response/${sessionId}`, function(message) {
    const response = JSON.parse(message.body);
    console.log('Jarvis:', response.reply);
    console.log('Processing time:', response.processingTimeMs, 'ms');
  });
  
  // Send message
  stompClient.send('/app/llm-chat', {}, JSON.stringify({
    sessionId: sessionId,
    message: 'Какая погода сегодня?'
  }));
});
```

**Message Format:**

Send to: `/app/llm-chat`
```json
{
  "sessionId": "user-123",
  "message": "текст сообщения"
}
```

Receive from: `/topic/llm-response/{sessionId}`
```json
{
  "sessionId": "user-123",
  "reply": "ответ от Jarvis",
  "timestamp": "2025-11-26T18:30:00Z",
  "processingTimeMs": 2500
}
```

## Configuration

### Java Service (`apps/llm-service/src/main/resources/application.yml`)

```yaml
llm:
  server:
    url: http://llm-server:5000  # Python server URL
  conversation:
    max-history: 20  # Keep last N messages
  system-prompt: >
    Ты - Jarvis, персональный AI-ассистент.
    # Customize as needed
```

### Python Server (`docker/llm-server/app/config.py`)

Environment variables:
```bash
MODEL_PATH=/models  # Path to h2oGPT model
DEVICE=cpu          # 'cpu' or 'cuda'
MAX_TOKENS=512      # Default max output tokens
TEMPERATURE=0.7     # Sampling temperature (0.0-1.0)
MAX_HISTORY_LENGTH=20  # Max conversation turns
```

### Docker Compose

For **CPU mode** (default):
```yaml
llm-server:
  environment:
    - DEVICE=cpu
  deploy:
    resources:
      limits:
        memory: 8G
      reservations:
        memory: 4G
```

For **GPU mode**:
```yaml
llm-server:
  environment:
    - DEVICE=cuda
  deploy:
    resources:
      reservations:
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]
```

## System Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| RAM | 8GB | 16GB |
| CPU Cores | 4 | 8+ |
| Disk Space | 15GB | 20GB |
| GPU (optional) | - | NVIDIA 8GB+ VRAM |

### Performance

- **CPU inference**: 2-5 seconds per response
- **GPU inference**: <1 second per response
- **Model size**: ~13GB on disk, ~4GB in RAM
- **Concurrent users**: 1-3 (CPU), 10+ (GPU)

## Conversation Flow

### Session Management

Each client gets a unique `sessionId`. The server maintains conversation history per session:

```
Session: user-123
├── System: "Ты - Jarvis..."
├── User: "Привет!"
├── Assistant: "Привет! Чем могу помочь?"
├── User: "Поставь таймер"
└── Assistant: "Таймер установлен!"
```

History is kept in-memory (last 20 messages by default).

### System Prompt

The first system message sets Jarvis context:
```
Ты - Jarvis, персональный AI-ассистент.
Твои возможности включают: управление компьютером, умный дом, 
трекинг финансов, календарь, аналитика.
Отвечай кратко, по-дружески, на русском языке.
```

Customize in `apps/llm-service/src/main/resources/application.yml`.

## Integration with Voice

### Option 1: Direct Voice → LLM

```
Microphone → voice-gateway (STT) → llm-service → llm-server → response → TTS
```

### Option 2: Hybrid (Rule-based + LLM Fallback)

```
Microphone → voice-gateway (STT) → orchestrator
  ├── High confidence → nlp-service (rule-based) → execute
  └── Low confidence → llm-service → execute
```

Future enhancement: Orchestrator can route complex queries to LLM while keeping simple commands in rule-based NLP.

## Troubleshooting

### Model Won't Load

**Check logs:**
```bash
kubectl logs -f deployment/llm-server -n jarvis
```

**Common issues:**
- Model path incorrect: Verify `~/.jarvis/models/h2ogpt-4096-llama2-7b-chat` exists
- Out of memory: Increase Docker memory limit (need 8GB+)
- Missing model files: Re-download model from HuggingFace

### Slow Responses

- **Normal on CPU**: 2-5 seconds is expected
- **Reduce max_tokens**: Lower to 256 for faster responses
- **Use GPU**: Switch `DEVICE=cuda` for 10x speedup

### Connection Errors

**Java → Python:**
```bash
# Test Python server directly
curl http://localhost:5000/health

# Check llm-service logs
kubectl logs -f deployment/llm-service -n jarvis
```

**WebSocket fails:**
- Check CORS settings in `WebSocketConfig.java`
- Verify SockJS fallback is enabled

### Memory Issues

**Reduce max history**:
```yaml
llm:
  conversation:
    max-history: 10  # From 20 to 10
```

**Clear sessions:**
```bash
# Auth headers are injected by the gateway; keep tokens out of docs.
curl -X DELETE https://api.jarvis.local/api/v1/llm/session/old-session \
  -H "Content-Type: application/json"
```

## Examples

### Simple Q&A
```bash
# Auth headers are injected by the gateway; keep tokens out of docs.
curl -X POST https://api.jarvis.local/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "qa-test",
    "messages": [{"role": "user", "content": "Что ты умеешь?"}]
  }'
```

### Multi-turn Conversation
```bash
# Turn 1
curl -X POST https://api.jarvis.local/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "conv-123",
    "messages": [
      {"role": "user", "content": "Сколько стоит кофе?"}
    ]
  }'

# Turn 2 (remembers context)
curl -X POST https://api.jarvis.local/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "conv-123",
    "messages": [
      {"role": "user", "content": "А если взять два?"}
    ]
  }'
```

### Task Execution
```bash
# Auth headers are injected by the gateway; keep tokens out of docs.
curl -X POST https://api.jarvis.local/api/v1/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "task-123",
    "messages": [
      {"role": "user", "content": "Установи таймер на 15 минут и прибавь громкость"}
    ]
  }'
# Jarvis confirms both actions
```

## Production Deployment

### Recommendations

1. **Use Redis for session storage**
   - Replace in-memory Map with Redis
   - Enables horizontal scaling

2. **Add rate limiting**
   - Prevent abuse (LLM is expensive)
   - Use Spring Cloud Gateway rate limiters

3. **Monitor performance**
   - Log processing times
   - Track token usage
   - Alert on slow responses

4. **GPU for production**
   - 10x faster inference
   - Handle more concurrent users
   - NVIDIA T4 or better

5. **Model quantization**
   - Use 4-bit or 8-bit quantization
   - Reduce memory from 8GB to 4GB
   - Slight quality tradeoff

## Future Enhancements

- [ ] Streaming responses (WebSocket)
- [ ] Redis session persistence
- [ ] Model fine-tuning on Jarvis data
- [ ] Multi-language support (English + Russian)
- [ ] Function calling for tool use
- [ ] Retrieval-Augmented Generation (RAG)
- [ ] Voice output via TTS integration

---

For more details, see:
- `ARCHITECTURE.md` - System architecture
- `docker/llm-server/README.md` - Python server docs
- `apps/llm-service/` - Java service code
