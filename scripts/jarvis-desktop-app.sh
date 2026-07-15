#!/usr/bin/env bash
# =============================================================================
# jarvis-desktop-app.sh — launch the Jarvis desktop Control Center from the
# prebuilt jar (fast; used by the desktop icon). Runs the SHELL entry point
# (DesktopAppApplicationKt) — NOT the legacy bootstrapper.
# =============================================================================
set -uo pipefail
ROOT="/home/kwaqa/Jarvis/Jarvis2.0"
JAR="$ROOT/apps/desktop-javafx/target/desktop-javafx-1.0.0.jar"
JAVA="/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"
LOG="$HOME/.jarvis/logs/desktop-app.log"; mkdir -p "$(dirname "$LOG")"

# Point at the running k3s ingress so login works (api.jarvis.local -> node IP).
export JARVIS_API_BASE_URL="${JARVIS_API_BASE_URL:-https://api.jarvis.local}"
# Default voice recognition + response language to Russian (override with
# JARVIS_VOICE_LANGUAGE=en-US). Otherwise the desktop's saved locale keeps STT on
# the English Vosk model.
export JARVIS_VOICE_LANGUAGE="${JARVIS_VOICE_LANGUAGE:-ru-RU}"
unset JARVIS_RUNTIME_MODE 2>/dev/null || true

cd "$ROOT"
# Rebuild the jar if it is missing OR any desktop source is newer than it, so the
# icon always launches the latest code without a manual build step.
needs_build=0
[ -f "$JAR" ] || needs_build=1
if [ -f "$JAR" ] && [ -n "$(find apps/desktop-javafx/src -type f \( -name '*.kt' -o -name '*.java' \) -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  needs_build=1
fi
if [ "$needs_build" = 1 ]; then
  command -v notify-send >/dev/null 2>&1 && notify-send "Jarvis" "Building desktop app…" || true
  mvn -q -o -pl apps/desktop-javafx -am -DskipTests -Dspotless.check.skip=true package >>"$LOG" 2>&1 || \
  mvn -q -pl apps/desktop-javafx -am -DskipTests -Dspotless.check.skip=true package >>"$LOG" 2>&1
fi

echo "=== launch $(date) gateway=$JARVIS_API_BASE_URL ===" >>"$LOG"
exec "$JAVA" -jar "$JAR" >>"$LOG" 2>&1
