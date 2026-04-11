# LLM Server

This file stays here because it documents the local contract of the Docker/Python worker in this directory.

## Runtime Role

- internal inference worker for `llm-service`
- canonical local runtime path: `ENABLE_LLM=true ./scripts/runtime-up.sh`
- canonical backend: `llamacpp`
- default local health URL: `http://127.0.0.1:15000/health`

## Local Model Contract

The worker expects either:

- the canonical GGUF file at `~/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf`, or
- `JARVIS_LLM_MODEL_PATH` in `~/.jarvis/run/local-runtime/local.env`

Direct Docker usage follows the same contract:

```bash
docker build -t jarvis-llm-server ./docker/llm-server
docker run --rm -p 15000:5000 \
  -v "$HOME/.jarvis/models/llm:/models:ro" \
  jarvis-llm-server
```

That default container path assumes:

- `LLM_BACKEND=llamacpp`
- `GGUF_MODEL_PATH=/models/qwen2.5-3b-instruct-q4_k_m.gguf`
- CPU-safe `llama-cpp-python` in the image by default

If you explicitly switch to `LLM_BACKEND=transformers`, also point `MODEL_PATH` at a mounted HuggingFace model directory.
For a transformers-capable image, build with:

```bash
docker build \
  --build-arg REQUIREMENTS_FILE=requirements.txt \
  --build-arg INSTALL_TORCH=true \
  -t jarvis-llm-server-transformers \
  ./docker/llm-server
```

For GPU offload inside Docker, opt in explicitly:

```bash
docker build \
  --build-arg LLAMA_CPP_EXTRA_INDEX_URL=https://abetlen.github.io/llama-cpp-python/whl/cu124 \
  -t jarvis-llm-server-gpu \
  ./docker/llm-server

docker run --rm --gpus all -p 15000:5000 \
  -e N_GPU_LAYERS=-1 \
  -v "$HOME/.jarvis/models/llm:/models:ro" \
  jarvis-llm-server-gpu
```

## Quick Checks

```bash
curl http://127.0.0.1:15000/health
./scripts/llm-smoke.sh
```

See the canonical service doc for the full component map:

- `docs/services/llm-server.md`
