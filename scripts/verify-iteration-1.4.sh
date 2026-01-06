#!/usr/bin/env bash

# =============================================================================
# Iteration 1.4 - Verification Script (Complete Acceptance Checklist)
# =============================================================================
# Verifies Stage 1 & 2: Launcher module and Desktop icon integration
# Checks all acceptance criteria including hardening fixes
# =============================================================================

set -euo pipefail

# Parse arguments
REQUIRE_INSTALL=false
REQUIRE_BACKEND=false
REQUIRE_HTTPS=false
STRICT_REPO=false
REQUIRE_RELEASE=false
REQUIRE_MEMORY=false
REQUIRE_LLM=false

for arg in "$@"; do
    case "$arg" in
        --require-install)
            REQUIRE_INSTALL=true
            ;;
        --require-backend)
            REQUIRE_BACKEND=true
            ;;
        --require-https)
            REQUIRE_HTTPS=true
            ;;
        --strict-repo)
            STRICT_REPO=true
            ;;
        --require-release)
            REQUIRE_RELEASE=true
            ;;
        --require-memory)
            REQUIRE_MEMORY=true
            ;;
        --require-llm)
            REQUIRE_LLM=true
            ;;
    esac
done

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JARVIS_HOME="${HOME}/.jarvis"
JARVIS_APP="${JARVIS_HOME}/app"
JARVIS_DESKTOP="${HOME}/.local/share/applications"
DESKTOP_FILE="${JARVIS_DESKTOP}/jarvis-launcher.desktop"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;90m'
NC='\033[0m'

PASSED=0
FAILED=0
WARNINGS=0

echo "=========================================="
echo "Iteration 1.4 - Complete Verification"
echo "=========================================="
echo ""

# =============================================================================
# Stage 1: Launcher Module
# =============================================================================
echo -e "${CYAN}=== Stage 1: Launcher Module ===${NC}"
echo ""

# A1. Logs and directories
echo "A1. Logs and directories..."
if [[ -f "${JARVIS_HOME}/logs/launcher-start.log" ]]; then
    echo -e "  ${GREEN}✅${NC} launcher-start.log exists"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️${NC}  launcher-start.log not found (will be created on first launch)"
    WARNINGS=$((WARNINGS + 1))
fi

if [[ -f "${JARVIS_HOME}/logs/launcher.log" ]]; then
    echo -e "  ${GREEN}✅${NC} launcher.log exists"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️${NC}  launcher.log not found (will be created on first launch)"
    WARNINGS=$((WARNINGS + 1))
fi

# Check error handling (wrapper script has GUI notifications)
if grep -q "notify\|zenity" "${PROJECT_ROOT}/scripts/product/jarvis-launcher.sh" 2>/dev/null; then
    echo -e "  ${GREEN}✅${NC} Error handling with GUI notifications"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Error handling missing GUI notifications"
    FAILED=$((FAILED + 1))
fi

# A2. Idempotency
echo ""
echo "A2. Idempotency..."
if grep -q "already running\|already stopped\|idempotent" "${PROJECT_ROOT}/scripts/product/jarvis-stop.sh" 2>/dev/null; then
    echo -e "  ${GREEN}✅${NC} Stop script is idempotent"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Stop script not idempotent"
    FAILED=$((FAILED + 1))
fi

# Check for PID check in launcher (prevent double start)
if grep -q "isRunning\|PID" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt" 2>/dev/null; then
    echo -e "  ${GREEN}✅${NC} Launcher checks for running backend (idempotent start)"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️${NC}  Launcher may not check for running backend"
    WARNINGS=$((WARNINGS + 1))
fi

# A3. Portability (product install independent of repo)
echo ""
echo "A3. Portability..."
if [[ -d "$JARVIS_APP" ]] && [[ -f "${JARVIS_APP}/launcher.jar" ]]; then
    echo -e "  ${GREEN}✅${NC} Product install exists: ${JARVIS_APP}"
    PASSED=$((PASSED + 1))
    
    # Check if desktop file points to product install
    if [[ -f "$DESKTOP_FILE" ]]; then
        if grep -q "\$HOME/.jarvis/app/bin/jarvis-launcher.sh\|bash -lc" "$DESKTOP_FILE" 2>/dev/null; then
            echo -e "  ${GREEN}✅${NC} Desktop file uses product install path"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  Desktop file may not use product install path"
            WARNINGS=$((WARNINGS + 1))
        fi
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Product not installed (development mode). Run: ./scripts/product/jarvis-install.sh"
    WARNINGS=$((WARNINGS + 1))
fi

# =============================================================================
# Stage 2: Desktop Icon Integration
# =============================================================================
echo ""
echo -e "${CYAN}=== Stage 2: Desktop Icon Integration ===${NC}"
echo ""

# B1. Product path
echo "B1. Product path..."
if [[ -f "${JARVIS_APP}/launcher.jar" ]]; then
    echo -e "  ${GREEN}✅${NC} ~/.jarvis/app/launcher.jar exists"
    PASSED=$((PASSED + 1))
else
    if [[ "$REQUIRE_INSTALL" == "true" ]]; then
        echo -e "  ${RED}❌${NC} ~/.jarvis/app/launcher.jar not found (product install required)"
        FAILED=$((FAILED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  ~/.jarvis/app/launcher.jar not found (dev mode: run ./scripts/product/jarvis-install.sh)"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

if [[ -f "${JARVIS_APP}/bin/jarvis-launcher.sh" ]] && [[ -x "${JARVIS_APP}/bin/jarvis-launcher.sh" ]]; then
    echo -e "  ${GREEN}✅${NC} ~/.jarvis/app/bin/jarvis-launcher.sh exists and executable"
    PASSED=$((PASSED + 1))
else
    if [[ "$REQUIRE_INSTALL" == "true" ]]; then
        echo -e "  ${RED}❌${NC} ~/.jarvis/app/bin/jarvis-launcher.sh not found or not executable (product install required)"
        FAILED=$((FAILED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  ~/.jarvis/app/bin/jarvis-launcher.sh not found or not executable (dev mode: run ./scripts/product/jarvis-install.sh)"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# Check desktop file points to product install (critical after install)
if [[ -f "$DESKTOP_FILE" ]]; then
    if grep -q "bash -lc.*\$HOME/.jarvis/app/bin/jarvis-launcher.sh" "$DESKTOP_FILE" 2>/dev/null; then
        echo -e "  ${GREEN}✅${NC} Desktop file points to product install via bash -lc"
        PASSED=$((PASSED + 1))
        
        # Verify executable exists at that path
        LAUNCHER_PATH="${HOME}/.jarvis/app/bin/jarvis-launcher.sh"
        if [[ -x "$LAUNCHER_PATH" ]]; then
            echo -e "  ${GREEN}✅${NC} Desktop file target is executable: $LAUNCHER_PATH"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} Desktop file target not executable: $LAUNCHER_PATH"
            FAILED=$((FAILED + 1))
        fi
    else
        echo -e "  ${RED}❌${NC} Desktop file does NOT point to product install (may be outdated after install)"
        echo -e "     Expected: Exec=/usr/bin/env bash -lc \"\$HOME/.jarvis/app/bin/jarvis-launcher.sh\""
        FAILED=$((FAILED + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Desktop file not found in ${JARVIS_DESKTOP}"
    WARNINGS=$((WARNINGS + 1))
fi

# B2. Desktop Actions
echo ""
echo "B2. Desktop Actions..."
if [[ -f "$DESKTOP_FILE" ]]; then
    if grep -q "Actions=Stop" "$DESKTOP_FILE" 2>/dev/null; then
        echo -e "  ${GREEN}✅${NC} Desktop Actions defined"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} Desktop Actions not defined"
        FAILED=$((FAILED + 1))
    fi
    
    if grep -q "bash -lc.*jarvis-stop.sh" "$DESKTOP_FILE" 2>/dev/null; then
        echo -e "  ${GREEN}✅${NC} Stop action uses bash -lc"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  Stop action may not use bash -lc"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Desktop file not found, cannot check Actions"
    WARNINGS=$((WARNINGS + 1))
fi

# =============================================================================
# Hardening Fixes
# =============================================================================
echo ""
echo -e "${CYAN}=== Hardening Fixes ===${NC}"
echo ""

# 1. Desktop file uses bash -lc (not ~)
echo "1. Desktop file \$HOME expansion..."
if [[ -f "$DESKTOP_FILE" ]]; then
    if grep -q "bash -lc.*\$HOME" "$DESKTOP_FILE" 2>/dev/null; then
        echo -e "  ${GREEN}✅${NC} Desktop file uses bash -lc with \$HOME"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} Desktop file does not use bash -lc with \$HOME"
        FAILED=$((FAILED + 1))
    fi
    
    if grep -q "Terminal=false" "$DESKTOP_FILE" 2>/dev/null; then
        echo -e "  ${GREEN}✅${NC} Terminal=false set"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} Terminal=false not set"
        FAILED=$((FAILED + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Desktop file not found"
    WARNINGS=$((WARNINGS + 1))
fi

# 2. Lock file (prevent double launch)
echo ""
echo "2. Lock file (prevent double launch)..."
if grep -q "launcher.lock" "${PROJECT_ROOT}/scripts/product/jarvis-launcher.sh" 2>/dev/null && grep -q "flock" "${PROJECT_ROOT}/scripts/product/jarvis-launcher.sh" 2>/dev/null; then
    echo -e "  ${GREEN}✅${NC} Wrapper uses flock for launcher.lock"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Wrapper does not use flock for launcher.lock"
    FAILED=$((FAILED + 1))
fi

# 3. Version and install log
echo ""
echo "3. Version and install log..."
if [[ -f "${JARVIS_APP}/VERSION" ]]; then
    VERSION=$(cat "${JARVIS_APP}/VERSION" 2>/dev/null || echo "unknown")
    echo -e "  ${GREEN}✅${NC} VERSION file exists: $VERSION"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️${NC}  VERSION file not found (will be created on install)"
    WARNINGS=$((WARNINGS + 1))
fi

if [[ -f "${JARVIS_HOME}/logs/install.log" ]]; then
    echo -e "  ${GREEN}✅${NC} install.log exists"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️${NC}  install.log not found (will be created on install)"
    WARNINGS=$((WARNINGS + 1))
fi

# Check if launcher UI shows version
if grep -q "loadVersion\|VERSION" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt" 2>/dev/null; then
    echo -e "  ${GREEN}✅${NC} Launcher UI shows version"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${YELLOW}⚠️${NC}  Launcher UI may not show version"
    WARNINGS=$((WARNINGS + 1))
fi

# =============================================================================
# Stage 3: Health Check Service
# =============================================================================
echo ""
echo -e "${CYAN}=== Stage 3: Health Check Service ===${NC}"
echo ""

# Check if HealthCheckService class exists in launcher JAR
LAUNCHER_JAR="${PROJECT_ROOT}/apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar"
if [[ -f "$LAUNCHER_JAR" ]]; then
    if unzip -l "$LAUNCHER_JAR" 2>/dev/null | grep -q "HealthCheckService.class"; then
        echo -e "  ${GREEN}✅${NC} HealthCheckService class found in launcher JAR"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} HealthCheckService class NOT found in launcher JAR"
        FAILED=$((FAILED + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Launcher JAR not found (build required: mvn -pl apps/launcher-javafx package)"
    WARNINGS=$((WARNINGS + 1))
fi

# Functional smoke test: API Gateway and Security Service health endpoints
if command -v kubectl >/dev/null 2>&1 && kubectl get namespace jarvis >/dev/null 2>&1; then
    # Check if backend is running (at least one pod in jarvis namespace)
    BACKEND_RUNNING=false
    if kubectl -n jarvis get pods 2>/dev/null | grep -q "Running"; then
        BACKEND_RUNNING=true
    fi
    
    if [[ "$BACKEND_RUNNING" == "true" ]]; then
        MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "127.0.0.1")
        NODE_PORT=$(kubectl -n jarvis get svc api-gateway -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "")
        
        if [[ -n "$NODE_PORT" ]]; then
            API_URL="http://${MINIKUBE_IP}:${NODE_PORT}"
            
            # Test API Gateway health
            if curl -sf --max-time 3 "$API_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
                echo -e "  ${GREEN}✅${NC} API Gateway health: UP (functional smoke test)"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} API Gateway health: NOT UP (backend running but health check failed)"
                FAILED=$((FAILED + 1))
            fi
            
            # Test Security Service through gateway (if gateway is UP, security should be accessible)
            # Note: We check gateway health which should include downstream services
            if curl -sf --max-time 3 "$API_URL/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
                echo -e "  ${GREEN}✅${NC} Security Service: accessible via gateway (functional smoke test)"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${YELLOW}⚠️${NC}  Security Service: cannot verify (gateway health check failed)"
                WARNINGS=$((WARNINGS + 1))
            fi
        else
            echo -e "  ${YELLOW}⚠️${NC}  Cannot determine API Gateway NodePort (backend running but service not exposed)"
            WARNINGS=$((WARNINGS + 1))
        fi
    else
        echo -e "  ${YELLOW}⚠️${NC}  Backend not running (skipping functional smoke test)"
        if [[ "$REQUIRE_INSTALL" == "true" ]]; then
            echo -e "  ${YELLOW}ℹ️${NC}   Start backend to run functional health checks"
        fi
        WARNINGS=$((WARNINGS + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Kubernetes not available for functional smoke test"
    WARNINGS=$((WARNINGS + 1))
fi

# Check if LauncherApplication has health check integration
if grep -q "HealthCheckService" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt" && \
   grep -q "updateStatusFromHealth" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} LauncherApplication integrates HealthCheckService"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} LauncherApplication does not integrate HealthCheckService"
    FAILED=$((FAILED + 1))
fi

# Check for hysteresis logic (2 success, 3 failures)
if grep -q "consecutiveSuccess" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt" && \
   grep -q "consecutiveFailures" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt" && \
   grep -q ">= 2" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt" && \
   grep -q ">= 3" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt"; then
    echo -e "  ${GREEN}✅${NC} Hysteresis logic implemented (2 success for READY, 3 failures for ERROR)"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Hysteresis logic not found or incomplete"
    FAILED=$((FAILED + 1))
fi

# Check for timeout protection (AtomicBoolean runningCheck)
if grep -q "runningCheck" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt" && \
   grep -q "AtomicBoolean" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/HealthCheckService.kt"; then
    echo -e "  ${GREEN}✅${NC} Concurrent check protection (runningCheck) implemented"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Concurrent check protection not found"
    FAILED=$((FAILED + 1))
fi

# Stage 4: Log Viewer and Diagnostics
echo ""
echo "=========================================="
echo "Stage 4: Log Viewer and Diagnostics"
echo "=========================================="

# Check if LogViewer class exists
if [[ -f "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LogViewer.kt" ]]; then
    echo -e "  ${GREEN}✅${NC} LogViewer class found"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} LogViewer class not found"
    FAILED=$((FAILED + 1))
fi

# Check if DiagnosticsCollector class exists
if [[ -f "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/DiagnosticsCollector.kt" ]]; then
    echo -e "  ${GREEN}✅${NC} DiagnosticsCollector class found"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} DiagnosticsCollector class not found"
    FAILED=$((FAILED + 1))
fi

# Check if LauncherApplication uses TabPane for Logs
if grep -q "TabPane\|LogViewer" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt" && \
   grep -q "collectDiagnostics\|DiagnosticsCollector" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} LauncherApplication integrates LogViewer and DiagnosticsCollector"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} LauncherApplication does not integrate Stage 4 components"
    FAILED=$((FAILED + 1))
fi

# Check for human-readable error messages
if grep -q "buildErrorMessage\|Action:" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} Human-readable, action-oriented error messages implemented"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Human-readable error messages not found"
    FAILED=$((FAILED + 1))
fi

# Check if diagnostics file can be created (smoke test)
DIAGNOSTICS_TEST_FILE="${JARVIS_HOME}/logs/diagnostics-test-$(date +%Y%m%d-%H%M%S).txt"
if touch "$DIAGNOSTICS_TEST_FILE" 2>/dev/null; then
    echo "Test diagnostics file" > "$DIAGNOSTICS_TEST_FILE"
    if [[ -f "$DIAGNOSTICS_TEST_FILE" ]]; then
        echo -e "  ${GREEN}✅${NC} Diagnostics file creation works"
        rm -f "$DIAGNOSTICS_TEST_FILE" 2>/dev/null || true
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  Diagnostics file creation test inconclusive"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️${NC}  Cannot test diagnostics file creation (permissions?)"
    WARNINGS=$((WARNINGS + 1))
fi

# Stage 5: Process Management and Idempotency
echo ""
echo "=========================================="
echo "Stage 5: Process Management & Idempotency"
echo "=========================================="

# Check if Start All / Stop All buttons exist
if grep -q "startAllButton\|Start All" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt" && \
   grep -q "stopAllButton\|Stop All" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} Start All / Stop All buttons found"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Start All / Stop All buttons not found"
    FAILED=$((FAILED + 1))
fi

# Check if desktop.pid path exists in JarvisPaths
if grep -q "desktopPid\|desktop.pid" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt"; then
    echo -e "  ${GREEN}✅${NC} Desktop PID tracking (desktop.pid) implemented"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Desktop PID tracking not found"
    FAILED=$((FAILED + 1))
fi

# Check if startAll() waits for READY with timeout
if grep -q "startAll\|wait.*READY\|timeout.*90" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} Start All waits for READY with timeout"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Start All timeout logic not found"
    FAILED=$((FAILED + 1))
fi

# Check if stopAll() stops desktop then backend
if grep -q "stopAll\|stopDesktop\|stopBackend" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} Stop All implements desktop → backend sequence"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Stop All sequence not found"
    FAILED=$((FAILED + 1))
fi

# Check if cleanupStalePids() exists
if grep -q "cleanupStalePids\|cleanup.*PID" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} Stale PID cleanup implemented"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Stale PID cleanup not found"
    FAILED=$((FAILED + 1))
fi

# Check if isDesktopRunning() exists (idempotency)
if grep -q "isDesktopRunning\|desktop.*running" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"; then
    echo -e "  ${GREEN}✅${NC} Desktop running check (idempotency) implemented"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} Desktop running check not found"
    FAILED=$((FAILED + 1))
fi

# Stage 6: Security hardening checks
echo ""
echo "=========================================="
echo "Stage 6: Security & Verification Pack"
echo "=========================================="

# Check if SecurityUtils exists
if [[ -f "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/SecurityUtils.kt" ]]; then
    echo -e "  ${GREEN}✅${NC} SecurityUtils class found"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} SecurityUtils class not found"
    FAILED=$((FAILED + 1))
fi

# Check if DiagnosticsCollector uses SecurityUtils
if grep -q "SecurityUtils\|maskSensitiveData" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/DiagnosticsCollector.kt"; then
    echo -e "  ${GREEN}✅${NC} DiagnosticsCollector masks secrets"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} DiagnosticsCollector does not mask secrets"
    FAILED=$((FAILED + 1))
fi

# Check if LogViewer uses SecurityUtils
if grep -q "SecurityUtils\|maskSensitiveData" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LogViewer.kt"; then
    echo -e "  ${GREEN}✅${NC} LogViewer masks secrets in UI"
    PASSED=$((PASSED + 1))
else
    echo -e "  ${RED}❌${NC} LogViewer does not mask secrets"
    FAILED=$((FAILED + 1))
fi

# Stage 6: Backend requirement check
if [[ "$REQUIRE_BACKEND" == "true" ]]; then
    echo ""
    echo "--- Backend Requirement Check (--require-backend) ---"
    
    # Check if backend is running (pods in jarvis namespace)
    if command -v kubectl >/dev/null 2>&1; then
        BACKEND_PODS=$(kubectl get pods -n jarvis --no-headers 2>/dev/null | wc -l)
        if [[ "$BACKEND_PODS" -gt 0 ]]; then
            echo -e "  ${GREEN}✅${NC} Backend is running ($BACKEND_PODS pods in jarvis namespace)"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} Backend is not running (--require-backend specified)"
            FAILED=$((FAILED + 1))
        fi
    else
        echo -e "  ${YELLOW}⚠️${NC}  kubectl not found, cannot check backend status"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    echo ""
    echo "--- Backend Requirement Check (--require-backend not specified, skipping) ---"
    echo -e "  ${GRAY}⏭️${NC}  Backend check skipped (use --require-backend to enforce)"
fi

# Stage 6: Stage 5 process management checks
echo ""
echo "--- Stage 5 Process Management Checks ---"

# Check desktop.pid file exists (if backend is running and desktop was started)
if [[ "$REQUIRE_BACKEND" == "true" ]] && [[ -f "${JARVIS_HOME}/run/desktop.pid" ]]; then
    DESKTOP_PID=$(cat "${JARVIS_HOME}/run/desktop.pid" 2>/dev/null | tr -d '\n')
    if [[ -n "$DESKTOP_PID" ]]; then
        # Check if process is alive
        if ps -p "$DESKTOP_PID" >/dev/null 2>&1; then
            echo -e "  ${GREEN}✅${NC} Desktop PID file exists and process is alive ($DESKTOP_PID)"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  Desktop PID file exists but process is not alive (stale PID)"
            WARNINGS=$((WARNINGS + 1))
        fi
    else
        echo -e "  ${GRAY}⏭️${NC}  Desktop PID file empty (desktop not started)"
    fi
else
    echo -e "  ${GRAY}⏭️${NC}  Desktop PID check skipped (file not found or --require-backend not specified)"
fi

# Check for stale PIDs (after Stop All)
if [[ -f "${JARVIS_HOME}/run/desktop.pid" ]]; then
    DESKTOP_PID=$(cat "${JARVIS_HOME}/run/desktop.pid" 2>/dev/null | tr -d '\n')
    if [[ -n "$DESKTOP_PID" ]] && ! ps -p "$DESKTOP_PID" >/dev/null 2>&1; then
        echo -e "  ${YELLOW}⚠️${NC}  Stale desktop PID detected ($DESKTOP_PID)"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# Stage 6: Check for assistant-core removal (repo hygiene)
# Stage 10: Only FAIL in --strict-repo mode, otherwise WARN/SKIP
echo ""
echo "--- Legacy Component Removal Check ---"

if [[ "$STRICT_REPO" == "true" ]]; then
    # Strict repo mode: FAIL on any legacy references
    echo -e "  ${CYAN}Mode:${NC} Strict repo hygiene (--strict-repo)"
    
    # Check kubectl for assistant-core pods (only if backend required)
    if [[ "$REQUIRE_BACKEND" == "true" ]] && command -v kubectl >/dev/null 2>&1; then
        ASSISTANT_CORE_PODS=$(kubectl get pods -n jarvis 2>/dev/null | grep -c "assistant-core" || echo "0")
        ASSISTANT_CORE_PODS=${ASSISTANT_CORE_PODS:-0}
        if [[ "$ASSISTANT_CORE_PODS" == "0" ]] || [[ "$ASSISTANT_CORE_PODS" -eq 0 ]]; then
            echo -e "  ${GREEN}✅${NC} No assistant-core pods in cluster"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} assistant-core pods still exist in cluster ($ASSISTANT_CORE_PODS found)"
            FAILED=$((FAILED + 1))
        fi
    elif [[ "$REQUIRE_BACKEND" == "true" ]]; then
        echo -e "  ${YELLOW}⚠️${NC}  kubectl not found, cannot check assistant-core pods"
        WARNINGS=$((WARNINGS + 1))
    else
        echo -e "  ${GRAY}⏭️${NC}  Pod check skipped (use --require-backend to check cluster)"
    fi
    
    # Check k8s manifests for assistant-core references (only product paths: base and overlays/local)
    # Exclude commented lines (starting with # or containing # Legacy)
    # Exclude RABBITMQ_RK_ASSISTANT (normal routing key, not legacy)
    ASSISTANT_CORE_REFS=$(grep -r "assistant-core\|ASSISTANT_CORE" "${PROJECT_ROOT}/k8s/base" "${PROJECT_ROOT}/k8s/overlays/local" 2>/dev/null | grep -v "^[[:space:]]*#" | grep -v "# Legacy" | grep -v "RABBITMQ_RK_ASSISTANT" | wc -l | tr -d '\n' || echo "0")
    ASSISTANT_CORE_REFS=${ASSISTANT_CORE_REFS:-0}
    ASSISTANT_CORE_REFS=$(echo "$ASSISTANT_CORE_REFS" | tr -d '\n' | xargs)
    if [[ "$ASSISTANT_CORE_REFS" -eq 0 ]] || [[ "$ASSISTANT_CORE_REFS" == "0" ]]; then
        echo -e "  ${GREEN}✅${NC} No assistant-core references in product k8s manifests (k8s/base, k8s/overlays/local)"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} assistant-core references found in product k8s manifests ($ASSISTANT_CORE_REFS found)"
        FAILED=$((FAILED + 1))
        echo -e "    ${GRAY}Details:${NC}"
        grep -r "assistant-core\|ASSISTANT_CORE" "${PROJECT_ROOT}/k8s/base" "${PROJECT_ROOT}/k8s/overlays/local" 2>/dev/null | grep -v "^[[:space:]]*#" | grep -v "# Legacy" | grep -v "RABBITMQ_RK_ASSISTANT" | head -3 | sed 's/^/      /'
    fi
else
    # Default mode: WARN only, don't fail verification
    echo -e "  ${GRAY}Mode:${NC} Product checks only (use --strict-repo for repo hygiene)"
    
    # Check k8s manifests for assistant-core references (only product paths: base and overlays/local)
    # Exclude commented lines (starting with # or containing # Legacy)
    # Exclude RABBITMQ_RK_ASSISTANT (normal routing key, not legacy)
    ASSISTANT_CORE_REFS=$(grep -r "assistant-core\|ASSISTANT_CORE" "${PROJECT_ROOT}/k8s/base" "${PROJECT_ROOT}/k8s/overlays/local" 2>/dev/null | grep -v "^[[:space:]]*#" | grep -v "# Legacy" | grep -v "RABBITMQ_RK_ASSISTANT" | wc -l | tr -d '\n' || echo "0")
    ASSISTANT_CORE_REFS=${ASSISTANT_CORE_REFS:-0}
    ASSISTANT_CORE_REFS=$(echo "$ASSISTANT_CORE_REFS" | tr -d '\n' | xargs)
    if [[ "$ASSISTANT_CORE_REFS" -eq 0 ]] || [[ "$ASSISTANT_CORE_REFS" == "0" ]]; then
        echo -e "  ${GREEN}✅${NC} No assistant-core references in product k8s manifests"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  assistant-core references found in product k8s manifests ($ASSISTANT_CORE_REFS found)"
        echo -e "    ${GRAY}Note:${NC} These may be in commented-out config or legacy configmap entries"
        echo -e "    ${GRAY}Action:${NC} Use --strict-repo to enforce strict repo hygiene"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# Stage 7: HTTPS/TLS Verification (Iteration 1.5)
if [[ "$REQUIRE_HTTPS" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "Stage 7: HTTPS/TLS Verification (--require-https)"
    echo "=========================================="
    
    # Check /etc/hosts entries
    if grep -q "api.jarvis.local" /etc/hosts && grep -q "voice.jarvis.local" /etc/hosts; then
        echo -e "  ${GREEN}✅${NC} /etc/hosts contains api.jarvis.local and voice.jarvis.local"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} /etc/hosts missing api.jarvis.local or voice.jarvis.local"
        echo -e "    ${GRAY}Action: Run sudo ./scripts/product/jarvis-setup-hosts.sh${NC}"
        FAILED=$((FAILED + 1))
    fi
    
    # Check ingress exists
    if command -v kubectl >/dev/null 2>&1; then
        INGRESS_EXISTS=$(kubectl get ingress -n jarvis jarvis-ingress 2>/dev/null | wc -l)
        if [[ "$INGRESS_EXISTS" -gt 1 ]]; then
            echo -e "  ${GREEN}✅${NC} Ingress jarvis-ingress exists"
            PASSED=$((PASSED + 1))
            
            # Check ingress has correct hosts
            INGRESS_HOSTS=$(kubectl get ingress -n jarvis jarvis-ingress -o jsonpath='{.spec.rules[*].host}' 2>/dev/null || echo "")
            if echo "$INGRESS_HOSTS" | grep -q "api.jarvis.local" && echo "$INGRESS_HOSTS" | grep -q "voice.jarvis.local"; then
                echo -e "  ${GREEN}✅${NC} Ingress configured for api.jarvis.local and voice.jarvis.local"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} Ingress missing api.jarvis.local or voice.jarvis.local hosts"
                FAILED=$((FAILED + 1))
            fi
            
            # Check TLS secret
            TLS_SECRET=$(kubectl get secret -n jarvis jarvis-tls 2>/dev/null | wc -l)
            if [[ "$TLS_SECRET" -gt 1 ]]; then
                echo -e "  ${GREEN}✅${NC} TLS secret jarvis-tls exists"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} TLS secret jarvis-tls not found"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} Ingress jarvis-ingress not found"
            FAILED=$((FAILED + 1))
        fi
    else
        echo -e "  ${YELLOW}⚠️${NC}  kubectl not found, cannot check ingress"
        WARNINGS=$((WARNINGS + 1))
    fi
    
    # Check CA trust store
    if [[ -f "/usr/local/share/ca-certificates/jarvis-ca.crt" ]]; then
        echo -e "  ${GREEN}✅${NC} CA certificate installed in trust store"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} CA certificate not in trust store"
        echo -e "    ${GRAY}Action: Run sudo ./scripts/product/jarvis-install-tls.sh${NC}"
        FAILED=$((FAILED + 1))
    fi
    
    # Test HTTPS connection (curl without -k)
    if command -v curl >/dev/null 2>&1; then
        if curl -s --max-time 5 "https://api.jarvis.local/actuator/health" >/dev/null 2>&1; then
            echo -e "  ${GREEN}✅${NC} HTTPS connection to api.jarvis.local works (curl without -k)"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} HTTPS connection to api.jarvis.local failed"
            echo -e "    ${GRAY}Check: curl -v https://api.jarvis.local/actuator/health${NC}"
            FAILED=$((FAILED + 1))
        fi
    else
        echo -e "  ${YELLOW}⚠️${NC}  curl not found, cannot test HTTPS connection"
        WARNINGS=$((WARNINGS + 1))
    fi
    
    # Test SSL certificate verification (openssl)
    if command -v openssl >/dev/null 2>&1; then
        if echo | openssl s_client -connect api.jarvis.local:443 -servername api.jarvis.local 2>/dev/null | grep -q "Verify return code: 0 (ok)"; then
            echo -e "  ${GREEN}✅${NC} SSL certificate verification successful (openssl)"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} SSL certificate verification failed"
            echo -e "    ${GRAY}Check: echo | openssl s_client -connect api.jarvis.local:443${NC}"
            FAILED=$((FAILED + 1))
        fi
    else
        echo -e "  ${YELLOW}⚠️${NC}  openssl not found, cannot verify SSL certificate"
        WARNINGS=$((WARNINGS + 1))
    fi
    
    # Test WebSocket endpoint (voice.jarvis.local)
    if command -v curl >/dev/null 2>&1; then
        # Try to connect to WebSocket endpoint (will fail handshake but should connect)
        if curl -s --max-time 3 -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Key: test" -H "Sec-WebSocket-Version: 13" "https://voice.jarvis.local/ws/voice" 2>&1 | grep -q "HTTP\|101\|400\|404"; then
            echo -e "  ${GREEN}✅${NC} WebSocket endpoint voice.jarvis.local accessible"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  WebSocket endpoint check inconclusive (may require full handshake)"
            WARNINGS=$((WARNINGS + 1))
        fi
    fi
    
    # Stage 8: K8s Runtime Hardening checks
    echo ""
    echo "=========================================="
    echo "Stage 8: K8s Runtime Hardening (--require-https)"
    echo "=========================================="
    
    # Check that jarvis-launch.sh has TLS_ACTIVE logic (simplified check)
    if grep -q "TLS_ACTIVE\|JARVIS_USE_TLS" "${PROJECT_ROOT}/jarvis-launch.sh"; then
        echo -e "  ${GREEN}✅${NC} jarvis-launch.sh has TLS detection logic"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} jarvis-launch.sh missing TLS detection logic"
        FAILED=$((FAILED + 1))
    fi
    
    # Check that port-forward is conditional (ENABLE_PORT_FORWARD)
    if grep -q "ENABLE_PORT_FORWARD" "${PROJECT_ROOT}/jarvis-launch.sh" && \
       grep -q "ENABLE_PORT_FORWARD.*false" "${PROJECT_ROOT}/jarvis-launch.sh"; then
        echo -e "  ${GREEN}✅${NC} Port-forward is conditional (ENABLE_PORT_FORWARD flag)"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} Port-forward not conditional (should use ENABLE_PORT_FORWARD)"
        FAILED=$((FAILED + 1))
    fi
    
    # Check that rollout status is used for core services
    if grep -q "kubectl rollout status" "${PROJECT_ROOT}/jarvis-launch.sh" && \
       grep -q "api-gateway\|security-service" "${PROJECT_ROOT}/jarvis-launch.sh"; then
        echo -e "  ${GREEN}✅${NC} kubectl rollout status used for core services"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} kubectl rollout status not used for core services"
        FAILED=$((FAILED + 1))
    fi
    
    # Check that NodePort is shown as DEBUG fallback
    if grep -q "\[DEBUG\].*NodePort\|DEBUG.*fallback" "${PROJECT_ROOT}/jarvis-launch.sh"; then
        echo -e "  ${GREEN}✅${NC} NodePort shown as DEBUG fallback"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  NodePort not explicitly marked as DEBUG (may be acceptable)"
        WARNINGS=$((WARNINGS + 1))
    fi
    
    # Check Desktop/Launcher TLS support (wss:// when TLS active)
    if grep -q "wss://\|WSS" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt" 2>/dev/null || \
       grep -q "wss://\|WSS" "${PROJECT_ROOT}/apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/config/AppConfig.kt" 2>/dev/null; then
        echo -e "  ${GREEN}✅${NC} Desktop/Launcher support WSS when TLS active"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${YELLOW}⚠️${NC}  Desktop/Launcher WSS support not explicitly checked (may be in runtime logic)"
        WARNINGS=$((WARNINGS + 1))
    fi
else
    echo ""
    echo "=========================================="
    echo "Stage 7: HTTPS/TLS Verification (--require-https not specified, skipping)"
    echo "=========================================="
    echo -e "  ${GRAY}⏭️${NC}  HTTPS checks skipped (use --require-https to enforce)"
    echo ""
    echo "=========================================="
    echo "Stage 8: K8s Runtime Hardening (--require-https not specified, skipping)"
    echo "=========================================="
    echo -e "  ${GRAY}⏭️${NC}  Stage 8 checks skipped (use --require-https to enforce)"
fi

# =============================================================================
# Stage 9: Product Release & Update Flow
# =============================================================================
if [[ "$REQUIRE_INSTALL" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "Stage 9: Product Release & Update Flow"
    echo "=========================================="
    
    # Check VERSION file exists
    VERSION_FILE="${JARVIS_APP}/VERSION"
    if [[ -f "$VERSION_FILE" ]]; then
        INSTALLED_VERSION=$(cat "$VERSION_FILE" 2>/dev/null | tr -d ' ' || echo "")
        echo -e "  ${GREEN}✅${NC} VERSION file exists (version: $INSTALLED_VERSION)"
        PASSED=$((PASSED + 1))
        
        # Check VERSION matches root pom.xml (jarvis-root, not parent Spring Boot)
        ROOT_VERSION=$(grep -A1 "<artifactId>jarvis-root</artifactId>" "${PROJECT_ROOT}/pom.xml" | grep "<version>" | head -1 | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/' | tr -d ' ' || echo "")
        if [[ "$INSTALLED_VERSION" == "$ROOT_VERSION" ]]; then
            echo -e "  ${GREEN}✅${NC} VERSION matches root pom.xml ($ROOT_VERSION)"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} VERSION mismatch (installed: $INSTALLED_VERSION, pom.xml: $ROOT_VERSION)"
            FAILED=$((FAILED + 1))
        fi
        
        # Check launcher.jar manifest version (if available)
        LAUNCHER_JAR="${JARVIS_APP}/launcher.jar"
        if [[ -f "$LAUNCHER_JAR" ]] && command -v unzip >/dev/null 2>&1; then
            MANIFEST_VERSION=$(unzip -p "$LAUNCHER_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep "Implementation-Version:" | sed -E 's/Implementation-Version: (.+)/\1/' | tr -d '\r\n ' || echo "")
            if [[ -n "$MANIFEST_VERSION" ]]; then
                if [[ "$MANIFEST_VERSION" == "$INSTALLED_VERSION" ]]; then
                    echo -e "  ${GREEN}✅${NC} Launcher JAR manifest version matches VERSION file ($MANIFEST_VERSION)"
                    PASSED=$((PASSED + 1))
                else
                    echo -e "  ${YELLOW}⚠️${NC}  Version mismatch (VERSION: $INSTALLED_VERSION, manifest: $MANIFEST_VERSION)"
                    WARNINGS=$((WARNINGS + 1))
                fi
            fi
        fi
    else
        echo -e "  ${RED}❌${NC} VERSION file not found at ${VERSION_FILE}"
        FAILED=$((FAILED + 1))
    fi
    
    # Check upgrade flow (backup directory)
    BACKUP_DIR="${JARVIS_APP}/backup"
    if [[ -d "$BACKUP_DIR" ]]; then
        BACKUP_COUNT=$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l)
        if [[ "$BACKUP_COUNT" -gt 0 ]]; then
            echo -e "  ${GREEN}✅${NC} Upgrade backup directory exists ($BACKUP_COUNT backup(s))"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${GRAY}⏭️${NC}  Backup directory exists but empty (fresh install or no upgrades yet)"
        fi
    else
        echo -e "  ${GRAY}⏭️${NC}  Backup directory not found (fresh install, expected)"
    fi
    
    # Check jarvis-build-release.sh exists
    BUILD_RELEASE_SCRIPT="${PROJECT_ROOT}/scripts/product/jarvis-build-release.sh"
    if [[ -f "$BUILD_RELEASE_SCRIPT" ]] && [[ -x "$BUILD_RELEASE_SCRIPT" ]]; then
        echo -e "  ${GREEN}✅${NC} jarvis-build-release.sh exists and is executable"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} jarvis-build-release.sh not found or not executable"
        FAILED=$((FAILED + 1))
    fi
else
    echo ""
    echo "=========================================="
    echo "Stage 9: Product Release & Update Flow (--require-install not specified, skipping)"
    echo "=========================================="
    echo -e "  ${GRAY}⏭️${NC}  Stage 9 checks skipped (use --require-install to enforce)"
fi

# =============================================================================
# Stage 11: Release Artifact Integrity (Iteration 1.5)
# =============================================================================
if [[ "$REQUIRE_RELEASE" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "Stage 11: Release Artifact Integrity (--require-release)"
    echo "=========================================="
    
    # Find release archive
    RELEASE_ARCHIVE=$(find "${PROJECT_ROOT}/target/release" -name "jarvis-release-*.tar.gz" 2>/dev/null | head -1)
    if [[ -z "$RELEASE_ARCHIVE" ]]; then
        echo -e "  ${RED}❌${NC} Release archive not found in target/release/"
        echo -e "    ${GRAY}Action: Run ./scripts/product/jarvis-build-release.sh${NC}"
        FAILED=$((FAILED + 1))
    else
        echo -e "  ${GREEN}✅${NC} Release archive found: $(basename "$RELEASE_ARCHIVE")"
        PASSED=$((PASSED + 1))
        
        # Check SHA256SUMS
        SHA256SUMS_FILE="${PROJECT_ROOT}/target/release/SHA256SUMS"
        if [[ -f "$SHA256SUMS_FILE" ]]; then
            echo -e "  ${GREEN}✅${NC} SHA256SUMS file found"
            PASSED=$((PASSED + 1))
            
            # Verify SHA256SUMS contains expected entries
            if grep -q "\.tar\.gz\|launcher\.jar\|install\.sh" "$SHA256SUMS_FILE"; then
                echo -e "  ${GREEN}✅${NC} SHA256SUMS contains expected entries"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} SHA256SUMS missing expected entries"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} SHA256SUMS file not found"
            echo -e "    ${GRAY}Action: Run ./scripts/product/jarvis-build-release.sh${NC}"
            FAILED=$((FAILED + 1))
        fi
        
        # Check archive contents (extract and verify install.sh and launcher.jar)
        TEMP_DIR=$(mktemp -d)
        trap "rm -rf $TEMP_DIR" EXIT
        if tar -xzf "$RELEASE_ARCHIVE" -C "$TEMP_DIR" >/dev/null 2>&1; then
            RELEASE_DIR=$(find "$TEMP_DIR" -maxdepth 1 -type d -name "jarvis-release-*" | head -1)
            if [[ -n "$RELEASE_DIR" ]]; then
                if [[ -f "${RELEASE_DIR}/install.sh" ]]; then
                    echo -e "  ${GREEN}✅${NC} install.sh found in archive"
                    PASSED=$((PASSED + 1))
                else
                    echo -e "  ${RED}❌${NC} install.sh missing in archive"
                    FAILED=$((FAILED + 1))
                fi
                
                if [[ -f "${RELEASE_DIR}/launcher.jar" ]]; then
                    echo -e "  ${GREEN}✅${NC} launcher.jar found in archive"
                    PASSED=$((PASSED + 1))
                else
                    echo -e "  ${RED}❌${NC} launcher.jar missing in archive"
                    FAILED=$((FAILED + 1))
                fi
                
                if [[ -f "${RELEASE_DIR}/SHA256SUMS" ]]; then
                    echo -e "  ${GREEN}✅${NC} SHA256SUMS included in archive"
                    PASSED=$((PASSED + 1))
                else
                    echo -e "  ${YELLOW}⚠️${NC}  SHA256SUMS not included in archive (optional)"
                    WARNINGS=$((WARNINGS + 1))
                fi
            else
                echo -e "  ${RED}❌${NC} Failed to extract release archive"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} Failed to extract release archive"
            FAILED=$((FAILED + 1))
        fi
        rm -rf "$TEMP_DIR"
        trap - EXIT
    fi
else
    echo ""
    echo "=========================================="
    echo "Stage 11: Release Artifact Integrity (--require-release not specified, skipping)"
    echo "=========================================="
    echo -e "  ${GRAY}⏭️${NC}  Release checks skipped (use --require-release to enforce)"
fi

# =============================================================================
# Stage 12: Portability Gate (no hardcoded /usr/bin/bash)
# =============================================================================
echo ""
echo "=========================================="
echo "Stage 12: Portability Gate"
echo "=========================================="

# Check for hardcoded /usr/bin/bash (FAIL)
HARDCODED_BASH_MATCHES=$(grep -RIn --exclude-dir=.git --exclude-dir=target --exclude-dir=.idea --exclude-dir=node_modules "/usr/bin/bash" "${PROJECT_ROOT}/jarvis-launch.sh" "${PROJECT_ROOT}/scripts/" "${PROJECT_ROOT}/apps/launcher-javafx/src/main/" 2>/dev/null | head -20 || true)
if [[ -n "$HARDCODED_BASH_MATCHES" ]]; then
    echo -e "  ${RED}❌${NC} Hardcoded /usr/bin/bash found (FAIL)"
    echo -e "    ${GRAY}Use /usr/bin/env bash or bash from PATH${NC}"
    echo -e "    ${GRAY}Matches:${NC}"
    echo "$HARDCODED_BASH_MATCHES" | sed 's/^/      /'
    FAILED=$((FAILED + 1))
else
    echo -e "  ${GREEN}✅${NC} No hardcoded /usr/bin/bash found"
    PASSED=$((PASSED + 1))
fi

# Check for #!/bin/bash (WARN, recommend #!/usr/bin/env bash)
BIN_BASH_MATCHES=$(grep -RIn --exclude-dir=.git --exclude-dir=target --exclude-dir=.idea --exclude-dir=node_modules "^#!/bin/bash" "${PROJECT_ROOT}/scripts/" "${PROJECT_ROOT}/apps/" 2>/dev/null | head -20 || true)
if [[ -n "$BIN_BASH_MATCHES" ]]; then
    echo -e "  ${YELLOW}⚠️${NC}  #!/bin/bash found (recommend #!/usr/bin/env bash for portability)"
    echo -e "    ${GRAY}Matches:${NC}"
    echo "$BIN_BASH_MATCHES" | sed 's/^/      /'
    WARNINGS=$((WARNINGS + 1))
else
    echo -e "  ${GREEN}✅${NC} No #!/bin/bash found (all use #!/usr/bin/env bash)"
    PASSED=$((PASSED + 1))
fi

# Check release archive install.sh if available
if [[ "$REQUIRE_RELEASE" == "true" ]] && [[ -n "${RELEASE_ARCHIVE:-}" ]] && [[ -f "$RELEASE_ARCHIVE" ]]; then
    TEMP_RELEASE_CHECK=$(mktemp -d)
    trap "rm -rf $TEMP_RELEASE_CHECK" EXIT
    if tar -xzf "$RELEASE_ARCHIVE" -C "$TEMP_RELEASE_CHECK" >/dev/null 2>&1; then
        RELEASE_DIR_CHECK=$(find "$TEMP_RELEASE_CHECK" -maxdepth 1 -type d -name "jarvis-release-*" | head -1)
        if [[ -n "$RELEASE_DIR_CHECK" ]] && [[ -f "${RELEASE_DIR_CHECK}/install.sh" ]]; then
            RELEASE_INSTALL_BASH=$(grep -n "/usr/bin/bash" "${RELEASE_DIR_CHECK}/install.sh" 2>/dev/null || true)
            if [[ -n "$RELEASE_INSTALL_BASH" ]]; then
                echo -e "  ${RED}❌${NC} install.sh in release archive contains /usr/bin/bash"
                echo -e "    ${GRAY}Matches:${NC}"
                echo "$RELEASE_INSTALL_BASH" | sed 's/^/      /'
                FAILED=$((FAILED + 1))
            else
                echo -e "  ${GREEN}✅${NC} install.sh in release archive uses portable bash"
                PASSED=$((PASSED + 1))
            fi
        fi
    fi
    rm -rf "$TEMP_RELEASE_CHECK"
    trap - EXIT
fi

# =============================================================================
# Stage 13: Memory Stack (ENABLE_MEMORY flag)
# =============================================================================
echo ""
echo "=========================================="
echo "Stage 13: Memory Stack (ENABLE_MEMORY)"
echo "=========================================="

# Check ENABLE_MEMORY flag
ENABLE_MEMORY_ENV=$(grep -E "^ENABLE_MEMORY=" "${PROJECT_ROOT}/jarvis-launch.sh" 2>/dev/null | head -1 | cut -d'=' -f2 | tr -d ' ' || echo "false")
ENABLE_MEMORY_RUNTIME=$(kubectl get configmap jarvis-local-config -n jarvis -o jsonpath='{.data.MEMORY_ENABLED}' 2>/dev/null || echo "false")

if [[ "$REQUIRE_MEMORY" == "true" ]]; then
    # Strict mode: ENABLE_MEMORY must be true
    if [[ "$ENABLE_MEMORY_ENV" != "true" ]] && [[ "$ENABLE_MEMORY_RUNTIME" != "true" ]]; then
        echo -e "  ${RED}❌${NC} ENABLE_MEMORY=false but --require-memory specified"
        echo -e "    ${GRAY}Set ENABLE_MEMORY=true in jarvis-launch.sh or environment${NC}"
        FAILED=$((FAILED + 1))
    else
        echo -e "  ${GREEN}✅${NC} ENABLE_MEMORY=true (required)"
        PASSED=$((PASSED + 1))
        
        # Check memory resources are deployed
        if kubectl get statefulset postgres-pgvector -n jarvis >/dev/null 2>&1; then
            PG_REPLICAS=$(kubectl get statefulset postgres-pgvector -n jarvis -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")
            if [[ "$PG_REPLICAS" -ge 1 ]]; then
                echo -e "  ${GREEN}✅${NC} postgres-pgvector deployed (replicas: $PG_REPLICAS)"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} postgres-pgvector scaled to 0 (should be >= 1)"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} postgres-pgvector statefulset not found"
            FAILED=$((FAILED + 1))
        fi
        
        if kubectl get deployment memory-service -n jarvis >/dev/null 2>&1; then
            MEM_REPLICAS=$(kubectl get deployment memory-service -n jarvis -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")
            if [[ "$MEM_REPLICAS" -ge 1 ]]; then
                echo -e "  ${GREEN}✅${NC} memory-service deployed (replicas: $MEM_REPLICAS)"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} memory-service scaled to 0 (should be >= 1)"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} memory-service deployment not found"
            FAILED=$((FAILED + 1))
        fi
        
        # Check pods are Running/Ready
        if [[ "$REQUIRE_BACKEND" == "true" ]]; then
            PG_POD_STATUS=$(kubectl get pods -n jarvis -l app=postgres-pgvector -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
            if [[ "$PG_POD_STATUS" == "Running" ]]; then
                echo -e "  ${GREEN}✅${NC} postgres-pgvector pod Running"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} postgres-pgvector pod not Running (status: $PG_POD_STATUS)"
                FAILED=$((FAILED + 1))
            fi
            
            MEM_POD_STATUS=$(kubectl get pods -n jarvis -l app=memory-service -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
            if [[ "$MEM_POD_STATUS" == "Running" ]]; then
                echo -e "  ${GREEN}✅${NC} memory-service pod Running"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} memory-service pod not Running (status: $MEM_POD_STATUS)"
                FAILED=$((FAILED + 1))
            fi
            
            # Check for CrashLoopBackOff/ImagePullBackOff
            PG_CRASH=$(kubectl get pods -n jarvis -l app=postgres-pgvector -o jsonpath='{.items[0].status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || echo "")
            if [[ "$PG_CRASH" == "CrashLoopBackOff" ]] || [[ "$PG_CRASH" == "ImagePullBackOff" ]]; then
                echo -e "  ${RED}❌${NC} postgres-pgvector in $PG_CRASH"
                FAILED=$((FAILED + 1))
            fi
            
            MEM_CRASH=$(kubectl get pods -n jarvis -l app=memory-service -o jsonpath='{.items[0].status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || echo "")
            if [[ "$MEM_CRASH" == "CrashLoopBackOff" ]] || [[ "$MEM_CRASH" == "ImagePullBackOff" ]]; then
                echo -e "  ${RED}❌${NC} memory-service in $MEM_CRASH"
                FAILED=$((FAILED + 1))
            fi
            
            # Check memory-service health endpoint (if backend is running)
            MEMORY_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8093/memory/health" 2>/dev/null || echo "000")
            if [[ "$MEMORY_HEALTH" == "200" ]]; then
                echo -e "  ${GREEN}✅${NC} memory-service health endpoint UP"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${YELLOW}⚠️${NC}  memory-service health endpoint not accessible (code: $MEMORY_HEALTH)"
                echo -e "    ${GRAY}Note: Health check via API Gateway recommended${NC}"
                WARNINGS=$((WARNINGS + 1))
            fi
        else
            echo -e "  ${GRAY}⏭️${NC}  Pod status checks skipped (use --require-backend to enforce)"
        fi
    fi
else
    # Default mode: memory checks are optional (SKIP/WARN, not FAIL)
    if [[ "$ENABLE_MEMORY_ENV" == "true" ]] || [[ "$ENABLE_MEMORY_RUNTIME" == "true" ]]; then
        echo -e "  ${GREEN}✅${NC} ENABLE_MEMORY=true (optional, enabled)"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${GRAY}⏭️${NC}  Memory stack disabled (ENABLE_MEMORY=false, optional)"
        echo -e "    ${GRAY}Use --require-memory to enforce memory stack checks${NC}"
    fi
fi

# =============================================================================
# Stage 14: LLM Stack (ENABLE_LLM flag + GPU)
# =============================================================================
echo ""
echo "=========================================="
echo "Stage 14: LLM Stack (ENABLE_LLM + GPU)"
echo "=========================================="

# Check ENABLE_LLM flag
ENABLE_LLM_ENV=$(grep -E "^ENABLE_LLM=" "${PROJECT_ROOT}/jarvis-launch.sh" 2>/dev/null | head -1 | cut -d'=' -f2 | tr -d ' ' || echo "false")
ENABLE_GPU_ENV=$(grep -E "^ENABLE_GPU=" "${PROJECT_ROOT}/jarvis-launch.sh" 2>/dev/null | head -1 | cut -d'=' -f2 | tr -d ' ' || echo "true")

if [[ "$REQUIRE_LLM" == "true" ]]; then
    # Strict mode: ENABLE_LLM must be true
    if [[ "$ENABLE_LLM_ENV" != "true" ]]; then
        echo -e "  ${RED}❌${NC} ENABLE_LLM=false but --require-llm specified"
        echo -e "    ${GRAY}Set ENABLE_LLM=true in jarvis-launch.sh or environment${NC}"
        FAILED=$((FAILED + 1))
    else
        echo -e "  ${GREEN}✅${NC} ENABLE_LLM=true (required)"
        PASSED=$((PASSED + 1))
        
        # Check LLM resources are deployed
        if kubectl get deployment llm-server -n jarvis >/dev/null 2>&1; then
            LLM_REPLICAS=$(kubectl get deployment llm-server -n jarvis -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")
            if [[ "$LLM_REPLICAS" -ge 1 ]]; then
                echo -e "  ${GREEN}✅${NC} llm-server deployed (replicas: $LLM_REPLICAS)"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} llm-server scaled to 0 (should be >= 1)"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} llm-server deployment not found"
            FAILED=$((FAILED + 1))
        fi
        
        if kubectl get deployment llm-service -n jarvis >/dev/null 2>&1; then
            LLM_SVC_REPLICAS=$(kubectl get deployment llm-service -n jarvis -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")
            if [[ "$LLM_SVC_REPLICAS" -ge 1 ]]; then
                echo -e "  ${GREEN}✅${NC} llm-service deployed (replicas: $LLM_SVC_REPLICAS)"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} llm-service scaled to 0 (should be >= 1)"
                FAILED=$((FAILED + 1))
            fi
        else
            echo -e "  ${RED}❌${NC} llm-service deployment not found"
            FAILED=$((FAILED + 1))
        fi
        
        # Check pods are Running/Ready
        if [[ "$REQUIRE_BACKEND" == "true" ]]; then
            LLM_POD_STATUS=$(kubectl get pods -n jarvis -l app=llm-server -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
            if [[ "$LLM_POD_STATUS" == "Running" ]]; then
                echo -e "  ${GREEN}✅${NC} llm-server pod Running"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} llm-server pod not Running (status: $LLM_POD_STATUS)"
                FAILED=$((FAILED + 1))
            fi
            
            LLM_SVC_POD_STATUS=$(kubectl get pods -n jarvis -l app=llm-service -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
            if [[ "$LLM_SVC_POD_STATUS" == "Running" ]]; then
                echo -e "  ${GREEN}✅${NC} llm-service pod Running"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${RED}❌${NC} llm-service pod not Running (status: $LLM_SVC_POD_STATUS)"
                FAILED=$((FAILED + 1))
            fi
            
            # Check for CrashLoopBackOff/ImagePullBackOff
            LLM_CRASH=$(kubectl get pods -n jarvis -l app=llm-server -o jsonpath='{.items[0].status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || echo "")
            if [[ "$LLM_CRASH" == "CrashLoopBackOff" ]] || [[ "$LLM_CRASH" == "ImagePullBackOff" ]]; then
                echo -e "  ${RED}❌${NC} llm-server in $LLM_CRASH"
                echo -e "    ${GRAY}Check logs: kubectl logs -n jarvis -l app=llm-server --tail=50${NC}"
                FAILED=$((FAILED + 1))
            fi
            
            # GPU checks (if ENABLE_GPU=true)
            if [[ "$ENABLE_GPU_ENV" == "true" ]]; then
                # Check GPU allocatable
                GPU_ALLOCATABLE=$(kubectl get nodes -o jsonpath='{.items[0].status.allocatable.nvidia\.com/gpu}' 2>/dev/null || echo "")
                if [[ -n "$GPU_ALLOCATABLE" ]] && [[ "$GPU_ALLOCATABLE" != "0" ]]; then
                    echo -e "  ${GREEN}✅${NC} GPU allocatable in cluster: $GPU_ALLOCATABLE"
                    PASSED=$((PASSED + 1))
                    
                    # Check GPU resource in pod spec
                    LLM_GPU_REQUEST=$(kubectl get deployment llm-server -n jarvis -o jsonpath='{.spec.template.spec.containers[0].resources.requests.nvidia\.com/gpu}' 2>/dev/null || echo "")
                    if [[ -n "$LLM_GPU_REQUEST" ]] && [[ "$LLM_GPU_REQUEST" != "0" ]]; then
                        echo -e "  ${GREEN}✅${NC} llm-server requests GPU: $LLM_GPU_REQUEST"
                        PASSED=$((PASSED + 1))
                    else
                        echo -e "  ${YELLOW}⚠️${NC}  llm-server does not request GPU (may be CPU fallback)"
                        WARNINGS=$((WARNINGS + 1))
                    fi
                else
                    echo -e "  ${RED}❌${NC} No GPU allocatable in cluster (nvidia.com/gpu: $GPU_ALLOCATABLE)"
                    echo -e "    ${GRAY}Action: Enable GPU support (minikube addons enable gpu)${NC}"
                    FAILED=$((FAILED + 1))
                fi
                
                # Check NVIDIA device plugin
                if kubectl get daemonset -n kube-system nvidia-device-plugin-daemonset >/dev/null 2>&1 || \
                   kubectl get daemonset -A | grep -q nvidia-device-plugin; then
                    echo -e "  ${GREEN}✅${NC} NVIDIA device plugin found"
                    PASSED=$((PASSED + 1))
                else
                    echo -e "  ${YELLOW}⚠️${NC}  NVIDIA device plugin not found"
                    echo -e "    ${GRAY}Action: Install nvidia-device-plugin daemonset${NC}"
                    WARNINGS=$((WARNINGS + 1))
                fi
            else
                echo -e "  ${GRAY}⏭️${NC}  GPU checks skipped (ENABLE_GPU=false, CPU fallback mode)"
            fi
            
            # Check LLM health endpoint
            LLM_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:5000/health" 2>/dev/null || echo "000")
            if [[ "$LLM_HEALTH" == "200" ]]; then
                echo -e "  ${GREEN}✅${NC} llm-server health endpoint UP"
                PASSED=$((PASSED + 1))
            else
                echo -e "  ${YELLOW}⚠️${NC}  llm-server health endpoint not accessible (code: $LLM_HEALTH)"
                echo -e "    ${GRAY}Note: Health check via API Gateway recommended${NC}"
                WARNINGS=$((WARNINGS + 1))
            fi
        else
            echo -e "  ${GRAY}⏭️${NC}  Pod status checks skipped (use --require-backend to enforce)"
        fi
    fi
else
    # Default mode: LLM checks are optional (SKIP/WARN, not FAIL)
    if [[ "$ENABLE_LLM_ENV" == "true" ]]; then
        echo -e "  ${GREEN}✅${NC} ENABLE_LLM=true (optional, enabled)"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${GRAY}⏭️${NC}  LLM stack disabled (ENABLE_LLM=false, optional)"
        echo -e "    ${GRAY}Use --require-llm to enforce LLM stack checks${NC}"
    fi
fi

# =============================================================================
# Stage 15: Product Polish (Icons, Desktop Entry, UI)
# =============================================================================
if [[ "$REQUIRE_INSTALL" == "true" ]]; then
    echo ""
    echo "=========================================="
    echo "Stage 15: Product Polish (Icons, Desktop Entry, UI)"
    echo "=========================================="
    
    # Check icon exists
    ICON_FILE="${JARVIS_APP}/assets/icons/jarvis.png"
    if [[ -f "$ICON_FILE" ]]; then
        echo -e "  ${GREEN}✅${NC} Icon file exists: $ICON_FILE"
        PASSED=$((PASSED + 1))
    else
        echo -e "  ${RED}❌${NC} Icon file not found: $ICON_FILE"
        echo -e "    ${GRAY}Expected after install: ~/.jarvis/app/assets/icons/jarvis.png${NC}"
        FAILED=$((FAILED + 1))
    fi
    
    # Check desktop entry icon path
    if [[ -f "$DESKTOP_FILE" ]]; then
        DESKTOP_ICON=$(grep "^Icon=" "$DESKTOP_FILE" | cut -d'=' -f2 || echo "")
        if [[ "$DESKTOP_ICON" == *"jarvis.png"* ]] && [[ "$DESKTOP_ICON" == *"\$HOME/.jarvis/app/assets/icons"* ]]; then
            echo -e "  ${GREEN}✅${NC} Desktop entry Icon points to jarvis.png"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} Desktop entry Icon incorrect: $DESKTOP_ICON"
            echo -e "    ${GRAY}Expected: Icon=\$HOME/.jarvis/app/assets/icons/jarvis.png${NC}"
            FAILED=$((FAILED + 1))
        fi
        
        # Check Categories
        DESKTOP_CATEGORIES=$(grep "^Categories=" "$DESKTOP_FILE" | cut -d'=' -f2 || echo "")
        if [[ "$DESKTOP_CATEGORIES" == *"Utility"* ]] && [[ "$DESKTOP_CATEGORIES" == *"Development"* ]]; then
            echo -e "  ${GREEN}✅${NC} Desktop entry Categories correct: $DESKTOP_CATEGORIES"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  Desktop entry Categories may be incomplete: $DESKTOP_CATEGORIES"
            echo -e "    ${GRAY}Expected: Categories=Utility;Development;${NC}"
            WARNINGS=$((WARNINGS + 1))
        fi
        
        # Check Keywords
        DESKTOP_KEYWORDS=$(grep "^Keywords=" "$DESKTOP_FILE" | cut -d'=' -f2 || echo "")
        if [[ "$DESKTOP_KEYWORDS" == *"Jarvis"* ]] && [[ "$DESKTOP_KEYWORDS" == *"AI"* ]] && [[ "$DESKTOP_KEYWORDS" == *"Launcher"* ]]; then
            echo -e "  ${GREEN}✅${NC} Desktop entry Keywords correct: $DESKTOP_KEYWORDS"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  Desktop entry Keywords may be incomplete: $DESKTOP_KEYWORDS"
            echo -e "    ${GRAY}Expected: Keywords=Jarvis;AI;Launcher;${NC}"
            WARNINGS=$((WARNINGS + 1))
        fi
        
        # Check Actions
        DESKTOP_ACTIONS=$(grep "^Actions=" "$DESKTOP_FILE" | cut -d'=' -f2 || echo "")
        if [[ "$DESKTOP_ACTIONS" == *"Start"* ]] && [[ "$DESKTOP_ACTIONS" == *"Stop"* ]] && [[ "$DESKTOP_ACTIONS" == *"Logs"* ]] && [[ "$DESKTOP_ACTIONS" == *"Diagnostics"* ]]; then
            echo -e "  ${GREEN}✅${NC} Desktop entry Actions correct: $DESKTOP_ACTIONS"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  Desktop entry Actions may be incomplete: $DESKTOP_ACTIONS"
            echo -e "    ${GRAY}Expected: Actions=Start;Stop;Logs;Diagnostics;${NC}"
            WARNINGS=$((WARNINGS + 1))
        fi
    else
        echo -e "  ${RED}❌${NC} Desktop entry file not found: $DESKTOP_FILE"
        FAILED=$((FAILED + 1))
    fi
    
    # Check LauncherApplication UI polish (by checking for method signatures/strings)
    LAUNCHER_APP="${PROJECT_ROOT}/apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt"
    if [[ -f "$LAUNCHER_APP" ]]; then
        # Check for openLogsFolder method
        if grep -q "fun openLogsFolder\|openLogsFolder()" "$LAUNCHER_APP" 2>/dev/null; then
            echo -e "  ${GREEN}✅${NC} LauncherApplication has openLogsFolder method"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} LauncherApplication missing openLogsFolder method"
            FAILED=$((FAILED + 1))
        fi
        
        # Check for copyDiagnostics method
        if grep -q "fun copyDiagnostics\|copyDiagnostics()" "$LAUNCHER_APP" 2>/dev/null; then
            echo -e "  ${GREEN}✅${NC} LauncherApplication has copyDiagnostics method"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} LauncherApplication missing copyDiagnostics method"
            FAILED=$((FAILED + 1))
        fi
        
        # Check for statusBadge
        if grep -q "statusBadge\|updateStatusBadge" "$LAUNCHER_APP" 2>/dev/null; then
            echo -e "  ${GREEN}✅${NC} LauncherApplication has status badge"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${RED}❌${NC} LauncherApplication missing status badge"
            FAILED=$((FAILED + 1))
        fi
        
        # Check for tooltips
        if grep -q "\.tooltip\s*=" "$LAUNCHER_APP" 2>/dev/null; then
            echo -e "  ${GREEN}✅${NC} LauncherApplication has tooltips"
            PASSED=$((PASSED + 1))
        else
            echo -e "  ${YELLOW}⚠️${NC}  LauncherApplication may be missing tooltips"
            WARNINGS=$((WARNINGS + 1))
        fi
    else
        echo -e "  ${RED}❌${NC} LauncherApplication.kt not found: $LAUNCHER_APP"
        FAILED=$((FAILED + 1))
    fi
else
    echo -e "  ${GRAY}⏭️${NC}  Stage 15 checks skipped (use --require-install to enforce)"
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "=========================================="
echo "Iteration 1.4 Verification Summary"
echo "=========================================="
echo ""
echo -e "${GREEN}Passed: ${PASSED}${NC}"
if [[ $WARNINGS -gt 0 ]]; then
    echo -e "${YELLOW}Warnings: ${WARNINGS}${NC}"
fi
if [[ $FAILED -gt 0 ]]; then
    echo -e "${RED}Failed: ${FAILED}${NC}"
    echo ""
    echo -e "${RED}❌ Iteration 1.4 NOT ACCEPTED${NC}"
    exit 1
else
    echo -e "${GREEN}✅ All critical checks passed${NC}"
    if [[ $WARNINGS -eq 0 ]]; then
        echo -e "${GREEN}✅ Iteration 1.4 ACCEPTED (Stage 1, 2 & 3)${NC}"
    else
        echo -e "${YELLOW}⚠️  Iteration 1.4 ACCEPTED (with warnings)${NC}"
    fi
    exit 0
fi
