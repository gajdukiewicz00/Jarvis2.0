"""
Configuration for Embedding Service
"""
import os


def _positive_int(name: str, default: str) -> int:
    value = int(os.getenv(name, default))
    if value < 1:
        raise ValueError(f"{name} must be greater than zero")
    return value


class Config:
    """Embedding Service configuration"""

    def __init__(self) -> None:
        self.MODEL_NAME = os.getenv("MODEL_NAME", "intfloat/multilingual-e5-small")
        self.EMBEDDING_DIM = _positive_int("EMBEDDING_DIM", "384")
        self.CACHE_SIZE = _positive_int("CACHE_SIZE", "1000")
        self.CACHE_TTL_SECONDS = _positive_int("CACHE_TTL_SECONDS", "3600")
        self.HOST = os.getenv("HOST", "0.0.0.0")
        self.PORT = _positive_int("PORT", "5001")
        self.MAX_BATCH_SIZE = _positive_int("MAX_BATCH_SIZE", "32")
        self.MAX_TEXT_LENGTH = _positive_int("MAX_TEXT_LENGTH", "512")
        self.MAX_REQUEST_TEXTS = _positive_int("MAX_REQUEST_TEXTS", "128")
        self.LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
        self.CORS_ALLOWED_ORIGINS = [
            origin.strip()
            for origin in os.getenv("CORS_ALLOWED_ORIGINS", "").split(",")
            if origin.strip()
        ]


config = Config()

