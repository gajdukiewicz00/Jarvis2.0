#!/usr/bin/env bash
# =============================================================================
# Open a live camera preview window for vision-security-service.
# =============================================================================
# Shows what the vision pipeline sees WITH detection/recognition overlays:
#   green box  = OWNER       (matched the enrolled owner)
#   red box    = UNKNOWN     (stranger)
#   yellow box = UNCERTAIN   (no enrollment, or score between thresholds)
# plus the raw LBPH distance per face and the aggregate Decision banner.
#
# The /preview endpoints are loopback-only, so this must run on the host.
#
# Usage:
#   ./scripts/product/jarvis-vision-preview.sh                 # owner=<none> (detect-only)
#   ./scripts/product/jarvis-vision-preview.sh --user=test1111 # classify against that enrolled owner
#   ./scripts/product/jarvis-vision-preview.sh --port=8094
# =============================================================================
set -euo pipefail

USER_ID="${JARVIS_VISION_PREVIEW_USER:-}"
PORT="${JARVIS_VISION_SECURITY_PORT:-8094}"

for arg in "$@"; do
    case "${arg}" in
        --user=*) USER_ID="${arg#*=}" ;;
        --port=*) PORT="${arg#*=}" ;;
        --help|-h)
            grep -E '^#( |!)' "$0" | sed -E 's/^#! ?//; s/^# ?//'
            exit 0
            ;;
        *) echo "❌ Unknown argument: ${arg}" >&2; exit 1 ;;
    esac
done

BASE="http://127.0.0.1:${PORT}/api/v1/vision-security/preview"
STREAM_URL="${BASE}/stream?user=$(printf '%s' "${USER_ID}" | sed 's/ /%20/g')"

# Confirm the local service is actually up before opening a window.
if ! curl -fsS -m 5 "http://127.0.0.1:${PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    echo "❌ vision-security-service is not healthy on :${PORT}." >&2
    echo "   Start it first: ./scripts/product/jarvis-vision-security-up.sh" >&2
    exit 1
fi

echo "Opening live vision preview (owner='${USER_ID:-<none>}')"
echo "  stream: ${STREAM_URL}"

if command -v ffplay >/dev/null 2>&1; then
    exec ffplay -hide_banner -loglevel warning -autoexit \
        -window_title "JARVIS Vision — what Jarvis sees" \
        -f mpjpeg -i "${STREAM_URL}"
elif command -v xdg-open >/dev/null 2>&1; then
    echo "ffplay not found — opening the MJPEG stream in your browser instead."
    exec xdg-open "${STREAM_URL}"
else
    echo "No ffplay or xdg-open available. Open this URL in a browser on the host:"
    echo "  ${STREAM_URL}"
fi
