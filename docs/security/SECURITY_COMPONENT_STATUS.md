# Security Component Status

Per-component security posture. Status legend:

- **secure enough** — appropriate for the documented trust boundary
- **acceptable for local** — fine for LAN/local use, not safe for public exposure
- **needs hardening** — works, but a recognised gap is open
- **risky** — known concrete weakness, see referenced finding
- **broken / drifted** — code does not match the documented intent
- **unknown** — not exhaustively reviewed in this audit
- **legacy candidate** — kept around but not actively maintained
- **insecure legacy** — remove or quarantine
- **must not expose publicly** — local-trust component

| Component | Path | Security role | Exposure | Status | Risks | Notes |
|-----------|------|---------------|----------|--------|-------|-------|
| api-gateway | `apps/api-gateway` | Edge for HTTP + WebSocket; JWT enforcement; service-token issuer | external (LAN) via `api.jarvis.local` and `voice.jarvis.local` | needs hardening | F-001, F-005, F-006, F-009, F-011 | Production behaviour relies on `application.yaml` being loaded so that `jarvis.jwt.enabled=true` overrides the in-code default `false` |
| security-service | `apps/security-service` | User auth issuer; user/refresh JWT; password ops | internal only (proxied via gateway) | secure enough | F-007 (per-request revocation not implemented), F-008 (no `kid`) | BCrypt, refresh rotation + reuse detection, password change revokes all refresh tokens |
| jarvis-common (security) | `apps/jarvis-common/src/main/java/org/jarvis/common/security` | `ServiceJwtFilter`, `GatewayAuthFilter`, `BaseSecurityConfig` | library | secure enough | none new | Correctly refuses `X-User-*` headers without a valid service JWT |
| voice-gateway | `apps/voice-gateway` | Voice STT/TTS, WS hub | internal; reachable through gateway WS proxy | secure enough | none new | `InternalVoiceCommandController` and `InternalVoiceNotificationController` require `SVC_INTERNAL` |
| nlp-service | `apps/nlp-service` | NLP intent classification | internal only | secure enough | none new | Uses `BaseSecurityConfig` defaults |
| orchestrator | `apps/orchestrator` | Command dispatch | internal only | secure enough | none new | `InternalOrchestratorSmartHomeController` requires `SVC_INTERNAL` |
| pc-control | `apps/pc-control` | Desktop control | local-only when in real mode; in cluster runs with `PC_CONTROL_STUB_MODE=true` | must not expose publicly | F-013 (drift) | Real desktop control bypasses sandboxing by design — host trust required |
| vision-security-service | `apps/vision-security-service` | Owner verification camera | local desktop only; deliberately absent from `k8s/base` | must not expose publicly | none new | Workstation-trust component |
| life-tracker | `apps/life-tracker` | Personal data store | internal; `/api/v1/lifetracker/**` via gateway | secure enough | none new | Relies on gateway JWT + service JWT |
| analytics-service | `apps/analytics-service` | Read-models over life-tracker | internal | secure enough | none new | Uses `BaseSecurityConfig`; integration test `AnalyticsControllerSecurityTest` covers it |
| planner-service | `apps/planner-service` | Tasks/reminders/recommendations | internal | secure enough | none new | `InternalPlannerVoiceNotificationController` requires `SVC_INTERNAL` |
| user-profile | `apps/user-profile` | Profile/context | internal | secure enough | none new | |
| llm-service | `apps/llm-service` | Authenticated AI facade | internal; backed by host-model daemon (prod) or `llm-server-py` (deprecated wrapper) | acceptable for local | F-013 (drift) | `LocalOnlyEnforcer` recently added; verify it before any non-local exposure |
| memory-service | `apps/memory-service` | pgvector semantic memory | internal | secure enough | none new | V4 migration adds audit events; V5 adds memory notes — review before enabling externally |
| smart-home-service | `apps/smart-home-service` | Home automation actions | internal | secure enough | none new | `ActionValidatorTest` confirms validation; MQTT password from secret only |
| sync-service | `apps/sync-service` | E2E sync inbox | internal; talks to `cloud-relay` | needs hardening | F-013 | Newly added module; full security review pending |
| cloud-relay | `apps/cloud-relay` | Off-prem opaque blob forwarder | external | needs hardening | F-013 | Off-prem boundary — review payload-shape, auth, retention before enabling |
| desktop-javafx | `apps/desktop-javafx` | Desktop shell + launcher | host-local | acceptable for local | F-010 (token export fixtures), legacy persisted endpoint fallback | `SecurityUtils.kt` masks `GRAFANA_ADMIN_PASSWORD` in launcher logs; verify masking covers JWT/Authorization too |
| android-app | `apps/android-app` | Phase 12 mobile scaffold | not in Maven reactor | unknown | full review pending | Not built today; security review needed before any APK release |
| llm-server-py | `apps/llm-server-py` | Deprecated Python LLM wrapper | optional | legacy candidate | F-013 | Marked deprecated in `README.md`; replace with host-model-daemon |
| embedding-service-py | `apps/embedding-service-py` | Embedding worker (Python) | optional | acceptable for local | none new | Containerfile-built, daemonless OCI |
| mosquitto MQTT | `k8s/base/mosquitto/` | Local broker for smart-home | optional/legacy | legacy candidate | none new | Documented as legacy; password-bootstrap initContainer uses `secretKeyRef` |
| postgres + pgvector | `k8s/base/postgres/`, `k8s/overlays/prod/postgres-pgvector.yaml` | Primary store | internal | secure enough | none new | Credentials sourced via `secretKeyRef`, init scripts gated on env |
| Grafana | `k8s/base/observability/grafana.yaml` | Observability UI | external (LAN) via `grafana.jarvis.local` | acceptable for local | F-004 | Admin login is the only barrier; restrict ingress before any non-LAN exposure |
| Prometheus / Loki / Tempo / Alloy | `k8s/base/observability/` | Observability backends | internal only | secure enough | F-009 | Cluster-internal; do not expose via ingress |
| ingress (nginx) | `k8s/base/ingress.yaml` | TLS termination, CORS, websocket route | external | needs hardening | F-005, F-009 | TLS-only; CORS allow-list narrow; tighten allowed-headers |
| Kyverno policies | `k8s/overlays/prod/kyverno/` | Pod-Security enforcement | n/a | secure enough | none new | runtime hardening, container hardening, host isolation, capability drops, disallow-latest, verify-images |
| `secrets/` | `secrets/` | Local secrets template | n/a | secure enough | none new | Only `secrets.example.env` and `.gitkeep` are tracked |
| `logs/exports/` | `logs/exports/` | Acceptance/verify run fixtures | host-local | acceptable for local | F-010 | Contains real bearer tokens from local runs; gitignored, prune after each run |
| `infra/k8s/` | `infra/k8s/overlays/prod/` | Parallel MicroK8s prod tree | external (LAN) when deployed | broken / drifted | F-013 | Drift vs `k8s/`; choose a single source of truth |
| `dev` profiles | `apps/api-gateway/.../security/DevSecurityConfig`, `apps/security-service/.../config/DevSecurityConfig` | Local development | dev only | insecure legacy if leaked | F-001 follow-up | Add startup guard preventing dev profile inside cluster |
| Bootstrap admin | `apps/security-service/.../BootstrapAdminInitializer.java` | Optional first-run admin | startup-only | secure enough | none new | Disabled by default; requires non-default ≥12-char password |
