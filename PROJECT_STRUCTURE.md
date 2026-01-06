# Jarvis 2.0 - Actual Project Structure

**Generated:** 2025-11-23  
**Build Status:** ✅ SUCCESS (`mvn clean package -DskipTests`)

## Maven Modules (Root pom.xml)

All 11 modules compile successfully:

1. **apps/assistant-core** - Original monolithic assistant (preserved)
2. **apps/analytics-service** - Analytics microservice  
3. **apps/voice-gateway** - Speech-to-Text gateway (Vosk)
4. **apps/nlp-service** - Natural Language Understanding (rule-based)
5. **apps/orchestrator** - Command routing orchestrator
6. **apps/pc-control** - PC system control (Linux/pactl/xdotool)
7. **apps/life-tracker** - Finance/Time/Calendar tracking (Postgres + Flyway)
8. **apps/api-gateway** - Spring Cloud Gateway (port 8080)
9. **apps/smart-home-service** - MQTT smart home integration
10. **apps/desktop-client-javafx** - JavaFX/Kotlin desktop UI
11. **apps/security-service** - JWT token generation/validation (port 8088)

## Non-Maven Projects

- **apps/mobile-client** - Android app (Gradle, `build.gradle.kts`)

## Service Details

### Voice Gateway (Port 8081)
- **Endpoints:**
  - `POST /api/v1/voice/transcribe` - Multipart audio upload
  - `POST /api/v1/voice/transcribe/stream` - Raw audio stream
  - `POST /api/v1/voice/command` - Text command
- **Tech:** Vosk STT, sends to Orchestrator

### NLP Service (Port 8082)
- **Endpoints:**
  - `POST /api/v1/nlp/analyze` - Intent classification
- **Tech:** RuleBasedNlpService (Russian regex patterns)

### Orchestrator (Port 8083)
- **Endpoints:**
  - `POST /api/v1/orchestrator/execute` - Execute text command
- **Logic:** Analyzes via NLP, routes to PC Control/Smart Home/etc.
- **Intents:** `hello`, `change_volume`, `set_timer`, `fallback`

### PC Control (Port 8084)
- **Endpoints:**
  - `POST /api/v1/pc/action` - Execute system action
- **Actions:**
  - `MEDIA_CONTROL` - Volume control (pactl)
  - `OPEN_APP` - Launch applications (xdg-open)
  - `HOTKEY` - Send key combinations (xdotool)
  - `SYSTEM_COMMAND` - Timer (notify-send + beep)

### Life Tracker (Port 8085)
- **Endpoints:**
  - `POST /api/v1/life/finance/expense` - Add expense
  - `GET /api/v1/life/finance/expenses` - List expenses
  - `GET /api/v1/life/time/records` - List time records
  - `POST /api/v1/life/time/start` - Start time tracker
  - `POST /api/v1/life/time/stop` - Stop time tracker
  - `GET /api/v1/life/calendar/events` - List events
  - `POST /api/v1/life/calendar/event` - Add calendar event
- **DB:** PostgreSQL 15 (Flyway migrations)

### Smart Home Service (Port 8086)
- **Endpoints:**
  - `POST /api/v1/smarthome/devices/{id}/action` - Control device
- **Tech:** MQTT (Eclipse Paho), Spring Integration
- **Broker:** Mosquitto (tcp://mosquitto:1883)

### Analytics Service (Port 8087)
- **Endpoints:**
  - `GET /api/v1/analytics/overview` - Aggregate stats
  - `GET /api/v1/analytics/expenses/by-month` - Monthly expense summaries
  - `GET /api/v1/analytics/expenses/by-category` - Category expense summaries
  - `GET /api/v1/analytics/time/summary` - Time tracking statistics
  - `GET /api/v1/analytics/calendar/summary` - Calendar statistics
- **Tech:** Feign client to Life Tracker, in-memory aggregations

### Security Service (Port 8088)
- **Endpoints:**
  - `POST /api/v1/security/token/generate` - Generate JWT token
  - `POST /api/v1/security/token/validate` - Validate JWT token
- **Tech:** JWT (jjwt library), HS256 signing

### API Gateway (Port 8080)
- **Routes:**
  - `/api/v1/voice/**` → voice-gateway:8081
  - `/api/v1/nlp/**` → nlp-service:8082
  - `/api/v1/orchestrator/**` → orchestrator:8083
  - `/api/v1/pc/**` → pc-control:8084
  - `/api/v1/life/**` → life-tracker:8085
  - `/api/v1/smarthome/**` → smart-home-service:8086
  - `/api/v1/analytics/**` → analytics-service:8087
  - `/api/v1/security/**` → security-service:8088

### Desktop Client (JavaFX)
- **Tech:** Kotlin + JavaFX 21
- **Tabs:**
  - **Home** - Welcome screen
  - **Devices** - Smart home device control (API connected)
  - **PC Control** - App launch, volume, timer (API connected)
  - **Life** - Expense & time tracking (API connected)
  - **Settings** - Placeholder
- **API Client:** HTTP client → `http://localhost:8080/api/v1`

### Mobile Client (Android)
- **Tech:** Kotlin, Android Gradle
- **Features:**
  - `MainActivity` with "Speak" button
  - `AudioStreamer` - Records audio, streams to voice-gateway
- **Endpoint:** `POST http://<SERVER>:8080/api/v1/voice/transcribe/stream`

## Infrastructure

### Docker Compose
- All 11 microservices + Mosquitto MQTT broker + PostgreSQL 15
- PostgreSQL 15 (port 5432) - Used by life-tracker
- Volumes: Postgres data, MQTT persistence
- Audio/Display: PulseAudio, X11 sockets mounted for pc-control

### Build Files
- Root: `pom.xml` (parent)
- Each service: `pom.xml` + `Dockerfile`
- Mobile: `settings.gradle.kts`, `app/build.gradle.kts`
- Root: `docker-compose.yml`

## OpenAPI Specifications
- `docs/openapi/voice-gateway.yaml`
- `docs/openapi/nlp-service.yaml`
- `docs/openapi/orchestrator.yaml`
- `docs/openapi/pc-control.yaml`
- `docs/openapi/life-tracker.yaml`
- `docs/openapi/smart-home-service.yaml`
- `docs/openapi/analytics-service.yaml`

## Verification

✅ **Maven Build:** All modules compile (`mvn clean package -DskipTests`)  
✅ **No Syntax Errors:** No `...` placeholders or incomplete code  
✅ **Dockerfiles:** Present for each service  
✅ **docker-compose.yml:** Complete with all services + Postgres + Mosquitto
