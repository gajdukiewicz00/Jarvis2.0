# STT Architecture Documentation

## Current State

### STT Components

1. **voice-gateway** (Primary)
   - Location: `/apps/voice-gateway`
   - Purpose: Centralized Speech-to-Text service
   - Technology: Vosk/Whisper
   - Endpoint: `POST /api/v1/voice/transcribe`

2. **Desktop Client** (Local STT)
   - Location: `/apps/desktop-client-javafx`
   - Purpose: Optional local processing
   - Use case: Offline mode

3. **Mobile Client** (Streams to server)
   - Location: `/apps/mobile-client`
   - Purpose: Send audio to voice-gateway
   - No local STT

---

## Recommended Architecture: Centralized STT

```
┌──────────────┐
│Desktop Client│─┐
└──────────────┘ │
                 │ Audio
┌──────────────┐ │    ┌───────────────┐
│Mobile Client │─┼───►│ voice-gateway │
└──────────────┘ │    │  (STT Engine) │
                 │    └───────┬───────┘
┌──────────────┐ │            │ Text
│  Web Client  │─┘            │
└──────────────┘              ▼
                    ┌──────────────────┐
                    │   nlp-service    │
                    │ (Intent parsing) │
                    └────────┬─────────┘
                             │
                    ┌────────▼──────────┐
                    │   orchestrator    │
                    └────────┬──────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ┌─────────┐   ┌──────────┐  ┌───────────┐
        │pc-control   │life-tracker  │smart-home │
        └─────────┘   └──────────┘  └───────────┘
```

---

## Benefits of Centralized STT

✅ **Single Source of Truth** - One STT engine to maintain  
✅ **Consistent Quality** - Same accuracy across all clients  
✅ **Easy Updates** - Update STT model in one place  
✅ **Resource Efficient** - Heavy processing on server  
✅ **Mobile Friendly** - Low battery consumption on devices

---

## Client Responsibilities

| Client | STT | Audio Streaming | Offline Mode |
|--------|-----|----------------|--------------|
| Desktop | Optional (local fallback) | ✓ | ✓ (local STT) |
| Mobile | ✗ | ✓ | ✗ |
| Web | ✗ | ✓ (WebRTC) | ✗ |

---

## Flow

1. **User speaks** → Client captures audio
2. **Audio stream** → voice-gateway (`/transcribe`)
3. **STT processing** → Vosk/Whisper converts to text
4. **Text** → nlp-service (intent extraction)
5. **Intent** → orchestrator (routing)
6. **Action** → microservice (execution)

---

## Implementation Status

✅ voice-gateway: STT endpoint exists  
✅ Mobile client: Streams audio correctly  
⚠️ Desktop client: Has duplicate local STT (optional)  
❌ assistant-core: Remove duplicate STT logic (no longer needed)

---

## Next Steps

1. ✅ Document architecture (this file)
2. [ ] Remove STT from assistant-core if duplicate
3. [ ] Desktop: Keep local STT as fallback only
4. [ ] Test full flow: Mobile → voice-gateway → NLP → Action
