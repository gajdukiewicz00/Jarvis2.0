# Jarvis 2.0 - Voice Architecture

**Дата обновления:** 2025-12-02

## Overview

voice-gateway обеспечивает голосовой ввод через Speech-to-Text (STT) и Text-to-Speech (TTS).

---

## Speech-to-Text (STT)

### Доступные провайдеры

| Provider | Status | Configuration |
|----------|--------|---------------|
| **Whisper** | ✅ Поддерживается | `jarvis.voice.whisper.enabled=true` |
| **NoOp (fallback)** | ⚠️ Заглушка | Возвращает ошибку `STT_UNAVAILABLE` |

### Конфигурация Whisper

```yaml
jarvis:
  voice:
    whisper:
      enabled: true                                    # Enable Whisper
      model-path: models/ggml-small.bin               # Path to Whisper model
```

### Как включить STT

1. **Скачать модель Whisper**:
   ```bash
   # Whisper models from: https://huggingface.co/ggerganov/whisper.cpp
   wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin
   mkdir -p models
   mv ggml-small.bin models/
   ```

2. **Настроить конфигурацию**:
   ```yaml
   # application.yml
   jarvis:
     voice:
       whisper:
         enabled: true
         model-path: models/ggml-small.bin
   ```

3. **Для Docker** (в docker-compose.yml):
   ```yaml
   voice-gateway:
     environment:
       - JARVIS_VOICE_WHISPER_ENABLED=true
       - JARVIS_VOICE_WHISPER_MODEL_PATH=/app/models/ggml-small.bin
     volumes:
       - ./models:/app/models:ro
   ```

### Ответы при ошибках

**Если STT не сконфигурирован**:
```json
{
  "timestamp": "2025-12-02T12:00:00",
  "status": 503,
  "error": "STT_UNAVAILABLE",
  "message": "Speech-to-Text is not configured. Set jarvis.voice.whisper.enabled=true",
  "service": "voice-gateway",
  "path": "/api/v1/voice/transcribe"
}
```

---

## Text-to-Speech (TTS)

### Доступные провайдеры

| Provider | Status | Notes |
|----------|--------|-------|
| **espeak** | ✅ Default | Бесплатный, локальный |
| **Google TTS** | ⚠️ Требует API key | Высокое качество |

### Конфигурация TTS

```yaml
tts:
  enabled: true
  provider: espeak      # espeak или google
  espeak:
    voice: russian
    speed: 150
  google:
    voice-name: ru-RU-Wavenet-A
    language-code: ru-RU
```

---

## Voice Processing Pipeline

```
1. Audio Input (WAV 16kHz mono)
      ↓
2. voice-gateway: Validate WAV format
      ↓
3. STT Service: Transcribe to text
      ↓
4. Forward to orchestrator → NLP processing
      ↓
5. Response → TTS → Audio output
```

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/voice/transcribe` | POST | Transcribe audio file |
| `/api/v1/voice/transcribe/stream` | POST | Stream audio transcription |
| `/api/v1/voice/command` | POST | Send text command |
| `/api/v1/voice/tts` | POST | Text-to-Speech |

### WebSocket (для real-time голоса)

```
ws://localhost:8081/ws/voice
```

**Protocol**:
1. Client sends audio chunks (binary)
2. Server sends partial/final transcription results (JSON)
3. Server sends TTS audio (binary)

---

## Логирование

```yaml
logging:
  level:
    org.jarvis.voicegateway: INFO
    org.vosk: WARN
```

**Типичные логи**:
```
INFO  Received audio file: test.wav (size: 32000 bytes)
INFO  WAV validation successful: 16000Hz, 1 channel(s), PCM
INFO  Transcribed text: привет джарвис
```

---

## Health Check

```bash
curl http://localhost:8081/actuator/health
```

**Ответ (с STT)**:
```json
{
  "status": "UP",
  "components": {
    "stt": {
      "status": "UP",
      "details": {
        "provider": "whisper"
      }
    }
  }
}
```

**Ответ (без STT)**:
```json
{
  "status": "UP",
  "components": {
    "stt": {
      "status": "DOWN",
      "details": {
        "error": "STT not configured"
      }
    }
  }
}
```

---

## Troubleshooting

### "STT_UNAVAILABLE" при каждом запросе

**Причина**: Whisper не включен или модель не найдена.

**Решение**:
1. Проверить конфигурацию: `jarvis.voice.whisper.enabled=true`
2. Проверить путь к модели
3. Убедиться что файл модели существует

### "WAV file format not supported"

**Причина**: Неверный формат аудио.

**Требования**:
- Формат: WAV (PCM)
- Sample rate: 16000 Hz
- Channels: 1 (mono)
- Bits: 16-bit

**Конвертация с ffmpeg**:
```bash
ffmpeg -i input.mp3 -ar 16000 -ac 1 -f wav output.wav
```

---

*Документ создан: 2025-12-02*

