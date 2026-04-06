# Legacy Migration Audit Summary

**Date:** 2026-03-15  
**Source archive:** `~/Downloads/нужно.7z`  
**Extraction target:** `target/legacy-archive`

## Current integration points

- `apps/voice-gateway`
  - `ConfiguredIntentHandler` now loads `src/main/resources/intents/*.yaml` at runtime.
  - `VoiceOutputService`, `VoiceAssetLoader`, and `RuleBasedVoiceOutputResolver` now serve real legacy `.wav` assets with TTS fallback.
  - `VoiceWebSocketHandler` now speaks `STT_UNAVAILABLE` responses instead of sending text only.
- `apps/orchestrator`
  - Legacy website, protocol, system-control, and small-talk actions are now routed to concrete desktop actions.
- `apps/pc-control`
  - Added `OPEN_URL`, `sleep`, and `monitor_off` execution paths.
- `apps/desktop-client-javafx`
  - Added URL opening, new scenario handling, and fixed monitor-off fallback behavior.

## Archive contents actually audited

- Archive extracted successfully with a local 7zip binary (`target/tools/7zip/pkg/usr/lib/7zip/7z`).
- Extracted payload: 159 files, 14 folders.
- Key legacy sources:
  - `voicecommands.xml`
  - `command_inventory_full.csv`
  - `payloads/*.xml`
  - `options.xml`
  - `UsbUirt/maps/*.xml`
  - `data/*.db3`
  - `maps_dump.sql`, `sample_dump.sql`
  - `smarts/*.xsp`
  - `voxLog.txt`

## Voice asset audit

- Total `.wav` files in the legacy sound pack: **95**
- XML sound references found: **37**
- Unique physical voice references after deduping path variants/macros: **27**
- Unresolved XML macro references: **5**
  - `{m:yes.{Rnd.1.4}}`
  - `{m:yes.{Rnd.1.6}}`
  - `{m:Yes.{Rnd.1.6}}`
  - `{m:fact.{Rnd.1.8}}`
  - `{m:Reshka.{Rnd.1.2}}`
- Exact extracted files with no XML reference: **69**
- One XML-referenced filename could not be mapped back to an exact extracted filename because of lossy archive name decoding: `Как пожелаете.wav`

### Voice migration result

- Integrated into runtime: **16**
- Archived for later/manual review: **78**
- Discarded: **1**

Integrated assets are now active in:

- `apps/voice-gateway/src/main/resources/voice-assets/manifest.yaml`
- `apps/voice-gateway/src/main/resources/voice-routing-rules.yaml`

Runtime use cases now covered by legacy audio:

- wake/ready responses
- greetings and morning greeting
- loading/opening acknowledgements
- success acknowledgements
- diagnostics/network-check
- standby/rest mode
- music/radio acknowledgements
- game-mode activation

Detailed evidence:

- `docs/_archive/legacy-migration/voice-asset-audit.csv`
- `docs/_archive/legacy-migration/unreferenced-voice-assets.txt`

## CSV command audit

- Total legacy CSV commands found: **243**
- Active YAML/runtime command definitions created or updated: **61**
- Source rows migrated now: **74**
- Source rows merged into existing intent families: **9**
- Source rows kept for later (`adapt_later` + `archive_only`): **90**
- Source rows discarded: **79**

Group breakdown from the real archive:

- `PowerPoint`: 50
- `фильмы`: 47
- `Управление системой`: 35
- `Общение`: 26
- `Управление Ютубом`: 20
- `Управление Microsoft Word`: 17
- `Открытие сайтов`: 12
- `яндекс музыка`: 9
- `Протокололы`: 6
- `рисунки`: 6
- `Диджей`: 6
- `jarvis system`: 5
- `диктовка текста`: 2
- `фокус`: 1
- `Игровой режим`: 1

Detailed evidence:

- `docs/_archive/legacy-migration/command-migration-audit.csv`

## Actual repo changes made

- Added runtime YAML intent loading with handler fallthrough.
- Imported **16** real legacy `.wav` files into `apps/voice-gateway/src/main/resources/voice-assets/`.
- Added a real voice asset manifest and routed legacy assets by semantic action.
- Migrated website, conversation, protocol, media, and system-control command sets into active YAML.
- Extended orchestrator routing for:
  - `OPEN_URL`
  - legacy protocols/scenarios
  - browser/system hotkeys
  - `sleep`
  - `monitor_off`
  - network/news flows
- Extended desktop and PC-control execution for URL opening and system commands.
- Added tests for:
  - config-driven intent loading
  - asset loading
  - voice fallback behavior
  - new orchestrator routing

## Validation

- `mvn -pl apps/voice-gateway,apps/orchestrator,apps/pc-control,apps/desktop-client-javafx -am test`
  - **BUILD SUCCESS**
- `mvn -pl apps/voice-gateway,apps/orchestrator -am test`
  - **BUILD SUCCESS**
- Config consistency checks:
  - no duplicate intent IDs
  - no missing voice-routing asset references
  - no unused active manifest assets

## Remaining limitations

- Payload vocabulary XMLs were audited, but the extracted YAML vocabulary files are still reference data and are not yet consumed at runtime.
- YouTube-specific browser macros, dictation mode, and timer/alarm flows remain future work because they need dedicated adapters rather than VoxCommando-style macros.
- One legacy voice filename (`Как пожелаете.wav`) is documented from XML, but the extracted filename is mangled enough that it could not be mapped back to an exact file path automatically.
