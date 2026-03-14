#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

MOSQUITTO_MANIFEST="${PROJECT_ROOT}/k8s/base/mosquitto/deployment.yaml"
SMART_HOME_MANIFEST="${PROJECT_ROOT}/k8s/base/smart-home-service/deployment.yaml"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "❌ Missing dependency: $1" >&2
    exit 1
  }
}

require_cmd rg

echo "🔎 Checking Mosquitto auth hardening..."
rg -n "allow_anonymous false" "${MOSQUITTO_MANIFEST}" >/dev/null
rg -n "password_file /mosquitto/config/auth/passwords" "${MOSQUITTO_MANIFEST}" >/dev/null
rg -n "acl_file /mosquitto/config/auth/aclfile" "${MOSQUITTO_MANIFEST}" >/dev/null
rg -n "key: MQTT_USERNAME" "${MOSQUITTO_MANIFEST}" >/dev/null
rg -n "key: MQTT_PASSWORD" "${MOSQUITTO_MANIFEST}" >/dev/null

echo "🔎 Checking smart-home MQTT credentials wiring..."
rg -n "name: MQTT_USERNAME" "${SMART_HOME_MANIFEST}" >/dev/null
rg -n "name: MQTT_PASSWORD" "${SMART_HOME_MANIFEST}" >/dev/null
rg -n "secretKeyRef:" "${SMART_HOME_MANIFEST}" >/dev/null

echo "✅ MQTT hardening checks passed"
