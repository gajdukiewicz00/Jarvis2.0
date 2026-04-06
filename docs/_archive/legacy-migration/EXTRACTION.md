# Legacy Archive Extraction

**Archive:** `~/Downloads/нужно.7z`  
**Extraction target used in this migration:** `target/legacy-archive`

## What was actually used

The environment did not have `7z`, `7za`, `bsdtar`, or `unar` installed globally.

The archive was extracted by:

1. Downloading the Debian `7zip` package locally into `target/tools/7zip`
2. Unpacking it without root
3. Running the local binary:

```bash
target/tools/7zip/pkg/usr/lib/7zip/7z x -otarget/legacy-archive ~/Downloads/нужно.7z -y
```

## Result

- Extraction succeeded
- Extracted payload: **159 files**, **14 folders**
- Top-level extracted directory: `target/legacy-archive/нужно`

## Important note

Some Cyrillic filenames were mangled during extraction, so the audit uses a mix of:

- exact extracted paths
- original XML references from `voicecommands.xml`

That is why `voice-asset-audit.csv` and `unreferenced-voice-assets.txt` are both kept: together they preserve the full evidence trail.

## Durable repo-local copies

The project no longer depends on `target/legacy-archive` at runtime.

The stable references worth keeping for future maintenance were copied into:

- `docs/_archive/legacy-migration/source/voicecommands.xml`
- `docs/_archive/legacy-migration/source/voicecommands.beforeApplyingConfig.xml`
- `docs/_archive/legacy-migration/source/command_inventory.csv`
- `docs/_archive/legacy-migration/source/command_inventory_full.csv`
- `docs/_archive/legacy-migration/source/options.xml`
- `docs/_archive/legacy-migration/source/payloads/payloadAllButtonNames.xml`
