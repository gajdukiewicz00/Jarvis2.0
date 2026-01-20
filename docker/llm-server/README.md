# LLM Server

Python FastAPI server for h2oGPT-7B model inference.

## Features

- h2oGPT-7B (Llama2-based) model loading
- REST API for chat completions
- Llama2 chat format support
- CPU and GPU support
- Conversation history management

## API Endpoints

### POST /api/v1/llm/chat

Process chat request with conversation history.

**Request:**
```json
{
  "messages": [
    {"role": "system", "content": "You are Jarvis"},
    {"role": "user", "content": "Hello!"}
  ],
  "max_tokens": 512,
  "temperature": 0.7
}
```

**Response:**
```json
{
  "reply": "Hello! How can I help you today?",
  "tokens": {
    "input": 25,
    "output": 12
  },
  "model": "h2oGPT-7B",
  "processing_time_ms": 2500
}
```

### GET /health

Health check endpoint.

**Response:**
```json
{
  "status": "healthy",
  "model_loaded": true,
  "model_path": "/models",
  "device": "cpu"
}
```

## Environment Variables

- `MODEL_PATH`: Path to h2oGPT model (default: `/models`)
- `DEVICE`: Device for inference - `cpu` or `cuda` (default: `cpu`)
- `HOST`: Server host (default: `0.0.0.0`)
- `PORT`: Server port (default: `5000`)
- `MAX_TOKENS`: Default max tokens (default: `512`)
- `TEMPERATURE`: Default temperature (default: `0.7`)

## Running Locally

```bash
# Install dependencies
pip install -r requirements.txt

# Set model path
export MODEL_PATH=~/.jarvis/models/h2ogpt-4096-llama2-7b-chat

# Run server
uvicorn app.main:app --host 0.0.0.0 --port 5000
```

## Docker

```bash
# Build
docker build -t jarvis-llm-server .

# Run
docker run -p 5000:5000 \
  -v ~/.jarvis/models/h2ogpt-4096-llama2-7b-chat:/models:ro \
  jarvis-llm-server
```

## System Requirements

- **RAM**: 8GB minimum (for 7B model)
- **CPU**: 4+ cores recommended
- **Disk**: ~15GB for model files
- **Latency**: 2-5 seconds per response (CPU), <1s (GPU)
