# Porcupine Wake Word Setup

This file stays next to `desktop-client-javafx` because it is about a directory-local asset and env contract, not about the whole project.

## What This Is For

- optional Porcupine wake-word support in the desktop path
- local model placement under `apps/desktop-client-javafx/src/main/resources/models/`
- local secret/env key `PORCUPINE_ACCESS_KEY`

## Current Supported Runtime Path

The supported desktop runtime path is `desktop-app-javafx` / `launcher-javafx`. `desktop-client-javafx` remains the module that owns the wake-word resources and client-side implementation details.

## Required Local Setup

1. Create or download a Porcupine `.ppn` wake-word model.
2. Place it under:

```text
apps/desktop-client-javafx/src/main/resources/models/
```

3. Export the Picovoice access key locally:

```bash
export PORCUPINE_ACCESS_KEY="your-picovoice-access-key"
```

## Run

```bash
mvn -pl apps/desktop-app-javafx -am javafx:run
```

## Notes

- keep the `.ppn` file version aligned with `ai.picovoice:porcupine-java`
- if you only need a quick smoke, Porcupine also supports built-in keywords without a custom trained model
