"""
LLM Backends for Jarvis
Supports: transformers (default), llamacpp
"""
from .base import LLMBackend

try:
    from .transformers_backend import TransformersBackend
    TRANSFORMERS_AVAILABLE = True
except ImportError:
    TRANSFORMERS_AVAILABLE = False
    TransformersBackend = None

# Optional llama.cpp backend
try:
    from .llamacpp_backend import LlamaCppBackend
    LLAMACPP_AVAILABLE = True
except ImportError:
    LLAMACPP_AVAILABLE = False
    LlamaCppBackend = None

__all__ = [
    "LLMBackend",
    "TransformersBackend",
    "TRANSFORMERS_AVAILABLE",
    "LlamaCppBackend",
    "LLAMACPP_AVAILABLE",
]


