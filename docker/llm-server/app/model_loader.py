"""
Model loader with multi-backend support
Supports: transformers (HuggingFace), llamacpp (GGUF)
"""
import logging
from typing import Optional, Generator, Dict, Tuple

from .config import config
from .backends.base import LLMBackend
from .backends.transformers_backend import TransformersBackend

logger = logging.getLogger(__name__)


class ModelLoader:
    """
    Unified model loader that supports multiple backends.
    Backend is selected via LLM_BACKEND environment variable.
    """
    
    def __init__(self):
        self._backend: Optional[LLMBackend] = None
        self._backend_name: str = config.LLM_BACKEND
    
    def load(self) -> None:
        """Load model using configured backend"""
        config.print_config()
        
        if self._backend_name == "llamacpp":
            self._load_llamacpp()
        else:
            self._load_transformers()
        
        # Run warmup if enabled
        if config.ENABLE_WARMUP and self._backend is not None:
            self._backend.warmup()
    
    def _load_transformers(self) -> None:
        """Load using Transformers backend"""
        logger.info("Initializing Transformers backend...")
        
        self._backend = TransformersBackend(
            model_path=config.MODEL_PATH,
            device=config.get_effective_device(),
            quantization=config.LLM_QUANT,
            max_new_tokens=config.MAX_NEW_TOKENS
        )
        self._backend.load()
    
    def _load_llamacpp(self) -> None:
        """Load using llama.cpp backend"""
        logger.info("Initializing llama.cpp backend...")
        
        try:
            from .backends.llamacpp_backend import LlamaCppBackend
        except ImportError as e:
            logger.error(f"llama.cpp backend not available: {e}")
            logger.warning("Falling back to Transformers backend")
            self._backend_name = "transformers"
            self._load_transformers()
            return
        
        self._backend = LlamaCppBackend(
            model_path=config.GGUF_MODEL_PATH,
            n_gpu_layers=config.N_GPU_LAYERS,
            n_ctx=config.N_CTX,
            n_batch=config.N_BATCH,
            max_new_tokens=config.MAX_NEW_TOKENS,
            verbose=config.VERBOSE_LLAMACPP
        )
        self._backend.load()
    
    def is_loaded(self) -> bool:
        """Check if model is loaded"""
        return self._backend is not None and self._backend.is_loaded()
    
    def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Tuple[str, int, int]:
        """
        Generate text from prompt.
        
        Returns:
            Tuple of (generated_text, input_tokens, output_tokens)
        """
        if not self.is_loaded():
            raise RuntimeError("Model not loaded. Call load() first.")
        
        return self._backend.generate(
            prompt=prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p
        )
    
    def generate_stream(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Generator[str, None, None]:
        """
        Generate text with streaming output.
        
        Yields:
            Text chunks as they are generated
        """
        if not self.is_loaded():
            raise RuntimeError("Model not loaded. Call load() first.")
        
        yield from self._backend.generate_stream(
            prompt=prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p
        )
    
    def get_diagnostics(self) -> Dict:
        """Get diagnostic information"""
        if self._backend is None:
            return {
                "backend": self._backend_name,
                "loaded": False,
                "error": "Backend not initialized"
            }
        
        return self._backend.get_diagnostics()
    
    def unload(self) -> None:
        """Unload model from memory"""
        if self._backend is not None:
            self._backend.unload()
            self._backend = None
    
    @property
    def backend_name(self) -> str:
        """Get current backend name"""
        return self._backend_name


# Global model instance
model_loader = ModelLoader()
