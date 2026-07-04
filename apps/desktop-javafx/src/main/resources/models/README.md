Desktop wake-word assets expected at build/runtime:

- `jarvis_ru.ppn`: optional custom Russian Porcupine wake-word model
- `PORCUPINE_ACCESS_KEY`: required for both custom and built-in Porcupine keywords

Canonical location:

- `apps/desktop-client-javafx/src/main/resources/models/jarvis_ru.ppn`
  - loaded from the desktop app classpath at runtime
  - if packaging hides it inside a jar, the app extracts it to a temp file automatically before Porcupine starts
  - bundled asset currently targets Porcupine 4.x; keep the `.ppn` major version aligned with
    `ai.picovoice:porcupine-java`

Behavior:

- If `jarvis_ru.ppn` is missing, desktop falls back to built-in English `JARVIS`.
- If `PORCUPINE_ACCESS_KEY` is missing, always-listening mode is disabled and push-to-talk still works.
