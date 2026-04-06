#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

cleanup() {
    local exit_code=$?

    if [[ -f "${RUNTIME_DIR}/voice-local-smoke-probe.pid" ]]; then
        local pid
        pid="$(cat "${RUNTIME_DIR}/voice-local-smoke-probe.pid" 2>/dev/null || true)"
        rm -f "${RUNTIME_DIR}/voice-local-smoke-probe.pid"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
            kill "${pid}" >/dev/null 2>&1 || true
        fi
    fi

    rm -f "${RUNTIME_DIR}/voice-local-smoke.ready" \
        "${RUNTIME_DIR}/voice-local-smoke.log" \
        "${RUNTIME_DIR}/voice-local-smoke.synth.wav" \
        "${RUNTIME_DIR}/voice-local-smoke.headers"

    if is_truthy "${JARVIS_VOICE_LOCAL_SMOKE_STOP_ON_EXIT:-false}"; then
        "${ROOT_DIR}/scripts/runtime-down.sh" >/dev/null 2>&1 || true
    fi

    exit "${exit_code}"
}
trap cleanup EXIT

export ENABLE_LLM="false"
export JARVIS_LLM_ENABLED="false"
export ENABLE_MEMORY="false"
export MEMORY_SERVICE_ENABLED="false"
export JARVIS_SKIP_BUILD="${JARVIS_SKIP_BUILD:-false}"

if ! is_truthy "${JARVIS_VOICE_SKIP_SETUP:-false}"; then
    log "Running canonical voice local setup..."
    "${ROOT_DIR}/scripts/setup-voice-local.sh"
fi

ensure_local_env

if [[ "$(voice_readiness_status)" != "full-audio ready" ]]; then
    fail "Canonical local voice stack is not ready: $(voice_readiness_status)"
fi

PUBLIC_API_BASE_URL="$(runtime_api_base_url)"
VOICE_WS_URL="$(runtime_voice_ws_url)"
RUNTIME_JAVA_TOOL_OPTIONS="$(runtime_java_tool_options "${JAVA_TOOL_OPTIONS:-}")"
VOICE_OUTPUT_FILE="${RUNTIME_DIR}/voice-local-smoke.log"
VOICE_READY_FILE="${RUNTIME_DIR}/voice-local-smoke.ready"
SYNTH_WAV_FILE="${RUNTIME_DIR}/voice-local-smoke.synth.wav"
SYNTH_HEADERS_FILE="${RUNTIME_DIR}/voice-local-smoke.headers"

rm -f "${VOICE_OUTPUT_FILE}" "${VOICE_READY_FILE}" "${SYNTH_WAV_FILE}" "${SYNTH_HEADERS_FILE}"

log "Bringing up local Jarvis runtime for voice full-audio smoke..."
"${ROOT_DIR}/scripts/runtime-up.sh"

register_response="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/auth/register" -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"voice-local-smoke\",\"password\":\"VoiceLocalSmoke!123\",\"role\":\"USER\"}" || true)"

if [[ -z "${register_response}" ]]; then
    register_response="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/auth/login" -fsS \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"voice-local-smoke\",\"password\":\"VoiceLocalSmoke!123\"}")"
fi

ACCESS_TOKEN="$(python3 - "${register_response}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
print(payload["accessToken"])
PY
)"

USER_ME_RESPONSE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/security/auth/me" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")"
USER_ID="$(python3 - "${USER_ME_RESPONSE}" <<'PY'
import json
import sys

print(json.loads(sys.argv[1])["id"])
PY
)"

SERVICE_TOKEN="$(python3 "${ROOT_DIR}/scripts/runtime/make_service_jwt.py" \
    --secret "${SERVICE_JWT_SECRET}" \
    --subject "voice-local-smoke" \
    --service "voice-local-smoke")"

log "Connecting voice websocket probe..."
JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/WsProbe.java" \
    voice \
    "${VOICE_WS_URL}" \
    "${VOICE_OUTPUT_FILE}" \
    "${VOICE_READY_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" >"${LOG_DIR}/voice-local-smoke-probe.log" 2>&1 &
printf '%s\n' "$!" >"${RUNTIME_DIR}/voice-local-smoke-probe.pid"

for _ in $(seq 1 20); do
    [[ -f "${VOICE_READY_FILE}" ]] && break
    sleep 1
done
[[ -f "${VOICE_READY_FILE}" ]] || fail "Voice websocket probe did not connect"

log "Checking voice runtime status..."
VOICE_RUNTIME_STATUS="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/runtime" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")"
python3 - "${VOICE_RUNTIME_STATUS}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["status"] == "ready", payload
assert payload["localDefaultStack"]["id"] == "vosk+espeak-ng", payload["localDefaultStack"]
assert payload["localDefaultStack"]["fullAudioReady"] is True, payload["localDefaultStack"]
assert payload["stt"]["configuredProvider"] == "vosk", payload["stt"]
assert payload["tts"]["configuredProvider"] == "espeak", payload["tts"]
assert payload["stt"]["available"] is True, payload["stt"]
assert payload["tts"]["available"] is True, payload["tts"]
PY

log "Checking voice websocket STATE payload..."
for _ in $(seq 1 20); do
    if grep -q '"type":"STATE"' "${VOICE_OUTPUT_FILE}" 2>/dev/null && \
       grep -q '"sttAvailable":true' "${VOICE_OUTPUT_FILE}" 2>/dev/null && \
       grep -q '"ttsAvailable":true' "${VOICE_OUTPUT_FILE}" 2>/dev/null; then
        break
    fi
    sleep 1
done
grep -q '"type":"STATE"' "${VOICE_OUTPUT_FILE}" || fail "Voice websocket did not emit STATE"
grep -q '"sttAvailable":true' "${VOICE_OUTPUT_FILE}" || fail "Voice websocket STATE did not advertise STT readiness"
grep -q '"ttsAvailable":true' "${VOICE_OUTPUT_FILE}" || fail "Voice websocket STATE did not advertise TTS readiness"

log "Checking voice command text path..."
VOICE_COMMAND_REPLY="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/command" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"сделай громче"}')"
python3 - "${VOICE_COMMAND_REPLY}" <<'PY'
import sys

reply = sys.argv[1].strip()
assert reply, "voice command reply must not be empty"
assert reply != "Processed: сделай громче", "voice command must not use placeholder replies"
PY

log "Checking synthesize endpoint..."
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/synthesize" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -D "${SYNTH_HEADERS_FILE}" \
    -o "${SYNTH_WAV_FILE}" \
    -d '{"text":"hello world hello world","languageCode":"en-US","voiceName":"en-US-Wavenet-D"}'

grep -qi '^content-type: audio/wav' "${SYNTH_HEADERS_FILE}" || fail "Synthesize endpoint did not return audio/wav"
grep -qi '^x-jarvis-tts-actual-provider: espeak' "${SYNTH_HEADERS_FILE}" || fail "Synthesize endpoint did not use espeak"

python3 - "${SYNTH_WAV_FILE}" <<'PY'
import sys
import wave

with wave.open(sys.argv[1], "rb") as wav:
    assert wav.getnchannels() == 1, wav.getnchannels()
    assert wav.getframerate() == 16000, wav.getframerate()
    assert wav.getsampwidth() == 2, wav.getsampwidth()
    assert wav.getnframes() > 0, wav.getnframes()
PY

log "Checking transcribe endpoint with synthesized WAV..."
TRANSCRIBE_RESPONSE="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/transcribe?language=en-US" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -F "file=@${SYNTH_WAV_FILE};type=audio/wav")"
python3 - "${TRANSCRIBE_RESPONSE}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["success"] is True, payload
assert payload["languageCode"] == "en-US", payload
assert payload["text"].strip(), payload
assert payload["stt"]["configuredProvider"] == "vosk", payload["stt"]
PY

log "Checking planner reminder delivery to voice websocket..."
binary_before="$(grep -c '"type":"BINARY"' "${VOICE_OUTPUT_FILE}" 2>/dev/null || true)"
NOW_TS="$(date -u -Is)"
curl -fsS \
    -X POST \
    -H "Authorization: Bearer ${SERVICE_TOKEN}" \
    -H "X-User-Id: ${USER_ID}" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"Voice local smoke reminder\",\"reminderTime\":\"${NOW_TS}\",\"reminderType\":\"ONCE\"}" \
    "${PLANNER_URL}/api/v1/planner/reminders" >/dev/null

for _ in $(seq 1 80); do
    binary_after="$(grep -c '"type":"BINARY"' "${VOICE_OUTPUT_FILE}" 2>/dev/null || true)"
    if grep -q 'Voice local smoke reminder' "${VOICE_OUTPUT_FILE}" 2>/dev/null && \
       [[ "${binary_after}" -gt "${binary_before}" ]]; then
        break
    fi
    sleep 1
done

grep -q 'Voice local smoke reminder' "${VOICE_OUTPUT_FILE}" || fail "Voice notification text was not delivered"
binary_after="$(grep -c '"type":"BINARY"' "${VOICE_OUTPUT_FILE}" 2>/dev/null || true)"
[[ "${binary_after}" -gt "${binary_before}" ]] || fail "Voice websocket did not receive notification audio"

log "Voice local full-audio smoke passed."
