"""
FastAPI application for LLM Server with multi-backend support
Supports: transformers (GPU/CPU), llama.cpp (GGUF, GPU)
"""
import asyncio
import logging
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from .config import config
from .model_loader import model_loader
from .chat_handler import chat_handler

# Configure logging
logging.basicConfig(
    level=getattr(logging, config.LOG_LEVEL.upper(), logging.INFO),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Thread pool for blocking operations
executor = ThreadPoolExecutor(max_workers=2)


# Request/Response models
class ChatMessage(BaseModel):
    """Single chat message"""
    role: str = Field(..., description="Message role: system, user, or assistant")
    content: str = Field(..., description="Message content")


class ChatRequest(BaseModel):
    """Chat request with conversation history"""
    messages: List[ChatMessage] = Field(..., description="Conversation messages")
    max_tokens: Optional[int] = Field(None, description="Maximum tokens to generate")
    temperature: Optional[float] = Field(None, description="Sampling temperature")
    stream: Optional[bool] = Field(False, description="Enable streaming response")


class ChatResponse(BaseModel):
    """Chat response"""
    reply: str = Field(..., description="Generated reply")
    tokens: dict = Field(..., description="Token usage info")
    model: str = Field(..., description="Model name")
    backend: str = Field(..., description="Backend used")
    processing_time_ms: int = Field(..., description="Processing time in milliseconds")


class HealthResponse(BaseModel):
    """Health check response"""
    model_config = {"protected_namespaces": ()}
    
    status: str
    backend: str
    model_loaded: bool
    device: str
    gpu_available: bool
    cuda_version: Optional[str] = None
    diagnostics: Optional[dict] = None


class ErrorResponse(BaseModel):
    """Error response"""
    error: str
    correlation_id: str
    detail: Optional[str] = None


# Lifespan context manager
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Handle startup and shutdown events"""
    logger.info("Starting LLM Server...")
    startup_time = time.time()
    
    try:
        # Log GPU status
        try:
            import torch
            gpu_available = torch.cuda.is_available()
            cuda_version = torch.version.cuda
            device_name = torch.cuda.get_device_name(0) if gpu_available else "N/A"
            
            logger.info("=" * 60)
            logger.info("STARTUP DIAGNOSTICS:")
            logger.info(f"  torch.cuda.is_available() = {gpu_available}")
            logger.info(f"  torch.version.cuda = {cuda_version}")
            logger.info(f"  Backend: {config.LLM_BACKEND}")
            if gpu_available:
                logger.info(f"  GPU name = {device_name}")
                vram_total = torch.cuda.get_device_properties(0).total_memory / 1e9
                logger.info(f"  VRAM total = {vram_total:.2f} GB")
            logger.info("=" * 60)
        except ImportError:
            logger.warning("PyTorch not available, cannot check GPU status")
        
        # Validate config and load model
        config.validate()
        model_loader.load()
        
        elapsed = time.time() - startup_time
        logger.info(f"LLM Server ready! (startup took {elapsed:.1f}s)")
        
    except Exception as e:
        logger.error(f"Failed to start LLM Server: {e}")
        raise
    
    yield
    
    # Shutdown
    logger.info("Shutting down LLM Server...")
    model_loader.unload()
    executor.shutdown(wait=False)


# Create FastAPI app
app = FastAPI(
    title="Jarvis LLM Server",
    description="Multi-backend LLM inference server (transformers/llama.cpp) with GPU support",
    version="2.0.0",
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
    """Health check endpoint with diagnostics"""
    try:
        import torch
        gpu_available = torch.cuda.is_available()
        cuda_version = torch.version.cuda if gpu_available else None
    except ImportError:
        gpu_available = False
        cuda_version = None
    
    diagnostics = model_loader.get_diagnostics() if model_loader.is_loaded() else None
    
    return HealthResponse(
        status="healthy" if model_loader.is_loaded() else "unhealthy",
        backend=model_loader.backend_name,
        model_loaded=model_loader.is_loaded(),
        device=config.get_effective_device(),
        gpu_available=gpu_available,
        cuda_version=cuda_version,
        diagnostics=diagnostics
    )


@app.post("/api/v1/llm/chat", response_model=ChatResponse, responses={504: {"model": ErrorResponse}})
async def chat(
    request: ChatRequest,
    x_correlation_id: Optional[str] = Header(None, alias="X-Correlation-ID")
):
    """
    Process chat request with conversation history.
    
    - Supports streaming via `stream=true`
    - Timeout: MAX_GENERATION_SECONDS
    - Max tokens: clamped to MAX_NEW_TOKENS
    """
    correlation_id = x_correlation_id or str(uuid.uuid4())[:8]
    
    if not model_loader.is_loaded():
        logger.error(f"[{correlation_id}] Model not loaded")
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    # Handle streaming request
    if request.stream and config.ENABLE_STREAMING:
        return await _handle_streaming_chat(request, correlation_id)
    
    logger.info(f"[{correlation_id}] Chat request: messages={len(request.messages)}, "
                f"max_tokens={request.max_tokens}, temperature={request.temperature}")
    
    try:
        start_time = time.time()
        
        messages = [{"role": msg.role, "content": msg.content} for msg in request.messages]
        
        # Run generation in thread pool with timeout
        loop = asyncio.get_event_loop()
        
        try:
            result = await asyncio.wait_for(
                loop.run_in_executor(
                    executor,
                    lambda: chat_handler.process_chat(
                        messages=messages,
                        max_tokens=request.max_tokens,
                        temperature=request.temperature
                    )
                ),
                timeout=config.MAX_GENERATION_SECONDS
            )
        except asyncio.TimeoutError:
            elapsed = time.time() - start_time
            logger.error(f"[{correlation_id}] Generation timeout after {elapsed:.1f}s")
            return JSONResponse(
                status_code=504,
                content={
                    "error": "Generation timeout",
                    "correlation_id": correlation_id,
                    "detail": f"Generation exceeded {config.MAX_GENERATION_SECONDS}s limit"
                }
            )
        
        processing_time = int((time.time() - start_time) * 1000)
        
        logger.info(f"[{correlation_id}] Chat response: reply_len={len(result['reply'])}, "
                   f"tokens={result['tokens']}, time={processing_time}ms")
        
        return ChatResponse(
            reply=result["reply"],
            tokens=result["tokens"],
            model=result["model"],
            backend=model_loader.backend_name,
            processing_time_ms=processing_time
        )
        
    except ValueError as e:
        logger.error(f"[{correlation_id}] Validation error: {e}")
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"[{correlation_id}] Chat request failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Internal server error")


async def _handle_streaming_chat(request: ChatRequest, correlation_id: str):
    """Handle streaming chat response using SSE"""
    
    async def generate_stream():
        start_time = time.time()
        total_tokens = 0
        
        messages = [{"role": msg.role, "content": msg.content} for msg in request.messages]
        prompt = chat_handler.format_prompt(messages)
        
        max_tokens = request.max_tokens or config.MAX_TOKENS
        temperature = request.temperature or config.TEMPERATURE
        
        logger.info(f"[{correlation_id}] Streaming chat: prompt_len={len(prompt)}, max_tokens={max_tokens}")
        
        try:
            # Yield SSE events
            for token in model_loader.generate_stream(
                prompt=prompt,
                max_tokens=max_tokens,
                temperature=temperature,
                top_p=config.TOP_P
            ):
                total_tokens += 1
                # SSE format: data: {json}\n\n
                yield f"data: {token}\n\n"
            
            elapsed = time.time() - start_time
            logger.info(f"[{correlation_id}] Streaming complete: tokens={total_tokens}, time={elapsed:.1f}s")
            
            # Send completion event
            yield f"data: [DONE]\n\n"
            
        except Exception as e:
            logger.error(f"[{correlation_id}] Streaming failed: {e}")
            yield f"data: [ERROR] {str(e)}\n\n"
    
    return StreamingResponse(
        generate_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Correlation-ID": correlation_id,
        }
    )


@app.get("/")
async def root():
    """Root endpoint with server info"""
    try:
        import torch
        gpu_available = torch.cuda.is_available()
    except ImportError:
        gpu_available = False
    
    return {
        "service": "Jarvis LLM Server",
        "version": "2.0.0",
        "backend": model_loader.backend_name,
        "device": config.get_effective_device(),
        "gpu_available": gpu_available,
        "streaming_enabled": config.ENABLE_STREAMING,
        "status": "running" if model_loader.is_loaded() else "loading"
    }


@app.get("/diagnostics")
async def diagnostics():
    """Detailed diagnostics endpoint"""
    diag = model_loader.get_diagnostics()
    
    # Add server config
    diag["config"] = {
        "backend": config.LLM_BACKEND,
        "max_new_tokens": config.MAX_NEW_TOKENS,
        "max_generation_seconds": config.MAX_GENERATION_SECONDS,
        "streaming_enabled": config.ENABLE_STREAMING,
    }
    
    return diag
