# Security Hardening Plan

Companion to [SECURITY.md](SECURITY.md) and [SECURITY_AUDIT.md](SECURITY_AUDIT.md). This file is the prioritized backlog of changes required to take Jarvis 2.0 from "safe enough for trusted LAN" to "safe to expose more broadly". Each row references the audit finding ID where applicable.

## P0 — must fix before any public/production exposure

| Priority | Task | Why | Files likely affected | Verification |
|----------|------|-----|----------------------|--------------|
| P0 | Make `jarvis.jwt.enabled` fail-closed (F-001) | Today the in-code default is `false`; only `application.yaml` flips it on. A deployment that loads only the dev profile silently bypasses gateway auth | `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java`, `apps/api-gateway/src/main/resources/application*.yaml` | Unit test that asserts the filter rejects requests when the property is unset; integration test boots without `application.yaml` and confirms 401 |
| P0 | Set `SERVICE_JWT_SECRET` independently of `JWT_SECRET` (F-002) | Today `service.jwt.secret` falls back to `${JWT_SECRET}`. Compromise of one secret breaks both planes | `secrets/secrets.example.env`, `scripts/product/jarvis-secrets-apply.sh`, `apps/api-gateway/src/main/resources/application.yaml` | `kubectl get secret jarvis-secrets -o json` shows distinct values |
| P0 | Tighten CORS for production hosts (F-005) | `allowed-headers: "*"` with `allow-credentials: true` is unsafe if origins ever broaden | `apps/api-gateway/src/main/resources/application.yaml`, `k8s/base/ingress.yaml` | Curl preflight from disallowed origin returns no `Access-Control-Allow-Origin` header |
| P0 | Restrict observability ingress to LAN (F-004) | Grafana admin login is the only barrier in front of Prometheus/Loki/Tempo data | `k8s/base/ingress.yaml`, `k8s/base/observability/networkpolicy.yaml`, `k8s/overlays/prod/networkpolicy-allowlist.yaml` | `nmap`/curl from outside LAN cannot reach `grafana.jarvis.local`; in-cluster smoke still works |
| P0 | Refuse `SPRING_PROFILES_ACTIVE=dev` in any cluster overlay | `DevSecurityConfig` permits everything; profile leakage = total bypass | `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/DevSecurityConfig.java`, `apps/security-service/src/main/java/org/jarvis/security/config/DevSecurityConfig.java`, k8s overlays | Application-startup guard logs HARD_FAIL when both `dev` profile and a known cluster marker are active |
| P0 | Confirm no edge TLS bypass | Make sure every prod overlay applies `nginx.ingress.kubernetes.io/ssl-redirect: "true"` and `force-ssl-redirect: "true"` | `k8s/base/ingress.yaml`, `k8s/overlays/prod-release-internal-tls-*` | `curl -I http://api.jarvis.local` returns 308 to https |

## P1 — strongly recommended before broader LAN deployment

| Priority | Task | Why | Files likely affected | Verification |
|----------|------|-----|----------------------|--------------|
| P1 | One source of truth for K8s manifests (F-013) | `k8s/` and `infra/k8s/` are parallel; drift is silent | `k8s/`, `infra/k8s/`, `docs/LEGACY_AND_CLEANUP.md` | Kustomize-build of both trees produces identical or explicitly-diffed sets |
| P1 | Single image lifecycle for prod (F-003) | Switch launcher overlay to digest-pinned by default; keep mutable `:local` only for dev | `k8s/overlays/prod/kustomization.yaml`, `k8s/overlays/prod-release/kustomization.yaml`, `scripts/product/jarvis-deploy-prod.sh` | Kyverno `verifyImages` policy passes on prod overlay |
| P1 | Make `JwtAuthFilter.PUBLIC_PATHS` exact-match (F-006) | `startsWith` is fragile if a sub-path of `/auth` ever needs auth | `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java` | New unit test rejects `/auth/login.malicious` |
| P1 | Drop handshake-header trust in WebSocket proxy (F-011) | Always derive `X-User-*` from SecurityContext, never the incoming handshake | `apps/api-gateway/src/main/java/org/jarvis/apigateway/websocket/VoiceWebSocketProxyHandler.java`, `PcControlWebSocketHandler.java` | Test that a handshake with attacker-supplied `X-User-Id` propagates only the JWT-derived value |
| P1 | Remove actuator endpoints from gateway public allow-list (F-009) | LAN-internal scrape should run via NetworkPolicy, not via ingress | `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/SecurityConfig.java`, `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java` | `/actuator/prometheus` returns 401 from off-host; in-cluster Prometheus still scrapes |
| P1 | Shorten access-token lifetime or add introspection (F-007) | Today disabled accounts keep working tokens for up to 1 h | `apps/security-service/src/main/resources/application.yml`, possibly `JwtAuthFilter` | Integration test: disable user, existing access token rejected within ≤5 min |
| P1 | Add CI dependency scanning (F-012) | `mvn org.owasp:dependency-check-maven` or `gh dependabot` for the Maven reactor | `pom.xml`, `.github/workflows/` | CI fails on critical CVEs |
| P1 | Per-service audit logging | Auth events (login, refresh, logout, password change, refuse) shipped to a tamper-evident sink | `apps/security-service/...`, optional `memory-service/audit` (V4 migration already adds events) | Loki query for `auth.*` events shows expected volume |
| P1 | Token masking in desktop logs | Re-confirm `SecurityUtils.MASK_PASSWORD_KEYS` covers JWT/Authorization/X-Service-Token | `apps/desktop-javafx/src/main/kotlin/org/jarvis/launcher/SecurityUtils.kt` | Replay desktop session with masked log assertion |
| P1 | Clamp WebSocket idle timeout for sensitive routes | `setMaxSessionIdleTimeout(0L)` disables container-side idle close — fine for voice but must not extend to PC-control if abuse model changes | `apps/api-gateway/src/main/java/org/jarvis/apigateway/websocket/WebSocketConfig.java` | New test asserts PC-control handler still uses ping/pong heartbeat |

## P2 — nice to have / supply chain

| Priority | Task | Why | Files likely affected | Verification |
|----------|------|-----|----------------------|--------------|
| P2 | Multi-key JWT rotation with `kid` (F-008) | Today rotation is a hard cutover; `kid` enables overlapping validation | `apps/security-service/src/main/java/org/jarvis/security/service/JwtService.java`, `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtUtil.java` | Issued token verifies under either old or new key during rotation window |
| P2 | SAST in CI (Semgrep / SpotBugs / OWASP rules) | Catch new injection / unsafe API surface early | `.github/workflows/`, `config/quality/` | CI report attached to every PR |
| P2 | SBOM generation (CycloneDX) | Required for any external software supply | `pom.xml` plugin, `scripts/product/jarvis-build-release.sh` | `*.cdx.json` artifact attached to release |
| P2 | Container image scanning (Trivy / Grype) | Catch base-image CVEs before promotion | `scripts/product/jarvis-promote-images.sh` | Promote step rejects images with critical CVEs |
| P2 | Cosign-signed images | Kyverno `verifyImages` policy is already in place; needs signed images | `scripts/product/jarvis-build-release.sh` | `cosign verify` succeeds |
| P2 | Secret-scanning pre-commit hook | Catch accidental commits of `.pem`, tokens, etc. | `.git/hooks` or `pre-commit` config | Hook fails the commit on injected fake JWT |
| P2 | Security regression tests | Lock the contracts: gateway rejects refresh as bearer, service JWT required for `/internal/**`, `X-User-*` ignored without service JWT | `apps/api-gateway/src/test/java/...`, `apps/jarvis-common/src/test/java/...` | New suite green in CI |
| P2 | mTLS internal | `JARVIS_GATEWAY_INTERNAL_HTTPS_ENABLED` stub is already in `application.yaml` — finish the path between gateway and downstream | `apps/api-gateway/src/main/resources/application.yaml`, `apps/jarvis-common/...`, `scripts/product/jarvis-generate-internal-tls-*` | Per-service `apply-internal-tls` smoke scripts succeed |
| P2 | Threat-modeling doc per surface | Voice WS, PC-control WS, desktop launcher, sync-service, cloud-relay | `docs/security/THREAT_MODEL_*.md` | Doc reviewed and linked from `SECURITY.md` |
