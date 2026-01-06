# Iteration 1.4 - Stage 1 Verification Checklist

**Дата:** 2026-01-05  
**Статус:** Ready for verification

---

## Verification Steps

### 1. Launch without terminal
```bash
java -jar apps/launcher-javafx/target/launcher-javafx-0.1.0-SNAPSHOT.jar
```
**Expected:**
- ✅ GUI window appears
- ✅ No terminal window
- ✅ Log written to ~/.jarvis/logs/launcher.log

---

### 2. Start Backend
- Click "Start Backend" button
- Watch status change: IDLE → STARTING → READY

**Check:**
```bash
# Log file updates
tail -f ~/.jarvis/logs/backend-launch.log

# PID file created
ls -la ~/.jarvis/run/backend.pid
cat ~/.jarvis/run/backend.pid
```

**Expected:**
- ✅ ~/.jarvis/logs/backend-launch.log updates in real-time
- ✅ ~/.jarvis/run/backend.pid created with valid PID
- ✅ Status shows READY when backend is up
- ✅ Logs appear in TextArea (last 200 lines)

---

### 3. Stop Backend
- Click "Stop Backend" button

**Check:**
```bash
# PID file removed
ls -la ~/.jarvis/run/backend.pid
# Expected: file not found

# Process actually stopped
ps aux | grep jarvis-launch | grep -v grep
# Expected: no process found
```

**Expected:**
- ✅ PID file removed
- ✅ Process actually stopped (not just PID file)
- ✅ Status changes to IDLE
- ✅ Can start again (repeat Start Backend)

---

### 4. Idempotency
- Start backend (wait for READY)
- Click "Start Backend" again

**Check:**
```bash
# Only one process
ps aux | grep jarvis-launch | grep -v grep | wc -l
# Expected: 1 (or 0 if stopped)
```

**Expected:**
- ✅ Launcher shows "Backend already running" message
- ✅ No duplicate jarvis-launch.sh process
- ✅ Status remains READY
- ✅ No error dialogs

---

### 5. Logs
```bash
tail -n 50 ~/.jarvis/logs/launcher.log
tail -n 50 ~/.jarvis/logs/backend-launch.log
```

**Expected:**
- ✅ launcher.log contains launcher activity
- ✅ backend-launch.log contains backend output
- ✅ No secrets/tokens in logs
- ✅ Logs are readable and informative

---

## Code Improvements (Already Implemented)

### ✅ Backend Status Detection
- Checks PID file on startup
- Detects stale PID files (process dead but file exists)
- Cleans up invalid PID files
- Shows correct status (READY if running, IDLE if not)

### ✅ Executable Check
- Checks if jarvis-launch.sh is executable
- Shows clear error dialog with fix command: `chmod +x <script>`
- Provides actionable error message

### ✅ Idempotency
- Checks if backend already running before starting
- Uses PID file + ProcessHandle check
- Prevents duplicate processes
- Shows "already running" message

### ✅ Project Root Detection
- Tries JARVIS_PROJECT_ROOT env var first
- Falls back to current directory
- Tries common locations
- Clear error if not found

---

## Known Issues / Future Improvements

- Desktop client start (stub) - will be implemented in Stage 4
- Health check polling - will be implemented in Stage 4
- Full log rotation - basic rotation works, polish in Stage 3

---

## Next Steps

After verification passes:
- ✅ Stage 1 accepted
- → Stage 2: Desktop Icon Integration

