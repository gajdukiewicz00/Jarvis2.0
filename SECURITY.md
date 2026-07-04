# Security Policy

Jarvis 2.0 is a local-first personal AI assistant; this policy describes the supported versions, the disclosure process, and where to find the canonical security documentation.

## Supported Versions

| Branch / Tag | Status | Receives security fixes |
| --- | --- | --- |
| `main` | active development (v1.0 in progress) | yes |
| any commit prior to `main` HEAD | unsupported | no — please re-base your fork |

There are no pre-v1.0 tagged releases yet. Once v1.0 ships, the latest minor will be supported.

## Reporting a Vulnerability

If you believe you have found a security vulnerability in Jarvis, **please do not open a public GitHub issue**.

1. Email a private report to the maintainer at the address in the repository owner's GitHub profile.
2. If you don't get a reply within 7 days, open a placeholder GitHub issue titled "Security: response needed (details sent privately)" with no details — that pings the maintainer.
3. Expect an initial response within 7 days, a triage decision within 14 days, and a fix or mitigation plan within 30 days for issues we accept.

When reporting, include:

- The component (`api-gateway`, `voice-gateway`, `desktop-javafx`, `host-model-daemon`, etc.) and version / commit SHA.
- Steps to reproduce, including a minimal proof-of-concept.
- Your assessment of impact (confidentiality / integrity / availability) and exploitability.
- Whether the issue requires special configuration (e.g. `JARVIS_RUNTIME_MODE=K8S`) or default settings.
- Whether you would like attribution in the fix announcement.

## Scope

In scope:

- All code under `apps/`, `libs/`, `scripts/`, `infra/k8s/`, `k8s/`, and `.github/workflows/`.
- Default configurations shipped in `application.yaml`, `application.yml`, `secrets/secrets.example.env`, K8s manifests, and runtime scripts.
- The desktop application's local token storage and IPC.
- The voice gateway's WebSocket handshake and frame validation.
- The LLM service's enforcement of `LocalOnlyEnforcer` (i.e. it must refuse cloud LLM URLs in non-test profiles).

Out of scope (acknowledged limitations, not vulnerabilities):

- Multi-tenant isolation. Jarvis v1.0 is single-user by design.
- Public-internet exposure. v1.0 is intended for the user's host machine and the trusted LAN.
- Phishing of the desktop app login dialog. The desktop app is not internet-facing.
- Denial-of-service via excessive local resource use (the runtime intentionally trusts the host operator).
- Issues in upstream dependencies that have public CVEs and are tracked in our dependency-update queue, unless you have evidence of an exploit chain specific to Jarvis.

## Known Hardening Items

This project tracks security work in [docs/security/SECURITY_HARDENING_PLAN.md](docs/security/SECURITY_HARDENING_PLAN.md). The current open P0 items as of 2026-05-09:

- **F-001** — JWT fail-closed default: **fixed**, see `apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthFilter.java`.
- **F-002** — Distinct service JWT secret: **fixed**, see `apps/jarvis-common/src/main/java/org/jarvis/common/security/ServiceJwtProvider.java`. Production must set `SERVICE_JWT_SECRET` independently of `JWT_SECRET`.
- **F-005** — CORS allowed-headers: in progress (see docs/audit/JARVIS_AUDIT_REPORT.md P1-010).
- **F-007** — Per-jti access-token revocation: open. Documented limitation; access-token TTL should be shortened in v1.1.
- **F-009** — Public actuator: open. Mitigation requires NetworkPolicy and ingress strip; documented in audit P1-007.

## Canonical Security Documents

| Document | Purpose |
| --- | --- |
| [docs/security/SECURITY.md](docs/security/SECURITY.md) | Long-form security overview: scope, trust boundaries, dual-plane auth, K8s posture, exposed endpoints. |
| [docs/security/SECURITY_AUDIT.md](docs/security/SECURITY_AUDIT.md) | Per-finding audit (F-001 .. F-013) with evidence and recommended fix. |
| [docs/security/SECURITY_HARDENING_PLAN.md](docs/security/SECURITY_HARDENING_PLAN.md) | Prioritized backlog (P0 / P1 / P2) of hardening work. |
| [docs/security/SECURITY_COMPONENT_STATUS.md](docs/security/SECURITY_COMPONENT_STATUS.md) | Per-component matrix of what is / is not hardened. |
| [docs/security/AUTH_MODEL.md](docs/security/AUTH_MODEL.md) | Formal dual-plane auth model: user JWT issuer (security-service), service JWT issuer (jarvis-common). |
| [docs/security/SECRETS_POLICY.md](docs/security/SECRETS_POLICY.md) | Where secrets live, how they rotate, what the `.gitignore` guards. |
| [docs/audit/JARVIS_AUDIT_REPORT.md](docs/audit/JARVIS_AUDIT_REPORT.md) | 2026-05-09 senior-engineer audit pass. |

## Disclosure Timeline Once a Fix is Ready

1. Coordinated disclosure: report → triage → fix in private branch → tag a release.
2. Public CVE filed via GitHub Security Advisory at the same time the patched release lands.
3. Reporter credited in the advisory unless they request anonymity.
