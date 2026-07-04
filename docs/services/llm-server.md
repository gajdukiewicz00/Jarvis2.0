# llm-server

> **Status: Deprecated runtime path / Legacy candidate.**
>
> The path under `docker/llm-server/` no longer exists on disk. The current
> Python wrapper lives at [`apps/llm-server-py/`](../../apps/llm-server-py/),
> which itself is **deprecated** in favour of the native Phase-3
> `host-model-daemon` (see [docs/services/host-model-daemon.md](host-model-daemon.md)
> and [docs/LEGACY_AND_CLEANUP.md](../LEGACY_AND_CLEANUP.md)).
>
> All path references in this file that begin with `docker/llm-server/...`
> should be read as `apps/llm-server-py/...`. Configuration semantics are
> unchanged. Do not rely on this file for fresh integrations — point new
> work at `host-model-daemon`.

## 1. Name

`apps/llm-server-py` (formerly `docker/llm-server`)

## 2. Type

Python FastAPI inference service.

## 3. Purpose

Loads a local language model and provides the actual inference endpoint used by `llm-service`.

## 4. Current Reality

This is a real runtime component, but it is not part of the Maven reactor. It is managed through Docker/runtime scripts and is optional in both local and Kubernetes paths.

Deprecated runtime path. Kept temporarily for compatibility and migration evidence. Production runtime target is native host + MicroK8s under `jarvis-prod`.

## 5. Entry Points

- Python app entry: `apps/llm-server-py/app/main.py`
- FastAPI app object: `app`

## 6. Configuration

Main configuration source:

- `apps/llm-server-py/app/config.py`

Important settings include:

- `LLM_BACKEND`, default `llamacpp`
- `GGUF_MODEL_PATH`, default `/models/qwen2.5-3b-instruct-q4_k_m.gguf`
- `MODEL_PATH`, default `/models`, only for explicit transformers mode
- `DEVICE`
- `N_GPU_LAYERS`
- `PORT`, default `5000`

The local runtime scripts expose it on port `15000`.

## 7. API / WebSocket Surface

HTTP endpoints:

- `GET /health`
- `POST /api/v1/llm/chat`
- `GET /`
- `GET /diagnostics`

`POST /api/v1/llm/chat` supports streaming when `stream=true` and streaming is enabled.

No WebSocket endpoint.

## 8. Main Internal Components

- `model_loader`
- `chat_handler`
- backends under `app/backends/`
  - `llamacpp_backend.py`
  - `transformers_backend.py`

## 9. Dependencies On Other Services

- consumed by `llm-service`
- depends on local model files and optional GPU/runtime libraries

## 10. Data / Storage

No database. Model files are loaded from the configured filesystem paths.

## 11. Security Model

No auth layer was confirmed in this service itself. It should be treated as an internal/private runtime component.

## 12. How To Run / Test

Supported repo runtime path:

```bash
ENABLE_LLM=true ./scripts/runtime-up.sh
```

The module is now packaged via [`apps/llm-server-py/Containerfile`](../../apps/llm-server-py/Containerfile) (rootless OCI build with podman/buildah). The older `docker/llm-server/` tree was retired and a CI guard ([`scripts/guards/reject-new-docker-runtime-files.sh`](../../scripts/guards/reject-new-docker-runtime-files.sh)) rejects new files in that path.

Canonical direct container path:

```bash
podman build -t jarvis-llm-server -f apps/llm-server-py/Containerfile apps/llm-server-py
podman run --rm -p 15000:5000 \
  -v "$HOME/.jarvis/models/llm:/models:ro" \
  jarvis-llm-server
```

That default image is CPU-safe by design: it keeps `LLM_BACKEND=llamacpp`, mounts the canonical GGUF file, and installs the plain `llama-cpp-python` wheel so the container can boot without `libcuda.so.1`.

Explicit transformers build profile:

```bash
podman build \
  --build-arg REQUIREMENTS_FILE=requirements.txt \
  --build-arg INSTALL_TORCH=true \
  -t jarvis-llm-server-transformers \
  -f apps/llm-server-py/Containerfile apps/llm-server-py
```

Explicit GPU llama.cpp build profile:

```bash
podman build \
  --build-arg LLAMA_CPP_EXTRA_INDEX_URL=https://abetlen.github.io/llama-cpp-python/whl/cu124 \
  -t jarvis-llm-server-gpu \
  -f apps/llm-server-py/Containerfile apps/llm-server-py

podman run --rm --device nvidia.com/gpu=all -p 15000:5000 \
  -e N_GPU_LAYERS=-1 \
  -v "$HOME/.jarvis/models/llm:/models:ro" \
  jarvis-llm-server-gpu
```

Note: the new canonical Phase-3 production path is the **native llama.cpp host
daemon** (see [docs/services/host-model-daemon.md](host-model-daemon.md));
the `apps/llm-server-py/` Python wrapper is retained only because the
local-runtime AI scripts (`scripts/ai-up.sh`, `scripts/runtime-up.sh
ENABLE_LLM=true`) still drive it.

## 13. Implementation Status

Implemented, optional.

## 14. Known Gaps / Caveats

- Requires a real model file to start successfully.
- The canonical repo default is `llamacpp` + GGUF; `transformers` is opt-in and requires `MODEL_PATH` to point at a HuggingFace model directory.
- The default Docker/Kubernetes image is CPU-safe; GPU wheel usage is explicit and requires a GPU-enabled runtime.
- Not part of the core Maven build/test path.
- Service health depends on model load, not just process startup.
