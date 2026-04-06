#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARVIS_HOME="${JARVIS_HOME:-${HOME}/.jarvis}"
RUNTIME_ENV_FILE="${JARVIS_HOME}/run/local-runtime/local.env"
if [[ -f "${RUNTIME_ENV_FILE}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${RUNTIME_ENV_FILE}"
    set +a
fi

LIFE_TRACKER_URL="${LIFE_TRACKER_URL:-http://127.0.0.1:${JARVIS_LIFE_TRACKER_PORT:-8085}}"
ANALYTICS_URL="${ANALYTICS_URL:-http://127.0.0.1:${JARVIS_ANALYTICS_PORT:-8087}}"
TIMEOUT_SEC="${TIMEOUT_SEC:-30}"
TEST_USER="analytics-smoke-$(date +%s)"

if [[ -z "${SERVICE_JWT_SECRET:-}" ]]; then
    echo "SERVICE_JWT_SECRET is required. Start the canonical local runtime first." >&2
    exit 1
fi

SERVICE_TOKEN="$(python3 "${SCRIPT_DIR}/runtime/make_service_jwt.py" \
    --secret "${SERVICE_JWT_SECRET}" \
    --subject "analytics-smoke" \
    --service "analytics-smoke")"

AUTH_HEADERS=(
    -H "Authorization: Bearer ${SERVICE_TOKEN}"
    -H "X-User-Id: ${TEST_USER}"
    -H "X-User-Roles: ROLE_USER"
    -H "Content-Type: application/json"
)

call_json() {
    local method="$1"
    local url="$2"
    local payload="${3:-}"
    if [[ -n "${payload}" ]]; then
        curl -fsS --max-time "${TIMEOUT_SEC}" "${AUTH_HEADERS[@]}" -X "${method}" -d "${payload}" "${url}"
    else
        curl -fsS --max-time "${TIMEOUT_SEC}" "${AUTH_HEADERS[@]}" -X "${method}" "${url}"
    fi
}

echo "Seeding life-tracker data for ${TEST_USER}..."
call_json POST "${LIFE_TRACKER_URL}/api/v1/life/finance/transaction" '{
  "amount": 12.50,
  "currency": "EUR",
  "category": "Food",
  "description": "Lunch",
  "type": "EXPENSE",
  "occurredAt": "2026-03-13T12:00:00"
}' >/dev/null

call_json POST "${LIFE_TRACKER_URL}/api/v1/life/finance/transaction" '{
  "amount": 35.00,
  "currency": "EUR",
  "category": "Transport",
  "description": "Train ticket",
  "type": "EXPENSE",
  "occurredAt": "2026-03-14T09:30:00"
}' >/dev/null

call_json POST "${LIFE_TRACKER_URL}/api/v1/life/calendar/event" '{
  "title": "Jarvis planning",
  "description": "Smoke test event",
  "startTime": "2026-03-21T10:00:00",
  "endTime": "2026-03-21T11:00:00",
  "allDay": false,
  "location": "Home office",
  "timezone": "Europe/Warsaw"
}' >/dev/null

call_json POST "${LIFE_TRACKER_URL}/api/v1/life/time/start" '{
  "activity": "Smoke test coding",
  "category": "Work"
}' >/dev/null
sleep 2
call_json POST "${LIFE_TRACKER_URL}/api/v1/life/time/stop" '{}' >/dev/null

echo "Checking analytics overview..."
OVERVIEW="$(call_json GET "${ANALYTICS_URL}/api/v1/analytics/overview")"
python3 - "${OVERVIEW}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["expenseCount"] >= 2, payload
assert payload["timeRecordCount"] >= 1, payload
PY

echo "Checking analytics expense category aggregation..."
BY_CATEGORY="$(call_json GET "${ANALYTICS_URL}/api/v1/analytics/expenses/by-category")"
python3 - "${BY_CATEGORY}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
categories = {item["category"] for item in payload}
assert "Food" in categories, payload
assert "Transport" in categories, payload
PY

echo "Checking analytics calendar summary..."
CALENDAR_SUMMARY="$(call_json GET "${ANALYTICS_URL}/api/v1/analytics/calendar/summary")"
python3 - "${CALENDAR_SUMMARY}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["totalEvents"] >= 1, payload
PY

echo "Checking analytics time summary..."
TIME_SUMMARY="$(call_json GET "${ANALYTICS_URL}/api/v1/analytics/time/summary")"
python3 - "${TIME_SUMMARY}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert len(payload) >= 1, payload
assert any(item["category"] == "Work" for item in payload), payload
PY

echo "Analytics smoke test passed for ${TEST_USER}."
