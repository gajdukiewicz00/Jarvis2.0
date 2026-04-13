# Porcupine Wake Word Setup

This file stays next to `desktop-javafx` because it is about a directory-local asset and env contract, not about the whole project.

## What This Is For

- optional Porcupine wake-word support in the desktop path
- local model placement under `apps/desktop-javafx/src/main/resources/models/`
- local secret/env key `PORCUPINE_ACCESS_KEY`

## Current Supported Runtime Path

The supported desktop runtime path is `desktop-javafx`. The unified module owns both the wake-word resources and the desktop-side implementation details.

## Required Local Setup

1. Create or download a Porcupine `.ppn` wake-word model.
2. Place it under:

```text
apps/desktop-javafx/src/main/resources/models/
```

3. Export the Picovoice access key locally:

```bash
export PORCUPINE_ACCESS_KEY="your-picovoice-access-key"
```

## Run

```bash
mvn -f apps/desktop-javafx/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
```

## Notes

- keep the `.ppn` file version aligned with `ai.picovoice:porcupine-java`
- if you only need a quick smoke, Porcupine also supports built-in keywords without a custom trained model
