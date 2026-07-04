#!/usr/bin/env bash

# =============================================================================
# Jarvis 2.0 - Stop Script (Product)
# =============================================================================
# Stops Jarvis backend gracefully.
# Used by desktop action "Stop Jarvis".
# Idempotent: safe to call multiple times.
# =============================================================================

set -euo pipefail

# Logs directory
LOG_DIR="${HOME}/.jarvis/logs"
mkdir -p "$LOG_DIR"
LOG="${LOG_DIR}/stop.log"

# Logging function
log() {
    echo "[$(date -Is)] $*" >> "$LOG" 2>&1 || true
}

# Notification function
notify() {
    local msg="$1"
    log "NOTIFY: $msg"
    command -v notify-send >/dev/null 2>&1 && notify-send "Jarvis 2.0" "$msg" --icon=info 2>/dev/null || true
}

log "=========================================="
log "Jarvis Stop requested"
log "User: $(whoami)"

JARVIS_HOME="${HOME}/.jarvis"
PID_FILE="${JARVIS_HOME}/run/backend.pid"
LAUNCHER_PID_FILE="${JARVIS_HOME}/run/launcher.pid"

STOPPED_SOMETHING=false

# Stop backend process (if PID file exists)
if [[ -f "$PID_FILE" ]]; then
    PID=$(cat "$PID_FILE" 2>/dev/null || echo "")
    if [[ -n "$PID" ]]; then
        if kill -0 "$PID" 2>/dev/null; then
            log "Stopping backend process (PID: $PID)"
            kill "$PID" 2>/dev/null || true
            sleep 2
            if kill -0 "$PID" 2>/dev/null; then
                log "Force killing backend process (PID: $PID)"
                kill -9 "$PID" 2>/dev/null || true
            fi
            STOPPED_SOMETHING=true
            log "Backend process stopped"
        else
            log "Backend PID file exists but process not running (stale PID: $PID)"
        fi
        rm -f "$PID_FILE" || true
    else
        log "Backend PID file is empty, removing"
        rm -f "$PID_FILE" || true
    fi
else
    log "No backend PID file found"
fi

# Stop port-forward processes
PORT_FORWARD_PIDS=$(pgrep -f "kubectl port-forward.*jarvis" 2>/dev/null || true)
if [[ -n "$PORT_FORWARD_PIDS" ]]; then
    log "Stopping port-forward processes: $PORT_FORWARD_PIDS"
    pkill -f "kubectl port-forward.*jarvis" 2>/dev/null || true
    STOPPED_SOMETHING=true
fi

# Try graceful shutdown via kubectl scale (if available)
if command -v kubectl >/dev/null 2>&1; then
    log "Attempting graceful shutdown via kubectl scale..."
    # Scale down deployments in the production namespace.
    if kubectl get namespace jarvis-prod >/dev/null 2>&1; then
        kubectl scale deployment --all --replicas=0 -n jarvis-prod >> "${LOG}" 2>&1 || true
        kubectl scale statefulset --all --replicas=0 -n jarvis-prod >> "${LOG}" 2>&1 || true
        log "Kubernetes resources scaled down"
        STOPPED_SOMETHING=true
        sleep 2  # Give pods time to terminate gracefully
    fi
fi

# Try to call jarvis-stop.sh if exists (for full cleanup)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${JARVIS_PROJECT_ROOT:-}"
if [[ -z "${REPO_ROOT}" ]]; then
    REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
fi
STOP_SCRIPT="${REPO_ROOT}/jarvis-stop.sh"

if [[ -f "$STOP_SCRIPT" ]] && [[ -x "$STOP_SCRIPT" ]]; then
    log "Running full stop script: $STOP_SCRIPT"
    cd "$REPO_ROOT"
    # Run non-interactively
    timeout 30 "$STOP_SCRIPT" --yes >> "${LOG}" 2>&1 || {
        log "Stop script returned non-zero or timed out (may be expected if nothing to stop)"
    }
    STOPPED_SOMETHING=true
fi

if [[ "$STOPPED_SOMETHING" == "true" ]]; then
    log "Jarvis stopped successfully"
    notify "Backend остановлен"
else
    log "Nothing was running (idempotent stop)"
    notify "Backend уже остановлен"
fi

log "Stop completed: $(date)"
exit 0
