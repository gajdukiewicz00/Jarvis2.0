# =============================================================================
# Jarvis Embedding Service - multilingual-e5-small (CPU)
# Provides vector embeddings for semantic search
# =============================================================================
FROM python:3.11-slim

WORKDIR /app

# System dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the model during build (faster startup)
ARG MODEL_NAME=intfloat/multilingual-e5-small
RUN python3 -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${MODEL_NAME}')"

# Copy application
COPY app/ ./app/

EXPOSE 5001

# Environment
ENV PYTHONUNBUFFERED=1
ENV MODEL_NAME=intfloat/multilingual-e5-small
ENV CACHE_SIZE=1000
ENV HOST=0.0.0.0
ENV PORT=5001
ENV LOG_LEVEL=INFO

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:5001/health || exit 1

CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5001"]



