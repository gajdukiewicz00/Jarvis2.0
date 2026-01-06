# Processing Chain Documentation

## Strict Processing Chain

The Jarvis system follows a strict processing chain for all requests:

```
┌─────────┐
│ Client  │
└────┬────┘
     │ (Audio/Text)
     ▼
┌──────────────────────────────────────────────────────┐
│ STAGE 1: Audio to Text (STT)                        │
│ Service: voice-gateway                               │
│ Input: Audio stream                                  │
│ Output: Text string                                  │
│ Confidence: Vosk confidence score                    │
└────┬─────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ STAGE 2: Text to Intent (NLU)                       │
│ Service: nlp-service                                 │
│ Input: Text string                                   │
│ Output: Intent + Entities + Confidence               │
│ Decision: confidence >= 0.8 → execute                │
│           confidence < 0.5 → clarify                 │
└────┬─────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ STAGE 3: Intent to Action (Routing)                 │
│ Service: orchestrator                                │
│ Input: Intent + Entities                             │
│ Output: Routed to appropriate service                │
│ Routes:                                              │
│  - set_timer → pc-control                            │
│  - change_volume → pc-control                        │
│  - track_expense → life-tracker                      │
│  - track_time → life-tracker                         │
│  - smart_home → smart-home-service                   │
└────┬─────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ STAGE 4: Execute Action                             │
│ Service: Appropriate service (pc-control, etc.)      │
│ Input: Action command with parameters                │
│ Output: Execution result                            │
│ Error handling: Try-catch with fallback              │
└────┬─────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ STAGE 5: Format Response                            │
│ Service: orchestrator                                │
│ Input: Execution result                              │
│ Output: User-friendly text response                  │
└────┬─────────────────────────────────────────────────┘
     │
     ▼
┌──────────────────────────────────────────────────────┐
│ STAGE 6: Text to Speech (TTS) [Optional]            │
│ Service: voice-gateway                               │
│ Input: Text response                                 │
│ Output: Audio stream                                 │
└────┬─────────────────────────────────────────────────┘
     │
     ▼
┌─────────┐
│ Client  │
└─────────┘
```

## Error Handling at Each Stage

### Stage 1: STT
- **Error**: Audio unintelligible
- **Response**: "Повторите, пожалуйста"
- **Retry**: Yes (up to 3 times)

### Stage 2: NLU
- **Low Confidence (<0.5)**: Request clarification
- **Example**: "Я не уверен. Вы хотите установить таймер или что-то другое?"
- **Fallback**: If still unclear, suggest common commands

### Stage 3-4: Routing & Execution
- **Service Unavailable**: "Сервис временно недоступен"
- **Invalid Parameters**: "Неверные параметры команды"
- **Execution Failed**: Return specific error message

### Stage 5-6: Response
- **TTS Failed**: Return text response only
- **No fallback needed**: Text always available

## Request/Response Contracts

### Stage 1: voice-gateway → orchestrator
```typescript
interface SttRequest {
  audio: Buffer
  userId: string
  sessionId?: string
}

interface SttResponse {
  text: string
  confidence: number  // 0.0-1.0
  language: string
}
```

### Stage 2: orchestrator → nlp-service
```typescript
interface NluRequest {
  text: string
  locale: string
}

interface NluResponse {
  intent: string
  entities: Map<string, string>
  confidence: number  // 0.0-1.0
  needsClarification: boolean
  clarificationQuestion?: string
}
```

### Stage 3: orchestrator → action-service
```typescript
interface ActionRequest {
  intent: string
  parameters: Map<string, any>
  userId: string
}

interface ActionResponse {
  success: boolean
  message: string
  data?: any
  error?: string
}
```

## Contract Validation

- All requests validated at service boundaries
- Invalid requests return 400 Bad Request
- Missing required fields cause immediate rejection
- Type mismatches caught early

## Monitoring Points

Each stage logs:
- Request received
- Processing time
- Confidence scores (where applicable)
- Errors/warnings
- Final output

This enables end-to-end tracing of requests through the entire chain.
