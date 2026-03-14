#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

cleanup() {
    local exit_code=$?

    for pid_file in \
        "${RUNTIME_DIR}/pc-probe.pid" \
        "${RUNTIME_DIR}/voice-probe.pid" \
        "${RUNTIME_DIR}/llm-server-stub.pid"; do
        if [[ -f "${pid_file}" ]]; then
            pid="$(cat "${pid_file}" 2>/dev/null || true)"
            rm -f "${pid_file}"
            if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
                kill "${pid}" >/dev/null 2>&1 || true
            fi
        fi
    done

    if is_truthy "${JARVIS_RUNTIME_SMOKE_STOP_ON_EXIT:-false}"; then
        "${ROOT_DIR}/scripts/runtime-down.sh" >/dev/null 2>&1 || true
    fi

    exit "${exit_code}"
}
trap cleanup EXIT

ensure_local_env
export ENABLE_LLM="true"
export JARVIS_SKIP_BUILD="${JARVIS_SKIP_BUILD:-false}"

LLM_CAPTURE_FILE="${RUNTIME_DIR}/llm-capture.jsonl"
PC_OUTPUT_FILE="${RUNTIME_DIR}/pc-probe.log"
PC_READY_FILE="${RUNTIME_DIR}/pc-probe.ready"
VOICE_OUTPUT_FILE="${RUNTIME_DIR}/voice-probe.log"
VOICE_READY_FILE="${RUNTIME_DIR}/voice-probe.ready"
STUB_LOG_FILE="${LOG_DIR}/llm-server-stub.log"

rm -f "${LLM_CAPTURE_FILE}" "${PC_OUTPUT_FILE}" "${PC_READY_FILE}" "${VOICE_OUTPUT_FILE}" "${VOICE_READY_FILE}"

log "Starting local LLM smoke stub..."
python3 "${ROOT_DIR}/scripts/runtime/llm_server_stub.py" \
    --port 5000 \
    --capture "${LLM_CAPTURE_FILE}" >"${STUB_LOG_FILE}" 2>&1 &
printf '%s\n' "$!" >"${RUNTIME_DIR}/llm-server-stub.pid"

for _ in $(seq 1 20); do
    if curl -fsS "http://127.0.0.1:5000/health" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
curl -fsS "http://127.0.0.1:5000/health" >/dev/null

log "Bringing up local Jarvis runtime..."
"${ROOT_DIR}/scripts/runtime-up.sh"

register_response="$(curl -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"runtime-smoke\",\"password\":\"RuntimeSmoke!123\",\"role\":\"USER\"}" \
    "http://127.0.0.1:8080/auth/register" || true)"

if [[ -z "${register_response}" ]]; then
    register_response="$(curl -fsS \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"runtime-smoke\",\"password\":\"RuntimeSmoke!123\"}" \
        "http://127.0.0.1:8080/auth/login")"
fi

ACCESS_TOKEN="$(python3 - "${register_response}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
print(payload["accessToken"])
PY
)"

USERNAME="runtime-smoke"
USER_ME_RESPONSE="$(curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://127.0.0.1:8080/api/v1/security/auth/me")"
USER_ID="$(python3 - "${USER_ME_RESPONSE}" <<'PY'
import json
import sys

print(json.loads(sys.argv[1])["id"])
PY
)"

SERVICE_TOKEN="$(python3 "${ROOT_DIR}/scripts/runtime/make_service_jwt.py" \
    --secret "${SERVICE_JWT_SECRET}" \
    --subject "runtime-smoke" \
    --service "runtime-smoke")"

log "Starting desktop and voice websocket probes..."
java "${ROOT_DIR}/scripts/runtime/WsProbe.java" \
    pc \
    "ws://127.0.0.1:8080/ws/pc-control" \
    "${PC_OUTPUT_FILE}" \
    "${PC_READY_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "${USERNAME}" >"${LOG_DIR}/pc-probe.log" 2>&1 &
printf '%s\n' "$!" >"${RUNTIME_DIR}/pc-probe.pid"

java "${ROOT_DIR}/scripts/runtime/WsProbe.java" \
    voice \
    "ws://127.0.0.1:8080/ws/voice" \
    "${VOICE_OUTPUT_FILE}" \
    "${VOICE_READY_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "${USERNAME}" >"${LOG_DIR}/voice-probe.log" 2>&1 &
printf '%s\n' "$!" >"${RUNTIME_DIR}/voice-probe.pid"

for _ in $(seq 1 20); do
    [[ -f "${PC_READY_FILE}" ]] && [[ -f "${VOICE_READY_FILE}" ]] && break
    sleep 1
done
[[ -f "${PC_READY_FILE}" ]] || fail "PC websocket probe did not connect"
[[ -f "${VOICE_READY_FILE}" ]] || fail "Voice websocket probe did not connect"

log "Checking orchestrator text command -> desktop action..."
curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"сделай громче"}' \
    "http://127.0.0.1:8080/api/v1/orchestrator/execute" >/dev/null

for _ in $(seq 1 20); do
    if grep -q '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" || fail "Desktop probe did not receive orchestrator action"

log "Checking planner focus action -> desktop scenario..."
curl -fsS \
    -X POST \
    -H "Authorization: Bearer ${SERVICE_TOKEN}" \
    -H "X-User-Id: ${USER_ID}" \
    "http://127.0.0.1:8092/api/v1/planner/actions/focus-mode?mode=WORK" >/dev/null

for _ in $(seq 1 20); do
    if grep -q '"action":"SCENARIO"' "${PC_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q '"action":"SCENARIO"' "${PC_OUTPUT_FILE}" || fail "Desktop probe did not receive planner scenario"

log "Checking planner reminder -> desktop and voice notifications..."
NOW_TS="$(date -u -Is)"
curl -fsS \
    -X POST \
    -H "Authorization: Bearer ${SERVICE_TOKEN}" \
    -H "X-User-Id: ${USER_ID}" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"Runtime smoke reminder\",\"reminderTime\":\"${NOW_TS}\",\"reminderType\":\"ONCE\"}" \
    "http://127.0.0.1:8092/api/v1/planner/reminders" >/dev/null

for _ in $(seq 1 80); do
    if grep -q 'Runtime smoke reminder' "${PC_OUTPUT_FILE}" 2>/dev/null && \
       grep -q 'Runtime smoke reminder' "${VOICE_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q 'Runtime smoke reminder' "${PC_OUTPUT_FILE}" || fail "Desktop notification was not delivered"
grep -q 'Runtime smoke reminder' "${VOICE_OUTPUT_FILE}" || fail "Voice notification was not delivered"

log "Checking smart-home registry and action execution..."
SMART_HOME_DEVICES="$(curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://127.0.0.1:8080/api/v1/smarthome/devices")"
python3 - "${SMART_HOME_DEVICES}" <<'PY'
import json
import sys

devices = json.loads(sys.argv[1])
assert any(device["id"] == "kitchen_light" for device in devices), "kitchen_light missing from registry"
PY

curl -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"action":"TOGGLE"}' \
    "http://127.0.0.1:8080/api/v1/smarthome/devices/kitchen_light/action" >/dev/null

SMART_HOME_DEVICE="$(curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://127.0.0.1:8080/api/v1/smarthome/devices/kitchen_light")"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["provider"] == "mock", "smart-home provider should default to mock for local runtime"
assert device["state"]["power"] is True, "kitchen_light should be on after TOGGLE"
PY

curl -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"action":"TURN_OFF"}' \
    "http://127.0.0.1:8080/api/v1/smarthome/devices/kitchen_light/action" >/dev/null

SMART_HOME_DEVICE="$(curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://127.0.0.1:8080/api/v1/smarthome/devices/kitchen_light")"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["state"]["power"] is False, "kitchen_light should be off after TURN_OFF reset"
PY

log "Checking orchestrator smart-home command -> device state and desktop confirmation..."
SMART_HOME_REPLY="$(curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"включи кухонный свет"}' \
    "http://127.0.0.1:8080/api/v1/orchestrator/execute")"
python3 - "${SMART_HOME_REPLY}" <<'PY'
import sys

reply = sys.argv[1].strip().lower()
assert "свет" in reply, f"smart-home assistant reply should mention the light, got: {reply!r}"
PY

for _ in $(seq 1 20); do
    if grep -q 'Kitchen Light is now on.' "${PC_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q 'Kitchen Light is now on.' "${PC_OUTPUT_FILE}" || fail "Desktop probe did not receive smart-home confirmation"

SMART_HOME_DEVICE="$(curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    "http://127.0.0.1:8080/api/v1/smarthome/devices/kitchen_light")"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["state"]["power"] is True, "kitchen_light should be on after orchestrator smart-home command"
PY

log "Checking orchestrator LLM fallback with personalized goals..."
curl -fsS \
    -X POST \
    -H "Authorization: Bearer ${SERVICE_TOKEN}" \
    -H "X-User-Id: ${USER_ID}" \
    -H 'Content-Type: application/json' \
    -d '{"title":"Runtime smoke goal","description":"LLM should see this goal"}' \
    "http://127.0.0.1:8089/api/v1/user-profile/${USER_ID}/goals" >/dev/null

curl -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -H 'X-Correlation-ID: runtime-smoke-llm' \
    -d '{"text":"какие у меня цели"}' \
    "http://127.0.0.1:8080/api/v1/orchestrator/execute" >/dev/null

for _ in $(seq 1 20); do
    if grep -q 'Runtime smoke goal' "${LLM_CAPTURE_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q 'Runtime smoke goal' "${LLM_CAPTURE_FILE}" || fail "LLM prompt did not include user goals"

log "Local runtime smoke passed."
