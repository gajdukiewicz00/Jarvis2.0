# Explicit Non-Migration List

This list is based on the **actual extracted archive**, not assumptions.

Supporting inventories:

- `docs/_archive/legacy-migration/voice-asset-audit.csv`
- `docs/_archive/legacy-migration/unreferenced-voice-assets.txt`
- `docs/_archive/legacy-migration/command-migration-audit.csv`
- `docs/_archive/legacy-migration/legacy-file-audit.csv`

## Unused `.wav` assets

### Referenced in `voicecommands.xml`, but not integrated

| Legacy asset | Decision | Why |
|---|---|---|
| `koshachiy-dom-zvuk-murlykaniya-koshki-ochen-rasslablyaet.wav` | `discard` | Third-party ambient cat-purr audio, not Jarvis persona output. |
| `Вы создали новый элемент.wav` | `adapt_later` | Only useful after adding context-aware file-manager creation feedback. |
| `Заряд батареи, %\wooow.wav` | `archive_only` | Reused by many legacy macros, but semantically vague compared with cleaner loading acknowledgements. |
| `Заряд батареи, %\монетка.wav` | `adapt_later` | Only useful once a deterministic coin-flip feature exists. |
| `Как пожелаете.wav` | `archive_only` | Persona-consistent, but redundant with the stronger acknowledgement assets already wired. |
| `Новый голос\Гора эверестwav.wav` | `adapt_later` | Long-form knowledge response; belongs in a future story/knowledge mode. |
| `Новый голос\Сказкаwav.wav` | `adapt_later` | Long-form storytelling response; belongs in a future story mode. |
| `Новый голос\есть ли жизнь.wav` | `adapt_later` | Long-form knowledge response; belongs in a future story/knowledge mode. |
| `Новый голос\что ты знаешь о космосе.wav` | `adapt_later` | Long-form knowledge response; belongs in a future story/knowledge mode. |
| `Сохранить его в центральной базе данных Stark Industries.wav` | `archive_only` | Novelty clip; too long and too specific for current runtime routing. |
| `либилердууу.wav` | `archive_only` | Joke/novelty clip with unclear semantic value. |

### Present in the extracted sound pack, but not referenced by `voicecommands.xml`

- Exact-path nonreferenced files: **69**
- The full list is stored in:
  - `docs/_archive/legacy-migration/unreferenced-voice-assets.txt`

Why they were not integrated:

- The archive extraction mangled many Cyrillic filenames, so most of these files no longer have stable transcript-safe names.
- They have no surviving XML command reference in the extracted profile.
- Wiring anonymous or ambiguous clips into the runtime would make the persona less predictable, not more useful.

### Special note

- `Как пожелаете.wav` is referenced in `voicecommands.xml`, but the extracted filename could not be mapped back to an exact on-disk path because of lossy archive name decoding.

## Unused CSV commands

The complete per-command decision log for all **243** rows lives in:

- `docs/_archive/legacy-migration/command-migration-audit.csv`

### `adapt_later` commands

Count: **43**

Representative buckets:

- Scenario rollback commands like `отмени протокол`
- Knowledge/story commands like `расскажи сказку`
- Timer/alarm/stopwatch commands from `jarvis system`
- Dictation mode commands
- Browser/video-surface-specific YouTube shortcuts
- Restore/minimize/new-folder/trash flows that need dedicated desktop adapters

### `archive_only` commands

Count: **47**

Representative bucket:

- The entire `фильмы` group
  - kept as media/bookmark catalog material
  - not migrated as runtime logic because these are content links, not stable assistant actions

### `discard` commands

Count: **79**

Explicit discard buckets from the real archive:

- `PowerPoint` group: **50**
- `Управление Microsoft Word` group: **17**
- `рисунки` group: **6**
- Windows/macros from `Управление системой`: **6**
  - `Клик`
  - `Управление звуком`
  - `открой дисковод`
  - `закрой дисковод`
  - `Запусти автокликер`
  - `выруби автокликер`

Why these were discarded:

- They depend on Windows-only hotkeys, Office internals, or obsolete hardware.
- They are coordinate-based or blind UI macros.
- They do not meet the Linux-first, maintainable-runtime requirement.

## Ignored legacy files

The complete non-runtime file audit lives in:

- `docs/_archive/legacy-migration/legacy-file-audit.csv`

High-value examples:

| File | Decision | Why |
|---|---|---|
| `payloads/DxKeys.xml` | `discard` | Windows DirectX key map. |
| `payloads/DxModKeys.xml` | `discard` | Windows DirectX key map. |
| `payloads/launchprograms.xml` | `discard` | Windows absolute path launcher map. |
| `C__JarvisAudit_input_fix.zip/fix/install_vosk_final.ps1` | `discard` | Windows PowerShell installer. |
| `C__JarvisAudit_input_fix.zip/fix/run_install_vosk.bat` | `discard` | Windows batch installer. |
| `JRiver/Defaults.xml` | `discard` | Plugin-specific media-center config. |
| `xMySql/Defaults.xml` | `discard` | Plugin-specific database plugin config. |
| `payloads/4directions.xml` | `adapt_later` | Good vocabulary source, but not runtime-wired yet. |
| `payloads/math.xml` | `adapt_later` | Good vocabulary source, but not runtime-wired yet. |
| `payloads/on_off.xml` | `adapt_later` | Good vocabulary source, but not runtime-wired yet. |
| `payloads/truefalse.xml` | `adapt_later` | Good vocabulary source, but not runtime-wired yet. |
| `payloads/payloadAllButtonNames.xml` | `archive_only` | Large reference dictionary for possible future IR work. |
| `UsbUirt/maps/*.xml` | `archive_only` | IR remote maps kept for future device integration. |
| `data/maps.db3` / `maps_dump.sql` | `archive_only` | Legacy IR/device data, not runtime logic. |
| `smarts/*.xsp` | `archive_only` | Media-center smart playlists, not current runtime features. |
| `voxLog.txt` | `archive_only` | Audit evidence only. |
