# Stage 15: Product Polish — Acceptance Run

**Date:** [TO BE FILLED]  
**Status:** ⬜ PENDING / ✅ ACCEPTED / ❌ FAIL

---

## Acceptance Steps

### 1. Install Product
```bash
./scripts/product/jarvis-install.sh
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Output:**
```
[TO BE FILLED]
```

---

### 2. Verify Script
```bash
./scripts/verify-iteration-1.4.sh --require-install
echo $?
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Exit Code:** `[TO BE FILLED]`

**Stage 15 Checks:**
- [ ] Icon file exists: `~/.jarvis/app/assets/icons/jarvis.png`
- [ ] Desktop entry Icon path correct
- [ ] Desktop entry Categories correct
- [ ] Desktop entry Keywords correct
- [ ] Desktop entry Actions correct
- [ ] LauncherApplication has `openLogsFolder()` method
- [ ] LauncherApplication has `copyDiagnostics()` method
- [ ] LauncherApplication has status badge
- [ ] LauncherApplication has tooltips

**Verify Output (last lines):**
```
[TO BE FILLED]
```

---

### 3. Visual Check (Launcher UI)
```bash
~/.jarvis/app/bin/jarvis-launcher.sh
```

**Result:** ⬜ PENDING / ✅ PASS / ❌ FAIL

**Checks:**
- [ ] Status badge visible with colors (IDLE/STARTING/READY/DEGRADED/ERROR)
- [ ] "Open Logs Folder" button works (opens `~/.jarvis/logs/`)
- [ ] "Copy Diagnostics" button works (copies masked diagnostics to clipboard)
- [ ] All buttons have tooltips on hover
- [ ] Error messages are human-readable (no stacktraces in UI)

**Notes:**
```
[TO BE FILLED]
```

---

### 4. Quick Verification
```bash
# Check icon exists
ls -la ~/.jarvis/app/assets/icons/jarvis.png

# Check desktop entry metadata
cat ~/.local/share/applications/jarvis-launcher.desktop | grep -E "Icon=|Categories=|Keywords=|Actions="
```

**Icon File:**
```
[TO BE FILLED]
```

**Desktop Entry:**
```
[TO BE FILLED]
```

---

## Summary

| Check | Status | Notes |
|-------|--------|-------|
| Install script | ⬜ | |
| Verify script (exit 0) | ⬜ | |
| Stage 15 checks | ⬜ | |
| Visual UI check | ⬜ | |
| Icon file exists | ⬜ | |
| Desktop entry correct | ⬜ | |

---

## Verdict

**⬜ PENDING** — Acceptance run required

**Criteria:**
- ✅ Verify script exits with code 0
- ✅ All Stage 15 checks pass
- ✅ UI looks as expected (badge, buttons, tooltips)
- ✅ No regressions in existing functionality

**If all criteria met → ✅ ACCEPTED**

---

## Notes

- No changes to LLM/memory/TLS logic
- No new dependencies
- Existing verify modes preserved

