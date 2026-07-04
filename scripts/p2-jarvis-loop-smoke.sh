#!/usr/bin/env bash
#
# P2 Jarvis Loop end-to-end smoke test.
#
# Drives the full chain — voice-gateway -> nlp-service -> orchestrator
# -> RabbitMQ -> desktop-agent -> result -> Kafka audit -> Postgres
# -> Obsidian journal — using HTTP only (no actual audio). The transcript
# is supplied verbatim, so the wake-word + STT legs are exercised
# separately by the desktop agent.
#
# Prereqs:
#   - voice-gateway, nlp-service, orchestrator, memory-service running
#     (./scripts/runtime-up.sh or kubectl port-forwards)
#   - desktop-javafx (jarvis-agent) connected to RabbitMQ
#   - JARVIS_OBSIDIAN_VAULT_PATH set or default ~/JarvisVault writable
#
# Env knobs:
#   VOICE_GATEWAY_URL   default http://127.0.0.1:8081
#   ORCHESTRATOR_URL    default http://127.0.0.1:8083
#   USER_ID             default smoke-owner
#   TRANSCRIPT          default "открой браузер"
#   AGENT_ID            default smoke-agent
#   VAULT_PATH          default $HOME/JarvisVault
#   WAIT_SECONDS        how long to poll for the Obsidian journal write (default 20)

set -euo pipefail

VOICE_GATEWAY_URL="${VOICE_GATEWAY_URL:-http://127.0.0.1:8081}"
ORCHESTRATOR_URL="${ORCHESTRATOR_URL:-http://127.0.0.1:8083}"
USER_ID="${USER_ID:-smoke-owner}"
TRANSCRIPT="${TRANSCRIPT:-открой браузер}"
AGENT_ID="${AGENT_ID:-smoke-agent}"
VAULT_PATH="${VAULT_PATH:-$HOME/JarvisVault}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"

GREEN=$(printf '\033[32m')
RED=$(printf '\033[31m')
YELLOW=$(printf '\033[33m')
RESET=$(printf '\033[0m')

step() { printf "%s%s%s\n" "${YELLOW}" "==> $*" "${RESET}"; }
ok()   { printf "%s%s%s\n" "${GREEN}" "✓ $*"  "${RESET}"; }
fail() { printf "%s%s%s\n" "${RED}"   "✗ $*"  "${RESET}"; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || fail "missing dependency: $1"; }
require curl
require jq

step "1/5 voice-gateway readiness"
if ! curl -sSf "${VOICE_GATEWAY_URL}/actuator/health" >/dev/null; then
    fail "voice-gateway not reachable at ${VOICE_GATEWAY_URL}"
fi
ok "voice-gateway up"

step "2/5 starting voice session"
SESSION_JSON=$(curl -sSf -X POST "${VOICE_GATEWAY_URL}/api/v1/voice/sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"agentId\":\"${AGENT_ID}\",\"userId\":\"${USER_ID}\"}")
SESSION_ID=$(echo "${SESSION_JSON}" | jq -r '.sessionId')
[[ -z "${SESSION_ID}" || "${SESSION_ID}" == "null" ]] \
    && fail "no sessionId returned: ${SESSION_JSON}"
ok "session started: ${SESSION_ID}"

step "3/5 sending utterance: '${TRANSCRIPT}'"
UTTER_JSON=$(curl -sSf -X POST \
    "${VOICE_GATEWAY_URL}/api/v1/voice/sessions/${SESSION_ID}/utterance" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg t "${TRANSCRIPT}" '{transcript:$t,locale:"ru"}')")

INTENT=$(echo "${UTTER_JSON}" | jq -r '.intent.intent // empty')
COMMAND_ID=$(echo "${UTTER_JSON}" | jq -r '.commandId // empty')
STATUS=$(echo "${UTTER_JSON}" | jq -r '.sessionStatus // empty')
SPOKEN=$(echo "${UTTER_JSON}" | jq -r '.feedback.spokenText // empty')

[[ -z "${INTENT}" ]] && fail "no intent classified — nlp-service down? raw=${UTTER_JSON}"
ok "intent classified: ${INTENT}"

[[ -z "${COMMAND_ID}" ]] && fail "no commandId returned — orchestrator dispatch failed"
ok "command_id: ${COMMAND_ID}"

case "${STATUS}" in
    COMPLETED) ok "desktop-agent executed command (status=COMPLETED)" ;;
    AWAITING_CONFIRM) fail "got AWAITING_CONFIRM — risk catalog rejected the intent" ;;
    EXPIRED) fail "got EXPIRED — desktop-agent didn't pick up command in time (consumer down?)" ;;
    *) fail "unexpected sessionStatus=${STATUS} (raw=${UTTER_JSON})" ;;
esac

ok "spoken feedback: ${SPOKEN}"

step "4/5 polling Obsidian vault for journal entry (${WAIT_SECONDS}s)"
TODAY=$(date -u +%Y-%m-%d)
DAILY_DIR="${VAULT_PATH}/01_Daily"
if [[ ! -d "${VAULT_PATH}" ]]; then
    printf "%svault path %s does not exist — Obsidian writer disabled?%s\n" \
        "${YELLOW}" "${VAULT_PATH}" "${RESET}"
fi

JOURNAL_FILE=""
for ((i=0; i<WAIT_SECONDS; i++)); do
    if [[ -d "${DAILY_DIR}" ]]; then
        match=$(find "${DAILY_DIR}" -maxdepth 1 -type f \
            -name "${TODAY}-jarvis-loop-*.md" 2>/dev/null | head -n 1 || true)
        if [[ -n "${match}" ]]; then
            JOURNAL_FILE="${match}"
            break
        fi
    fi
    sleep 1
done
if [[ -n "${JOURNAL_FILE}" ]]; then
    ok "obsidian journal written: ${JOURNAL_FILE}"
    grep -E "^(intent|command_id|status):" "${JOURNAL_FILE}" || true
else
    printf "%sno journal entry found under %s — memory-service journaler may be off%s\n" \
        "${YELLOW}" "${DAILY_DIR}" "${RESET}"
fi

step "5/5 voice session end"
curl -sSf -X POST "${VOICE_GATEWAY_URL}/api/v1/voice/sessions/${SESSION_ID}/end" \
    -o /dev/null && ok "session closed"

printf "\n%sP2 Jarvis Loop smoke OK%s\n" "${GREEN}" "${RESET}"
echo "  session    = ${SESSION_ID}"
echo "  command    = ${COMMAND_ID}"
echo "  nlp_intent = ${INTENT}"
echo "  status     = ${STATUS}"
