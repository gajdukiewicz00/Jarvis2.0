# syntax=docker/dockerfile:1
# =============================================================================
# Jarvis LLM Server - Multi-backend (Transformers + llama.cpp)
# Supports both:
# - CPU-safe default builds (plain llama-cpp-python wheel, runs without libcuda)
# - GPU builds (override LLAMA_CPP_EXTRA_INDEX_URL / INSTALL_TORCH and run with --gpus)
# =============================================================================
ARG BASE_IMAGE=nvidia/cuda:12.4.1-runtime-ubuntu22.04
FROM ${BASE_IMAGE}

WORKDIR /app

# Build arguments
ARG INSTALL_LLAMACPP=true
ARG INSTALL_TORCH=false
ARG CUDA_VERSION=cu124
ARG REQUIREMENTS_FILE=requirements-local.txt
ARG LLAMA_CPP_PACKAGE=llama-cpp-python==0.3.19
ARG LLAMA_CPP_EXTRA_INDEX_URL=
ENV CUDA_VERSION=cu124
ENV HF_HOME=/app/.cache/huggingface

# System dependencies + Python 3.10
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip python3-dev \
    build-essential cmake \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 jarvis && \
    useradd --system --uid 10001 --gid 10001 --home /app --shell /usr/sbin/nologin jarvis

# Install PyTorch when the selected runtime actually needs it.
RUN python3 -m pip install -U pip && \
    if [ "$INSTALL_TORCH" = "true" ]; then \
      python3 -m pip install --no-cache-dir \
        --index-url https://download.pytorch.org/whl/${CUDA_VERSION} \
        torch==2.6.0+${CUDA_VERSION}; \
    else \
      echo "Skipping torch installation"; \
    fi

# Sanity check only when torch is present.
RUN if [ "$INSTALL_TORCH" = "true" ]; then \
      python3 -c "import torch; print('torch version:', torch.__version__); print('cuda build version:', torch.version.cuda)"; \
    else \
      echo "Skipping torch sanity check"; \
    fi

# Install base dependencies
COPY requirements.txt requirements-local.txt ./
RUN python3 -m pip install --no-cache-dir -r "${REQUIREMENTS_FILE}"

# Install llama-cpp-python. The default build stays CPU-safe; GPU builds opt in
# to the CUDA wheel index explicitly.
RUN if [ "$INSTALL_LLAMACPP" = "true" ]; then \
    if [ -n "$LLAMA_CPP_EXTRA_INDEX_URL" ]; then \
      echo "Installing llama-cpp-python from custom wheel index" && \
      python3 -m pip install --no-cache-dir \
        "${LLAMA_CPP_PACKAGE}" \
        --extra-index-url "${LLAMA_CPP_EXTRA_INDEX_URL}" ; \
    else \
      echo "Installing llama-cpp-python from default index" && \
      python3 -m pip install --no-cache-dir "${LLAMA_CPP_PACKAGE}" ; \
    fi ; \
  else \
    echo "Skipping llama-cpp-python installation"; \
  fi

# Copy application
COPY app/ ./app/
RUN mkdir -p /app/.cache && chown -R 10001:10001 /app

EXPOSE 5000

# Environment defaults
ENV PYTHONUNBUFFERED=1
ENV MODEL_PATH=/models
ENV GGUF_MODEL_PATH=/models/qwen2.5-3b-instruct-q4_k_m.gguf
ENV HOST=0.0.0.0
ENV PORT=5000
ENV LLM_BACKEND=llamacpp
ENV MAX_NEW_TOKENS=512
ENV MAX_GENERATION_SECONDS=120
ENV ENABLE_STREAMING=true
ENV ENABLE_WARMUP=true
ENV LOG_LEVEL=INFO

# llama.cpp specific
ENV N_GPU_LAYERS=0
ENV N_CTX=4096
ENV N_BATCH=512

HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 \
    CMD curl -f http://localhost:5000/health || exit 1

USER 10001:10001

CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5000"]
