#!/usr/bin/env bash
set -euo pipefail

# Enrolls the owner from a curated dataset via the vision-security-service API.
#
# Prereqs:
#   - vision-security-service running on VISION_SECURITY_URL
#   - A valid JWT in JARVIS_TOKEN (or pass --token)
#   - Dataset directory built by curate-owner-dataset.py
#
# Usage:
#   scripts/enroll-from-dataset.sh \
#       --user <userId> \
#       --dataset apps/vision-security-service/dataset/owner/enrollment \
#       [--url http://localhost:8094] \
#       [--token <jwt>]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VISION_URL="${VISION_SECURITY_URL:-http://localhost:8094}"
TOKEN="${JARVIS_TOKEN:-}"
USER_ID=""
DATASET_DIR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --user)   USER_ID="$2"; shift 2 ;;
        --dataset) DATASET_DIR="$2"; shift 2 ;;
        --url)    VISION_URL="$2"; shift 2 ;;
        --token)  TOKEN="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [[ -z "$USER_ID" ]]; then
    echo "ERROR: --user is required"
    exit 1
fi

if [[ -z "$DATASET_DIR" ]]; then
    DATASET_DIR="$REPO_ROOT/apps/vision-security-service/dataset/owner/enrollment"
fi

DATASET_DIR="$(cd "$DATASET_DIR" && pwd)"

if [[ ! -d "$DATASET_DIR" ]]; then
    echo "ERROR: Dataset directory not found: $DATASET_DIR"
    exit 1
fi

IMAGE_COUNT=$(find "$DATASET_DIR" -maxdepth 1 -type f \( -name "*.jpg" -o -name "*.png" -o -name "*.jpeg" \) | wc -l)
echo "Dataset: $DATASET_DIR ($IMAGE_COUNT images)"
echo "Service: $VISION_URL"
echo "User:    $USER_ID"
echo ""

AUTH_HEADER=""
if [[ -n "$TOKEN" ]]; then
    AUTH_HEADER="Authorization: Bearer $TOKEN"
fi

echo "Calling enrollment import endpoint..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "${VISION_URL}/api/v1/vision-security/enrollment/import" \
    -H "Content-Type: application/json" \
    ${AUTH_HEADER:+-H "$AUTH_HEADER"} \
    -d "{\"datasetDirectory\": \"$DATASET_DIR\"}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo ""
echo "HTTP Status: $HTTP_CODE"
echo "Response:"
echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"

if [[ "$HTTP_CODE" == "200" ]]; then
    echo ""
    echo "Enrollment successful."
else
    echo ""
    echo "Enrollment failed (HTTP $HTTP_CODE)."
    exit 1
fi
