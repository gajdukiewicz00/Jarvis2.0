# Voice Rule Command Layer

## What this adds

The voice runtime now has a first-class deterministic command path in `apps/voice-gateway`:

1. STT transcript enters `VoiceWebSocketHandler`
2. `RuleBasedVoiceCommandService` tries the external rule catalog in `src/main/resources/voice-commands/*.yaml`
3. If a rule matches:
   - `VoiceCommandActionDispatcher` dispatches the mapped action
   - `WavResponseRegistry` resolves a response key to a WAV asset
   - `VoiceOutputService` returns WAV first, then TTS if the WAV is missing
4. If no rule matches:
   - existing `IntentService` + orchestrator + current fallback flow remains unchanged

## Config format

### Command catalog

```yaml
commands:
  - id: rule_open_browser
    priority: 30
    matchers:
      - type: exact
        values: ["открой браузер", "open browser"]
      - type: alias
        values: ["браузер"]
      - type: contains
        values: ["открой браузер", "запусти браузер"]
    action:
      target: pc_control
      name: OPEN_APP
      params:
        app: browser
    response:
      key: loading_sir
```

Supported matcher types now:

- `exact`
- `alias`
- `contains`
- `regex`

Supported action targets now:

- `internal`
- `pc_control`
- `system`
- `smart_home`

### WAV response registry

```yaml
responses:
  - key: loading_sir
    assets:
      ru: ru/assistant/loading_sir
    text:
      ru: "Загружаю, сэр."
      en: "Loading, sir."
```

The response registry keeps the stable response key separate from the physical WAV path, so the audio asset can change without rewriting every command.

## Seeded coverage

The initial runtime catalog now includes a meaningful deterministic slice across:

- conversation / wake / greetings
- browser and desktop control
- media control and scenarios
- smart-home light commands
- system actions (`sleep`, `monitor_off`)

## Extension path to the full legacy catalog

1. Use `docs/_archive/legacy-migration/source/command_inventory_full.csv` as the phrase backlog.
2. Add commands in domain files under `src/main/resources/voice-commands/`.
3. Reuse existing response keys where possible.
4. Add new response keys in `voice-response-registry.yaml` only when a distinct WAV or fallback text is needed.
5. Add new WAVs to `src/main/resources/voice-assets/` and `voice-assets/manifest.yaml`.
6. For commands that need variable slots later, prefer `regex` first, then extend the matcher result model to expose captured groups.

## Current audit summary

- Existing partial logic was real but incomplete:
  - YAML intent loading existed
  - action-based WAV routing existed
  - WebSocket voice flow still always deferred command execution/response shaping to orchestrator
- The new layer keeps that legacy flow as fallback instead of replacing it outright.
