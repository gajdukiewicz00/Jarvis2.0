"""
LLM Backends for Jarvis
Supports: transformers (default), llamacpp
"""
from .base import LLMBackend
from .transformers_backend import TransformersBackend

# Optional llama.cpp backend
try:
    from .llamacpp_backend import LlamaCppBackend
    LLAMACPP_AVAILABLE = True
except ImportError:
    LLAMACPP_AVAILABLE = False
    LlamaCppBackend = None

__all__ = ["LLMBackend", "TransformersBackend", "LlamaCppBackend", "LLAMACPP_AVAILABLE"]



