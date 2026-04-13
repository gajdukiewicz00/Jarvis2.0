#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/runtime/common.sh"

cleanup() {
    local exit_code=$?

    for pid_file in \
        "${RUNTIME_DIR}/voice-local-smoke-probe.pid" \
        "${RUNTIME_DIR}/voice-local-smoke-pc-probe.pid"; do
        if [[ ! -f "${pid_file}" ]]; then
            continue
        fi
        local pid
        pid="$(cat "${pid_file}" 2>/dev/null || true)"
        rm -f "${pid_file}"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
            kill "${pid}" >/dev/null 2>&1 || true
        fi
    done

    rm -f "${RUNTIME_DIR}/voice-local-smoke.ready" \
        "${RUNTIME_DIR}/voice-local-smoke.log" \
        "${RUNTIME_DIR}/voice-local-smoke.pc.ready" \
        "${RUNTIME_DIR}/voice-local-smoke.pc.log" \
        "${RUNTIME_DIR}/voice-local-smoke.synth.wav" \
        "${RUNTIME_DIR}/voice-local-smoke.headers" \
        "${RUNTIME_DIR}/voice-local-smoke.ws-roundtrip.log" \
        "${RUNTIME_DIR}/voice-local-smoke.ws-audio-before-start.log" \
        "${RUNTIME_DIR}/voice-local-smoke.ws-duplicate-start.log" \
        "${RUNTIME_DIR}/voice-local-smoke.ws-end-without-audio.log" \
        "${RUNTIME_DIR}/voice-local-smoke.ws-timeout.log"

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
PC_OUTPUT_FILE="${RUNTIME_DIR}/voice-local-smoke.pc.log"
PC_READY_FILE="${RUNTIME_DIR}/voice-local-smoke.pc.ready"
SYNTH_WAV_FILE="${RUNTIME_DIR}/voice-local-smoke.synth.wav"
SYNTH_HEADERS_FILE="${RUNTIME_DIR}/voice-local-smoke.headers"
VOICE_WS_ROUNDTRIP_FILE="${RUNTIME_DIR}/voice-local-smoke.ws-roundtrip.log"
VOICE_WS_AUDIO_BEFORE_START_FILE="${RUNTIME_DIR}/voice-local-smoke.ws-audio-before-start.log"
VOICE_WS_DUPLICATE_START_FILE="${RUNTIME_DIR}/voice-local-smoke.ws-duplicate-start.log"
VOICE_WS_END_WITHOUT_AUDIO_FILE="${RUNTIME_DIR}/voice-local-smoke.ws-end-without-audio.log"
VOICE_WS_TIMEOUT_FILE="${RUNTIME_DIR}/voice-local-smoke.ws-timeout.log"

rm -f \
    "${VOICE_OUTPUT_FILE}" \
    "${VOICE_READY_FILE}" \
    "${PC_OUTPUT_FILE}" \
    "${PC_READY_FILE}" \
    "${SYNTH_WAV_FILE}" \
    "${SYNTH_HEADERS_FILE}" \
    "${VOICE_WS_ROUNDTRIP_FILE}" \
    "${VOICE_WS_AUDIO_BEFORE_START_FILE}" \
    "${VOICE_WS_DUPLICATE_START_FILE}" \
    "${VOICE_WS_END_WITHOUT_AUDIO_FILE}" \
    "${VOICE_WS_TIMEOUT_FILE}"

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

log "Connecting voice websocket probe..."
JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/WsProbe.java" \
    pc \
    "$(runtime_pc_ws_url)" \
    "${PC_OUTPUT_FILE}" \
    "${PC_READY_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" >"${LOG_DIR}/voice-local-smoke-pc-probe.log" 2>&1 &
printf '%s\n' "$!" >"${RUNTIME_DIR}/voice-local-smoke-pc-probe.pid"

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
    [[ -f "${VOICE_READY_FILE}" ]] && [[ -f "${PC_READY_FILE}" ]] && break
    sleep 1
done
[[ -f "${VOICE_READY_FILE}" ]] || fail "Voice websocket probe did not connect"
[[ -f "${PC_READY_FILE}" ]] || fail "PC websocket probe did not connect"

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

log "Checking voice diagnostics endpoint..."
VOICE_DIAGNOSTICS_STATUS="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/diagnostics" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}")"
python3 - "${VOICE_DIAGNOSTICS_STATUS}" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
assert payload["service"] == "voice-gateway", payload
assert payload["status"] == "UP", payload["status"]
assert payload["capture"]["managedBy"] == "desktop-client", payload["capture"]
assert payload["capture"]["microphoneProbe"] == "not-applicable", payload["capture"]
assert payload["execution"]["primaryCommandLoop"] == "rule-based", payload["execution"]
assert payload["execution"]["orchestratorRequiredForFullCommandSet"] is True, payload["execution"]
assert payload["execution"]["runtimeCapabilitySource"] == "/api/v1/capabilities", payload["execution"]
assert payload["stt"]["componentStatus"] == "UP", payload["stt"]
assert payload["stt"]["working"] is True, payload["stt"]
assert payload["tts"]["componentStatus"] == "UP", payload["tts"]
assert payload["tts"]["working"] is True, payload["tts"]
assert payload["websocket"]["componentStatus"] == "UP", payload["websocket"]
assert payload["websocket"]["working"] is True, payload["websocket"]
assert payload["assets"]["componentStatus"] == "UP", payload["assets"]
assert payload["orchestrator"]["componentStatus"] == "UP", payload["orchestrator"]
assert payload["preRecorded"]["enabled"] is True, payload["preRecorded"]
assert payload["preRecorded"]["activeAssetCount"] > 0, payload["preRecorded"]
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
volume_up_before="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
VOICE_COMMAND_REPLY="$(curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/command" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"text":"сделай громче"}')"
python3 - "${VOICE_COMMAND_REPLY}" <<'PY'
import sys

reply = sys.argv[1].strip()
normalized = reply.lower()
assert reply, "voice command reply must not be empty"
assert reply != "Processed: сделай громче", "voice command must not use placeholder replies"
assert "ошибка" not in normalized, f"voice command must not return a generic error reply: {reply!r}"
assert "не удалось" not in normalized, f"voice command must not return a capability failure reply: {reply!r}"
assert "error" not in normalized, f"voice command must not return an error reply: {reply!r}"
PY
for _ in $(seq 1 20); do
    volume_up_after="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
    if [[ "${volume_up_after}" -gt "${volume_up_before}" ]]; then
        break
    fi
    sleep 1
done
volume_up_after="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
[[ "${volume_up_after}" -gt "${volume_up_before}" ]] || fail "PC probe did not receive a new VOLUME_UP event during text command path"

log "Checking synthesize endpoint..."
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/voice/synthesize" -fsS \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -D "${SYNTH_HEADERS_FILE}" \
    -o "${SYNTH_WAV_FILE}" \
    -d '{"text":"volume up please","languageCode":"en-US","voiceName":"en-US-Wavenet-D"}'

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

log "Checking full voice websocket roundtrip with synthesized audio..."
JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_WS_ROUNDTRIP_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" \
    roundtrip \
    voice-local-roundtrip \
    en-US \
    "${SYNTH_WAV_FILE}"

grep -q '"type":"TRANSCRIPT_PARTIAL"' "${VOICE_WS_ROUNDTRIP_FILE}" || fail "Voice WS roundtrip did not emit partial transcripts"
grep -q '"type":"TRANSCRIPT_FINAL"' "${VOICE_WS_ROUNDTRIP_FILE}" || fail "Voice WS roundtrip did not emit a final transcript"
grep -q '"action":"VOLUME_UP"' "${VOICE_WS_ROUNDTRIP_FILE}" || fail "Voice WS roundtrip did not resolve VOLUME_UP"
grep -q '"executionSucceeded":true' "${VOICE_WS_ROUNDTRIP_FILE}" || fail "Voice WS roundtrip did not execute successfully"
grep -q '^IN_BINARY bytes=' "${VOICE_WS_ROUNDTRIP_FILE}" || fail "Voice WS roundtrip did not receive synthesized response audio"
grep -q '"state":"DONE"' "${VOICE_WS_ROUNDTRIP_FILE}" || fail "Voice WS roundtrip did not finish with DONE state"

log "Checking transcribe endpoint with synthesized WAV..."
volume_up_before_transcribe="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
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
for _ in $(seq 1 20); do
    volume_up_after_transcribe="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
    if [[ "${volume_up_after_transcribe}" -gt "${volume_up_before_transcribe}" ]]; then
        break
    fi
    sleep 1
done
volume_up_after_transcribe="$(grep -c '"action":"VOLUME_UP"' "${PC_OUTPUT_FILE}" 2>/dev/null || true)"
[[ "${volume_up_after_transcribe}" -gt "${volume_up_before_transcribe}" ]] || fail "Transcribe endpoint did not trigger a new VOLUME_UP desktop action"

log "Checking websocket protocol guardrails..."
JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_WS_AUDIO_BEFORE_START_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" \
    audio-before-start \
    voice-local-audio-before-start \
    en-US \
    "${SYNTH_WAV_FILE}"
grep -q '"code":"AUDIO_BEFORE_START"' "${VOICE_WS_AUDIO_BEFORE_START_FILE}" || fail "Voice WS did not reject audio-before-start"

JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_WS_DUPLICATE_START_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" \
    duplicate-start \
    voice-local-duplicate-start \
    en-US
grep -q '"code":"DUPLICATE_START"' "${VOICE_WS_DUPLICATE_START_FILE}" || fail "Voice WS did not reject duplicate START"

JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_WS_END_WITHOUT_AUDIO_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" \
    end-without-audio \
    voice-local-end-without-audio \
    en-US
grep -q '"code":"NO_AUDIO_RECEIVED"' "${VOICE_WS_END_WITHOUT_AUDIO_FILE}" || fail "Voice WS did not reject END without audio"
grep -q '"state":"DONE"' "${VOICE_WS_END_WITHOUT_AUDIO_FILE}" || fail "Voice WS END-without-audio path did not terminate cleanly"

JAVA_TOOL_OPTIONS="${RUNTIME_JAVA_TOOL_OPTIONS}" java "${ROOT_DIR}/scripts/runtime/VoiceWsScenario.java" \
    "${VOICE_WS_URL}" \
    "${VOICE_WS_TIMEOUT_FILE}" \
    "${ACCESS_TOKEN}" \
    "${USER_ID}" \
    "voice-local-smoke" \
    timeout \
    voice-local-timeout \
    en-US
grep -q '"code":"TIMEOUT"' "${VOICE_WS_TIMEOUT_FILE}" || fail "Voice WS timeout path did not emit TIMEOUT error"
grep -q '"action":"STT_TIMEOUT"' "${VOICE_WS_TIMEOUT_FILE}" || fail "Voice WS timeout path did not emit STT_TIMEOUT response"
grep -q '"state":"TIMEOUT"' "${VOICE_WS_TIMEOUT_FILE}" || fail "Voice WS timeout path did not emit TIMEOUT state"

log "Checking planner reminder delivery to voice websocket..."
binary_before="$(grep -c '"type":"BINARY"' "${VOICE_OUTPUT_FILE}" 2>/dev/null || true)"
NOW_TS="$(date -u -Is)"
curl_with_runtime_tls "${PUBLIC_API_BASE_URL}/api/v1/planner/reminders" -fsS \
    -X POST \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"Voice local smoke reminder\",\"reminderTime\":\"${NOW_TS}\",\"reminderType\":\"ONCE\"}" >/dev/null

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
