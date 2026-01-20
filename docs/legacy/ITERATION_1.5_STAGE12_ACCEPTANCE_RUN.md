# Iteration 1.5 Stage 12: Release UX + Zero-Surprises — Acceptance Run

**Date:** $(date -Is)  
**Stage:** 12  
**Goal:** Release installer strict + informative, portability gates, Launcher Release Info UX

---

## A) Portability Gate

### Test 1: Hardcoded /usr/bin/bash Check

**Command:**
```bash
grep -RIn "/usr/bin/bash" jarvis-launch.sh scripts/ scripts/product/ apps/launcher-javafx/src/main/
```

**Expected:** No matches (all scripts use `#!/usr/bin/env bash` or `bash` from PATH)

**Result:** ✅ PASS — No hardcoded `/usr/bin/bash` found

---

## B) Release Build & Verify

### Test 2: Build Release Archive

**Command:**
```bash
./scripts/product/jarvis-build-release.sh
```

**Expected:**
- Release archive created in `target/release/jarvis-release-<version>.tar.gz`
- SHA256SUMS generated and included in archive
- install.sh uses `#!/usr/bin/env bash`

**Result:** ✅ PASS — Release archive built successfully

### Test 3: Verify Release Gate

**Command:**
```bash
./scripts/verify-iteration-1.4.sh --require-release
```

**Expected:** Exit code 0, Stage 12 portability checks pass

**Result:** ✅ PASS — All release checks passed

---

## C) Install.sh from Archive

### Test 4: Extract & Inspect Release

**Commands:**
```bash
ARCH=$(ls -1 target/release/jarvis-release-*.tar.gz | tail -1)
rm -rf /tmp/jarvis-release-test && mkdir -p /tmp/jarvis-release-test
tar -xzf "$ARCH" -C /tmp/jarvis-release-test
REL_DIR=$(find /tmp/jarvis-release-test -maxdepth 1 -type d -name 'jarvis-release-*' | head -1)
```

**Expected:**
- Archive extracts successfully
- `install.sh` exists and uses `#!/usr/bin/env bash`
- `SHA256SUMS` exists with relative paths

**Result:** ✅ PASS — Release extracted, install.sh portable

### Test 5: SHA256 Integrity Check

**Command:**
```bash
(cd "$REL_DIR" && sha256sum -c SHA256SUMS)
```

**Expected:** All checksums match (launcher.jar, install.sh)

**Result:** ✅ PASS — All checksums verified

### Test 6: Install.sh Structure Check

**Checks:**
- `RELEASE_SOURCE` is written to `~/.jarvis/app/RELEASE_SOURCE`
- Human-readable output (version, path, type, backup)
- Strict SHA256 check (FAIL on mismatch)

**Result:** ✅ PASS — install.sh structure correct

---

## D) Product + Release Verify

### Test 7: Full Verify Run

**Command:**
```bash
./scripts/verify-iteration-1.4.sh --require-install --require-release
```

**Expected:** Exit code 0, all checks pass

**Result:** ✅ PASS — Full verification passed

---

## E) Desktop Entry Check

### Test 8: Desktop Entry Portability

**Check:** `~/.local/share/applications/jarvis-launcher.desktop`

**Expected:**
- `Exec=/usr/bin/env bash -lc "$HOME/.jarvis/app/bin/jarvis-launcher.sh"`
- No hardcoded `/usr/bin/bash`

**Result:** ✅ PASS — Desktop entry uses portable bash

---

## F) Launcher Release Info UX (Manual Check)

### Test 9: UI Release Info Display

**Manual Steps:**
1. Launch launcher: `$HOME/.jarvis/app/bin/jarvis-launcher.sh`
2. Check version label: Should show `Installed: X | Bundle: Y | Source: Z`
3. Click "Open Install Log" → Should open `~/.jarvis/logs/install.log`
4. Click "Open Release Folder" → Should open release directory or show fallback

**Expected:**
- Version info displayed correctly
- Buttons work (or show friendly error in headless)
- No crashes

**Result:** ⚠️ MANUAL — Requires GUI environment (headless test skipped)

---

## Summary

| Test | Status | Notes |
|------|--------|-------|
| Portability Gate | ✅ PASS | No hardcoded `/usr/bin/bash` |
| Release Build | ✅ PASS | Archive created with SHA256SUMS |
| Release Verify | ✅ PASS | All checks pass |
| Install.sh Structure | ✅ PASS | Portable, strict SHA256, RELEASE_SOURCE |
| SHA256 Integrity | ✅ PASS | Checksums verified |
| Full Verify | ✅ PASS | `--require-install --require-release` passes |
| Desktop Entry | ✅ PASS | Uses portable bash |
| UI Release Info | ⚠️ MANUAL | Requires GUI (headless test skipped) |

---

## Verdict

**✅ Stage 12 ACCEPTED**

All automated checks pass. UI Release Info requires manual verification in GUI environment.

---

## Files Changed

1. `scripts/verify-iteration-1.4.sh` — Stage 12 portability gate
2. `scripts/product/jarvis-build-release.sh` — Strict SHA256, UX, RELEASE_SOURCE, relative paths
3. `scripts/product/jarvis-install.sh` — RELEASE_SOURCE tracking
4. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` — installLog, releaseSourceFile
5. `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` — Release Info UI, buttons

---

## Next Steps

1. **perf/hardware:** LLM GPU (CUDA error) — Fix llm-server GPU support
2. **enable-memory:** postgres-pgvector + memory-service — Enable memory stack
3. **product-polish:** UI/иконки/косметика — Visual improvements


