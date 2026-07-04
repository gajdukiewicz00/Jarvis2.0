#!/usr/bin/env bash
# =============================================================================
# fix-sync-auth.sh — one-shot operator fix for the Android phone (pairing 401).
#
# sync-service is the device-facing, local-first sync endpoint. The phone pairs
# over an end-to-end encrypted handshake (Ed25519 + X25519) and every payload is
# ChaCha20-Poly1305 sealed, so the HTTP channel is intentionally open. Spring
# Boot's default security auto-config (pulled in transitively) accidentally locks
# every endpoint behind HTTP Basic, which a paired device cannot satisfy → 401.
#
# This disables that accidental auto-config (the documented intended state).
# It is an explicit operator action because it opens an unauthenticated endpoint
# on the LAN — confidentiality/integrity come from the E2E envelope, not the wire.
#
#   Run:  bash scripts/fix-sync-auth.sh
# =============================================================================
set -uo pipefail
NS="${JARVIS_NAMESPACE:-jarvis-prod}"
NODE_IP="${JARVIS_HOST_IP:-10.113.0.176}"
NODEPORT="${JARVIS_SYNC_NODEPORT:-30095}"

KC=(sudo k3s kubectl)
command -v kubectl >/dev/null 2>&1 && kubectl -n "$NS" get ns >/dev/null 2>&1 && KC=(kubectl)

# Build the (long) exclude value from short pieces so nothing wraps in a terminal.
# NOTE: ManagementWebSecurityAutoConfiguration (actuator) also needs an HttpSecurity
# bean, so it MUST be excluded too or the context fails to start (CrashLoopBackOff).
P=org.springframework.boot.autoconfigure.security.servlet
M=org.springframework.boot.actuate.autoconfigure.security.servlet
EXCLUDE="$P.UserDetailsServiceAutoConfiguration,$P.SecurityAutoConfiguration,$P.SecurityFilterAutoConfiguration,$M.ManagementWebSecurityAutoConfiguration"

echo "==> Setting SPRING_AUTOCONFIGURE_EXCLUDE on sync-service (disable accidental Basic auth)…"
"${KC[@]}" -n "$NS" set env deploy/sync-service "SPRING_AUTOCONFIGURE_EXCLUDE=$EXCLUDE"

echo "==> Waiting for rollout…"
if ! "${KC[@]}" -n "$NS" rollout status deploy/sync-service --timeout=150s; then
  echo "⚠️  Rollout did not become ready — auto-rolling back to keep the cluster green."
  "${KC[@]}" -n "$NS" rollout undo deploy/sync-service
  "${KC[@]}" -n "$NS" rollout status deploy/sync-service --timeout=120s || true
  echo "Rolled back. Inspect: ${KC[*]} -n ${NS} logs deploy/sync-service --tail=50"
  exit 1
fi

echo "==> Verifying pairing/init (expect a JSON nonce, NOT 401):"
sleep 3
CODE="$(curl -s -m10 -o /tmp/sync_init.$$ -w '%{http_code}' -X POST \
  "http://${NODE_IP}:${NODEPORT}/api/v1/sync/pairing/init" -d '' 2>/dev/null)"
echo "    HTTP ${CODE}"
head -c 300 /tmp/sync_init.$$ 2>/dev/null; echo; rm -f /tmp/sync_init.$$

if [ "$CODE" = "200" ]; then
  echo "✅ DONE — sync-service is open for pairing. Try 'Спарить' on the phone again."
  echo "   Phone Server URL:  http://${NODE_IP}:${NODEPORT}"
else
  echo "⚠️  Still HTTP ${CODE}. Check: ${KC[*]} -n ${NS} logs deploy/sync-service --tail=50"
fi
