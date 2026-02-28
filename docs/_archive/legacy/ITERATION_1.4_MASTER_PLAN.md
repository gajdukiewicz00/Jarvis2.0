# Iteration 1.4 - Master Plan: Ubuntu Icon-Only Launch

**Goal:** Implement GUI launcher with icon-only launch, no terminal, persistent logs, strict product behavior.

---

## Stage 1: Create Launcher Module (JavaFX)

### Goal
Create new JavaFX application `apps/launcher` that provides GUI for backend startup/status.

### Files to Change
- `apps/launcher/pom.xml` (new)
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (new)
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/BackendManager.kt` (new)
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/HealthChecker.kt` (new)
- `apps/launcher/src/main/resources/fxml/LauncherView.fxml` (new)
- `pom.xml` (add launcher module)

### Risks
- JavaFX dependencies conflicts
- Process management complexity (PID tracking, stdout/stderr capture)
- Health check timing (when is backend "ready"?)

### How to Verify
```bash
# Build launcher
mvn -pl apps/launcher -DskipTests clean package

# Run launcher directly
java -jar apps/launcher/target/launcher-0.1.0-SNAPSHOT.jar

# Check GUI appears, no terminal
# Check logs written to ~/.jarvis/logs/backend-launch.log
```

### Definition of Done
- ✅ Launcher module compiles and runs
- ✅ GUI window appears with status area and buttons
- ✅ Can start backend via jarvis-launch.sh (captures logs)
- ✅ Logs written to ~/.jarvis/logs/backend-launch.log
- ✅ PID stored in ~/.jarvis/run/backend.pid

---

## Stage 2: Desktop Icon Integration

### Goal
Update `.desktop` file to launch launcher (not terminal), add Stop action.

### Files to Change
- `jarvis.desktop` (update Exec to launcher JAR)
- `jarvis-stop.sh` (update to work without terminal, use launcher or minimal wrapper)

### Risks
- Desktop file not recognized by Ubuntu
- Icon path incorrect
- Stop action doesn't work

### How to Verify
```bash
# Install desktop file
desktop-file-install jarvis.desktop
update-desktop-database ~/.local/share/applications/

# Check icon appears in app menu
# Click icon - should open launcher GUI (no terminal)
# Right-click icon - should show "Stop Jarvis" action
```

### Definition of Done
- ✅ Icon appears in Ubuntu app menu
- ✅ Clicking icon opens launcher GUI (no terminal)
- ✅ Stop action works (graceful shutdown)
- ✅ No terminal windows appear

---

## Stage 3: Logs and Directories

### Goal
Ensure directories exist, implement log rotation, capture all logs.

### Files to Change
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/LogManager.kt` (new)
- `jarvis-launch.sh` (update to write logs to ~/.jarvis/logs/)
- `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/DesktopApplication.kt` (update logging config)

### Risks
- Log rotation not working
- Permissions issues on ~/.jarvis/
- Log files grow too large

### How to Verify
```bash
# Check directories created
ls -la ~/.jarvis/logs/
ls -la ~/.jarvis/run/

# Check log rotation
# Write >10MB to log, check old files rotated

# Check logs contain backend output
tail -f ~/.jarvis/logs/backend-launch.log
```

### Definition of Done
- ✅ Directories created on first launch
- ✅ Logs written to ~/.jarvis/logs/
- ✅ Log rotation works (10MB, keep 10 files)
- ✅ Desktop logs in ~/.jarvis/logs/desktop.log

---

## Stage 4: Health Check and Ready Criteria

### Goal
Launcher polls API Gateway health, shows "Ready" when backend is up.

### Files to Change
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/HealthChecker.kt` (implement)
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` (add polling UI)

### Risks
- Health check too aggressive (spam requests)
- False positives (backend up but not ready)
- Network issues (minikube IP changes)

### How to Verify
```bash
# Start launcher
# Watch status change: Starting -> Checking -> Ready
# Check health endpoint called: curl http://<minikube-ip>:<nodeport>/actuator/health

# Test failure case: stop backend, check error message shown
```

### Definition of Done
- ✅ Launcher polls /actuator/health
- ✅ Status shows "Ready" when backend is up
- ✅ Error messages actionable (link to logs)
- ✅ Ready criteria: health UP AND auth verify works

---

## Stage 5: Idempotency and Process Management

### Goal
Second launch detects already running backend, just opens UI + checks health.

### Files to Change
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/BackendManager.kt` (add PID check)
- `jarvis-launch.sh` (add PID file creation)

### Risks
- PID file stale (process died but file exists)
- Multiple launchers running simultaneously
- Port conflicts (port-forward already running)

### How to Verify
```bash
# Start launcher, start backend
# Start second launcher instance
# Check: second instance detects running backend, doesn't start new one
# Check: only one port-forward process
```

### Definition of Done
- ✅ Second launch detects running backend
- ✅ No duplicate port-forward processes
- ✅ PID file cleaned up on shutdown
- ✅ Launcher shows current status (not "Starting" if already running)

---

## Stage 6: Security and Verification Pack

### Goal
Never log secrets, respect TLS fail-fast, add verification script.

### Files to Change
- `apps/launcher/src/main/kotlin/org/jarvis/launcher/BackendManager.kt` (sanitize logs)
- `scripts/verify-iteration-1.4.sh` (new)

### Risks
- Secrets leaked in logs
- Verification script too strict/loose

### How to Verify
```bash
# Check logs don't contain secrets
grep -i "password\|secret\|token" ~/.jarvis/logs/backend-launch.log

# Run verification script
./scripts/verify-iteration-1.4.sh
# Should pass all checks
```

### Definition of Done
- ✅ No secrets in logs
- ✅ TLS fail-fast respected
- ✅ Verification script passes
- ✅ All checks green

---

## Overall Definition of Done

- ✅ Icon launches launcher GUI (no terminal)
- ✅ Launcher starts backend, shows status
- ✅ Logs in ~/.jarvis/logs/ with rotation
- ✅ Health check works, shows "Ready"
- ✅ Idempotent (second launch detects running)
- ✅ Stop action works
- ✅ Verification script passes
- ✅ No dev modes, no shortcuts

---

## Implementation Order

1. Stage 1: Launcher module (foundation)
2. Stage 3: Logs and directories (needed by launcher)
3. Stage 4: Health check (needed for status)
4. Stage 5: Idempotency (polish)
5. Stage 2: Desktop icon (integration)
6. Stage 6: Security and verification (final checks)

---

## Testing Checklist

- [ ] Launcher compiles and runs
- [ ] GUI appears, no terminal
- [ ] Backend starts via launcher
- [ ] Logs written to ~/.jarvis/logs/
- [ ] Health check works
- [ ] Status shows "Ready"
- [ ] Second launch detects running backend
- [ ] Stop action works
- [ ] Icon appears in app menu
- [ ] No secrets in logs
- [ ] Verification script passes


