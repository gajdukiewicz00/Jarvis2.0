"""
FastAPI application for LLM Server with multi-backend support
Supports: transformers (GPU/CPU), llama.cpp (GGUF, GPU)
"""
import asyncio
import logging
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Header
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

def detect_gpu_status() -> tuple[bool, Optional[str], Optional[str]]:
    """Detect GPU presence without requiring PyTorch."""
    try:
        import torch
        if torch.cuda.is_available():
            return True, torch.version.cuda, torch.cuda.get_device_name(0)
    except ImportError:
        pass

    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
            capture_output=True,
            text=True,
            check=False,
            timeout=5,
        )
        if result.returncode == 0 and result.stdout.strip():
            return True, None, result.stdout.strip().splitlines()[0]
    except (FileNotFoundError, subprocess.SubprocessError):
        pass

    return False, None, None


# Thread pool for blocking operations
executor = ThreadPoolExecutor(max_workers=config.CHAT_WORKERS)

# Bounds how many non-streaming chat generations may be running on the
# shared executor at once (mirrors executor's worker count). asyncio.wait_for
# can only abandon the *awaiting* coroutine on timeout - it cannot cancel or
# interrupt work already running in a worker thread - so with CHAT_WORKERS
# defaulting to 1, a single stuck/slow generation would otherwise silently
# tie up the sole worker thread and every subsequent request would queue
# behind it indefinitely instead of failing fast. See Finding #35.
_chat_worker_slots = threading.Semaphore(config.CHAT_WORKERS)


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
    configured_device: str
    effective_device: str
    device: str
    configured_n_gpu_layers: int
    effective_n_gpu_layers: Optional[int] = None
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
        gpu_available, cuda_version, device_name = detect_gpu_status()
        logger.info("=" * 60)
        logger.info("STARTUP DIAGNOSTICS:")
        logger.info(f"  GPU available = {gpu_available}")
        logger.info(f"  CUDA version = {cuda_version}")
        logger.info(f"  Backend: {config.LLM_BACKEND}")
        if device_name:
            logger.info(f"  GPU name = {device_name}")
        logger.info("=" * 60)
        
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

# Add CORS middleware (explicit allowlist only)
if config.CORS_ALLOWED_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=config.CORS_ALLOWED_ORIGINS,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint with diagnostics"""
    gpu_available, cuda_version, _ = detect_gpu_status()
    
    diagnostics = model_loader.get_diagnostics() if model_loader.is_loaded() else None
    effective_n_gpu_layers = None
    if diagnostics:
        effective_n_gpu_layers = diagnostics.get("effective_n_gpu_layers")
    
    return HealthResponse(
        status="healthy" if model_loader.is_loaded() else "unhealthy",
        backend=model_loader.backend_name,
        model_loaded=model_loader.is_loaded(),
        configured_device=config.DEVICE,
        effective_device=config.get_effective_device(),
        device=config.get_effective_device(),
        configured_n_gpu_layers=config.N_GPU_LAYERS,
        effective_n_gpu_layers=effective_n_gpu_layers,
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

        # Fail fast instead of silently queuing behind a stuck/slow
        # generation: once every worker thread is genuinely busy, tell the
        # caller now rather than have them wait (and eventually time out
        # anyway) behind a call this server can never cancel once started.
        if not _chat_worker_slots.acquire(blocking=False):
            logger.error(f"[{correlation_id}] Chat worker pool saturated (CHAT_WORKERS={config.CHAT_WORKERS})")
            return JSONResponse(
                status_code=503,
                content={
                    "error": "Server busy",
                    "correlation_id": correlation_id,
                    "detail": "All chat workers are currently busy processing other requests"
                }
            )

        def _run_chat():
            try:
                return chat_handler.process_chat(
                    messages=messages,
                    max_tokens=request.max_tokens,
                    temperature=request.temperature
                )
            finally:
                # Released only when the worker thread actually finishes
                # running this call - not when the asyncio.wait_for below
                # gives up - so the slot keeps accurately reflecting real
                # worker occupancy even after a client-facing timeout.
                _chat_worker_slots.release()

        # Run generation in thread pool with timeout
        loop = asyncio.get_event_loop()

        try:
            future = loop.run_in_executor(executor, _run_chat)
        except Exception:
            _chat_worker_slots.release()
            raise

        try:
            result = await asyncio.wait_for(
                future,
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


# Sentinel used to detect exhaustion of a blocking generator without
# letting a StopIteration cross a thread-pool future boundary.
_STREAM_EXHAUSTED = object()


async def _bridge_blocking_stream(loop: asyncio.AbstractEventLoop, sync_iterator, deadline: Optional[float] = None):
    """
    Bridge a blocking (thread-blocking) synchronous token generator to an
    async generator.

    Backend token generation (llama.cpp / transformers) is a synchronous,
    CPU/GPU-bound call. Iterating it directly inside an `async def` freezes
    the asyncio event loop for the entire response, blocking every other
    request (including /health) until the stream ends. Instead, each
    `next()` call is executed in the shared thread-pool executor and
    awaited, so the event loop stays free between tokens.

    `deadline`, if given, is an absolute `loop.time()` value. The endpoint
    docstring promises MAX_GENERATION_SECONDS applies to the whole request,
    but without this the streaming path never enforced any cutoff (Finding
    #34): a stuck or abnormally slow backend would stream forever. Each
    wait for the next token is bounded by the remaining time until the
    deadline, and a `TimeoutError` is raised once it's exceeded so the
    caller can end the response instead of hanging indefinitely.
    """
    def _advance():
        try:
            return next(sync_iterator)
        except StopIteration:
            return _STREAM_EXHAUSTED

    while True:
        if deadline is not None:
            remaining = deadline - loop.time()
            if remaining <= 0:
                raise TimeoutError(
                    "Streaming generation exceeded MAX_GENERATION_SECONDS"
                )
            token = await asyncio.wait_for(
                loop.run_in_executor(executor, _advance), timeout=remaining
            )
        else:
            token = await loop.run_in_executor(executor, _advance)
        if token is _STREAM_EXHAUSTED:
            return
        yield token


async def _handle_streaming_chat(request: ChatRequest, correlation_id: str):
    """Handle streaming chat response using SSE"""

    async def generate_stream():
        start_time = time.time()
        total_tokens = 0

        messages = [{"role": msg.role, "content": msg.content} for msg in request.messages]
        max_tokens = request.max_tokens or config.MAX_TOKENS
        temperature = request.temperature or config.TEMPERATURE

        logger.info(f"[{correlation_id}] Streaming chat: messages={len(messages)}, max_tokens={max_tokens}")
        
        try:
            # Build the (synchronous) backend token generator. Constructing
            # it is cheap/non-blocking - generator bodies don't run until
            # the first `next()` call, which is dispatched off the event
            # loop below via `_bridge_blocking_stream`.
            stream = (
                model_loader.chat_stream(
                    messages=messages,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    top_p=config.TOP_P
                )
                if model_loader.supports_chat_messages()
                else model_loader.generate_stream(
                    prompt=chat_handler.format_prompt(messages),
                    max_tokens=max_tokens,
                    temperature=temperature,
                    top_p=config.TOP_P
                )
            )
            loop = asyncio.get_event_loop()
            # Enforce the same MAX_GENERATION_SECONDS cutoff the endpoint
            # docstring promises for the non-streaming path (Finding #34).
            deadline = loop.time() + config.MAX_GENERATION_SECONDS
            # Yield SSE events without blocking the event loop: each token
            # is generated in the thread-pool executor.
            async for token in _bridge_blocking_stream(loop, stream, deadline=deadline):
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
    gpu_available, _, _ = detect_gpu_status()
    
    return {
        "service": "Jarvis LLM Server",
        "version": "2.0.0",
        "backend": model_loader.backend_name,
        "model": model_loader.model_name,
        "configured_device": config.DEVICE,
        "effective_device": config.get_effective_device(),
        "device": config.get_effective_device(),
        "configured_n_gpu_layers": config.N_GPU_LAYERS,
        "effective_n_gpu_layers": (
            model_loader.get_diagnostics().get("effective_n_gpu_layers")
            if model_loader.is_loaded()
            else None
        ),
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
        "configured_device": config.DEVICE,
        "effective_device": config.get_effective_device(),
        "configured_n_gpu_layers": config.N_GPU_LAYERS,
        "max_new_tokens": config.MAX_NEW_TOKENS,
        "max_generation_seconds": config.MAX_GENERATION_SECONDS,
        "streaming_enabled": config.ENABLE_STREAMING,
    }
    
    return diag
