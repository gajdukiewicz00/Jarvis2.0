# nlp-service

## 1. Name

`nlp-service`

## 2. Type

Backend NLP service.

## 3. Purpose

Analyzes text and produces normalized intent/entity output for other services, especially `orchestrator`.

## 4. Current Reality

The service is real and wired into runtime flows, but it is rule-based rather than model-driven. It should be documented as a deterministic intent parser, not as a general-purpose modern NLP stack.

## 5. Entry Points

- Spring Boot app: `org.jarvis.nlp.NlpServiceApplication`
- REST base path: `/api/v1/nlp`

## 6. Configuration

Main configuration source:

- `apps/nlp-service/src/main/resources/application.yml`

Important settings include:

- server port `8082`
- JWT/service auth integration
- actuator and tracing settings

## 7. API / WebSocket Surface

REST endpoints:

- `POST /api/v1/nlp/analyze`
- `POST /api/v1/nlp/analyze-enhanced`

No WebSocket surface.

## 8. Main Internal Components

- `RuleBasedNlpService`
- `EnhancedRuleBasedNlpService`
- `TextNormalizer`

## 9. Dependencies On Other Services

None required for core processing.

## 10. Data / Storage

No database or persistent storage.

## 11. Security Model

Protected by the standard Spring security configuration used by internal services.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/nlp-service -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Rule-based parsing only.
- Capability breadth is limited to what is encoded in the service logic and DTOs.
