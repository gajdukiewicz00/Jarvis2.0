# Models Directory

This directory holds **metadata only**. Model binaries are never stored in git.

## Layout

```
models/
├── README.md          # this file
├── llm/.gitkeep       # main + coding + router LLM GGUF files (host-managed in Phase 3)
└── stt/
    ├── whisper/.gitkeep   # Whisper STT model files (loaded by voice-gateway)
    └── vosk/.gitkeep      # Vosk STT model files (alternative STT path)
```

## Why no binaries

- SPEC-1 forbids automatic model downloads.
- Model files are large (multi-GB) and version-specific to a particular host
  GPU/CPU profile.
- Binaries belong to the host model daemon (Phase 3) under
  `~/.jarvis/models/` or a Kubernetes PersistentVolume, not to the source
  repository.

## How to populate

Binaries are placed manually by the operator. The runtime layout looks like:

```text
~/.jarvis/models/llm/{main,coding,router}/<model>.gguf
~/.jarvis/models/stt/whisper/<model>.bin
~/.jarvis/models/stt/vosk/<model>/
```

Phase 3 introduces `infra/scripts/model-runtime/` to manage the host LLM
daemon and validate that the expected models are present.

## How the runtime finds them

- **STT (voice-gateway)**: configured by `JARVIS_STT_MODEL_DIR` (or
  `application.yml: jarvis.stt.model-path`).
- **LLM (host daemon)**: configured by `infra/scripts/model-runtime/model-profile.yml`
  (Phase 3).
- **Embeddings (`apps/embedding-service-py`)**: model name is configured via
  env, downloaded once into the embedding-service PVC, never into git.

## Verification

`infra/scripts/microk8s/verify-no-docker-runtime.sh` enforces that no large
binaries leak into the active path; the .gitignore rules above are the first
defense.
