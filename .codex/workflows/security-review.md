# Security Review Workflow

Jarvis project convention.

## Checklist

- [ ] inspect auth boundaries and token issuance
- [ ] inspect service-to-service access paths
- [ ] inspect access-token and refresh-token separation
- [ ] inspect secret handling and `docs/security/SECRETS_POLICY.md`
- [ ] inspect CORS and origin assumptions
- [ ] inspect WebSocket or session security
- [ ] inspect dangerous local actions and command execution paths
- [ ] inspect audit logging and traceability

## Focus Areas

- `apps/security-service`
- `apps/api-gateway`
- `apps/voice-gateway`
- orchestrator actions that trigger external or local side effects
- deployment scripts that handle secrets or certificates

## Output

- verified protections
- gaps and risky assumptions
- severity-ordered findings
- required fixes before release
