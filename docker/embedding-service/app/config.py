"""
Configuration for Embedding Service
"""
import os


class Config:
    """Embedding Service configuration"""
    
    # Model
    MODEL_NAME: str = os.getenv("MODEL_NAME", "intfloat/multilingual-e5-small")
    
    # Embedding dimension (multilingual-e5-small = 384)
    EMBEDDING_DIM: int = 384
    
    # Cache settings
    CACHE_SIZE: int = int(os.getenv("CACHE_SIZE", "1000"))
    CACHE_TTL_SECONDS: int = int(os.getenv("CACHE_TTL_SECONDS", "3600"))
    
    # Server
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "5001"))
    
    # Batching
    MAX_BATCH_SIZE: int = int(os.getenv("MAX_BATCH_SIZE", "32"))
    MAX_TEXT_LENGTH: int = int(os.getenv("MAX_TEXT_LENGTH", "512"))
    
    # Logging
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")

    # CORS (comma-separated list, no wildcard)
    CORS_ALLOWED_ORIGINS = [
        origin.strip()
        for origin in os.getenv("CORS_ALLOWED_ORIGINS", "").split(",")
        if origin.strip()
    ]


config = Config()


