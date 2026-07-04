#!/usr/bin/env bash
# =============================================================================
# jarvis-memory-sync.sh — push a screen-context observation into the cluster
# memory-service (pgvector) so J.A.R.V.I.S. remembers what you were working on.
# =============================================================================
# Host -> nginx ingress (api.jarvis.local) -> api-gateway -> memory-service
# /api/v1/memory/ingest. Authenticated with a short-lived service JWT minted
# from $SERVICE_JWT_SECRET (operator-provided, the SAME secret the cluster uses
# — this script never reads the secret store, only the env var).
#
# No-op (exit 0) when SERVICE_JWT_SECRET is unset, so the local JSONL store
# remains the source of truth and callers never fail.
#
# Usage: jarvis-memory-sync.sh <sessionId> "<observation text>"
# Env:
#   SERVICE_JWT_SECRET   required to actually push (else skip)
#   JARVIS_GATEWAY_URL   default https://api.jarvis.local
#   JARVIS_USER          default owner
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="${JARVIS_GATEWAY_URL:-https://api.jarvis.local}"
USER_ID="${JARVIS_USER:-owner}"
SESSION="${1:-screen-context}"
TEXT="${2:-}"

[ -z "$TEXT" ] && exit 0
if [ -z "${SERVICE_JWT_SECRET:-}" ]; then
  echo "memory-sync: SERVICE_JWT_SECRET unset — skipping cluster write (local store only)"
  exit 0
fi

HELPER="${PROJECT_DIR}/scripts/runtime/make_service_jwt.py"
[ -f "$HELPER" ] || { echo "memory-sync: jwt helper missing — skip"; exit 0; }

TOKEN="$(python3 "$HELPER" --secret "$SERVICE_JWT_SECRET" \
          --service jarvis-host --subject jarvis-host --ttl-seconds 120 2>/dev/null)" \
  || { echo "memory-sync: token mint failed — skip"; exit 0; }

BODY="$(python3 -c 'import json,sys
print(json.dumps({
  "sessionId": sys.argv[1],
  "messages": [{"role": "user", "content": sys.argv[2]}],
  "metadata": {"source": "screen-context"}
}))' "$SESSION" "$TEXT")"

code="$(curl -sk -m 10 -o /dev/null -w '%{http_code}' -X POST "${GW%/}/api/v1/memory/ingest" \
        -H "Content-Type: application/json" \
        -H "X-Service-Token: ${TOKEN}" \
        -H "X-User-Id: ${USER_ID}" \
        --data "$BODY" 2>/dev/null)"
echo "memory-sync: cluster ingest -> HTTP ${code}"
exit 0   # best-effort: never fail the caller
