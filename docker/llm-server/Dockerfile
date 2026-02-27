# =============================================================================
# Jarvis LLM Server - Multi-backend (Transformers + llama.cpp)
# Supports: NVIDIA CUDA 12.4, RTX 5070 (12GB VRAM)
# =============================================================================
FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04

WORKDIR /app

# Build arguments
ARG INSTALL_LLAMACPP=true
ARG CUDA_VERSION=cu124
ENV CUDA_VERSION=cu124
ENV HF_HOME=/app/.cache/huggingface

# System dependencies + Python 3.10
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip python3-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 jarvis && \
    useradd --system --uid 10001 --gid 10001 --home /app --shell /usr/sbin/nologin jarvis

# Install PyTorch with CUDA 12.4
RUN python3 -m pip install -U pip && \
    python3 -m pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/${CUDA_VERSION} \
      torch==2.6.0+${CUDA_VERSION}

# Sanity check: print torch version and CUDA availability
# NOTE: cuda.is_available() returns False during build (no GPU access)
# This is expected - real check happens at runtime
RUN python3 -c "import torch; print('torch version:', torch.__version__); print('cuda build version:', torch.version.cuda)"

# Install base dependencies
COPY requirements.txt .
RUN python3 -m pip install --no-cache-dir -r requirements.txt

# Install llama-cpp-python with CUDA wheel (no source build)
RUN if [ "$INSTALL_LLAMACPP" = "true" ]; then \
    echo "Installing llama-cpp-python CUDA wheel (cu124)" && \
    python3 -m pip install --no-cache-dir \
      llama-cpp-python==0.3.4 \
      --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124 ; \
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
ENV GGUF_MODEL_PATH=/models/model.gguf
ENV HOST=0.0.0.0
ENV PORT=5000
ENV LLM_BACKEND=transformers
ENV MAX_NEW_TOKENS=512
ENV MAX_GENERATION_SECONDS=120
ENV ENABLE_STREAMING=true
ENV ENABLE_WARMUP=true
ENV LOG_LEVEL=INFO

# llama.cpp specific
ENV N_GPU_LAYERS=-1
ENV N_CTX=4096
ENV N_BATCH=512

HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 \
    CMD curl -f http://localhost:5000/health || exit 1

USER 10001:10001

CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5000"]
