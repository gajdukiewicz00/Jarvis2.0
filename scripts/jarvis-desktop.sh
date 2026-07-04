#!/usr/bin/env bash
# =============================================================================
# jarvis-desktop.sh — launch the Jarvis desktop Control Center.
#
# ALWAYS runs the freshly compiled UI (javafx:run compiles from source), so you
# never accidentally open a stale jar. Needs a display (X11/Wayland).
#
#   ./jarvis desktop          (or)   bash scripts/jarvis-desktop.sh
# =============================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -d "${JAVA_HOME:-/nonexistent}" ] || export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"

# Point the app at the running k3s ingress so login works out-of-the-box
# (api.jarvis.local resolves to the node IP via /etc/hosts). Override with
# JARVIS_API_BASE_URL=... if your gateway is elsewhere.
export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-https://api.jarvis.local}"
# Make sure we don't fall into the local-runtime default (127.0.0.1:8080).
unset JARVIS_RUNTIME_MODE 2>/dev/null || true

echo "Launching Jarvis desktop — Control Center (fresh build)…"
echo "Gateway: $JARVIS_API_BASE_URL   ·   login: test1111 / test1111"
echo "If a window does not appear, you need a graphical display on this machine."
exec mvn -q -pl apps/desktop-javafx -am -Dspotless.check.skip=true -DskipTests javafx:run
