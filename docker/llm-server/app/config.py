"""
Configuration for LLM Server with multi-backend support
Supports: transformers, llamacpp
"""
import os
import logging

logger = logging.getLogger(__name__)


class Config:
    """LLM Server configuration with ENV support"""
    
    # Backend selection: 'transformers' or 'llamacpp'
    LLM_BACKEND: str = os.getenv("LLM_BACKEND", "transformers")
    
    # Model paths
    # For transformers: path to HuggingFace model directory
    MODEL_PATH: str = os.getenv("MODEL_PATH", "/models/h2ogpt-4096-llama2-7b-chat")
    # For llama.cpp: path to GGUF file
    GGUF_MODEL_PATH: str = os.getenv("GGUF_MODEL_PATH", "/models/h2ogpt-7b-chat-q4_k_m.gguf")
    
    # Device: 'cuda', 'cpu', or 'auto' (auto-detect)
    DEVICE: str = os.getenv("DEVICE", "auto")
    
    # Quantization for transformers: 'none', '4bit', '8bit'
    LLM_QUANT: str = os.getenv("LLM_QUANT", "none")
    
    # llama.cpp specific
    N_GPU_LAYERS: int = int(os.getenv("N_GPU_LAYERS", "-1"))  # -1 = all on GPU
    N_CTX: int = int(os.getenv("N_CTX", "4096"))  # Context window
    N_BATCH: int = int(os.getenv("N_BATCH", "512"))  # Batch size
    
    # Server settings
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "5000"))
    
    # Generation defaults
    MAX_TOKENS: int = int(os.getenv("MAX_TOKENS", "512"))
    TEMPERATURE: float = float(os.getenv("TEMPERATURE", "0.7"))
    TOP_P: float = float(os.getenv("TOP_P", "0.9"))
    
    # Safety limits
    MAX_NEW_TOKENS: int = int(os.getenv("MAX_NEW_TOKENS", "512"))
    MAX_GENERATION_SECONDS: int = int(os.getenv("MAX_GENERATION_SECONDS", "120"))
    
    # Performance
    MAX_HISTORY_LENGTH: int = int(os.getenv("MAX_HISTORY_LENGTH", "20"))
    ENABLE_WARMUP: bool = os.getenv("ENABLE_WARMUP", "true").lower() == "true"
    ENABLE_STREAMING: bool = os.getenv("ENABLE_STREAMING", "true").lower() == "true"
    
    # Logging
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    VERBOSE_LLAMACPP: bool = os.getenv("VERBOSE_LLAMACPP", "false").lower() == "true"

    # CORS (comma-separated list, no wildcard)
    CORS_ALLOWED_ORIGINS = [
        origin.strip()
        for origin in os.getenv("CORS_ALLOWED_ORIGINS", "").split(",")
        if origin.strip()
    ]
    
    @classmethod
    def get_effective_device(cls) -> str:
        """Get effective device, resolving 'auto' to actual device"""
        if cls.DEVICE == "auto":
            try:
                import torch
                return "cuda" if torch.cuda.is_available() else "cpu"
            except ImportError:
                return "cpu"
        return cls.DEVICE
    
    @classmethod
    def validate(cls) -> None:
        """Validate configuration"""
        # Validate backend
        if cls.LLM_BACKEND not in ["transformers", "llamacpp"]:
            raise ValueError(f"Invalid LLM_BACKEND: {cls.LLM_BACKEND}. Must be 'transformers' or 'llamacpp'")
        
        # Validate model paths based on backend
        if cls.LLM_BACKEND == "transformers":
            if not os.path.exists(cls.MODEL_PATH):
                raise ValueError(f"Transformers model path does not exist: {cls.MODEL_PATH}")
        elif cls.LLM_BACKEND == "llamacpp":
            if not os.path.exists(cls.GGUF_MODEL_PATH):
                raise ValueError(f"GGUF model path does not exist: {cls.GGUF_MODEL_PATH}")
        
        # Validate quantization
        if cls.LLM_QUANT not in ["none", "4bit", "8bit"]:
            raise ValueError(f"Invalid LLM_QUANT: {cls.LLM_QUANT}. Must be 'none', '4bit', or '8bit'")

        if "*" in cls.CORS_ALLOWED_ORIGINS:
            raise ValueError("CORS_ALLOWED_ORIGINS cannot include '*'")
        
        logger.info("Configuration validated successfully")
    
    @classmethod
    def print_config(cls) -> None:
        """Print current configuration"""
        logger.info("=" * 60)
        logger.info("LLM SERVER CONFIGURATION:")
        logger.info(f"  Backend: {cls.LLM_BACKEND}")
        logger.info(f"  Device: {cls.DEVICE} -> {cls.get_effective_device()}")
        
        if cls.LLM_BACKEND == "transformers":
            logger.info(f"  Model path: {cls.MODEL_PATH}")
            logger.info(f"  Quantization: {cls.LLM_QUANT}")
        else:
            logger.info(f"  GGUF model: {cls.GGUF_MODEL_PATH}")
            logger.info(f"  n_gpu_layers: {cls.N_GPU_LAYERS}")
            logger.info(f"  n_ctx: {cls.N_CTX}")
            logger.info(f"  n_batch: {cls.N_BATCH}")
        
        logger.info(f"  Max new tokens: {cls.MAX_NEW_TOKENS}")
        logger.info(f"  Max generation seconds: {cls.MAX_GENERATION_SECONDS}")
        logger.info(f"  Streaming enabled: {cls.ENABLE_STREAMING}")
        logger.info(f"  Warmup enabled: {cls.ENABLE_WARMUP}")
        logger.info("=" * 60)


config = Config()
