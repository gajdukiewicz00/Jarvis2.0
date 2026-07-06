# Runbook: Security Key Rotation (`JWT_SECRET` / `SERVICE_JWT_SECRET`)

## Scope

Jarvis has two independent HMAC secrets (see `docs/security/AUTH_MODEL.md` at the
repo root for the full dual-plane model):

| Secret | Plane | Signs/verifies | Config key | Owning code |
|---|---|---|---|---|
| `JWT_SECRET` | User-auth plane | User access + refresh tokens | `jarvis.jwt.secret` | `apps/security-service/.../service/JwtService.java` (issuer), `apps/api-gateway/.../security/JwtAuthFilter.java` + `JwtUtil` (edge validator) |
| `SERVICE_JWT_SECRET` | Internal service trust plane | Service-to-service tokens (`X-Service-Token`) | `service.jwt.secret` | `apps/jarvis-common/.../security/ServiceJwtProvider.java`, consumed by every service that mints/validates `X-Service-Token` (api-gateway, analytics-service, smart-home-service, life-tracker, ...) |

This runbook covers rotating either secret. **Read the constraints below before
rotating anything** - the current implementation does not support the kind of
overlapping dual-key rotation you may be used to from other systems.

## Hard constraints (read first)

Both `JwtService` (security-service) and `ServiceJwtProvider` (jarvis-common)
are explicitly single-key implementations:

- One active HMAC secret per plane, read once at startup via `@Value`.
- No `kid` claim, no key ring, no multi-key verification path.
- Changing the secret is a **hard cutover**: the instant a process reloads
  with the new secret, every token signed with the old secret fails signature
  verification (`JwtException` -> `401 INVALID_TOKEN` for user tokens; the
  equivalent rejection for service tokens) on its very next use. There is no
  grace window where both old- and new-signed tokens validate.

Practical effect of rotating `JWT_SECRET`:

- Every logged-in user is forced to log in again (all outstanding access
  tokens *and* refresh tokens stop validating). This is expected, not a bug.
- `security-service` and `api-gateway` **must** both be updated and restarted
  with the same new secret - they each parse/verify user tokens independently
  (`api-gateway`'s `JwtAuthFilter`/`JwtUtil` verify at the edge; they do not
  call back into `security-service` to validate a token).

Practical effect of rotating `SERVICE_JWT_SECRET`:

- Every in-flight and cached service-to-service token (`X-Service-Token`)
  stops validating. Any service that mints or validates it must be updated
  and restarted with the same new secret. `service.jwt.secret` defaults to
  `${SERVICE_JWT_SECRET:${JWT_SECRET}}` in most services' `application.yml`
  purely as a local-dev convenience - **production must set
  `SERVICE_JWT_SECRET` explicitly and independently of `JWT_SECRET`** (see
  `docs/security/SECURITY_HARDENING_PLAN.md`, item F-002). Before rotating,
  grep each service's `application.yml`/`application.yaml` for
  `SERVICE_JWT_SECRET` to get the current, authoritative list of consumers -
  it changes as services are added.

There is no code-level "zero session interruption" rotation today. The
"zero-downtime" procedure below means **zero service/API outage** (rolling
restarts, no failed health checks, no dropped requests) - it does **not**
mean existing sessions or in-flight service calls survive the rotation. If
true overlapping dual-key rotation becomes a requirement, `JwtService` and
`ServiceJwtProvider` would need a `kid`-tagged key ring (accept N most-recent
keys for verification, sign with the newest); that is out of scope for this
runbook.

## How `tokens_valid_from` interacts (or doesn't)

`tokens_valid_from` (see `V6__add_tokens_valid_from_to_users.sql`,
`AuthService.isBeforeSessionFloor`) is a **per-user** access-token validity
floor, bumped only by `TokenRevocationService.revokeAllForUser` (the
OWNER-only "revoke all sessions" endpoint and its self-service sibling,
`revokeOwnSession`). It is checked *after* JWT signature verification
succeeds - `JwtService.parseClaims` rejects a bad signature before
`AuthService` ever looks at `tokensValidFrom`.

Consequences:

- **You do not need to touch `tokens_valid_from` as part of a `JWT_SECRET`
  rotation.** Every token signed with the old secret already fails at the
  signature-check step; the per-user floor is never reached for those
  tokens. Rotating the secret is a strictly stronger, global cutover than
  bumping every user's floor would be.
- `tokens_valid_from` remains meaningful only for tokens that pass
  verification under the **new** secret - i.e. sessions established (or
  re-established) after the rotation. If, after rotating, you additionally
  need to force out one specific user's post-rotation session (e.g. you
  believe their new credentials were also compromised), use the existing
  `POST /auth/revoke-all/{userId}` endpoint exactly as you would outside a
  rotation - it is unaffected by, and unrelated to, the secret rotation
  itself.
- The per-jti `revoked_tokens` table is likewise orthogonal to secret
  rotation: entries created before the rotation become moot (their
  underlying tokens can no longer pass signature verification anyway), and
  no cleanup of that table is required as part of a rotation.
- To confirm a rotation actually invalidated everything, and to get an
  auditable record of it, use the audit viewer added alongside this runbook:
  `GET /auth/audit/events?eventType=TOKEN_REVOKED_ALL` (OWNER-only) shows any
  explicit revoke-all actions taken around the rotation window; a spike in
  `401 INVALID_TOKEN` responses at the gateway immediately after rotation is
  the expected signature-based cutover working as intended, not something to
  alert on.

## Pre-rotation checklist

- [ ] Confirm the reason for rotation (scheduled hygiene vs. suspected
      compromise) - it changes the urgency but not the mechanics below.
- [ ] Identify every service that must change in lockstep:
      - `JWT_SECRET`: `security-service`, `api-gateway` (both mandatory).
      - `SERVICE_JWT_SECRET`: grep `application.yml`/`application.yaml` in
        every `apps/*` service for `SERVICE_JWT_SECRET` / `service.jwt.secret`
        and include all matches.
- [ ] Schedule a maintenance window or at least notify users/operators that
      **all active sessions will be force-logged-out** the moment the
      rotation completes.
- [ ] Generate the new secret out-of-band, e.g. `openssl rand -base64 48`,
      and place it in your secret manager / deployment config - never commit
      it to source control (`secrets/secrets.example.env` is a template only).
- [ ] Confirm you can roll back the deployment config to the previous secret
      quickly if the rotation reveals an unrelated deployment problem.

## Procedure A: rotate `JWT_SECRET` (user-auth plane)

1. Generate the new secret value (`openssl rand -base64 48` or your secret
   manager's equivalent) and stage it in the deployment config for **both**
   `security-service` and `api-gateway` - do not roll out to only one.
2. Announce the maintenance window; expect every currently logged-in user to
   need to log in again once step 3 completes.
3. Update the `JWT_SECRET` value used by `security-service` and `api-gateway`
   and restart both. `JwtService`'s secret is read once in its constructor
   via `@Value("${jarvis.jwt.secret}")` - there is no live-reload, a process
   restart is required for the new value to take effect. Restart order
   between the two services does not matter functionally, since both must
   end up on the new secret before either is considered "rotated" - but
   restarting `security-service` first avoids a window where the gateway
   would reject tokens that `security-service` could otherwise still mint
   correctly.
4. Verify: hit `security-service`'s `/auth/health` and `api-gateway`'s health
   endpoint; confirm both are healthy post-restart.
5. Verify the new secret is live end-to-end: perform a fresh
   `POST /auth/login`, confirm the returned access token is accepted by
   `GET /auth/me` through `api-gateway`, and confirm `POST /auth/refresh`
   rotates successfully.
6. Verify the old secret is dead: attempt to use an access token issued
   before the rotation against `GET /auth/me` - expect `401` /
   `INVALID_TOKEN`. This is the expected outcome for every previously issued
   token, not a regression.
7. Communicate that all users must log in again. No database migration or
   `tokens_valid_from` bump is required or useful here (see above).
8. Optional, for incident/compliance record-keeping: call
   `POST /auth/revoke-all/{userId}` for any specific accounts you want an
   explicit, queryable revocation record for (visible later via
   `GET /auth/audit/events?userId=...&eventType=TOKEN_REVOKED_ALL`), even
   though the secret rotation alone already made their prior tokens unusable.

## Procedure B: rotate `SERVICE_JWT_SECRET` (internal service trust plane)

1. Grep every service under `apps/*` for `SERVICE_JWT_SECRET` /
   `service.jwt.secret` to get the current, authoritative consumer list (it
   changes as services are added - do not rely on a stale list).
2. Generate the new secret (`openssl rand -base64 48`) and stage it for
   every consuming service.
3. Roll out the new secret and restart all consuming services together (or
   within as tight a window as your deployment tooling allows). Because
   `service.jwt.secret` in most services falls back to `${JWT_SECRET}` when
   `SERVICE_JWT_SECRET` is unset, double-check `SERVICE_JWT_SECRET` is
   explicitly set everywhere in production before rotating - relying on the
   fallback means a `JWT_SECRET` rotation would silently also rotate the
   internal service secret (or vice versa), which is very likely not what
   you want given the two planes are meant to be independent
   (`SECURITY_HARDENING_PLAN.md` F-002).
4. Verify: confirm proxied calls through `api-gateway` to a downstream
   service (e.g. `smart-home-service`) succeed post-restart, and that
   service logs show no `X-Service-Token` verification failures after the
   rollout window closes.
5. Expect a burst of service-to-service auth failures for any request that
   was already in flight with an old-secret token at the moment of restart;
   this is the same hard-cutover behavior as Procedure A, scoped to the
   internal plane. Retries at the calling layer should recover automatically
   once all consumers are on the new secret.

## Post-rotation

- [ ] Remove the old secret value from wherever it was staged (secret
      manager history/versioning aside - do not leave it in a shell history,
      ad-hoc file, or chat log).
- [ ] Record the rotation (date, reason, operator) in your incident/change
      log.
- [ ] If the rotation was compromise-driven, treat it as a security incident
      in full: confirm the rotation actually closed the exposure, review the
      rest of the codebase/config for the same class of issue, and document
      the timeline - don't stop at rotating the one secret.
