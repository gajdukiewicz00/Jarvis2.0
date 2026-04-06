# Legacy Migration: VoxCommando -> Jarvis 2.0

This directory now contains the **actual** migration audit for `~/Downloads/нужно.7z`.

## What was completed

- The archive was extracted and audited from real contents.
- Real legacy `.wav` assets were imported into the live voice runtime.
- Real CSV commands were migrated into active YAML/runtime handling.
- Non-migrated items were classified from the archive, not guessed.

## Runtime artifacts added

- `apps/voice-gateway/src/main/resources/voice-assets/manifest.yaml`
- `apps/voice-gateway/src/main/resources/voice-routing-rules.yaml`
- `apps/voice-gateway/src/main/resources/intents/*.yaml`
- `apps/voice-gateway/src/main/resources/voice-assets/ru/**`
- `docs/_archive/legacy-migration/source/**`
  - stable repo-local copies of the legacy command/XML sources used during migration

## Audit artifacts in this folder

- `AUDIT-SUMMARY.md`
  - high-level architecture, migration counts, validation status
- `NON-MIGRATION-LIST.md`
  - explicit non-migration decisions and pointers to inventories
- `command-migration-audit.csv`
  - all **243** CSV commands with `migrate_now` / `adapt_later` / `archive_only` / `discard`
- `voice-asset-audit.csv`
  - all XML-referenced legacy voice clips with migration decisions
- `unreferenced-voice-assets.txt`
  - extracted sound-pack files not referenced by `voicecommands.xml`
- `legacy-file-audit.csv`
  - non-runtime XML / config / DB / script decisions
- `EXTRACTION.md`
  - extraction notes

## Headline numbers

- Legacy sound-pack `.wav` files found: **95**
- `.wav` files integrated into runtime: **16**
- CSV commands found: **243**
- CSV rows migrated now: **74**
- Active YAML/runtime command definitions created or updated: **61**

## Important caveat

One referenced legacy asset, `Как пожелаете.wav`, is documented from `voicecommands.xml`, but the extracted filename was mangled enough that it could not be mapped back to an exact on-disk path automatically after 7z extraction.
