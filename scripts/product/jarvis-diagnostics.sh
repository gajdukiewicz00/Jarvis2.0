#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Diagnostics Script
# =============================================================================
# Shows diagnostic information about Jarvis installation.
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

JARVIS_HOME="${HOME}/.jarvis"

DIAGNOSTICS=$(cat <<EOF
=== Jarvis 2.0 Diagnostics ===

Project Root: ${PROJECT_ROOT}
Jarvis Home: ${JARVIS_HOME}

=== Scripts ===
Launch script: ${PROJECT_ROOT}/jarvis-launch.sh
  Exists: $([ -f "${PROJECT_ROOT}/jarvis-launch.sh" ] && echo "YES" || echo "NO")
  Executable: $([ -x "${PROJECT_ROOT}/jarvis-launch.sh" ] && echo "YES" || echo "NO")

Stop script: ${PROJECT_ROOT}/jarvis-stop.sh
  Exists: $([ -f "${PROJECT_ROOT}/jarvis-stop.sh" ] && echo "YES" || echo "NO")
  Executable: $([ -x "${PROJECT_ROOT}/jarvis-stop.sh" ] && echo "YES" || echo "NO")

=== Desktop JavaFX ===
Unified desktop JAR: ${PROJECT_ROOT}/apps/desktop-javafx/target/desktop-javafx-0.1.0-SNAPSHOT.jar
  Exists: $([ -f "${PROJECT_ROOT}/apps/desktop-javafx/target/desktop-javafx-0.1.0-SNAPSHOT.jar" ] && echo "YES" || echo "NO")

=== Java ===
Java: $(which java 2>/dev/null || echo "NOT FOUND")
JAVA_HOME: ${JAVA_HOME:-NOT SET}
Java Version: $(java -version 2>&1 | head -1 || echo "ERROR")

=== Kubernetes ===
kubectl: $(which kubectl 2>/dev/null || echo "NOT FOUND")
k3s: $(which k3s 2>/dev/null || echo "NOT FOUND")
Ingress class: $(kubectl get ingressclass nginx -o name 2>/dev/null || echo "NOT FOUND")

=== TLS ===
TLS dir: ${JARVIS_HOME}/tls
CA cert: ${JARVIS_HOME}/tls/jarvis-ca.crt
Server cert: ${JARVIS_HOME}/tls/jarvis.crt

=== Directories ===
Logs: ${JARVIS_HOME}/logs
  Exists: $([ -d "${JARVIS_HOME}/logs" ] && echo "YES" || echo "NO")

Run: ${JARVIS_HOME}/run
  Exists: $([ -d "${JARVIS_HOME}/run" ] && echo "YES" || echo "NO")

=== Backend Status ===
PID File: ${JARVIS_HOME}/run/backend.pid
  Exists: $([ -f "${JARVIS_HOME}/run/backend.pid" ] && echo "YES" || echo "NO")
EOF
)

if [ -f "${JARVIS_HOME}/run/backend.pid" ]; then
    PID=$(cat "${JARVIS_HOME}/run/backend.pid" 2>/dev/null || echo "")
    if [ -n "$PID" ]; then
        if kill -0 "$PID" 2>/dev/null; then
            DIAGNOSTICS="${DIAGNOSTICS}\n  PID: $PID (RUNNING)"
        else
            DIAGNOSTICS="${DIAGNOSTICS}\n  PID: $PID (STALE - process not running)"
        fi
    fi
fi

# Write diagnostics to file
DIAGNOSTICS_FILE="${JARVIS_HOME}/logs/diagnostics-$(date +%Y%m%d-%H%M%S).txt"
mkdir -p "${JARVIS_HOME}/logs"
echo -e "$DIAGNOSTICS" > "$DIAGNOSTICS_FILE"
echo "Diagnostics saved to: $DIAGNOSTICS_FILE"

# Show in zenity dialog if available
if command -v zenity >/dev/null 2>&1; then
    echo -e "$DIAGNOSTICS\n\nDiagnostics saved to: $DIAGNOSTICS_FILE" | zenity --text-info --title="Jarvis Diagnostics" --width=800 --height=600 2>/dev/null || true
else
    # Fallback: open file in default editor
    if command -v xdg-open >/dev/null 2>&1; then
        xdg-open "$DIAGNOSTICS_FILE" 2>/dev/null || true
    else
        # Last resort: terminal
        echo -e "$DIAGNOSTICS"
        echo ""
        echo "Diagnostics saved to: $DIAGNOSTICS_FILE"
        read -p "Press Enter to continue..."
    fi
fi
