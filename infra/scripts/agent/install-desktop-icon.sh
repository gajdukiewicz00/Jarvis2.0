#!/usr/bin/env bash
# =============================================================================
# Install the Native Desktop Agent .desktop entry (Phase 6).
# =============================================================================
# Drops two files:
#   ~/.local/share/applications/jarvis-agent.desktop  (icon in app menu)
#   ~/.config/autostart/jarvis-agent.desktop          (start at login)
#
# Uses an env-file pattern so the operator can tweak runtime settings without
# editing the .desktop entry:
#   ~/.jarvis/agent/agent.env
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

LAUNCHER="${JARVIS_AGENT_LAUNCHER:-${ROOT_DIR}/scripts/jarvis-agent}"
ICON="${JARVIS_AGENT_ICON:-${ROOT_DIR}/icons/jarvis.png}"
APPS_DIR="${HOME}/.local/share/applications"
AUTOSTART_DIR="${HOME}/.config/autostart"
ENV_DIR="${HOME}/.jarvis/agent"
ENV_FILE="${ENV_DIR}/agent.env"
TEMPLATE="${SCRIPT_DIR}/jarvis-agent.desktop"

usage() {
  cat <<EOF
Usage: ./infra/scripts/agent/install-desktop-icon.sh [options]

Options:
  --launcher=PATH   Path to the agent launcher script (default: ${LAUNCHER})
  --icon=PATH       Path to the icon file (default: ${ICON})
  --no-autostart    Install only the menu entry, skip ~/.config/autostart
  --uninstall       Remove both .desktop entries
  --help, -h
EOF
}

ENABLE_AUTOSTART=true
UNINSTALL=false
for arg in "$@"; do
  case "${arg}" in
    --launcher=*)    LAUNCHER="${arg#*=}" ;;
    --icon=*)        ICON="${arg#*=}" ;;
    --no-autostart)  ENABLE_AUTOSTART=false ;;
    --uninstall)     UNINSTALL=true ;;
    --help|-h)       usage; exit 0 ;;
    *) echo "❌ Unknown argument: ${arg}" >&2; usage >&2; exit 1 ;;
  esac
done

if "${UNINSTALL}"; then
  rm -f "${APPS_DIR}/jarvis-agent.desktop" "${AUTOSTART_DIR}/jarvis-agent.desktop"
  echo "✅ removed jarvis-agent.desktop entries"
  exit 0
fi

if [[ ! -x "${LAUNCHER}" ]]; then
  echo "❌ launcher not found or not executable: ${LAUNCHER}" >&2
  echo "   Provide --launcher=PATH or build the agent jar first." >&2
  exit 1
fi
if [[ ! -f "${ICON}" ]]; then
  echo "⚠ icon not found at ${ICON} — installing without an icon (XDG will fall back)" >&2
  ICON=""
fi

mkdir -p "${APPS_DIR}" "${ENV_DIR}"
[[ ! -f "${ENV_FILE}" ]] && cat >"${ENV_FILE}" <<EOF
# Edit to override agent runtime settings.
JARVIS_AGENT_BACKEND_URL=https://api.jarvis.local
JARVIS_AGENT_RABBITMQ_HOST=localhost
JARVIS_AGENT_RABBITMQ_PORT=5672
JARVIS_AGENT_RABBITMQ_USERNAME=jarvis
JARVIS_AGENT_RABBITMQ_PASSWORD=jarvis
JARVIS_AGENT_RABBITMQ_VHOST=jarvis
JARVIS_AGENT_CONFIRMATION_STRATEGY=cli
EOF

substitute() {
  sed -e "s|@JARVIS_AGENT_LAUNCHER@|${LAUNCHER}|g" \
      -e "s|@JARVIS_AGENT_ICON@|${ICON}|g" \
      "${TEMPLATE}"
}

substitute >"${APPS_DIR}/jarvis-agent.desktop"
chmod 644 "${APPS_DIR}/jarvis-agent.desktop"
echo "✅ installed ${APPS_DIR}/jarvis-agent.desktop"

if "${ENABLE_AUTOSTART}"; then
  mkdir -p "${AUTOSTART_DIR}"
  cp "${APPS_DIR}/jarvis-agent.desktop" "${AUTOSTART_DIR}/jarvis-agent.desktop"
  chmod 644 "${AUTOSTART_DIR}/jarvis-agent.desktop"
  echo "✅ installed ${AUTOSTART_DIR}/jarvis-agent.desktop (start-on-login)"
fi

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "${APPS_DIR}" >/dev/null 2>&1 || true
fi

cat <<EOF

Installation complete.

Tweak runtime settings in:
  ${ENV_FILE}

Launch by clicking the icon in your application menu, or run manually:
  ${LAUNCHER}
EOF
