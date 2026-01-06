"""
FastAPI application for Embedding Service
Provides vector embeddings using multilingual-e5-small
"""
import logging
import time
import uuid
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .config import config
from .embedder import embedder

# Configure logging
logging.basicConfig(
    level=getattr(logging, config.LOG_LEVEL.upper(), logging.INFO),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


# Request/Response models
class EmbedRequest(BaseModel):
    """Embedding request"""
    texts: List[str] = Field(..., description="List of texts to embed", min_length=1)


class EmbedResponse(BaseModel):
    """Embedding response"""
    embeddings: List[List[float]] = Field(..., description="List of embedding vectors")
    model: str = Field(..., description="Model used")
    dimension: int = Field(..., description="Embedding dimension")
    count: int = Field(..., description="Number of embeddings")
    processing_time_ms: int = Field(..., description="Processing time in milliseconds")


class SingleEmbedRequest(BaseModel):
    """Single text embedding request"""
    text: str = Field(..., description="Text to embed")


class SingleEmbedResponse(BaseModel):
    """Single text embedding response"""
    embedding: List[float] = Field(..., description="Embedding vector")
    model: str = Field(..., description="Model used")
    dimension: int = Field(..., description="Embedding dimension")
    processing_time_ms: int = Field(..., description="Processing time in milliseconds")


class HealthResponse(BaseModel):
    """Health check response"""
    status: str
    model_loaded: bool
    model_name: str
    embedding_dim: int
    cache_stats: dict


# Lifespan context manager
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Handle startup and shutdown events"""
    logger.info("Starting Embedding Service...")
    
    try:
        embedder.load()
        logger.info("Embedding Service ready!")
    except Exception as e:
        logger.error(f"Failed to start Embedding Service: {e}")
        raise
    
    yield
    
    logger.info("Shutting down Embedding Service...")


# Create FastAPI app
app = FastAPI(
    title="Jarvis Embedding Service",
    description="Vector embedding service using multilingual-e5-small",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint"""
    stats = embedder.get_stats()
    
    return HealthResponse(
        status="healthy" if embedder.is_loaded() else "unhealthy",
        model_loaded=embedder.is_loaded(),
        model_name=config.MODEL_NAME,
        embedding_dim=config.EMBEDDING_DIM,
        cache_stats={
            "size": stats["cache_size"],
            "max_size": stats["cache_max_size"],
        }
    )


@app.post("/embed", response_model=EmbedResponse)
async def embed_batch(
    request: EmbedRequest,
    x_correlation_id: Optional[str] = Header(None, alias="X-Correlation-ID")
):
    """
    Generate embeddings for multiple texts.
    
    - Supports batching for efficiency
    - Caches results for repeated texts
    """
    correlation_id = x_correlation_id or str(uuid.uuid4())[:8]
    
    if not embedder.is_loaded():
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    logger.info(f"[{correlation_id}] Embed request: {len(request.texts)} texts")
    
    try:
        start_time = time.time()
        
        embeddings = embedder.embed_batch(request.texts)
        
        processing_time = int((time.time() - start_time) * 1000)
        
        logger.info(f"[{correlation_id}] Embed complete: {len(embeddings)} embeddings in {processing_time}ms")
        
        return EmbedResponse(
            embeddings=embeddings,
            model=config.MODEL_NAME,
            dimension=config.EMBEDDING_DIM,
            count=len(embeddings),
            processing_time_ms=processing_time
        )
        
    except Exception as e:
        logger.error(f"[{correlation_id}] Embedding failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/embed/single", response_model=SingleEmbedResponse)
async def embed_single(
    request: SingleEmbedRequest,
    x_correlation_id: Optional[str] = Header(None, alias="X-Correlation-ID")
):
    """Generate embedding for a single text."""
    correlation_id = x_correlation_id or str(uuid.uuid4())[:8]
    
    if not embedder.is_loaded():
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    logger.debug(f"[{correlation_id}] Single embed: {request.text[:50]}...")
    
    try:
        start_time = time.time()
        
        embedding = embedder.embed_single(request.text)
        
        processing_time = int((time.time() - start_time) * 1000)
        
        return SingleEmbedResponse(
            embedding=embedding,
            model=config.MODEL_NAME,
            dimension=config.EMBEDDING_DIM,
            processing_time_ms=processing_time
        )
        
    except Exception as e:
        logger.error(f"[{correlation_id}] Embedding failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/")
async def root():
    """Root endpoint with service info"""
    return {
        "service": "Jarvis Embedding Service",
        "version": "1.0.0",
        "model": config.MODEL_NAME,
        "dimension": config.EMBEDDING_DIM,
        "status": "running" if embedder.is_loaded() else "loading"
    }


@app.post("/cache/clear")
async def clear_cache():
    """Clear the embedding cache"""
    embedder.clear_cache()
    return {"status": "ok", "message": "Cache cleared"}


@app.get("/stats")
async def get_stats():
    """Get service statistics"""
    return embedder.get_stats()



