#!/usr/bin/env bash
# Run pc-control on the HOST in non-stub mode so real desktop actions
# (notify, media, volume, screenshot, lock, hotkeys) actually execute.
# The cluster reaches it via the host-pc-control selectorless Service.
#
# Launch detached:
#   systemd-run --user --unit=jarvis-pc-control scripts/jarvis-pc-control-up.sh
set -euo pipefail

JAR="${HOME}/Jarvis/Jarvis2.0/apps/pc-control/target/pc-control-1.0.0.jar"
PORT="${PC_CONTROL_PORT:-8084}"
WAKE_ENV="${HOME}/.jarvis/wake.env"

# Secret for validating inbound service tokens — never hardcoded, read from host env file.
if [[ -f "$WAKE_ENV" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$WAKE_ENV"; set +a
fi
: "${SERVICE_JWT_SECRET:?SERVICE_JWT_SECRET must be set (in ~/.jarvis/wake.env)}"

# Graphical session so xdotool / notify-send / gnome-screenshot reach the real display.
export DISPLAY="${DISPLAY:-:1}"
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=/run/user/$(id -u)/bus}"
export PC_CONTROL_STUB_MODE=false
export JWT_SECRET="${JWT_SECRET:-$SERVICE_JWT_SECRET}"
# WINDOW_FOCUS + TYPE_TEXT are required for the api-gateway
# /api/v1/pc/desktop/** official desktop-action route (Roadmap #1.8/#8).
export SECURITY_ALLOWED_ACTIONS="${SECURITY_ALLOWED_ACTIONS:-MEDIA_CONTROL,VOLUME_UP,VOLUME_DOWN,SET_VOLUME,VOLUME_SET,MUTE,UNMUTE,PLAY_PAUSE,PAUSE,NEXT,PREV,OPEN_APP,OPEN_URL,HOTKEY,TYPE_TEXT,WINDOW_FOCUS,NOTIFY,SCREENSHOT,LOCK_SCREEN,SYSTEM_COMMAND,SCENARIO}"

exec java -jar "$JAR" --server.port="$PORT"
