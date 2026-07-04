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

LLM_SMOKE_PORT="${JARVIS_RUNTIME_SMOKE_LLM_PORT:-15000}"
export LLM_SERVER_PORT="${LLM_SMOKE_PORT}"
export LLM_SERVER_URL="http://127.0.0.1:${LLM_SMOKE_PORT}"

ensure_local_env
SMOKE_INCLUDE_LLM="true"
if is_truthy "${JARVIS_RUNTIME_SMOKE_SKIP_LLM:-false}"; then
    SMOKE_INCLUDE_LLM="false"
fi

PUBLIC_API_BASE_URL="$(runtime_api_base_url)"
VOICE_WS_URL="$(runtime_voice_ws_url)"
PC_WS_URL="$(runtime_pc_ws_url)"
VOICE_GATEWAY_BASE_URL="$(runtime_local_http_url "${JARVIS_VOICE_GATEWAY_PORT}")"
RUNTIME_JAVA_TOOL_OPTIONS="$(runtime_java_tool_options "${JAVA_TOOL_OPTIONS:-}")"

export ENABLE_LLM="${SMOKE_INCLUDE_LLM}"
export JARVIS_LLM_ENABLED="${SMOKE_INCLUDE_LLM}"
export ENABLE_MEMORY="false"
export MEMORY_SERVICE_ENABLED="false"
export JARVIS_LLM_MANAGED_SERVER="false"
export JARVIS_SKIP_BUILD="${JARVIS_SKIP_BUILD:-false}"

LLM_CAPTURE_FILE="${RUNTIME_DIR}/llm-capture.jsonl"
PC_OUTPUT_FILE="${RUNTIME_DIR}/pc-probe.log"
PC_READY_FILE="${RUNTIME_DIR}/pc-probe.ready"
VOICE_OUTPUT_FILE="${RUNTIME_DIR}/voice-probe.log"
VOICE_READY_FILE="${RUNTIME_DIR}/voice-probe.ready"
VOICE_WS_WAV_FILE="${RUNTIME_DIR}/voice-ws-roundtrip.wav"
VOICE_WS_TRACE_FILE="${RUNTIME_DIR}/voice-ws-roundtrip.log"
VOICE_TIMEOUT_TRACE_FILE="${RUNTIME_DIR}/voice-ws-timeout.log"
STUB_LOG_FILE="${LOG_DIR}/llm-server-stub.log"

rm -f \
    "${LLM_CAPTURE_FILE}" \
    "${PC_OUTPUT_FILE}" \
    "${PC_READY_FILE}" \
    "${VOICE_OUTPUT_FILE}" \
    "${VOICE_READY_FILE}" \
    "${VOICE_WS_WAV_FILE}" \
    "${VOICE_WS_TRACE_FILE}" \
    "${VOICE_TIMEOUT_TRACE_FILE}"

if [[ "${SMOKE_INCLUDE_LLM}" == "true" ]]; then
    log "Starting local LLM smoke stub..."
    python3 "${ROOT_DIR}/scripts/runtime/llm_server_stub.py" \
        --port "${LLM_SMOKE_PORT}" \
        --capture "${LLM_CAPTURE_FILE}" >"${STUB_LOG_FILE}" 2>&1 &
    printf '%s\n' "$!" >"${RUNTIME_DIR}/llm-server-stub.pid"

    for _ in $(seq 1 20); do
        if curl -fsS "${LLM_SERVER_URL}/health" >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
    curl -fsS "${LLM_SERVER_URL}/health" >/dev/null
else
    log "Skipping LLM smoke stub (JARVIS_RUNTIME_SMOKE_SKIP_LLM=true)."
fi

log "Bringing up local Jarvis runtime..."
"${ROOT_DIR}/scripts/runtime-up.sh"

register_response="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/auth/register" -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"runtime-smoke\",\"password\":\"RuntimeSmoke!123\",\"role\":\"USER\"}" || true)"

if [[ -z "${register_response}" ]]; then
    register_response="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/auth/login" -fsS \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"runtime-smoke\",\"password\":\"RuntimeSmoke!123\"}")"
fi

ACCESS_TOKEN="$(python3 - "${register_response}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
print(payload["accessToken"])
PY
)"

USERNAME="runtime-smoke"
USER_ME_RESPONSE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/security/auth/me" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    )"
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
JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/WsProbe.java" \
    pc \
    "${PC_WS_URL}" \
    "${PC_OUTPUT_FILE}" \
    "${PC_READY_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "${USERNAME}" >"${LOG_DIR}/pc-probe.log" 2>&1 &
printf '%s\n' "$!" >"${RUNTIME_DIR}/pc-probe.pid"

JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/WsProbe.java" \
    voice \
    "${VOICE_WS_URL}" \
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
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/orchestrator/execute" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"сделай громче"}' >/dev/null

for _ in $(seq 1 20); do
    if grep -q '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" || fail "Desktop probe did not receive orchestrator action"

log "Checking voice runtime status endpoint..."
VOICE_READINESS_STATUS="$(curl -fsS "${VOICE_GATEWAY_BASE_URL}/actuator/health/readiness")"
python3 - "${VOICE_READINESS_STATUS}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["status"] in {"UP", "DEGRADED"}, payload["status"]
components = payload["components"]
assert set(components.keys()) == {"stt", "tts", "assets", "orchestrator", "websocket"}, components
assert components["stt"] == "UP", components
assert components["websocket"] == "UP", components
assert components["assets"] == "UP", components
assert components["orchestrator"] == "UP", components
assert components["tts"] in {"UP", "DOWN"}, components
PY

VOICE_RUNTIME_STATUS="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/runtime" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")"
python3 - "${VOICE_RUNTIME_STATUS}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["service"] == "voice-gateway", payload
assert payload["status"] in {"ready", "partial"}, payload["status"]
assert payload["localDefaultStack"]["id"] == "vosk+espeak-ng", payload["localDefaultStack"]
assert payload["localDefaultStack"]["sttProvider"] == "vosk", payload["localDefaultStack"]
assert payload["localDefaultStack"]["ttsProvider"] == "espeak", payload["localDefaultStack"]
assert payload["routing"]["publicHttpBasePath"] == "/api/v1/voice"
assert payload["routing"]["publicWebSocketPath"] == "/ws/voice"
assert payload["routing"]["notificationPath"] == "/internal/voice/notify"
assert payload["maturity"]["textCommandPath"] == "verified"
assert payload["maturity"]["voiceNotifications"] == "verified-text"
assert payload["readiness"]["status"] in {"UP", "DEGRADED"}
assert payload["readiness"]["components"]["stt"] == "UP"
assert payload["readiness"]["components"]["websocket"] == "UP"
assert payload["tts"]["status"] in {"available", "degraded", "unavailable", "disabled"}
assert isinstance(payload["tts"]["available"], bool)
assert payload["stt"]["configuredProvider"] in {"vosk", "whisper", "noop"}
PY

log "Checking voice diagnostics status endpoint..."
VOICE_DIAGNOSTICS_STATUS="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/diagnostics" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")"
python3 - "${VOICE_DIAGNOSTICS_STATUS}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["service"] == "voice-gateway", payload
assert payload["status"] in {"UP", "DEGRADED"}, payload["status"]
assert payload["capture"]["managedBy"] == "desktop-client", payload["capture"]
assert payload["capture"]["microphoneProbe"] == "not-applicable", payload["capture"]
assert payload["execution"]["primaryCommandLoop"] == "rule-based", payload["execution"]
assert payload["execution"]["orchestratorRequiredForFullCommandSet"] is True, payload["execution"]
assert payload["execution"]["runtimeCapabilitySource"] == "/api/v1/capabilities", payload["execution"]
assert payload["stt"]["componentStatus"] == "UP", payload["stt"]
assert payload["stt"]["working"] is True, payload["stt"]
assert payload["websocket"]["componentStatus"] == "UP", payload["websocket"]
assert payload["websocket"]["working"] is True, payload["websocket"]
assert payload["assets"]["componentStatus"] == "UP", payload["assets"]
assert "componentStatus" in payload["tts"], payload["tts"]
assert "componentStatus" in payload["orchestrator"], payload["orchestrator"]
assert payload["apiGatewayRoute"]["status"] == "UP", payload["apiGatewayRoute"]
PY

log "Checking voice websocket end-to-end roundtrip..."
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/synthesize" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -o "${VOICE_WS_WAV_FILE}" \
    -d '{"text":"volume up please","languageCode":"en-US","voiceName":"en-US-Wavenet-D"}'

JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_WS_TRACE_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "${USERNAME}" \
    roundtrip \
    runtime-smoke-ws \
    en-US \
    "${VOICE_WS_WAV_FILE}"

grep -q '"type":"TRANSCRIPT_PARTIAL"' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not emit partial transcripts"
grep -q '"type":"TRANSCRIPT_FINAL"' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not emit a final transcript"
grep -q '"type":"RESPONSE"' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not emit a RESPONSE frame"
grep -q '"action":"VOLUME_UP"' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not resolve the expected VOLUME_UP action"
grep -q '"executionSucceeded":true' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not execute successfully"
grep -q '^IN_BINARY bytes=' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not return voice audio"
grep -q '"state":"DONE"' "${VOICE_WS_TRACE_FILE}" || fail "Voice websocket roundtrip did not complete with DONE state"

log "Checking voice websocket timeout recovery..."
JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_TIMEOUT_TRACE_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "${USERNAME}" \
    timeout \
    runtime-smoke-timeout \
    en-US

grep -q '"code":"TIMEOUT"' "${VOICE_TIMEOUT_TRACE_FILE}" || fail "Voice websocket timeout scenario did not emit TIMEOUT error"
grep -q '"action":"STT_TIMEOUT"' "${VOICE_TIMEOUT_TRACE_FILE}" || fail "Voice websocket timeout scenario did not emit STT_TIMEOUT response"
grep -q '"state":"TIMEOUT"' "${VOICE_TIMEOUT_TRACE_FILE}" || fail "Voice websocket timeout scenario did not report TIMEOUT state"

log "Checking voice command proxy -> real orchestrator reply..."
voice_command_volume_up_before="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
VOICE_COMMAND_REPLY="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/command" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"сделай громче"}')"
python3 - "${VOICE_COMMAND_REPLY}" <<'PY'
import sys

reply = sys.argv[1].strip()
normalized = reply.lower()
assert reply, "voice command reply must not be empty"
assert reply != "Processed: сделай громче", "voice command must return the real orchestrator reply, not a placeholder"
assert "ошибка" not in normalized, f"voice command must not return a generic error reply: {reply!r}"
assert "не удалось" not in normalized, f"voice command must not return a capability failure reply: {reply!r}"
assert "error" not in normalized, f"voice command must not return an error reply: {reply!r}"
PY
for _ in $(seq 1 20); do
    voice_command_volume_up_after="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
    if [[ "${voice_command_volume_up_after}" -gt "${voice_command_volume_up_before}" ]]; then
        break
    fi
    sleep 1
done
voice_command_volume_up_after="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
[[ "${voice_command_volume_up_after}" -gt "${voice_command_volume_up_before}" ]] || fail "Voice command proxy did not trigger a new VOLUME_UP desktop action"

log "Checking planner focus action -> desktop scenario..."
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/planner/actions/focus-mode?mode=WORK" -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" >/dev/null

for _ in $(seq 1 20); do
    if grep -q '"action":"SCENARIO"' "${PC_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q '"action":"SCENARIO"' "${PC_OUTPUT_FILE}" || fail "Desktop probe did not receive planner scenario"

log "Checking planner reminder -> desktop and voice notifications..."
NOW_TS="$(date -u -Is)"
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/planner/reminders" -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"Runtime smoke reminder\",\"reminderTime\":\"${NOW_TS}\",\"reminderType\":\"ONCE\"}" >/dev/null

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
SMART_HOME_DEVICES="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    )"
python3 - "${SMART_HOME_DEVICES}" <<'PY'
import json
import sys

devices = json.loads(sys.argv[1])
assert any(device["id"] == "kitchen_light" for device in devices), "kitchen_light missing from registry"
PY

# Normalize the mock device into a known state so the smoke remains deterministic
# across repeated local runs.
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light/action" -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"action":"TURN_OFF"}' >/dev/null

SMART_HOME_DEVICE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    )"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["state"]["power"] is False, "kitchen_light baseline reset should leave the device off"
PY

curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light/action" -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"action":"TOGGLE"}' >/dev/null

SMART_HOME_DEVICE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    )"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["provider"] == "mock", "smart-home provider should default to mock for local runtime"
assert device["state"]["power"] is True, "kitchen_light should be on after TOGGLE"
PY

curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light/action" -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"action":"TURN_OFF"}' >/dev/null

SMART_HOME_DEVICE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    )"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["state"]["power"] is False, "kitchen_light should be off after TURN_OFF reset"
PY

log "Checking orchestrator smart-home command -> device state and desktop confirmation..."
SMART_HOME_REPLY="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/orchestrator/execute" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"включи кухонный свет"}')"
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

SMART_HOME_DEVICE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/smarthome/devices/kitchen_light" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    )"
python3 - "${SMART_HOME_DEVICE}" <<'PY'
import json
import sys

device = json.loads(sys.argv[1])
assert device["state"]["power"] is True, "kitchen_light should be on after orchestrator smart-home command"
PY

if [[ "${SMOKE_INCLUDE_LLM}" == "true" ]]; then
    log "Checking orchestrator LLM fallback with personalized goals..."
    curl -fsS \
        -X POST \
        -H "X-Service-Token: ${SERVICE_TOKEN}" \
        -H "X-User-Id: ${USER_ID}" \
        -H 'Content-Type: application/json' \
        -d '{"title":"Runtime smoke goal","description":"LLM should see this goal"}' \
        "${USER_PROFILE_URL}/api/v1/user-profile/${USER_ID}/goals" >/dev/null

    curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/orchestrator/execute" -fsS \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H 'Content-Type: application/json' \
        -H 'X-Correlation-ID: runtime-smoke-llm' \
        -d '{"text":"какие у меня цели"}' >/dev/null

    for _ in $(seq 1 20); do
        if grep -q 'Runtime smoke goal' "${LLM_CAPTURE_FILE}" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    grep -q 'Runtime smoke goal' "${LLM_CAPTURE_FILE}" || fail "LLM prompt did not include user goals"
else
    log "Skipping LLM personalization check (JARVIS_RUNTIME_SMOKE_SKIP_LLM=true)."
fi

log "Local runtime smoke passed."
