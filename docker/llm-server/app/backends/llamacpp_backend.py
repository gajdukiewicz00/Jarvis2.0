"""
llama.cpp backend for LLM inference (GGUF models)
Uses llama-cpp-python with GPU offload
"""
import logging
from typing import Dict, Tuple, Generator
import os

from .base import LLMBackend

logger = logging.getLogger(__name__)

# Try to import llama-cpp-python
try:
    from llama_cpp import Llama
    LLAMA_CPP_AVAILABLE = True
except ImportError:
    LLAMA_CPP_AVAILABLE = False
    Llama = None


class LlamaCppBackend(LLMBackend):
    """
    llama.cpp backend using llama-cpp-python.
    Optimized for low latency with GPU offload.
    """
    
    def __init__(
        self,
        model_path: str,
        n_gpu_layers: int = -1,  # -1 = all layers on GPU
        n_ctx: int = 4096,
        n_batch: int = 512,
        max_new_tokens: int = 512,
        verbose: bool = False
    ):
        if not LLAMA_CPP_AVAILABLE:
            raise ImportError(
                "llama-cpp-python is not installed. "
                "Install with: pip install llama-cpp-python --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu124"
            )
        
        self.model_path = model_path
        self.n_gpu_layers = n_gpu_layers
        self.n_ctx = n_ctx
        self.n_batch = n_batch
        self.max_new_tokens_limit = max_new_tokens
        self.verbose = verbose
        
        self.model = None
        self._model_info = {}
    
    def load(self) -> None:
        """Load GGUF model"""
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(f"Model not found: {self.model_path}")
        
        logger.info("=" * 60)
        logger.info("LLAMA.CPP BACKEND LOADING:")
        logger.info(f"  Model path: {self.model_path}")
        logger.info(f"  n_gpu_layers: {self.n_gpu_layers} (-1 = all)")
        logger.info(f"  n_ctx: {self.n_ctx}")
        logger.info(f"  n_batch: {self.n_batch}")
        logger.info("=" * 60)
        
        try:
            self.model = Llama(
                model_path=self.model_path,
                n_gpu_layers=self.n_gpu_layers,
                n_ctx=self.n_ctx,
                n_batch=self.n_batch,
                verbose=self.verbose,
                # Performance optimizations
                use_mmap=True,
                use_mlock=False,  # Don't lock memory
            )
            
            # Store model metadata
            self._model_info = {
                "n_vocab": self.model.n_vocab(),
                "n_ctx_train": self.model.n_ctx_train(),
                "model_size": os.path.getsize(self.model_path) / 1e9,
            }
            
            logger.info(f"Model loaded successfully!")
            logger.info(f"  Vocab size: {self._model_info['n_vocab']}")
            logger.info(f"  Training context: {self._model_info['n_ctx_train']}")
            logger.info(f"  Model size: {self._model_info['model_size']:.2f} GB")
            
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise
    
    def is_loaded(self) -> bool:
        return self.model is not None
    
    def generate(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Tuple[str, int, int]:
        """Generate text (non-streaming)"""
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        effective_max = min(max_tokens, self.max_new_tokens_limit)
        
        try:
            # Tokenize to count input tokens
            input_tokens = self.model.tokenize(prompt.encode("utf-8"))
            input_length = len(input_tokens)
            
            # Generate
            output = self.model(
                prompt,
                max_tokens=effective_max,
                temperature=temperature,
                top_p=top_p,
                echo=False,  # Don't include prompt in output
                stop=["</s>", "[/INST]", "<|end|>", "<|eot_id|>"],
            )
            
            generated_text = output["choices"][0]["text"].strip()
            output_tokens = output["usage"]["completion_tokens"]
            
            return generated_text, input_length, output_tokens
            
        except Exception as e:
            logger.error(f"Generation failed: {e}")
            raise
    
    def generate_stream(
        self,
        prompt: str,
        max_tokens: int = 512,
        temperature: float = 0.7,
        top_p: float = 0.9
    ) -> Generator[str, None, None]:
        """Generate text with streaming output"""
        if not self.is_loaded():
            raise RuntimeError("Model not loaded")
        
        effective_max = min(max_tokens, self.max_new_tokens_limit)
        
        try:
            stream = self.model(
                prompt,
                max_tokens=effective_max,
                temperature=temperature,
                top_p=top_p,
                echo=False,
                stop=["</s>", "[/INST]", "<|end|>", "<|eot_id|>"],
                stream=True,
            )
            
            for output in stream:
                token_text = output["choices"][0]["text"]
                if token_text:
                    yield token_text
                    
        except Exception as e:
            logger.error(f"Streaming generation failed: {e}")
            raise
    
    def warmup(self) -> None:
        """Run warmup inference"""
        if not self.is_loaded():
            return
        
        logger.info("Running warmup inference...")
        try:
            self.generate("Hello", max_tokens=5, temperature=0.1)
            logger.info("Warmup complete")
        except Exception as e:
            logger.warning(f"Warmup failed (non-fatal): {e}")
    
    def get_diagnostics(self) -> Dict:
        """Get backend diagnostics"""
        diag = {
            "backend": "llamacpp",
            "model_path": self.model_path,
            "n_gpu_layers": self.n_gpu_layers,
            "n_ctx": self.n_ctx,
            "n_batch": self.n_batch,
            "loaded": self.is_loaded(),
            "max_new_tokens_limit": self.max_new_tokens_limit,
        }
        
        if self._model_info:
            diag.update(self._model_info)
        
        # Try to get GPU info
        try:
            import torch
            if torch.cuda.is_available():
                diag["gpu_name"] = torch.cuda.get_device_name(0)
                diag["gpu_memory_total_gb"] = round(
                    torch.cuda.get_device_properties(0).total_memory / 1e9, 2
                )
                diag["cuda_version"] = torch.version.cuda
        except ImportError:
            pass
        
        return diag
    
    def unload(self) -> None:
        """Unload model from memory"""
        if self.model is not None:
            del self.model
            self.model = None
            self._model_info = {}
        
        logger.info("Model unloaded")


